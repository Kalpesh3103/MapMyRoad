package com.mapmyroad.backend.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.mapmyroad.backend.support.AbstractPostgisIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Time-travel retention test (TESTING_STRATEGY.md section 6): rows are inserted with explicit ages, then the job
 * runs once. Synthetic coordinates only.
 */
class RetentionJobIntegrationTest extends AbstractPostgisIntegrationTest {

    @Autowired
    private RetentionJob job;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private UUID activeDevice;

    @BeforeEach
    void cleanAndSeed() {
        for (String table : new String[] {"hazard_event", "segment_observation", "geocode_cache",
                "hazard_recompute_queue"}) {
            jdbc.getJdbcOperations().update("DELETE FROM " + table); // fixed table names, test-only cleanup
        }
        activeDevice = insertDevice("now()");
    }

    private UUID insertDevice(String lastSeenExpression) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO device (id, last_seen_at) VALUES (:id, " + lastSeenExpression + ")",
                new MapSqlParameterSource("id", id));
        return id;
    }

    private UUID insertEvent(UUID deviceId, String age) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO hazard_event (id, device_id, trip_token, geom, severity, vehicle_type, speed_kmh,
                                          observed_at, received_at)
                VALUES (:id, :deviceId, :trip, ST_SetSRID(ST_MakePoint(73.8567, 18.5204), 4326)::geography, 2, 2, 30,
                        now() - CAST(:age AS interval), now() - CAST(:age AS interval))
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("deviceId", deviceId).addValue("trip", UUID.randomUUID())
                .addValue("age", age));
        return id;
    }

    private UUID insertWindow(UUID deviceId, String age) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO segment_observation (id, device_id, trip_token, geom, roughness, speed_kmh, vehicle_type,
                                                 observed_at, received_at)
                VALUES (:id, :deviceId, :trip,
                        ST_SetSRID(ST_MakeLine(ST_MakePoint(73.8565, 18.5201), ST_MakePoint(73.8568, 18.5206)), 4326)::geography,
                        0.4, 28, 2, now() - CAST(:age AS interval), now() - CAST(:age AS interval))
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("deviceId", deviceId).addValue("trip", UUID.randomUUID())
                .addValue("age", age));
        return id;
    }

    private Map<String, Object> eventRow(UUID id) {
        return jdbc.queryForMap(
                "SELECT device_id, trip_token FROM hazard_event WHERE id = :id", Map.of("id", id));
    }

    private int count(String sql, UUID id) {
        Integer n = jdbc.queryForObject(sql, Map.of("id", id), Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void appliesAllRetentionRules() {
        UUID fresh = insertEvent(activeDevice, "1 hour");
        UUID dayOld = insertEvent(activeDevice, "25 hours");
        UUID aged91 = insertEvent(activeDevice, "91 days");
        UUID aged89 = insertEvent(activeDevice, "89 days");
        UUID aged366 = insertEvent(activeDevice, "366 days");
        UUID window91 = insertWindow(activeDevice, "91 days");
        UUID window366 = insertWindow(activeDevice, "366 days");
        jdbc.getJdbcOperations().update("""
                INSERT INTO geocode_cache (cache_key, response, expires_at) VALUES
                  ('expired', '{}'::jsonb, now() - interval '1 hour'),
                  ('valid', '{}'::jsonb, now() + interval '1 day')
                """);
        UUID inactiveDevice = insertDevice("now() - interval '13 months'");
        UUID recentlyIdleDevice = insertDevice("now() - interval '11 months'");

        RetentionReport report = job.runOnce();

        // Trip tokens: cleared after 24 hours.
        assertThat(eventRow(fresh).get("trip_token")).isNotNull();
        assertThat(eventRow(dayOld).get("trip_token")).isNull();
        // Device IDs: removed after 90 days.
        assertThat(eventRow(dayOld).get("device_id")).isEqualTo(activeDevice);
        assertThat(eventRow(aged89).get("device_id")).isEqualTo(activeDevice);
        assertThat(eventRow(aged91).get("device_id")).isNull();
        assertThat(count("SELECT count(*) FROM segment_observation WHERE id = :id AND device_id IS NULL", window91))
                .isEqualTo(1);
        // Raw rows: deleted after 365 days.
        assertThat(count("SELECT count(*) FROM hazard_event WHERE id = :id", aged366)).isZero();
        assertThat(count("SELECT count(*) FROM segment_observation WHERE id = :id", window366)).isZero();
        // Geocode cache: expired rows deleted.
        assertThat(jdbc.getJdbcOperations().queryForList("SELECT cache_key FROM geocode_cache", String.class))
                .containsExactly("valid");
        // Devices: deleted after 12 months without activity.
        assertThat(count("SELECT count(*) FROM device WHERE id = :id", inactiveDevice)).isZero();
        assertThat(count("SELECT count(*) FROM device WHERE id = :id", recentlyIdleDevice)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM device WHERE id = :id", activeDevice)).isEqualTo(1);

        assertThat(report.rows(RetentionJob.STEP_EVENTS_DELETED)).isEqualTo(1);
        assertThat(report.rows(RetentionJob.STEP_DEVICES_DELETED)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void runningTwiceIsSafe() {
        insertEvent(activeDevice, "91 days");
        insertEvent(activeDevice, "366 days");

        job.runOnce();
        RetentionReport second = job.runOnce();

        assertThat(second.rowsByStep().values()).allMatch(rows -> rows == 0L);
    }
}
