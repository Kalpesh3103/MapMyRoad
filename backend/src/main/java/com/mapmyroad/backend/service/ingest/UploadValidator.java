package com.mapmyroad.backend.service.ingest;

import com.mapmyroad.backend.config.IngestProperties;
import com.mapmyroad.backend.config.SupportedRegion;
import com.mapmyroad.backend.domain.ingest.EventObservation;
import com.mapmyroad.backend.domain.ingest.ItemKind;
import com.mapmyroad.backend.domain.ingest.Rejection;
import com.mapmyroad.backend.domain.ingest.RejectionReason;
import com.mapmyroad.backend.domain.ingest.UploadBatch;
import com.mapmyroad.backend.domain.ingest.WindowObservation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Plausibility checks for uploaded events and windows (API_SPEC.md section 5.3, SECURITY.md sections 4 T4/T11
 * and 7). A failing item is rejected on its own; the rest of the request is kept. Pure and deterministic: the
 * current time is a parameter, so tests need no clock or Spring context.
 *
 * <p>Checks run in a fixed order and the first failure is the reported reason:
 * region, speed, timestamp, then severity (events) or window shape (windows).
 *
 * <p>Non-finite numbers (NaN, Infinity; standard JSON cannot carry NaN, but huge literals overflow to Infinity)
 * fail the check they belong to: coordinates give {@code OUT_OF_REGION}, speed gives {@code IMPLAUSIBLE_SPEED},
 * an event's {@code peakScore} gives {@code INVALID_SEVERITY} (it is the strength measure behind the severity),
 * a window's {@code lengthM} or {@code roughness} gives {@code INVALID_WINDOW}. Every comparison is written so
 * that NaN fails it.
 *
 * <p>{@code headingDeg} is an optional map-matching hint. An out-of-range heading is dropped (stored as NULL)
 * instead of rejecting the event, because no rejection reason covers it and the event itself is still useful.
 */
public final class UploadValidator {

    private static final int MAX_HEADING_DEG = 359;
    private static final int MIN_SEVERITY = 1;
    private static final int MAX_SEVERITY = 3;

    private final IngestProperties limits;
    private final SupportedRegion region;

    public UploadValidator(IngestProperties limits, SupportedRegion region) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.region = Objects.requireNonNull(region, "region");
    }

    /** Request-level shape rules. A non-empty result means the whole request is rejected with 400. */
    public List<FieldProblem> checkBatchShape(int eventCount, int windowCount) {
        List<FieldProblem> problems = new ArrayList<>();
        if (eventCount == 0 && windowCount == 0) {
            problems.add(new FieldProblem("events", "events and windows must not both be empty"));
        }
        if (eventCount > limits.maxEventsPerRequest()) {
            problems.add(new FieldProblem("events", "at most " + limits.maxEventsPerRequest() + " events per request"));
        }
        if (windowCount > limits.maxWindowsPerRequest()) {
            problems.add(
                    new FieldProblem("windows", "at most " + limits.maxWindowsPerRequest() + " windows per request"));
        }
        return problems;
    }

    /** Splits a batch into accepted and rejected items. Accepted events have their heading normalized. */
    public ValidationOutcome validate(UploadBatch batch, Instant now) {
        List<EventObservation> events = new ArrayList<>(batch.events().size());
        List<WindowObservation> windows = new ArrayList<>(batch.windows().size());
        List<Rejection> rejected = new ArrayList<>();
        for (EventObservation event : batch.events()) {
            Optional<RejectionReason> reason = check(event, now);
            if (reason.isPresent()) {
                rejected.add(new Rejection(event.id(), ItemKind.EVENT, reason.get()));
            } else {
                events.add(event.withHeadingDeg(normalizeHeading(event.headingDeg())));
            }
        }
        for (WindowObservation window : batch.windows()) {
            Optional<RejectionReason> reason = check(window, now);
            if (reason.isPresent()) {
                rejected.add(new Rejection(window.id(), ItemKind.WINDOW, reason.get()));
            } else {
                windows.add(window);
            }
        }
        return new ValidationOutcome(events, windows, rejected);
    }

    public Optional<RejectionReason> check(EventObservation event, Instant now) {
        if (!region.contains(event.lat(), event.lon())) {
            return Optional.of(RejectionReason.OUT_OF_REGION);
        }
        if (!(event.speedKmh() >= limits.minEventSpeedKmh() && event.speedKmh() <= limits.maxSpeedKmh())) {
            return Optional.of(RejectionReason.IMPLAUSIBLE_SPEED);
        }
        Optional<RejectionReason> timestamp = checkTimestamp(event.observedAt(), now);
        if (timestamp.isPresent()) {
            return timestamp;
        }
        if (event.severity() < MIN_SEVERITY || event.severity() > MAX_SEVERITY || !Double.isFinite(event.peakScore())) {
            return Optional.of(RejectionReason.INVALID_SEVERITY);
        }
        return Optional.empty();
    }

    public Optional<RejectionReason> check(WindowObservation window, Instant now) {
        if (!region.contains(window.startLat(), window.startLon())
                || !region.contains(window.endLat(), window.endLon())) {
            return Optional.of(RejectionReason.OUT_OF_REGION);
        }
        if (!(window.speedKmh() >= 0 && window.speedKmh() <= limits.maxSpeedKmh())) {
            return Optional.of(RejectionReason.IMPLAUSIBLE_SPEED);
        }
        Optional<RejectionReason> timestamp = checkTimestamp(window.observedAt(), now);
        if (timestamp.isPresent()) {
            return timestamp;
        }
        if (!isWindowShapeValid(window)) {
            return Optional.of(RejectionReason.INVALID_WINDOW);
        }
        return Optional.empty();
    }

    /** Returns the heading if it is within 0..359, otherwise {@code null}. */
    public static Integer normalizeHeading(Integer headingDeg) {
        if (headingDeg == null || headingDeg < 0 || headingDeg > MAX_HEADING_DEG) {
            return null;
        }
        return headingDeg;
    }

    private Optional<RejectionReason> checkTimestamp(Instant observedAt, Instant now) {
        // Boundaries are inclusive: exactly maxFutureSkew ahead or exactly maxAge old is still accepted.
        if (observedAt.isAfter(now.plus(limits.maxFutureSkew()))) {
            return Optional.of(RejectionReason.FUTURE_TIMESTAMP);
        }
        if (observedAt.isBefore(now.minus(limits.maxAge()))) {
            return Optional.of(RejectionReason.TOO_OLD);
        }
        return Optional.empty();
    }

    private boolean isWindowShapeValid(WindowObservation window) {
        boolean lengthOk = window.lengthM() >= limits.minWindowLengthM() && window.lengthM() <= limits.maxWindowLengthM();
        boolean roughnessOk = Double.isFinite(window.roughness()) && window.roughness() >= 0;
        // A window whose start equals its end would be a degenerate line that cannot be map-matched.
        boolean distinctEnds = window.startLat() != window.endLat() || window.startLon() != window.endLon();
        return lengthOk && roughnessOk && distinctEnds;
    }
}
