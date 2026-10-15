package com.mapmyroad.backend.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mapmyroad.backend.config.MatchingProperties;
import com.mapmyroad.backend.integration.valhalla.ValhallaUnavailableException;
import com.mapmyroad.backend.service.matching.MatchingService;
import com.mapmyroad.backend.service.matching.TripMatchResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatchingJobTest {

    private final MatchingService service = mock(MatchingService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MatchingJob job = new MatchingJob(service, MatchingProperties.defaults(), registry);

    @Test
    void stopsAtFirstUnavailableAndKeepsCounts() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        when(service.pendingTrips(anyInt())).thenReturn(List.of(first, second, third));
        when(service.matchTrip(first)).thenReturn(new TripMatchResult(2, 1, 3, 0));
        when(service.matchTrip(second)).thenThrow(new ValhallaUnavailableException("down"));
        when(service.backlogEvents()).thenReturn(4L);
        when(service.backlogWindows()).thenReturn(6L);

        MatchingJob.Report report = job.runOnce();

        assertThat(report.trips()).isEqualTo(1);
        assertThat(report.rowsMatched()).isEqualTo(5);
        assertThat(report.rowsUnmatched()).isEqualTo(1);
        assertThat(report.valhallaUnavailable()).isTrue();
        verify(service, never()).matchTrip(third);
        assertThat(registry.get(MatchingJob.METRIC_RUNS).tag("outcome", "valhalla_unavailable").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(MatchingJob.METRIC_BACKLOG).tag("kind", "window").gauge().value()).isEqualTo(6.0);
        assertThat(registry.get(MatchingJob.METRIC_ROWS).tag("kind", "window").tag("result", "matched")
                .counter().count()).isEqualTo(3.0);
    }

    @Test
    void disabledScheduleDoesNothing() {
        MatchingJob disabled = new MatchingJob(service,
                new MatchingProperties(false, null, null, null, null, null, null), registry);

        disabled.runScheduled();

        verify(service, never()).pendingTrips(anyInt());
    }
}
