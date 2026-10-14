package com.mapmyroad.backend.job;

import com.mapmyroad.backend.config.RetentionProperties;
import com.mapmyroad.backend.repo.retention.RetentionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Nightly retention job (M4-16, DATABASE_SCHEMA.md A10, SECURITY.md section 6.3).
 *
 * <p>Steps, in this order:
 * <ol>
 *   <li>Remove {@code device_id} from raw events and observations older than {@code deviceIdDays}.
 *   <li>Clear {@code trip_token} older than {@code tripTokenHours} (the matching job normally clears it sooner).
 *   <li>Delete raw events and observations older than {@code rawRowDays}.
 *   <li>Delete expired geocode cache entries.
 *   <li>Delete devices not seen for {@code inactiveDeviceMonths}.
 * </ol>
 *
 * <p>Each batch of at most {@code batchSize} rows is its own short transaction, so the job never holds a huge
 * transaction or long locks against ingestion. It is idempotent: an interrupted run leaves only completed batches
 * behind and the next run continues (CODING_STANDARDS.md 4.5).
 *
 * <p>Inactive devices: step 1 runs first, so by the time a device is 12 months inactive its raw rows no longer
 * reference it and the {@code ON DELETE CASCADE} removes only its refresh tokens and feedback votes. Feedback
 * votes do influence hazard confidence, so the affected hazards are queued for recomputation in the same
 * transaction as the delete, exactly like "delete my data". Events still linked to the device (only possible if
 * the periods were configured so that {@code deviceIdDays} exceeds the inactivity period) are covered by the same
 * queue insert.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    public static final String STEP_EVENT_DEVICE_IDS = "event_device_ids_cleared";
    public static final String STEP_OBSERVATION_DEVICE_IDS = "observation_device_ids_cleared";
    public static final String STEP_EVENT_TRIP_TOKENS = "event_trip_tokens_cleared";
    public static final String STEP_OBSERVATION_TRIP_TOKENS = "observation_trip_tokens_cleared";
    public static final String STEP_EVENTS_DELETED = "events_deleted";
    public static final String STEP_OBSERVATIONS_DELETED = "observations_deleted";
    public static final String STEP_GEOCODE_DELETED = "geocode_cache_deleted";
    public static final String STEP_DEVICES_DELETED = "devices_deleted";

    static final String METRIC_ROWS = "mmr.retention.rows";
    static final String METRIC_RUNS = "mmr.retention.runs";
    static final String METRIC_DURATION = "mmr.retention.duration";

    private final RetentionRepository repository;
    private final RetentionProperties properties;
    private final TransactionTemplate transactions;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public RetentionJob(
            RetentionRepository repository,
            RetentionProperties properties,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * Scheduled trigger. The placeholders read the same keys as {@link RetentionProperties}; the defaults here
     * must match the record's defaults.
     */
    @Scheduled(cron = "${mmr.retention.cron:0 30 3 * * *}", zone = "${mmr.retention.zone:Asia/Kolkata}")
    public void runScheduled() {
        if (!properties.enabled()) {
            log.info("Retention job is disabled, skipping scheduled run");
            return;
        }
        runOnce();
    }

    /** Runs all steps now. Public so tests and operators can trigger a run on demand. */
    public RetentionReport runOnce() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Retention job is already running, skipping this run");
            return new RetentionReport(Map.of());
        }
        long startedAtNs = System.nanoTime();
        try {
            RetentionReport report = runAllSteps(clock.instant());
            meterRegistry.counter(METRIC_RUNS, "outcome", "success").increment();
            log.info("Retention job finished: {}", report.rowsByStep());
            return report;
        } catch (RuntimeException e) {
            // Completed batches stay committed; the next run picks up the rest.
            meterRegistry.counter(METRIC_RUNS, "outcome", "failure").increment();
            log.error("Retention job failed: {}", e.getClass().getSimpleName());
            throw e;
        } finally {
            meterRegistry.timer(METRIC_DURATION).record(Duration.ofNanos(System.nanoTime() - startedAtNs));
            running.set(false);
        }
    }

    private RetentionReport runAllSteps(Instant now) {
        int batch = properties.batchSize();
        Instant deviceIdCutoff = now.minus(Duration.ofDays(properties.deviceIdDays()));
        Instant tripTokenCutoff = now.minus(Duration.ofHours(properties.tripTokenHours()));
        Instant rawRowCutoff = now.minus(Duration.ofDays(properties.rawRowDays()));
        Instant inactiveDeviceCutoff = now.atZone(ZoneOffset.UTC)
                .minusMonths(properties.inactiveDeviceMonths())
                .toInstant();

        Map<String, Long> rows = new LinkedHashMap<>();
        rows.put(STEP_EVENT_DEVICE_IDS, step(STEP_EVENT_DEVICE_IDS,
                () -> repository.clearEventDeviceIds(deviceIdCutoff, batch)));
        rows.put(STEP_OBSERVATION_DEVICE_IDS, step(STEP_OBSERVATION_DEVICE_IDS,
                () -> repository.clearObservationDeviceIds(deviceIdCutoff, batch)));
        rows.put(STEP_EVENT_TRIP_TOKENS, step(STEP_EVENT_TRIP_TOKENS,
                () -> repository.clearEventTripTokens(tripTokenCutoff, batch)));
        rows.put(STEP_OBSERVATION_TRIP_TOKENS, step(STEP_OBSERVATION_TRIP_TOKENS,
                () -> repository.clearObservationTripTokens(tripTokenCutoff, batch)));
        rows.put(STEP_EVENTS_DELETED, step(STEP_EVENTS_DELETED,
                () -> repository.deleteEvents(rawRowCutoff, batch)));
        rows.put(STEP_OBSERVATIONS_DELETED, step(STEP_OBSERVATIONS_DELETED,
                () -> repository.deleteObservations(rawRowCutoff, batch)));
        rows.put(STEP_GEOCODE_DELETED, step(STEP_GEOCODE_DELETED,
                () -> repository.deleteExpiredGeocodeEntries(now, batch)));
        rows.put(STEP_DEVICES_DELETED, step(STEP_DEVICES_DELETED,
                () -> deleteInactiveDeviceBatch(inactiveDeviceCutoff, batch)));
        return new RetentionReport(rows);
    }

    /** One batch: queue affected hazards, then delete the devices (cascade), in a single transaction. */
    private int deleteInactiveDeviceBatch(Instant cutoff, int batch) {
        List<UUID> deviceIds = repository.findInactiveDevices(cutoff, batch);
        if (deviceIds.isEmpty()) {
            return 0;
        }
        repository.enqueueHazardsOfDevices(deviceIds);
        return repository.deleteDevices(deviceIds, cutoff);
    }

    /**
     * Repeats one batch, each in its own transaction, until a batch affects no rows. Returns the total and
     * records it as a metric.
     */
    private long step(String name, IntSupplier batchOperation) {
        long total = 0;
        while (true) {
            Integer affected = transactions.execute(status -> batchOperation.getAsInt());
            if (affected == null || affected == 0) {
                break;
            }
            total += affected;
        }
        meterRegistry.counter(METRIC_ROWS, "step", name).increment(total);
        return total;
    }
}
