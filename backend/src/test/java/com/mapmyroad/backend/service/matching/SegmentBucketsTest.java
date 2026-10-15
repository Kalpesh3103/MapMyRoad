package com.mapmyroad.backend.service.matching;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SegmentBucketsTest {

    @Test
    void bucketIsFloorOfDistanceAlongWayOver50m() {
        assertThat(SegmentBuckets.bucketFor(0.0, 230)).isEqualTo(0);
        assertThat(SegmentBuckets.bucketFor(49.9 / 230, 230)).isEqualTo(0);
        assertThat(SegmentBuckets.bucketFor(50.0 / 230, 230)).isEqualTo(1);
        assertThat(SegmentBuckets.bucketFor(120.0 / 230, 230)).isEqualTo(2);
        assertThat(SegmentBuckets.bucketFor(1.0, 230)).isEqualTo(4);
    }

    @Test
    void endOfWayWithLengthMultipleOf50StaysInLastBucket() {
        assertThat(SegmentBuckets.lastBucket(200)).isEqualTo(3);
        assertThat(SegmentBuckets.bucketFor(1.0, 200)).isEqualTo(3);
        assertThat(SegmentBuckets.bucketFor(1.0, 50)).isEqualTo(0);
    }

    @Test
    void outOfRangeInputsAreClamped() {
        assertThat(SegmentBuckets.bucketFor(-0.1, 230)).isEqualTo(0);
        assertThat(SegmentBuckets.bucketFor(1.5, 230)).isEqualTo(4);
        assertThat(SegmentBuckets.bucketFor(Double.NaN, 230)).isEqualTo(0);
        assertThat(SegmentBuckets.bucketFor(0.5, 0)).isEqualTo(0);
        assertThat(SegmentBuckets.bucketFor(0.5, 20)).isEqualTo(0);
    }
}
