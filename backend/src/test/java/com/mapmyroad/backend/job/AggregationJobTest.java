package com.mapmyroad.backend.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mapmyroad.backend.config.AggregationProperties;
import com.mapmyroad.backend.service.aggregation.AggregationLock;
import com.mapmyroad.backend.service.aggregation.AggregationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AggregationJobTest {

    private final AggregationService service = mock(AggregationService.class);
    private final AggregationLock lock = new AggregationLock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AggregationProperties properties = AggregationProperties.defaults();
    private final AggregationJob job = new AggregationJob(service, properties, lock, registry);
    private final RecomputeQueueJob recompute = new RecomputeQueueJob(service, properties, lock, registry);

    @Test
    void repeatsFullBatchesAndStopsAtAPartialOne() {
        int full = properties.batchSize();
        when(service.clusterBatch()).thenReturn(
                new AggregationService.ClusterBatch(full, 3, 3), new AggregationService.ClusterBatch(2, 1, 1));
        when(service.windowBatch()).thenReturn(new AggregationService.WindowBatch(7, 2, 1));
        when(service.staleBatch()).thenReturn(0);

        AggregationJob.Report report = job.runOnce();

        verify(service, times(2)).clusterBatch();
        verify(service, times(1)).windowBatch();
        assertThat(report.eventsClustered()).isEqualTo(full + 2L);
        assertThat(report.hazardsCreated()).isEqualTo(4);
        assertThat(report.windowsAggregated()).isEqualTo(7);
        assertThat(registry.get(AggregationJob.METRIC_ROWS).tag("step", "events_clustered").counter().count())
                .isEqualTo(full + 2.0);
    }

    @Test
    void recomputeSkipsWhileAggregationRuns() {
        AtomicReference<Long> inner = new AtomicReference<>();
        when(service.windowBatch()).thenReturn(new AggregationService.WindowBatch(0, 0, 0));

        // ReentrantLock is reentrant on the same thread, so run the competing call on another thread.
        when(service.clusterBatch()).thenAnswer(invocation -> {
            Thread other = new Thread(() -> inner.set(recompute.runOnce()));
            other.start();
            other.join();
            return new AggregationService.ClusterBatch(0, 0, 0);
        });

        job.runOnce();

        assertThat(inner.get()).isEqualTo(-1L);
        verify(service, never()).recomputeBatch();
    }

    @Test
    void recomputeDrainsQueue() {
        when(service.recomputeBatch()).thenReturn(
                new AggregationService.RecomputeBatch(properties.batchSize(), 1),
                new AggregationService.RecomputeBatch(3, 2));

        assertThat(recompute.runOnce()).isEqualTo(properties.batchSize() + 3L);
        verify(service, times(2)).recomputeBatch();
    }
}
