package com.mapmyroad.backend.repo.device;

import com.mapmyroad.backend.domain.device.NewDevice;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL for the {@code device} table and the "delete my data" statements (DATABASE_SCHEMA.md sections A4, A10). */
@Repository
public class DeviceRepository {

    private static final String INSERT = """
            INSERT INTO device (app_version, os_api_level, trust_score)
            VALUES (:appVersion, :osApiLevel, :trustScore)
            RETURNING id
            """;

    private static final String EXISTS = "SELECT EXISTS (SELECT 1 FROM device WHERE id = :id)";

    private static final String TOUCH_LAST_SEEN = "UPDATE device SET last_seen_at = :now WHERE id = :id";

    private static final String QUEUE_HAZARDS_FROM_EVENTS = """
            INSERT INTO hazard_recompute_queue (hazard_id)
              SELECT DISTINCT hazard_id FROM hazard_event WHERE device_id = :deviceId AND hazard_id IS NOT NULL
              ON CONFLICT DO NOTHING
            """;

    private static final String QUEUE_HAZARDS_FROM_FEEDBACK = """
            INSERT INTO hazard_recompute_queue (hazard_id)
              SELECT DISTINCT hazard_id FROM hazard_feedback WHERE device_id = :deviceId
              ON CONFLICT DO NOTHING
            """;

    /** Cascades to refresh tokens, events, observations and feedback (foreign keys ON DELETE CASCADE). */
    private static final String DELETE = "DELETE FROM device WHERE id = :deviceId";

    private final NamedParameterJdbcTemplate jdbc;

    public DeviceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(NewDevice device) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("appVersion", device.appVersion())
                .addValue("osApiLevel", device.osApiLevel())
                .addValue("trustScore", device.trustScore());
        return jdbc.queryForObject(INSERT, params, (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    public boolean exists(UUID id) {
        Boolean exists = jdbc.queryForObject(EXISTS, new MapSqlParameterSource("id", id), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public void touchLastSeen(UUID id, Instant now) {
        jdbc.update(TOUCH_LAST_SEEN, new MapSqlParameterSource().addValue("id", id).addValue("now", utc(now)));
    }

    /** Queues every hazard this device contributed to, so it is recomputed without that contribution. */
    public void queueContributedHazardsForRecompute(UUID deviceId) {
        MapSqlParameterSource params = new MapSqlParameterSource("deviceId", deviceId);
        jdbc.update(QUEUE_HAZARDS_FROM_EVENTS, params);
        jdbc.update(QUEUE_HAZARDS_FROM_FEEDBACK, params);
    }

    /** Returns the number of device rows deleted (0 or 1). */
    public int delete(UUID deviceId) {
        return jdbc.update(DELETE, new MapSqlParameterSource("deviceId", deviceId));
    }

    /** PostgreSQL JDBC binds {@link OffsetDateTime} to timestamptz; {@link Instant} is not portable. */
    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
