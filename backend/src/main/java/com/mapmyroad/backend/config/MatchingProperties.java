package com.mapmyroad.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Map-matching job settings (M4-9, DATABASE_SCHEMA.md A6). Initial values, to be tuned with pilot data.
 *
 * @param enabled whether the scheduled trigger runs (tests call {@code MatchingJob.runOnce()} directly)
 * @param fixedDelay pause between the end of one run and the start of the next
 * @param tripsPerRun at most this many trips are matched per run, oldest received first
 * @param maxSnapDistanceM a matched point farther than this from its input point is treated as unmatched
 * @param maxPointsPerRequest longer traces are split into overlapping chunks of at most this many points (Valhalla
 *     rejects very long shapes, {@code service_limits.trace.max_shape})
 * @param settleDelay a trip is matched only when its newest row is at least this old, so a trip uploaded in several
 *     requests is usually matched as one trace
 * @param orphanBatchSize rows per statement when marking rows without a trip token as matched (way unknown)
 */
@ConfigurationProperties(prefix = "mmr.matching")
public record MatchingProperties(
        Boolean enabled,
        Duration fixedDelay,
        Integer tripsPerRun,
        Double maxSnapDistanceM,
        Integer maxPointsPerRequest,
        Duration settleDelay,
        Integer orphanBatchSize) {

    /** Points shared by neighboring chunks of a long trace, so chunk edges are matched with context. */
    public static final int CHUNK_OVERLAP_POINTS = 10;

    public MatchingProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        fixedDelay = fixedDelay == null ? Duration.ofSeconds(60) : fixedDelay;
        tripsPerRun = tripsPerRun == null ? 50 : tripsPerRun;
        maxSnapDistanceM = maxSnapDistanceM == null ? 25.0 : maxSnapDistanceM;
        maxPointsPerRequest = maxPointsPerRequest == null ? 2000 : maxPointsPerRequest;
        settleDelay = settleDelay == null ? Duration.ofMinutes(1) : settleDelay;
        orphanBatchSize = orphanBatchSize == null ? 5000 : orphanBatchSize;
        if (tripsPerRun < 1 || orphanBatchSize < 1 || !(maxSnapDistanceM > 0)) {
            throw new IllegalArgumentException("mmr.matching batch sizes and max-snap-distance-m must be positive");
        }
        if (maxPointsPerRequest < 4 * CHUNK_OVERLAP_POINTS) {
            throw new IllegalArgumentException("mmr.matching.max-points-per-request must be at least "
                    + 4 * CHUNK_OVERLAP_POINTS);
        }
        if (settleDelay.isNegative() || fixedDelay.isNegative() || fixedDelay.isZero()) {
            throw new IllegalArgumentException("mmr.matching delays must not be negative");
        }
    }

    public static MatchingProperties defaults() {
        return new MatchingProperties(null, null, null, null, null, null, null);
    }
}
