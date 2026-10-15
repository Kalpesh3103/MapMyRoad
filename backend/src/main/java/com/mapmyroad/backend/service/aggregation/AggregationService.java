package com.mapmyroad.backend.service.aggregation;

import com.mapmyroad.backend.config.AggregationProperties;
import com.mapmyroad.backend.repo.aggregation.ConfidenceInput;
import com.mapmyroad.backend.repo.aggregation.HazardAggregationRepository;
import com.mapmyroad.backend.repo.aggregation.SegmentAggregationRepository;
import com.mapmyroad.backend.repo.aggregation.SegmentKey;
import com.mapmyroad.backend.repo.aggregation.SegmentStatUpdate;
import com.mapmyroad.backend.repo.aggregation.WindowSample;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Aggregation steps (M4-10, ARCHITECTURE.md section 8, DATABASE_SCHEMA.md A9). Every public method processes one
 * bounded batch in its own short transaction and is idempotent: events are processed once through
 * {@code hazard_id}, windows through {@code aggregated_at} (V8), and statistics are recomputed from raw rows
 * rather than incremented, except the segment EWMA, which is written in the same transaction as the marker.
 */
@Service
public class AggregationService {

    /** Lowest possible key, start of the nightly keyset iteration over segments. */
    public static final SegmentKey FIRST_SEGMENT = new SegmentKey(Long.MIN_VALUE, Integer.MIN_VALUE);

    /** Lowest UUID in PostgreSQL's ordering, start of the keyset iteration over hazards. */
    public static final UUID FIRST_HAZARD = new UUID(0L, 0L);

    private final HazardAggregationRepository hazards;
    private final SegmentAggregationRepository segments;
    private final AggregationProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AggregationService(
            HazardAggregationRepository hazards,
            SegmentAggregationRepository segments,
            AggregationProperties properties,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.hazards = hazards;
        this.segments = segments;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Result of one clustering batch. */
    public record ClusterBatch(int events, int hazardsCreated, int hazardsTouched) {
    }

    /** Result of one window batch. */
    public record WindowBatch(int windows, int segmentsUpserted, int cleanPasses) {
    }

    /** Result of one recompute-queue batch. */
    public record RecomputeBatch(int hazards, int deleted) {
    }

    /**
     * Clusters one batch of newly matched events (A9 "find the hazard an event belongs to"), then recomputes
     * statistics, clean-pass state, confidence and suppression of the touched hazards. Events are handled one by
     * one so two close events of the same batch join the same new hazard.
     */
    public ClusterBatch clusterBatch() {
        ClusterBatch result = transactions.execute(status -> {
            Instant now = clock.instant();
            List<UUID> eventIds = hazards.findUnclusteredEvents(properties.batchSize());
            if (eventIds.isEmpty()) {
                return new ClusterBatch(0, 0, 0);
            }
            Set<UUID> touched = new LinkedHashSet<>();
            int created = 0;
            for (UUID eventId : eventIds) {
                Optional<UUID> hazardId = hazards.findNearestHazard(eventId, properties.clusterRadiusM());
                if (hazardId.isEmpty()) {
                    hazardId = hazards.insertHazardFromEvent(eventId, now);
                    created += hazardId.isPresent() ? 1 : 0;
                }
                if (hazardId.isPresent() && hazards.assignEvent(eventId, hazardId.get()) > 0) {
                    touched.add(hazardId.get());
                }
            }
            hazards.reactivateHazards(eventIds, now);
            refreshHazards(touched, now, false);
            return new ClusterBatch(eventIds.size(), created, touched.size());
        });
        return result == null ? new ClusterBatch(0, 0, 0) : result;
    }

    /**
     * Aggregates one batch of matched windows: clean passes over nearby hazards (ARCHITECTURE.md 8.3) and the
     * road segment statistics (A7), then marks the windows aggregated.
     */
    public WindowBatch windowBatch() {
        WindowBatch result = transactions.execute(status -> {
            Instant now = clock.instant();
            List<WindowSample> windows = segments.findUnaggregatedWindows(properties.batchSize());
            if (windows.isEmpty()) {
                return new WindowBatch(0, 0, 0);
            }
            List<UUID> windowIds = windows.stream().map(WindowSample::id).toList();

            List<UUID> passedHazards = hazards.recordCleanPasses(windowIds,
                    properties.cleanPassRadiusM(),
                    properties.cleanPassRoughnessMax(),
                    properties.cleanPassEventWindow().toMillis() / 1000.0,
                    properties.cleanPassEventRadiusM());
            hazards.refreshCleanPassState(passedHazards, properties.fixedAfterCleanPasses(),
                    properties.fixedMinDevices(), false, now);

            Set<Long> wayIds = new LinkedHashSet<>();
            for (WindowSample window : windows) {
                if (window.wayId() != null && window.bucket() != null) {
                    wayIds.add(window.wayId());
                }
            }
            List<SegmentStatUpdate> updates = SegmentStatPlanner.plan(windows, segments.lockStats(wayIds),
                    properties.ewmaAlpha(), properties.roughnessGood(), properties.roughnessPoor());
            int upserted = segments.upsertStats(updates, now);
            segments.markAggregated(windowIds, now);
            return new WindowBatch(windows.size(), upserted, passedHazards.size());
        });
        return result == null ? new WindowBatch(0, 0, 0) : result;
    }

    /** Marks one batch of active hazards stale (ARCHITECTURE.md 8.3). Returns the number of hazards changed. */
    public int staleBatch() {
        Integer changed = transactions.execute(status -> {
            Instant now = clock.instant();
            Instant cutoff = now.minus(Duration.ofDays(properties.staleAfterDays()));
            return hazards.markStale(cutoff, properties.batchSize(), now);
        });
        return changed == null ? 0 : changed;
    }

    /**
     * Pops one batch from {@code hazard_recompute_queue} (after "delete my data", device expiry or feedback votes)
     * and recomputes those hazards from what remains; hazards with no remaining events and no feedback are deleted,
     * so a deleted contributor's influence disappears (TESTING_STRATEGY.md 5.2, PRD PV-3).
     */
    public RecomputeBatch recomputeBatch() {
        RecomputeBatch result = transactions.execute(status -> {
            Instant now = clock.instant();
            List<UUID> queued = hazards.pollRecomputeQueue(properties.batchSize());
            if (queued.isEmpty()) {
                return new RecomputeBatch(0, 0);
            }
            List<UUID> deleted = hazards.deleteHazardsWithoutEvidence(queued);
            List<UUID> remaining = new ArrayList<>(queued);
            remaining.removeAll(Set.copyOf(deleted));
            hazards.recomputeFeedbackCounts(remaining, now);
            hazards.resetStatsWithoutEvents(remaining, now);
            // Recompute after a deletion or feedback: a fixed status may lose its evidence.
            refreshHazards(remaining, now, true);
            return new RecomputeBatch(queued.size(), deleted.size());
        });
        return result == null ? new RecomputeBatch(0, 0) : result;
    }

    /**
     * Nightly refresh of one batch of hazards after {@code afterId}: statistics (distinct devices drop as retention
     * clears device IDs, A7), confidence (recency decays even without new events) and suppression (calming points
     * added by an OSM refresh). Returns the last hazard ID of the batch, or null when done.
     */
    public UUID nightlyHazardBatch(UUID afterId) {
        return transactions.execute(status -> {
            Instant now = clock.instant();
            List<UUID> ids = hazards.findHazardIdsAfter(afterId, properties.batchSize());
            if (ids.isEmpty()) {
                return null;
            }
            refreshHazards(ids, now, false);
            return ids.get(ids.size() - 1);
        });
    }

    /** Nightly refresh of {@code road_segment_stat.distinct_devices}; returns the last key, or null when done. */
    public SegmentKey nightlySegmentBatch(SegmentKey after) {
        return transactions.execute(status ->
                segments.refreshDistinctDevices(after, properties.batchSize(), clock.instant()));
    }

    /** Statistics, clean-pass state, confidence and suppression for the given hazards, in this order. */
    private void refreshHazards(Collection<UUID> hazardIds, Instant now, boolean allowFixedRevert) {
        if (hazardIds.isEmpty()) {
            return;
        }
        hazards.recomputeStats(hazardIds, now);
        hazards.refreshCleanPassState(hazardIds, properties.fixedAfterCleanPasses(), properties.fixedMinDevices(),
                allowFixedRevert, now);
        updateConfidence(hazardIds, now);
        hazards.suppress(hazardIds, properties.suppressionRadiusM(), now);
    }

    private void updateConfidence(Collection<UUID> hazardIds, Instant now) {
        Map<UUID, Double> confidence = new LinkedHashMap<>();
        for (ConfidenceInput input : hazards.loadConfidenceInputs(hazardIds)) {
            confidence.put(input.hazardId(), ConfidenceFormula.confidence(input.trustScores(), input.lastSeenAt(),
                    input.confirmCount(), input.dismissCount(), now, properties.recencyHalfLifeDays()));
        }
        hazards.updateConfidence(confidence, now);
    }
}
