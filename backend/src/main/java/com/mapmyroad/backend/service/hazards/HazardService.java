package com.mapmyroad.backend.service.hazards;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.config.IngestProperties;
import com.mapmyroad.backend.repo.hazards.HazardRepository;
import com.mapmyroad.backend.repo.hazards.HazardRow;
import com.mapmyroad.backend.repo.hazards.HazardState;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hazard list, detail and feedback (API_SPEC.md 5.5 and 5.6, M4-12). Visibility rules live in SQL
 * ({@code VisibilityRules}); this class validates input and applies publication precision ({@link PublicationRules}).
 *
 * <p>Feedback is collected after the trip or when stopped (ADR-0013), so the server only checks that the reported
 * location is near the hazard and that {@code observedAt} is plausible. The location is never stored. The vote
 * updates the exact confirm and dismiss counts at once; confidence is recomputed asynchronously by the
 * aggregation job from {@code hazard_recompute_queue}, so the response carries the confidence stored before
 * that recompute.
 */
@Service
public class HazardService {

    /** API_SPEC.md 5.6: "within about 50 m of the hazard" (SECURITY.md T6). Initial value. */
    public static final double FEEDBACK_MAX_DISTANCE_M = 50;

    static final String METRIC_FEEDBACK = "mmr.hazards.feedback";

    private final HazardRepository repository;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final Duration maxFutureSkew;
    private final Duration maxAge;

    /**
     * {@code observedAt} bounds reuse the upload window (IngestProperties, 5 min future skew and 14 days): a vote
     * is about something the phone saw on a trip, and the same clock drift and review lag apply. A fixed hazard
     * also accepts feedback for {@code maxAge} after it was marked fixed, because post-trip review lags the drive.
     */
    public HazardService(
            HazardRepository repository, IngestProperties ingestProperties, Clock clock, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.maxFutureSkew = ingestProperties.maxFutureSkew();
        this.maxAge = ingestProperties.maxAge();
    }

    @Transactional(readOnly = true)
    public List<PublishedHazard> list(HazardQuery query) {
        BoundingBox box = query.bbox();
        return repository
                .findInBbox(
                        box.minLon(),
                        box.minLat(),
                        box.maxLon(),
                        box.maxLat(),
                        query.minSeverity(),
                        query.status().wireName(),
                        query.limit())
                .stream()
                .map(HazardService::publish)
                .toList();
    }

    /** The hazard, or 404 NOT_FOUND when it is unknown or not visible (the two are indistinguishable on purpose). */
    @Transactional(readOnly = true)
    public PublishedHazard get(UUID id) {
        return repository.findVisible(id).map(HazardService::publish).orElseThrow(HazardService::notFound);
    }

    /**
     * Records one vote in a single transaction: lock and check the hazard, upsert the vote, recount, queue the
     * confidence recompute.
     *
     * @throws ApiException 400 for an implausible {@code observedAt}, 404 when the hazard is unknown or not
     *     eligible, 422 TOO_FAR when the location is more than 50 m away, 401 when the device no longer exists
     */
    @Transactional
    public FeedbackResult feedback(FeedbackCommand command) {
        Instant now = clock.instant();
        validateObservedAt(command.observedAt(), now);
        Optional<Boolean> near = repository.lockFeedbackTarget(
                command.hazardId(), command.lat(), command.lon(), FEEDBACK_MAX_DISTANCE_M, now.minus(maxAge));
        if (near.isEmpty()) {
            throw notFound();
        }
        if (!near.get()) {
            meterRegistry.counter(METRIC_FEEDBACK, "result", "too_far").increment();
            throw new ApiException(ErrorCode.TOO_FAR, "The reported location is too far from the hazard.");
        }
        if (!repository.upsertFeedback(
                command.hazardId(), command.deviceId(), command.action().wireName(), command.observedAt())) {
            // The device was deleted while its access token was still valid.
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Unknown device.");
        }
        HazardState state = repository.recountFeedback(command.hazardId());
        repository.enqueueRecompute(command.hazardId());
        meterRegistry.counter(METRIC_FEEDBACK, "result", command.action().wireName()).increment();
        return new FeedbackResult(
                command.hazardId(),
                PublicationRules.roundConfidence(state.confidence()),
                HazardStatus.fromWireName(state.status())
                        .orElseThrow(() -> new IllegalStateException("Unknown hazard status in database")));
    }

    void validateObservedAt(Instant observedAt, Instant now) {
        if (observedAt.isAfter(now.plus(maxFutureSkew))) {
            throw InvalidRequests.field("observedAt", "must not be in the future");
        }
        if (observedAt.isBefore(now.minus(maxAge))) {
            throw InvalidRequests.field("observedAt", "is too old");
        }
    }

    static PublishedHazard publish(HazardRow row) {
        return new PublishedHazard(
                row.id(),
                PublicationRules.roundCoordinate(row.lon()),
                PublicationRules.roundCoordinate(row.lat()),
                row.severityMax(),
                PublicationRules.roundConfidence(row.confidence()),
                HazardStatus.fromWireName(row.status())
                        .orElseThrow(() -> new IllegalStateException("Unknown hazard status in database")),
                PublicationRules.truncateToDay(row.firstSeenAt()),
                PublicationRules.truncateToDay(row.lastSeenAt()),
                row.confirmCount(),
                row.dismissCount());
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Hazard not found.");
    }
}
