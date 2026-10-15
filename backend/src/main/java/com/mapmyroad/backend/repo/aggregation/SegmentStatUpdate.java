package com.mapmyroad.backend.repo.aggregation;

import java.time.Instant;

/**
 * New state for one segment after a batch of windows.
 *
 * @param roughnessAvg the new EWMA (already including the new samples)
 * @param newSamples samples added by this batch (added to {@code sample_count})
 * @param lastObservedAt latest window time of this batch
 */
public record SegmentStatUpdate(
        SegmentKey key, double roughnessAvg, int score, int newSamples, Instant lastObservedAt) {
}
