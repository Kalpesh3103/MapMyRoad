package com.mapmyroad.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Aggregation settings (M4-10, ARCHITECTURE.md section 8, DATABASE_SCHEMA.md A9). All thresholds are initial
 * values, not measured facts; they are tuned with pilot data (M6-3).
 *
 * @param enabled whether the scheduled triggers of the aggregation, nightly and recompute-queue jobs run
 * @param fixedDelay pause between incremental aggregation runs
 * @param recomputeFixedDelay pause between runs of the {@code hazard_recompute_queue} consumer
 * @param batchSize rows (events, windows or hazards) per batch; each batch is its own transaction
 * @param maxBatchesPerRun upper bound on batches per step and run, so one run never takes unbounded time
 * @param clusterRadiusM an event joins the nearest hazard within this distance (ARCHITECTURE.md 8.1)
 * @param suppressionRadiusM hazards this close to an OSM traffic-calming point or level crossing are suppressed
 * @param recencyHalfLifeDays half-life of the recency weight in the confidence formula (ARCHITECTURE.md 8.2)
 * @param cleanPassRadiusM a window passing this close to a hazard can count as a clean pass (ARCHITECTURE.md 8.3)
 * @param cleanPassRoughnessMax a clean pass needs roughness below this. TODO(M1-5): calibrate from field data
 * @param cleanPassEventWindow no event from the same device within this time of the window ...
 * @param cleanPassEventRadiusM ... and within this distance of the window, otherwise it is not a clean pass
 * @param fixedAfterCleanPasses clean passes since the last report needed to mark a hazard fixed
 * @param fixedMinDevices distinct devices among those clean passes (SECURITY.md T6)
 * @param staleAfterDays active hazards with no report and no clean pass for this long become stale
 * @param ewmaAlpha weight of a new window in the segment roughness average
 * @param roughnessGood roughness that scores 100. TODO(M1-5): same initial curve as the app's TripScoring
 * @param roughnessPoor roughness that scores 0. TODO(M1-5): same initial curve as the app's TripScoring
 * @param nightlyCron when distinct-device counts, confidence decay and suppression are refreshed for all rows
 * @param zone time zone for {@code nightlyCron}
 */
@ConfigurationProperties(prefix = "mmr.aggregation")
public record AggregationProperties(
        Boolean enabled,
        Duration fixedDelay,
        Duration recomputeFixedDelay,
        Integer batchSize,
        Integer maxBatchesPerRun,
        Double clusterRadiusM,
        Double suppressionRadiusM,
        Double recencyHalfLifeDays,
        Double cleanPassRadiusM,
        Double cleanPassRoughnessMax,
        Duration cleanPassEventWindow,
        Double cleanPassEventRadiusM,
        Integer fixedAfterCleanPasses,
        Integer fixedMinDevices,
        Integer staleAfterDays,
        Double ewmaAlpha,
        Double roughnessGood,
        Double roughnessPoor,
        String nightlyCron,
        String zone) {

    public AggregationProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        fixedDelay = fixedDelay == null ? Duration.ofMinutes(5) : fixedDelay;
        recomputeFixedDelay = recomputeFixedDelay == null ? Duration.ofMinutes(2) : recomputeFixedDelay;
        batchSize = batchSize == null ? 500 : batchSize;
        maxBatchesPerRun = maxBatchesPerRun == null ? 100 : maxBatchesPerRun;
        clusterRadiusM = clusterRadiusM == null ? 12.0 : clusterRadiusM;
        suppressionRadiusM = suppressionRadiusM == null ? 15.0 : suppressionRadiusM;
        recencyHalfLifeDays = recencyHalfLifeDays == null ? 30.0 : recencyHalfLifeDays;
        cleanPassRadiusM = cleanPassRadiusM == null ? 10.0 : cleanPassRadiusM;
        cleanPassRoughnessMax = cleanPassRoughnessMax == null ? 0.6 : cleanPassRoughnessMax;
        cleanPassEventWindow = cleanPassEventWindow == null ? Duration.ofSeconds(60) : cleanPassEventWindow;
        cleanPassEventRadiusM = cleanPassEventRadiusM == null ? 20.0 : cleanPassEventRadiusM;
        fixedAfterCleanPasses = fixedAfterCleanPasses == null ? 3 : fixedAfterCleanPasses;
        fixedMinDevices = fixedMinDevices == null ? 2 : fixedMinDevices;
        staleAfterDays = staleAfterDays == null ? 60 : staleAfterDays;
        ewmaAlpha = ewmaAlpha == null ? 0.2 : ewmaAlpha;
        roughnessGood = roughnessGood == null ? 0.3 : roughnessGood;
        roughnessPoor = roughnessPoor == null ? 2.0 : roughnessPoor;
        nightlyCron = nightlyCron == null || nightlyCron.isBlank() ? "0 15 4 * * *" : nightlyCron;
        zone = zone == null || zone.isBlank() ? "Asia/Kolkata" : zone;
        if (batchSize < 1 || maxBatchesPerRun < 1 || fixedAfterCleanPasses < 1 || fixedMinDevices < 1
                || staleAfterDays < 1) {
            throw new IllegalArgumentException("mmr.aggregation counts must be positive");
        }
        if (!(clusterRadiusM > 0) || !(suppressionRadiusM > 0) || !(cleanPassRadiusM > 0)
                || !(cleanPassEventRadiusM > 0) || !(recencyHalfLifeDays > 0) || !(cleanPassRoughnessMax > 0)) {
            throw new IllegalArgumentException("mmr.aggregation radii, half-life and roughness limit must be positive");
        }
        if (!(ewmaAlpha > 0 && ewmaAlpha <= 1)) {
            throw new IllegalArgumentException("mmr.aggregation.ewma-alpha must be in (0, 1]");
        }
        if (!(roughnessGood >= 0 && roughnessPoor > roughnessGood)) {
            throw new IllegalArgumentException("mmr.aggregation needs 0 <= roughness-good < roughness-poor");
        }
        if (fixedDelay.isNegative() || fixedDelay.isZero() || recomputeFixedDelay.isNegative()
                || recomputeFixedDelay.isZero() || cleanPassEventWindow.isNegative()) {
            throw new IllegalArgumentException("mmr.aggregation durations must be positive");
        }
    }

    public static AggregationProperties defaults() {
        return new AggregationProperties(null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }
}
