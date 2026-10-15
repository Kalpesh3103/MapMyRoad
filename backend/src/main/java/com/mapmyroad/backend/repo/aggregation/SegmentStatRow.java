package com.mapmyroad.backend.repo.aggregation;

/** Current incremental state of one road segment. */
public record SegmentStatRow(SegmentKey key, double roughnessAvg, int sampleCount) {
}
