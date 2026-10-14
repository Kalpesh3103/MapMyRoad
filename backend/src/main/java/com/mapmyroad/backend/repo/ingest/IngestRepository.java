package com.mapmyroad.backend.repo.ingest;

import com.mapmyroad.backend.domain.ingest.EventObservation;
import com.mapmyroad.backend.domain.ingest.VehicleType;
import com.mapmyroad.backend.domain.ingest.WindowObservation;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * Stores uploaded events and roughness windows (DATABASE_SCHEMA.md A6). Inserts are idempotent through
 * {@code ON CONFLICT (id) DO NOTHING} on the client-generated IDs, so retried uploads are safe.
 *
 * <p>Geometry is built as {@code geometry} with SRID 4326 and cast to {@code geography}, because the raw tables
 * use geography (distances in meters, DATABASE_SCHEMA.md A1). Note the PostGIS argument order: longitude first.
 * {@code received_at} keeps its column default {@code now()}, which is the transaction start time.
 */
@Repository
public class IngestRepository {

    private static final String TOUCH_DEVICE = """
            UPDATE device SET last_seen_at = now() WHERE id = :deviceId
            """;

    private static final String INSERT_EVENT = """
            INSERT INTO hazard_event
              (id, device_id, trip_token, geom, heading_deg, severity, vehicle_type, speed_kmh, peak_score, observed_at)
            VALUES
              (:id, :deviceId, :tripToken, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
               :headingDeg, :severity, :vehicleType, :speedKmh, :peakScore, :observedAt)
            ON CONFLICT (id) DO NOTHING
            """;

    private static final String INSERT_WINDOW = """
            INSERT INTO segment_observation
              (id, device_id, trip_token, geom, roughness, speed_kmh, vehicle_type, observed_at)
            VALUES
              (:id, :deviceId, :tripToken,
               ST_SetSRID(ST_MakeLine(ST_MakePoint(:startLon, :startLat), ST_MakePoint(:endLon, :endLat)), 4326)::geography,
               :roughness, :speedKmh, :vehicleType, :observedAt)
            ON CONFLICT (id) DO NOTHING
            """;

    // Fallback counts, used only when the driver reports SUCCESS_NO_INFO (for example with
    // reWriteBatchedInserts=true). Rows inserted by the current transaction carry received_at = now(), because
    // now() is fixed for the whole transaction; rows from earlier uploads were stamped by another transaction.
    private static final String COUNT_EVENTS_INSERTED_NOW = """
            SELECT count(*) FROM hazard_event
            WHERE id IN (:ids) AND device_id = :deviceId AND received_at = now()
            """;

    private static final String COUNT_WINDOWS_INSERTED_NOW = """
            SELECT count(*) FROM segment_observation
            WHERE id IN (:ids) AND device_id = :deviceId AND received_at = now()
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public IngestRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Marks the device as active. Returns 0 when the device no longer exists (for example after deletion). */
    public int touchDevice(UUID deviceId) {
        return jdbc.update(TOUCH_DEVICE, new MapSqlParameterSource("deviceId", deviceId));
    }

    /** Inserts events and returns how many were new (the rest already existed). */
    public int insertEvents(UUID deviceId, UUID tripToken, VehicleType vehicleType, List<EventObservation> events) {
        if (events.isEmpty()) {
            return 0;
        }
        SqlParameterSource[] batch = events.stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("id", e.id())
                        .addValue("deviceId", deviceId)
                        .addValue("tripToken", tripToken)
                        .addValue("lat", e.lat())
                        .addValue("lon", e.lon())
                        .addValue("headingDeg", e.headingDeg(), Types.SMALLINT)
                        .addValue("severity", e.severity(), Types.SMALLINT)
                        .addValue("vehicleType", vehicleType.code(), Types.SMALLINT)
                        .addValue("speedKmh", e.speedKmh())
                        .addValue("peakScore", e.peakScore())
                        .addValue("observedAt", toTimestamptz(e.observedAt())))
                .toArray(SqlParameterSource[]::new);
        int[] counts = jdbc.batchUpdate(INSERT_EVENT, batch);
        return insertedCount(counts, COUNT_EVENTS_INSERTED_NOW, deviceId, events.stream().map(EventObservation::id).toList());
    }

    /** Inserts windows and returns how many were new (the rest already existed). */
    public int insertWindows(UUID deviceId, UUID tripToken, VehicleType vehicleType, List<WindowObservation> windows) {
        if (windows.isEmpty()) {
            return 0;
        }
        SqlParameterSource[] batch = windows.stream()
                .map(w -> new MapSqlParameterSource()
                        .addValue("id", w.id())
                        .addValue("deviceId", deviceId)
                        .addValue("tripToken", tripToken)
                        .addValue("startLat", w.startLat())
                        .addValue("startLon", w.startLon())
                        .addValue("endLat", w.endLat())
                        .addValue("endLon", w.endLon())
                        .addValue("roughness", w.roughness())
                        .addValue("speedKmh", w.speedKmh())
                        .addValue("vehicleType", vehicleType.code(), Types.SMALLINT)
                        .addValue("observedAt", toTimestamptz(w.observedAt())))
                .toArray(SqlParameterSource[]::new);
        int[] counts = jdbc.batchUpdate(INSERT_WINDOW, batch);
        return insertedCount(
                counts, COUNT_WINDOWS_INSERTED_NOW, deviceId, windows.stream().map(WindowObservation::id).toList());
    }

    /**
     * Each batch count is 1 (inserted) or 0 (ON CONFLICT skipped it). If the driver cannot tell
     * ({@link Statement#SUCCESS_NO_INFO}), count this transaction's rows instead.
     */
    private int insertedCount(int[] counts, String fallbackSql, UUID deviceId, List<UUID> ids) {
        int inserted = 0;
        for (int count : counts) {
            if (count == Statement.SUCCESS_NO_INFO) {
                return countInsertedNow(fallbackSql, deviceId, ids);
            }
            inserted += count;
        }
        return inserted;
    }

    private int countInsertedNow(String sql, UUID deviceId, List<UUID> ids) {
        var params = new MapSqlParameterSource().addValue("ids", ids).addValue("deviceId", deviceId);
        Integer count = jdbc.queryForObject(sql, params, Integer.class);
        return count == null ? 0 : count;
    }

    /** The PostgreSQL driver binds OffsetDateTime to timestamptz; it does not accept Instant directly. */
    private static OffsetDateTime toTimestamptz(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
