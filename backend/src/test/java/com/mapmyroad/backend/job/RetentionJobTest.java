package com.mapmyroad.backend.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mapmyroad.backend.config.RetentionProperties;
import com.mapmyroad.backend.repo.retention.RetentionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Step order, cutoffs and batching loop of the retention job, without a database. */
class RetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-10-13T22:00:00Z");

    private final RetentionRepository repository = mock(RetentionRepository.class);
    private final CountingTransactionManager transactions = new CountingTransactionManager();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RetentionJob job = new RetentionJob(
            repository, RetentionProperties.defaults(), transactions, meters, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void usesConfiguredCutoffsAndLoopsUntilABatchAffectsNothing() {
        Instant deviceIdCutoff = Instant.parse("2026-07-15T22:00:00Z"); // 90 days
        Instant tripTokenCutoff = Instant.parse("2026-10-12T22:00:00Z"); // 24 hours
        Instant rawCutoff = Instant.parse("2025-10-13T22:00:00Z"); // 365 days
        Instant inactiveCutoff = Instant.parse("2025-10-13T22:00:00Z"); // 12 months
        when(repository.clearEventDeviceIds(deviceIdCutoff, 5000)).thenReturn(5000, 1200, 0);
        when(repository.deleteEvents(rawCutoff, 5000)).thenReturn(7, 0);

        RetentionReport report = job.runOnce();

        assertThat(report.rows(RetentionJob.STEP_EVENT_DEVICE_IDS)).isEqualTo(6200);
        assertThat(report.rows(RetentionJob.STEP_EVENTS_DELETED)).isEqualTo(7);
        assertThat(report.rows(RetentionJob.STEP_DEVICES_DELETED)).isZero();
        verify(repository, times(3)).clearEventDeviceIds(deviceIdCutoff, 5000);
        verify(repository).clearObservationDeviceIds(deviceIdCutoff, 5000);
        verify(repository).clearEventTripTokens(tripTokenCutoff, 5000);
        verify(repository).clearObservationTripTokens(tripTokenCutoff, 5000);
        verify(repository, times(2)).deleteEvents(rawCutoff, 5000);
        verify(repository).deleteObservations(rawCutoff, 5000);
        verify(repository).deleteExpiredGeocodeEntries(NOW, 5000);
        verify(repository).findInactiveDevices(inactiveCutoff, 5000);
        verify(repository, never()).deleteDevices(anyList(), eq(inactiveCutoff));
        // Every batch is its own transaction: 3 + 1 + 1 + 1 + 2 + 1 + 1 + 1.
        assertThat(transactions.commits).isEqualTo(11);
        assertThat(report.rowsByStep().keySet()).containsExactly(
                RetentionJob.STEP_EVENT_DEVICE_IDS,
                RetentionJob.STEP_OBSERVATION_DEVICE_IDS,
                RetentionJob.STEP_EVENT_TRIP_TOKENS,
                RetentionJob.STEP_OBSERVATION_TRIP_TOKENS,
                RetentionJob.STEP_EVENTS_DELETED,
                RetentionJob.STEP_OBSERVATIONS_DELETED,
                RetentionJob.STEP_GEOCODE_DELETED,
                RetentionJob.STEP_DEVICES_DELETED);
        assertThat(meters.counter(RetentionJob.METRIC_ROWS, "step", RetentionJob.STEP_EVENT_DEVICE_IDS).count())
                .isEqualTo(6200.0);
        assertThat(meters.counter(RetentionJob.METRIC_RUNS, "outcome", "success").count()).isEqualTo(1.0);
    }

    @Test
    void inactiveDevicesAreQueuedForRecomputeBeforeDeletion() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(repository.findInactiveDevices(any(), anyInt()))
                .thenReturn(ids, List.of());
        when(repository.deleteDevices(eq(ids), any())).thenReturn(2);

        RetentionReport report = job.runOnce();

        assertThat(report.rows(RetentionJob.STEP_DEVICES_DELETED)).isEqualTo(2);
        InOrder order = inOrder(repository);
        order.verify(repository).enqueueHazardsOfDevices(ids);
        order.verify(repository).deleteDevices(eq(ids), any());
    }

    @Test
    void secondRunWithNothingLeftChangesNothing() {
        RetentionReport report = job.runOnce();
        assertThat(report.rowsByStep().values()).allMatch(rows -> rows == 0L);
    }

    /** Minimal transaction manager that counts commits; the repository is mocked, so no real resources exist. */
    private static final class CountingTransactionManager implements PlatformTransactionManager {
        int commits;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            commits++;
        }

        @Override
        public void rollback(TransactionStatus status) {}
    }
}
