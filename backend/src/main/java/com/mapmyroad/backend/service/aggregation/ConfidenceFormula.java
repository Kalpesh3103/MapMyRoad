package com.mapmyroad.backend.service.aggregation;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

/**
 * Hazard confidence, the single implementation of the heuristic in ARCHITECTURE.md section 8.2 and
 * DATABASE_SCHEMA.md A9 (initial values, not measured):
 *
 * <pre>
 * confidence = clamp(
 *     min(1, trustWeightedDevices / 3)
 *   * 2^(-age(last_seen_at) / halfLife)
 *   * (1 + 0.5 * (confirm - dismiss) / (confirm + dismiss + 2)),
 *   0, 1)
 *
 * trustWeightedDevices = sum over distinct reporting devices of min(1, trust_score / 0.5)
 * </pre>
 *
 * A device with the default trust score 0.5 counts as one device; a low-trust device counts less, and a high trust
 * score never makes one device count more than one (SECURITY.md section 7, T4 and T5: one device cannot dominate,
 * corroboration needs several independent devices). Devices whose ID was removed by retention no longer count
 * ("distinct devices within the retention window", DATABASE_SCHEMA.md A7).
 */
public final class ConfidenceFormula {

    /** Weighted devices needed for full corroboration. */
    static final double CORROBORATING_DEVICES = 3.0;

    /** Trust score of a new device (V1 column default); such a device has weight 1. */
    static final double DEFAULT_TRUST_SCORE = 0.5;

    private static final double SECONDS_PER_DAY = 86_400.0;

    private ConfidenceFormula() {
    }

    /**
     * @param trustScores trust score of each distinct reporting device (one entry per device)
     * @param lastSeenAt latest event time of the hazard
     * @param confirmCount confirm votes
     * @param dismissCount dismiss votes
     * @param now reference time
     * @param halfLifeDays recency half-life
     */
    public static double confidence(
            Collection<? extends Number> trustScores,
            Instant lastSeenAt,
            int confirmCount,
            int dismissCount,
            Instant now,
            double halfLifeDays) {
        double deviceWeight = Math.min(1.0, trustWeightedDevices(trustScores) / CORROBORATING_DEVICES);
        double value = deviceWeight
                * recencyWeight(lastSeenAt, now, halfLifeDays)
                * agreementWeight(confirmCount, dismissCount);
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** Sum of {@code min(1, trust / 0.5)}; negative or missing scores count 0. */
    static double trustWeightedDevices(Collection<? extends Number> trustScores) {
        double sum = 0;
        for (Number trust : trustScores) {
            if (trust != null && trust.doubleValue() > 0) {
                sum += Math.min(1.0, trust.doubleValue() / DEFAULT_TRUST_SCORE);
            }
        }
        return sum;
    }

    /** {@code 2^(-age / halfLife)}; 1 for a last-seen time in the future (phone clock skew). */
    static double recencyWeight(Instant lastSeenAt, Instant now, double halfLifeDays) {
        if (lastSeenAt == null) {
            return 0.0;
        }
        double ageDays = Math.max(0, Duration.between(lastSeenAt, now).toMillis() / 1000.0) / SECONDS_PER_DAY;
        return Math.exp(-Math.log(2) * ageDays / halfLifeDays);
    }

    /** From 0.5 (only dismissals) through 1 (no votes or a tie) to 1.5 (only confirmations). */
    static double agreementWeight(int confirmCount, int dismissCount) {
        int confirm = Math.max(0, confirmCount);
        int dismiss = Math.max(0, dismissCount);
        return 1.0 + 0.5 * (confirm - dismiss) / (double) (confirm + dismiss + 2);
    }
}
