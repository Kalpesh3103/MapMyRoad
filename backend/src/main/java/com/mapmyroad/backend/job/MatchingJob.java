package com.mapmyroad.backend.job;

import com.mapmyroad.backend.config.MatchingProperties;
import com.mapmyroad.backend.integration.valhalla.ValhallaUnavailableException;
import com.mapmyroad.backend.service.matching.MatchingService;
import com.mapmyroad.backend.service.matching.TripMatchResult;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Map-matching job (M4-9, DATABASE_SCHEMA.md A6). Each run:
 * <ol>
 *   <li>matches up to {@code tripsPerRun} trips, oldest received first, each trip in its own transaction;
 *   <li>marks rows whose trip token was already cleared (retention, after 24 hours) as processed without way;
 *   <li>refreshes the backlog gauges ({@code mmr.matching.backlog}, DEPLOYMENT.md "Ingestion backlog").
 * </ol>
 * If Valhalla is unavailable the run stops at once and the remaining trips wait for the next run; no row is
 * written for a trip whose matching failed that way. Idempotent: every write is guarded by
 * {@code matched_at IS NULL}. Logs carry counts only, never trip tokens or locations.
 */
@Component
public class MatchingJob {

    private static final Logger log = LoggerFactory.getLogger(MatchingJob.class);

    static final String METRIC_ROWS = "mmr.matching.rows";
    static final String METRIC_RUNS = "mmr.matching.runs";
    static final String METRIC_DURATION = "mmr.matching.duration";
    static final String METRIC_BACKLOG = "mmr.matching.backlog";

    /** Bound on orphan batches per run, so a huge backlog is worked off over several runs. */
    private static final int MAX_ORPHAN_BATCHES_PER_RUN = 20;

    private final MatchingService service;
    private final MatchingProperties properties;
    private final MeterRegistry meterRegistry;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong backlogEvents = new AtomicLong();
    private final AtomicLong backlogWindows = new AtomicLong();

    public MatchingJob(MatchingService service, MatchingProperties properties, MeterRegistry meterRegistry) {
        this.service = service;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        // Updated at the end of each run rather than queried on every scrape, so scraping never hits the database.
        Gauge.builder(METRIC_BACKLOG, backlogEvents, AtomicLong::get).tag("kind", "event").register(meterRegistry);
        Gauge.builder(METRIC_BACKLOG, backlogWindows, AtomicLong::get).tag("kind", "window").register(meterRegistry);
    }

    /** Placeholder default must match {@link MatchingProperties}. */
    @Scheduled(fixedDelayString = "${mmr.matching.fixed-delay:PT60S}", initialDelayString = "PT30S")
    public void runScheduled() {
        if (!properties.enabled()) {
            return;
        }
        runOnce();
    }

    /** Runs one bounded pass now. Public so tests and operators can trigger it. */
    public Report runOnce() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Matching job is already running, skipping this run");
            return Report.SKIPPED;
        }
        long startedAtNs = System.nanoTime();
        try {
            Report report = runSteps();
            meterRegistry.counter(METRIC_RUNS, "outcome", report.valhallaUnavailable() ? "valhalla_unavailable"
                    : "success").increment();
            if (report.trips() > 0 || report.orphanRows() > 0 || report.valhallaUnavailable()) {
                log.info("Matching job finished: trips={} rowsMatched={} rowsUnmatched={} orphanRows={} "
                                + "valhallaUnavailable={} backlog={}",
                        report.trips(), report.rowsMatched(), report.rowsUnmatched(), report.orphanRows(),
                        report.valhallaUnavailable(), backlogEvents.get() + backlogWindows.get());
            }
            return report;
        } catch (RuntimeException e) {
            // Completed trips stay committed; the next run continues with the rest.
            meterRegistry.counter(METRIC_RUNS, "outcome", "failure").increment();
            log.error("Matching job failed: {}", e.getClass().getSimpleName());
            throw e;
        } finally {
            meterRegistry.timer(METRIC_DURATION).record(Duration.ofNanos(System.nanoTime() - startedAtNs));
            running.set(false);
        }
    }

    private Report runSteps() {
        int trips = 0;
        long matched = 0;
        long unmatched = 0;
        boolean unavailable = false;
        List<UUID> pending = service.pendingTrips(properties.tripsPerRun());
        for (UUID tripToken : pending) {
            TripMatchResult result;
            try {
                result = service.matchTrip(tripToken);
            } catch (ValhallaUnavailableException e) {
                // The matcher already logged the status code; details (and the trip) stay out of the log.
                unavailable = true;
                break;
            }
            trips++;
            matched += result.eventsMatched() + result.windowsMatched();
            unmatched += result.eventsUnmatched() + result.windowsUnmatched();
            record("event", "matched", result.eventsMatched());
            record("event", "unmatched", result.eventsUnmatched());
            record("window", "matched", result.windowsMatched());
            record("window", "unmatched", result.windowsUnmatched());
        }

        long orphans = 0;
        for (int batch = 0; batch < MAX_ORPHAN_BATCHES_PER_RUN; batch++) {
            int touched = service.markOrphanBatch();
            orphans += touched;
            if (touched == 0) {
                break;
            }
        }
        meterRegistry.counter(METRIC_ROWS, "kind", "orphan", "result", "unmatched").increment(orphans);

        backlogEvents.set(service.backlogEvents());
        backlogWindows.set(service.backlogWindows());
        return new Report(trips, matched, unmatched, orphans, unavailable);
    }

    private void record(String kind, String result, int rows) {
        if (rows > 0) {
            meterRegistry.counter(METRIC_ROWS, "kind", kind, "result", result).increment(rows);
        }
    }

    /**
     * Outcome of one run.
     *
     * @param trips trips matched (fully written) in this run
     * @param rowsMatched events and windows that got a way
     * @param rowsUnmatched events and windows marked processed without way
     * @param orphanRows rows without trip token marked processed without way
     * @param valhallaUnavailable the run stopped early because Valhalla could not be used
     */
    public record Report(
            int trips, long rowsMatched, long rowsUnmatched, long orphanRows, boolean valhallaUnavailable) {

        public static final Report SKIPPED = new Report(0, 0, 0, 0, false);
    }
}
