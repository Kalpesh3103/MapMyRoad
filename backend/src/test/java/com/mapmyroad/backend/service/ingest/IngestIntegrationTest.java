package com.mapmyroad.backend.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.mapmyroad.backend.domain.ingest.EventObservation;
import com.mapmyroad.backend.domain.ingest.IngestResult;
import com.mapmyroad.backend.domain.ingest.RejectionReason;
import com.mapmyroad.backend.domain.ingest.UploadBatch;
import com.mapmyroad.backend.domain.ingest.VehicleType;
import com.mapmyroad.backend.domain.ingest.WindowObservation;
import com.mapmyroad.backend.support.AbstractPostgisIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Ingest against real PostgreSQL + PostGIS (TESTING_STRATEGY.md section 5.2). Synthetic coordinates only. */
class IngestIntegrationTest extends AbstractPostgisIntegrationTest {

    @Autowired
    private IngestService ingestService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private UUID deviceId;

    @BeforeEach
    void createDevice() {
        jdbc.getJdbcOperations().update("DELETE FROM hazard_event");
        jdbc.getJdbcOperations().update("DELETE FROM segment_observation");
        deviceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO device (id, last_seen_at) VALUES (:id, now() - interval '3 days')",
                new MapSqlParameterSource("id", deviceId));
    }

    private static EventObservation event(double speedKmh) {
        Instant at = Instant.now().minus(1, ChronoUnit.HOURS);
        return new EventObservation(UUID.randomUUID(), 18.5204, 73.8567, at, 2, speedKmh, 2.8, 187);
    }

    private static WindowObservation window() {
        Instant at = Instant.now().minus(1, ChronoUnit.HOURS);
        return new WindowObservation(UUID.randomUUID(), 18.5201, 73.8565, 18.5206, 73.8568, at, 52, 0.42, 28);
    }

    @Test
    void storesItemsAndCountsRetriesAsDuplicates() {
        EventObservation good = event(32.5);
        EventObservation slow = event(4);
        WindowObservation w = window();
        UploadBatch batch = new UploadBatch(UUID.randomUUID(), VehicleType.SCOOTER, List.of(good, slow), List.of(w));

        IngestResult first = ingestService.ingest(deviceId, batch);
        IngestResult retry = ingestService.ingest(deviceId, batch);

        assertThat(first.acceptedEvents()).isEqualTo(1);
        assertThat(first.acceptedWindows()).isEqualTo(1);
        assertThat(first.duplicateEvents()).isZero();
        assertThat(first.rejected()).hasSize(1);
        assertThat(first.rejected().get(0).reason()).isEqualTo(RejectionReason.IMPLAUSIBLE_SPEED);
        assertThat(retry.acceptedEvents()).isZero();
        assertThat(retry.acceptedWindows()).isZero();
        assertThat(retry.duplicateEvents()).isEqualTo(1);
        assertThat(retry.duplicateWindows()).isEqualTo(1);

        Integer slowRows = jdbc.queryForObject(
                "SELECT count(*) FROM hazard_event WHERE id = :id", Map.of("id", slow.id()), Integer.class);
        assertThat(slowRows).isZero();
    }

    @Test
    void storesGeometryInLonLatOrderWithCodes() {
        EventObservation good = event(32.5);
        WindowObservation w = window();
        UUID trip = UUID.randomUUID();
        ingestService.ingest(deviceId, new UploadBatch(trip, VehicleType.AUTO_RICKSHAW, List.of(good), List.of(w)));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT ST_X(geom::geometry) AS x, ST_Y(geom::geometry) AS y, vehicle_type, severity, heading_deg,
                       device_id, trip_token
                FROM hazard_event WHERE id = :id
                """, Map.of("id", good.id()));
        assertThat(((Number) row.get("x")).doubleValue()).isCloseTo(73.8567, within(1e-9));
        assertThat(((Number) row.get("y")).doubleValue()).isCloseTo(18.5204, within(1e-9));
        assertThat(((Number) row.get("vehicle_type")).intValue()).isEqualTo(4);
        assertThat(((Number) row.get("severity")).intValue()).isEqualTo(2);
        assertThat(((Number) row.get("heading_deg")).intValue()).isEqualTo(187);
        assertThat(row.get("device_id")).isEqualTo(deviceId);
        assertThat(row.get("trip_token")).isEqualTo(trip);

        Map<String, Object> line = jdbc.queryForMap("""
                SELECT ST_X(ST_StartPoint(geom::geometry)) AS sx, ST_Y(ST_StartPoint(geom::geometry)) AS sy,
                       ST_X(ST_EndPoint(geom::geometry)) AS ex, ST_Y(ST_EndPoint(geom::geometry)) AS ey
                FROM segment_observation WHERE id = :id
                """, Map.of("id", w.id()));
        assertThat(((Number) line.get("sx")).doubleValue()).isCloseTo(73.8565, within(1e-9));
        assertThat(((Number) line.get("sy")).doubleValue()).isCloseTo(18.5201, within(1e-9));
        assertThat(((Number) line.get("ex")).doubleValue()).isCloseTo(73.8568, within(1e-9));
        assertThat(((Number) line.get("ey")).doubleValue()).isCloseTo(18.5206, within(1e-9));
    }

    @Test
    void updatesDeviceLastSeen() {
        ingestService.ingest(deviceId, new UploadBatch(UUID.randomUUID(), VehicleType.CAR, List.of(event(30)), List.of()));

        Boolean recent = jdbc.queryForObject(
                "SELECT last_seen_at > now() - interval '1 minute' FROM device WHERE id = :id",
                Map.of("id", deviceId), Boolean.class);
        assertThat(recent).isTrue();
    }
}
