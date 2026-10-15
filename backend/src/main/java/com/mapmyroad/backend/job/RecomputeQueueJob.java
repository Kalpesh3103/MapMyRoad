package com.mapmyroad.backend.job;

import com.mapmyroad.backend.config.AggregationProperties;
import com.mapmyroad.backend.service.aggregation.AggregationLock;
import com.mapmyroad.backend.service.aggregation.AggregationService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Consumer of {@code hazard_recompute_queue} (DATABASE_SCHEMA.md A10). Producers: "delete my data", the retention
 * job (inactive devices) and the feedback endpoint (confirm or dismiss votes). Each batch pops queue rows and
 * recomputes those hazards in one transaction, so a failure puts the rows back. Hazards with no remaining events
 * and no feedback are deleted. Runs under {@link AggregationLock}; logs carry counts only.
 */
@Component
public class RecomputeQueueJob {

    private static final Logger log = LoggerFactory.getLogger(RecomputeQueueJob.class);

    static final String METRIC_HAZARDS = "mmr.aggregation.recompute.hazards";
    static final String METRIC_RUNS = "mmr.aggregation.runs";
    static final String METRIC_DURATION = "mmr.aggregation.duration";

    private final AggregationService service;
    private final AggregationProperties properties;
    private final AggregationLock lock;
    private final MeterRegistry meterRegistry;

    public RecomputeQueueJob(
            AggregationService service,
            AggregationProperties properties,
            AggregationLock lock,
            MeterRegistry meterRegistry) {
        this.service = service;
        this.properties = properties;
        this.lock = lock;
        this.meterRegistry = meterRegistry;
    }

    /** Placeholder default must match {@link AggregationProperties}. */
    @Scheduled(fixedDelayString = "${mmr.aggregation.recompute-fixed-delay:PT2M}", initialDelayString = "PT45S")
    public void runScheduled() {
        if (!properties.enabled()) {
            return;
        }
        runOnce();
    }

    /**
     * Drains the queue in bounded batches now. Returns the number of hazards processed, or -1 when skipped because
     * other aggregation work was running.
     */
    public long runOnce() {
        AtomicLong processed = new AtomicLong(-1);
        boolean ran = lock.tryRun(() -> processed.set(drain()));
        if (!ran) {
            log.info("Aggregation is running, recompute queue waits for the next run");
        }
        return processed.get();
    }

    private long drain() {
        long startedAtNs = System.nanoTime();
        long hazards = 0;
        long deleted = 0;
        try {
            for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
                AggregationService.RecomputeBatch result = service.recomputeBatch();
                hazards += result.hazards();
                deleted += result.deleted();
                if (result.hazards() < properties.batchSize()) {
                    break;
                }
            }
            meterRegistry.counter(METRIC_RUNS, "job", "recompute", "outcome", "success").increment();
            if (hazards > 0) {
                meterRegistry.counter(METRIC_HAZARDS, "result", "recomputed").increment(hazards - deleted);
                meterRegistry.counter(METRIC_HAZARDS, "result", "deleted").increment(deleted);
                log.info("Recompute queue processed: hazards={} deleted={}", hazards, deleted);
            }
            return hazards;
        } catch (RuntimeException e) {
            meterRegistry.counter(METRIC_RUNS, "job", "recompute", "outcome", "failure").increment();
            log.error("Recompute queue job failed: {}", e.getClass().getSimpleName());
            throw e;
        } finally {
            meterRegistry.timer(METRIC_DURATION, "job", "recompute")
                    .record(Duration.ofNanos(System.nanoTime() - startedAtNs));
        }
    }
}
