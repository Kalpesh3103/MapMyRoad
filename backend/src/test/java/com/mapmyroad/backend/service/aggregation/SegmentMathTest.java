package com.mapmyroad.backend.service.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.mapmyroad.backend.repo.aggregation.SegmentKey;
import com.mapmyroad.backend.repo.aggregation.SegmentStatRow;
import com.mapmyroad.backend.repo.aggregation.SegmentStatUpdate;
import com.mapmyroad.backend.repo.aggregation.WindowSample;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SegmentMathTest {

    private static final double GOOD = 0.3;
    private static final double POOR = 2.0;

    @Test
    void scoreMatchesTheAppCurve() {
        assertThat(SegmentMath.score(0.0, GOOD, POOR)).isEqualTo(100);
        assertThat(SegmentMath.score(0.3, GOOD, POOR)).isEqualTo(100);
        assertThat(SegmentMath.score(1.15, GOOD, POOR)).isEqualTo(50);
        assertThat(SegmentMath.score(2.0, GOOD, POOR)).isEqualTo(0);
        assertThat(SegmentMath.score(5.0, GOOD, POOR)).isEqualTo(0);
        assertThat(SegmentMath.score(Double.NaN, GOOD, POOR)).isEqualTo(0);
        // (2.0 - 0.42) / 1.7 * 100 = 92.94 -> 93, as TripScoring.roughnessToScore rounds.
        assertThat(SegmentMath.score(0.42, GOOD, POOR)).isEqualTo(93);
    }

    @Test
    void scoreIsMonotonicNonIncreasing() {
        int previous = 100;
        for (double r = 0; r < 3; r += 0.01) {
            int score = SegmentMath.score(r, GOOD, POOR);
            assertThat(score).isLessThanOrEqualTo(previous).isBetween(0, 100);
            previous = score;
        }
    }

    @Test
    void ewmaStartsAtFirstSampleAndWeightsNewSamples() {
        assertThat(SegmentMath.ewma(null, List.of(1.0), 0.2)).isEqualTo(1.0);
        assertThat(SegmentMath.ewma(null, List.of(1.0, 2.0), 0.2)).isCloseTo(1.2, within(1e-9));
        assertThat(SegmentMath.ewma(1.0, List.of(2.0), 0.2)).isCloseTo(1.2, within(1e-9));
        assertThat(SegmentMath.ewma(1.0, List.of(2.0, 2.0), 0.2)).isCloseTo(1.36, within(1e-9));
        assertThat(SegmentMath.ewma(0.7, List.of(), 0.2)).isEqualTo(0.7);
        assertThatThrownBy(() -> SegmentMath.ewma(null, List.of(), 0.2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plannerGroupsByKeyInTimeOrderAndSkipsWindowsWithoutWay() {
        Instant t0 = Instant.parse("2026-10-13T08:30:00Z");
        List<WindowSample> windows = List.of(
                new WindowSample(UUID.randomUUID(), 10L, 0, 2.0, t0.plusSeconds(20)),
                new WindowSample(UUID.randomUUID(), 10L, 0, 1.0, t0),
                new WindowSample(UUID.randomUUID(), 10L, 1, 0.3, t0.plusSeconds(5)),
                new WindowSample(UUID.randomUUID(), null, null, 0.1, t0));
        List<SegmentStatRow> existing = List.of(new SegmentStatRow(new SegmentKey(10L, 1), 1.0, 7));

        List<SegmentStatUpdate> updates = SegmentStatPlanner.plan(windows, existing, 0.2, GOOD, POOR);

        assertThat(updates).hasSize(2);
        SegmentStatUpdate first = updates.get(0);
        assertThat(first.key()).isEqualTo(new SegmentKey(10L, 0));
        assertThat(first.roughnessAvg()).isCloseTo(1.2, within(1e-9)); // 1.0 first (earlier), then 2.0
        assertThat(first.newSamples()).isEqualTo(2);
        assertThat(first.lastObservedAt()).isEqualTo(t0.plusSeconds(20));
        SegmentStatUpdate second = updates.get(1);
        assertThat(second.roughnessAvg()).isCloseTo(0.2 * 0.3 + 0.8 * 1.0, within(1e-9));
        assertThat(second.score()).isEqualTo(SegmentMath.score(second.roughnessAvg(), GOOD, POOR));
        assertThat(second.newSamples()).isEqualTo(1);
    }
}
