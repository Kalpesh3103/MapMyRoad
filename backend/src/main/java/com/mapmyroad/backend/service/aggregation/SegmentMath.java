package com.mapmyroad.backend.service.aggregation;

import java.util.List;

/** Road segment statistics (DATABASE_SCHEMA.md A7): EWMA roughness and the 0 to 100 score. Pure functions. */
public final class SegmentMath {

    private SegmentMath() {
    }

    /**
     * Applies new samples in order to an exponentially weighted average: {@code avg = alpha * x + (1 - alpha) * avg}.
     * A segment without history starts at its first sample.
     *
     * @param previous current average, or null for a new segment
     */
    public static double ewma(Double previous, List<Double> samples, double alpha) {
        if (samples.isEmpty()) {
            if (previous == null) {
                throw new IllegalArgumentException("no previous average and no samples");
            }
            return previous;
        }
        double avg = previous == null ? samples.get(0) : alpha * samples.get(0) + (1 - alpha) * previous;
        for (int i = 1; i < samples.size(); i++) {
            avg = alpha * samples.get(i) + (1 - alpha) * avg;
        }
        return avg;
    }

    /**
     * Score 0 to 100, higher is smoother: linear from 100 at {@code roughnessGood} to 0 at {@code roughnessPoor},
     * clamped, rounded. Same initial curve as the app's {@code TripScoring.roughnessToScore}, so a road looks the
     * same in the personal and the crowdsourced map. NaN scores 0. TODO(M1-5): calibrate both from field data.
     */
    public static int score(double roughness, double roughnessGood, double roughnessPoor) {
        if (Double.isNaN(roughness)) {
            return 0;
        }
        double fraction = (roughnessPoor - roughness) / (roughnessPoor - roughnessGood);
        double clamped = Math.max(0.0, Math.min(100.0, fraction * 100.0));
        return (int) Math.round(clamped);
    }
}
