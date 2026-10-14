package com.mapmyroad.backend.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.config.IngestProperties;
import com.mapmyroad.backend.config.MmrProperties;
import com.mapmyroad.backend.domain.ingest.EventObservation;
import com.mapmyroad.backend.domain.ingest.IngestResult;
import com.mapmyroad.backend.domain.ingest.RejectionReason;
import com.mapmyroad.backend.domain.ingest.UploadBatch;
import com.mapmyroad.backend.domain.ingest.VehicleType;
import com.mapmyroad.backend.domain.ingest.WindowObservation;
import com.mapmyroad.backend.repo.ingest.IngestRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IngestServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T08:30:00Z");
    private static final String BBOX = "72.6,17.9,75.1,19.6";
    private static final UUID DEVICE = UUID.randomUUID();
    private static final UUID TRIP = UUID.randomUUID();

    private final IngestRepository repository = mock(IngestRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private IngestService service(boolean uploadsEnabled) {
        return new IngestService(
                repository,
                new MmrProperties(BBOX, "1.0.0", uploadsEnabled),
                IngestProperties.defaults(),
                meters,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static EventObservation event(double speedKmh) {
        return new EventObservation(UUID.randomUUID(), 18.52, 73.85, NOW.minusSeconds(30), 2, speedKmh, 2.8, 90);
    }

    private static WindowObservation window() {
        return new WindowObservation(
                UUID.randomUUID(), 18.5201, 73.8565, 18.5206, 73.8568, NOW.minusSeconds(40), 52, 0.42, 28);
    }

    @Test
    void storesValidItemsAndCountsDuplicatesAndRejections() {
        EventObservation good1 = event(30);
        EventObservation good2 = event(40);
        EventObservation slow = event(3);
        UploadBatch batch = new UploadBatch(
                TRIP, VehicleType.SCOOTER, List.of(good1, slow, good2), List.of(window(), window()));
        when(repository.touchDevice(DEVICE)).thenReturn(1);
        when(repository.insertEvents(eq(DEVICE), eq(TRIP), eq(VehicleType.SCOOTER), anyList())).thenReturn(1);
        when(repository.insertWindows(eq(DEVICE), eq(TRIP), eq(VehicleType.SCOOTER), anyList())).thenReturn(2);

        IngestResult result = service(true).ingest(DEVICE, batch);

        assertThat(result.acceptedEvents()).isEqualTo(1);
        assertThat(result.duplicateEvents()).isEqualTo(1);
        assertThat(result.acceptedWindows()).isEqualTo(2);
        assertThat(result.duplicateWindows()).isZero();
        assertThat(result.rejected()).hasSize(1);
        assertThat(result.rejected().get(0).id()).isEqualTo(slow.id());
        assertThat(result.rejected().get(0).reason()).isEqualTo(RejectionReason.IMPLAUSIBLE_SPEED);
        // Rejected items never reach the repository.
        verify(repository).insertEvents(DEVICE, TRIP, VehicleType.SCOOTER, List.of(good1, good2));

        assertThat(meters.counter(IngestService.METRIC_ACCEPTED, "kind", "event").count()).isEqualTo(1.0);
        assertThat(meters.counter(IngestService.METRIC_ACCEPTED, "kind", "window").count()).isEqualTo(2.0);
        assertThat(meters.counter(IngestService.METRIC_DUPLICATES, "kind", "event").count()).isEqualTo(1.0);
        assertThat(meters.counter(IngestService.METRIC_REJECTED, "kind", "event", "reason", "IMPLAUSIBLE_SPEED")
                .count()).isEqualTo(1.0);
    }

    @Test
    void killSwitchReturnsUpstreamUnavailableAndStoresNothing() {
        UploadBatch batch = new UploadBatch(TRIP, VehicleType.CAR, List.of(event(30)), List.of());

        assertThatThrownBy(() -> service(false).ingest(DEVICE, batch))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
                    assertThat(e.getMessage()).isEqualTo("Uploads are paused.");
                });
        verify(repository, never()).touchDevice(any());
    }

    @Test
    void emptyBatchIsInvalidRequest() {
        UploadBatch batch = new UploadBatch(TRIP, VehicleType.CAR, List.of(), List.of());

        assertThatThrownBy(() -> service(true).ingest(DEVICE, batch))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(e.properties()).containsKey("errors");
                });
        verify(repository, never()).touchDevice(any());
    }

    @Test
    void tooManyEventsIsInvalidRequest() {
        List<EventObservation> events = Collections.nCopies(501, event(30));
        UploadBatch batch = new UploadBatch(TRIP, VehicleType.CAR, events, List.of());

        assertThatThrownBy(() -> service(true).ingest(DEVICE, batch))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void deletedDeviceIsUnauthorizedAndNothingIsInserted() {
        UploadBatch batch = new UploadBatch(TRIP, VehicleType.CAR, List.of(event(30)), List.of());
        when(repository.touchDevice(DEVICE)).thenReturn(0);

        assertThatThrownBy(() -> service(true).ingest(DEVICE, batch))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verify(repository, never()).insertEvents(any(), any(), any(), anyList());
    }

    @Test
    void allItemsRejectedStillTouchesDeviceAndReturnsRejections() {
        UploadBatch batch = new UploadBatch(TRIP, VehicleType.CAR, List.of(event(500)), List.of());
        when(repository.touchDevice(DEVICE)).thenReturn(1);

        IngestResult result = service(true).ingest(DEVICE, batch);

        assertThat(result.acceptedEvents()).isZero();
        assertThat(result.duplicateEvents()).isZero();
        assertThat(result.rejected()).hasSize(1);
    }
}
