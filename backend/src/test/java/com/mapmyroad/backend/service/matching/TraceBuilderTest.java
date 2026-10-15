package com.mapmyroad.backend.service.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.mapmyroad.backend.integration.valhalla.TracePoint;
import com.mapmyroad.backend.repo.matching.PendingEvent;
import com.mapmyroad.backend.repo.matching.PendingWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Synthetic coordinates only. */
class TraceBuilderTest {

    private static final Instant T0 = Instant.parse("2026-10-13T08:30:00Z");

    @Test
    void ordersEventAndWindowPointsByTime() {
        // 50 m at 36 km/h = 10 m/s takes 5 s: the window spans [T0 + 5 s, T0 + 10 s].
        PendingWindow window = new PendingWindow(UUID.randomUUID(), 1.0, 1.0, 2.0, 2.0, T0.plusSeconds(10), 50, 36, 1);
        PendingEvent inside = new PendingEvent(UUID.randomUUID(), 1.5, 1.5, T0.plusSeconds(7), 1);
        PendingEvent before = new PendingEvent(UUID.randomUUID(), 0.5, 0.5, T0, 1);

        Trace trace = TraceBuilder.build(List.of(inside, before), List.of(window));

        assertThat(trace.points()).extracting(TracePoint::lat).containsExactly(0.5, 1.0, 1.5, 2.0);
        assertThat(trace.eventPoint()).containsExactly(2, 0);
        assertThat(trace.windowStartPoint()).containsExactly(1);
        assertThat(trace.windowEndPoint()).containsExactly(3);
        assertThat(trace.points().get(1).time()).isEqualTo(T0.plusSeconds(5));
    }

    @Test
    void tiesKeepInputOrder() {
        PendingEvent a = new PendingEvent(UUID.randomUUID(), 1.0, 1.0, T0, 2);
        PendingEvent b = new PendingEvent(UUID.randomUUID(), 2.0, 2.0, T0, 2);

        Trace trace = TraceBuilder.build(List.of(a, b), List.of());

        assertThat(trace.eventPoint()).containsExactly(0, 1);
    }

    @Test
    void durationEstimateIsBounded() {
        assertThat(TraceBuilder.estimatedDuration(50, 36)).isEqualTo(Duration.ofSeconds(5));
        assertThat(TraceBuilder.estimatedDuration(0, 36)).isEqualTo(Duration.ZERO);
        assertThat(TraceBuilder.estimatedDuration(100, 0)).isEqualTo(Duration.ofSeconds(100));
        assertThat(TraceBuilder.estimatedDuration(1000, 0)).isEqualTo(Duration.ofMinutes(2));
        assertThat(TraceBuilder.estimatedDuration(100, Double.NaN)).isEqualTo(Duration.ofSeconds(100));
    }
}
