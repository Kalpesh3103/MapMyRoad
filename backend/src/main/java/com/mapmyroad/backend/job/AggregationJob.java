package com.mapmyroad.backend.job;

import com.mapmyroad.backend.config.AggregationProperties;
import com.mapmyroad.backend.repo.aggregation.SegmentKey;
import com.mapmyroad.backend.service.aggregation.AggregationLock;
import com.mapmyroad.backend.service.aggregation.AggregationService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Aggregation jobs (M4-10, ARCHITECTURE.md section 8, DATABASE_SCHEMA.md A7 and A9).
 *
 * <p>Incremental run (every {@code fixedDelay}), each step in bounded batches with one transaction per batch:
 * <ol>
 *   <li>cluster newly matched events into hazards, recompute statistics, clean-pass state, confidence and
 *       suppression of the touched hazards, reactivate fixed or stale hazards that were reported again;
 *   <li>aggregate newly matched windows: clean passes (and the fixed transition) and road segment statistics;
 *   <li>mark long-unseen active hazards stale.
 * </ol>
 * Events go first so a hazard created from a trip's events can already receive clean passes from other windows
 * processed in the same run.
 *
 * <p>Nightly run ({@code nightlyCron}): refreshes statistics, confidence (recency decay) and suppression of all
 * hazards and {@code distinct_devices} of all segments from raw rows.
 *
 * <p>Both share {@link AggregationLock} with the recompute-queue consumer. Logs carry counts only.
 */
@Component
public class AggregationJob {

    private static final Logger log = LoggerFactory.getLogger(AggregationJob.class);

    static final String METRIC_ROWS = "mmr.aggregation.rows";
    static final String METRIC_RUNS = "mmr.aggregation.runs";
    static final String METRIC_DURATION = "mmr.aggregation.duration";

    private final AggregationService service;
    private final AggregationProperties properties;
    private final AggregationLock lock;
    private final MeterRegistry meterRegistry;

    public AggregationJob(
            AggregationService service,
            AggregationProperties properties,
            AggregationLock lock,
            MeterRegistry meterRegistry) {
        this.service = service;
        this.properties = properties;
        this.lock = lock;
        this.meterRegistry = meterRegistry;
    }

    /** Placeholder defaults must match {@link AggregationProperties}. */
    @Scheduled(fixedDelayString = "${mmr.aggregation.fixed-delay:PT5M}", initialDelayString = "PT1M")
    public void runScheduled() {
        if (!properties.enabled()) {
            return;
        }
        runOnce();
    }

    @Scheduled(cron = "${mmr.aggregation.nightly-cron:0 15 4 * * *}", zone = "${mmr.aggregation.zone:Asia/Kolkata}")
    public void runNightlyScheduled() {
        if (!properties.enabled()) {
            return;
        }
        runNightly();
    }

    /** One incremental run now; skipped (empty report) while other aggregation work runs. */
    public Report runOnce() {
        AtomicReference<Report> report = new AtomicReference<>(Report.SKIPPED);
        boolean ran = lock.tryRun(() -> report.set(timed("incremental", this::incrementalSteps)));
        if (!ran) {
            log.warn("Aggregation is already running, skipping this run");
        }
        return report.get();
    }

    /** The nightly refresh now; waits for running aggregation work to finish first. */
    public void runNightly() {
        lock.run(() -> timed("nightly", () -> {
            nightlySteps();
            return Report.SKIPPED;
        }));
    }

    private Report incrementalSteps() {
        long events = 0;
        long hazardsCreated = 0;
        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            AggregationService.ClusterBatch result = service.clusterBatch();
            events += result.events();
            hazardsCreated += result.hazardsCreated();
            if (result.events() < properties.batchSize()) {
                break;
            }
        }
        long windows = 0;
        long segmentsUpserted = 0;
        long cleanPasses = 0;
        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            AggregationService.WindowBatch result = service.windowBatch();
            windows += result.windows();
            segmentsUpserted += result.segmentsUpserted();
            cleanPasses += result.cleanPasses();
            if (result.windows() < properties.batchSize()) {
                break;
            }
        }
        long stale = 0;
        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            int changed = service.staleBatch();
            stale += changed;
            if (changed < properties.batchSize()) {
                break;
            }
        }
        count("events_clustered", events);
        count("hazards_created", hazardsCreated);
        count("windows_aggregated", windows);
        count("segments_upserted", segmentsUpserted);
        count("clean_pass_hazards", cleanPasses);
        count("hazards_stale", stale);
        Report report = new Report(events, hazardsCreated, windows, segmentsUpserted, cleanPasses, stale);
        if (events > 0 || windows > 0 || stale > 0) {
            log.info("Aggregation finished: events={} hazardsCreated={} windows={} segmentsUpserted={} "
                            + "hazardsWithCleanPass={} hazardsStale={}",
                    events, hazardsCreated, windows, segmentsUpserted, cleanPasses, stale);
        }
        return report;
    }

    private void nightlySteps() {
        long hazardBatches = 0;
        UUID afterHazard = AggregationService.FIRST_HAZARD;
        while (afterHazard != null) {
            afterHazard = service.nightlyHazardBatch(afterHazard);
            hazardBatches++;
        }
        long segmentBatches = 0;
        SegmentKey afterSegment = AggregationService.FIRST_SEGMENT;
        while (afterSegment != null) {
            afterSegment = service.nightlySegmentBatch(afterSegment);
            segmentBatches++;
        }
        log.info("Nightly aggregation refresh finished: hazardBatches={} segmentBatches={}",
                hazardBatches - 1, segmentBatches - 1);
    }

    private Report timed(String job, Supplier<Report> steps) {
        long startedAtNs = System.nanoTime();
        try {
            Report report = steps.get();
            meterRegistry.counter(METRIC_RUNS, "job", job, "outcome", "success").increment();
            return report;
        } catch (RuntimeException e) {
            // Completed batches stay committed; the next run continues.
            meterRegistry.counter(METRIC_RUNS, "job", job, "outcome", "failure").increment();
            log.error("Aggregation job {} failed: {}", job, e.getClass().getSimpleName());
            throw e;
        } finally {
            meterRegistry.timer(METRIC_DURATION, "job", job)
                    .record(Duration.ofNanos(System.nanoTime() - startedAtNs));
        }
    }

    private void count(String step, long rows) {
        if (rows > 0) {
            meterRegistry.counter(METRIC_ROWS, "step", step).increment(rows);
        }
    }

    /** Counts of one incremental run. */
    public record Report(
            long eventsClustered,
            long hazardsCreated,
            long windowsAggregated,
            long segmentsUpserted,
            long hazardsWithCleanPass,
            long hazardsStale) {

        public static final Report SKIPPED = new Report(0, 0, 0, 0, 0, 0);
    }
}
