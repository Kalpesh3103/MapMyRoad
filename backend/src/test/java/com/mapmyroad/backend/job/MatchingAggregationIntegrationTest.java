package com.mapmyroad.backend.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.mapmyroad.backend.integration.valhalla.MatchedPoint;
import com.mapmyroad.backend.integration.valhalla.TracePoint;
import com.mapmyroad.backend.integration.valhalla.ValhallaCosting;
import com.mapmyroad.backend.integration.valhalla.ValhallaMapMatcher;
import com.mapmyroad.backend.support.AbstractPostgisIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Matching, aggregation and the recompute queue end to end on PostGIS (TESTING_STRATEGY.md 5.2), with a fake map
 * matcher that snaps every point to one synthetic east-west way. Synthetic coordinates only.
 *
 * <p>Layout along the way (latitude 18.5, longitude 73.8500 to 73.8530, about 317 m): hazard H1 at 73.8510
 * reported by devices A and B 4 m apart; hazard H2 at 73.8525 reported by device C next to a speed bump node;
 * later clean passes over H1 by A, B and A again.
 */
class MatchingAggregationIntegrationTest extends AbstractPostgisIntegrationTest {

    private static final long WAY_ID = 9_000_001L;
    private static final long BUMP_NODE_ID = 9_100_001L;

    @TestConfiguration
    static class FakeMatcherConfig {

        /** Every point matches the test way, 1 m from the trace. */
        @Bean
        @Primary
        ValhallaMapMatcher fakeMapMatcher() {
            return (List<TracePoint> points, ValhallaCosting costing) ->
                    points.stream().map(p -> new MatchedPoint(WAY_ID, p.lat(), p.lon(), 1.0)).toList();
        }
    }

    @Autowired
    private MatchingJob matchingJob;

    @Autowired
    private AggregationJob aggregationJob;

    @Autowired
    private RecomputeQueueJob recomputeQueueJob;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private UUID deviceA;
    private UUID deviceB;
    private UUID deviceC;

    @BeforeEach
    void cleanAndSeed() {
        for (String table : new String[] {"hazard_clean_pass", "hazard_feedback", "hazard_recompute_queue",
                "hazard_event", "segment_observation", "hazard", "road_segment_stat"}) {
            jdbc.getJdbcOperations().update("DELETE FROM " + table); // fixed table names, test-only cleanup
        }
        jdbc.update("DELETE FROM osm_way WHERE way_id = :id", Map.of("id", WAY_ID));
        jdbc.update("DELETE FROM osm_calming_point WHERE node_id = :id", Map.of("id", BUMP_NODE_ID));
        jdbc.update("""
                INSERT INTO osm_way (way_id, highway, geom, length_m)
                SELECT :id, 'secondary', g, ST_Length(g::geography)
                FROM (SELECT ST_SetSRID(ST_MakeLine(ST_MakePoint(73.8500, 18.5000), ST_MakePoint(73.8530, 18.5000)),
                                        4326) AS g) w
                """, Map.of("id", WAY_ID));
        jdbc.update("""
                INSERT INTO osm_calming_point (node_id, kind, geom)
                VALUES (:id, 'bump', ST_SetSRID(ST_MakePoint(73.85258, 18.50002), 4326))
                """, Map.of("id", BUMP_NODE_ID));
        deviceA = insertDevice();
        deviceB = insertDevice();
        deviceC = insertDevice();
    }

    private UUID insertDevice() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO device (id) VALUES (:id)", Map.of("id", id));
        return id;
    }

    /** Received 10 minutes ago, so the matching settle delay (1 minute) has passed. */
    private void insertEvent(UUID deviceId, UUID trip, double lat, double lon, String age) {
        jdbc.update("""
                INSERT INTO hazard_event (id, device_id, trip_token, geom, severity, vehicle_type, speed_kmh,
                                          observed_at, received_at)
                VALUES (:id, :deviceId, :trip, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, 2, 1, 30,
                        now() - CAST(:age AS interval), now() - interval '10 minutes')
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID()).addValue("deviceId", deviceId).addValue("trip", trip)
                .addValue("lat", lat).addValue("lon", lon).addValue("age", age));
    }

    private void insertWindow(UUID deviceId, UUID trip, double lat, double startLon, double endLon, double roughness,
            String age) {
        jdbc.update("""
                INSERT INTO segment_observation (id, device_id, trip_token, geom, roughness, speed_kmh, vehicle_type,
                                                 observed_at, received_at)
                VALUES (:id, :deviceId, :trip,
                        ST_SetSRID(ST_MakeLine(ST_MakePoint(:startLon, :lat), ST_MakePoint(:endLon, :lat)),
                                   4326)::geography,
                        :roughness, 30, 1, now() - CAST(:age AS interval), now() - interval '10 minutes')
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID()).addValue("deviceId", deviceId).addValue("trip", trip)
                .addValue("lat", lat).addValue("startLon", startLon).addValue("endLon", endLon)
                .addValue("roughness", roughness).addValue("age", age));
    }

    /** Trip with one bump and a rough window over it. */
    private void bumpTrip(UUID deviceId, double lat, double lon) {
        UUID trip = UUID.randomUUID();
        insertWindow(deviceId, trip, lat, lon - 0.0004, lon + 0.0001, 1.5, "4 hours");
        insertEvent(deviceId, trip, lat, lon, "4 hours 1 second");
    }

    /** Trip with one smooth window passing over H1. */
    private void cleanPass(UUID deviceId, String age) {
        insertWindow(deviceId, UUID.randomUUID(), 18.50002, 73.8508, 73.8513, 0.2, age);
    }

    private long count(String sql) {
        Long n = jdbc.getJdbcOperations().queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private Map<String, Object> hazardNear(double lon) {
        return jdbc.queryForMap("""
                SELECT id, event_count, distinct_devices, status, suppressed_reason, clean_pass_count, fixed_at,
                       confidence, way_id
                FROM hazard
                WHERE ST_DWithin(geom, ST_SetSRID(ST_MakePoint(:lon, 18.50002), 4326)::geography, 15)
                """, Map.of("lon", lon));
    }

    @Test
    void matchesAggregatesAndRecomputes() {
        bumpTrip(deviceA, 18.50000, 73.8510);
        bumpTrip(deviceB, 18.50004, 73.8510); // about 4.4 m north of A's report
        bumpTrip(deviceC, 18.50000, 73.8525);
        cleanPass(deviceA, "3 hours");
        cleanPass(deviceB, "2 hours");
        cleanPass(deviceA, "1 hour");

        MatchingJob.Report matching = matchingJob.runOnce();

        assertThat(matching.trips()).isEqualTo(6);
        assertThat(matching.valhallaUnavailable()).isFalse();
        assertThat(count("SELECT count(*) FROM hazard_event WHERE matched_at IS NULL OR trip_token IS NOT NULL"))
                .isZero();
        assertThat(count("SELECT count(*) FROM segment_observation WHERE matched_at IS NULL OR trip_token IS NOT NULL"))
                .isZero();
        assertThat(count("SELECT count(*) FROM hazard_event WHERE way_id = " + WAY_ID)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM segment_observation WHERE way_id = " + WAY_ID + " AND bucket >= 0"))
                .isEqualTo(6);

        AggregationJob.Report aggregation = aggregationJob.runOnce();

        assertThat(aggregation.eventsClustered()).isEqualTo(3);
        assertThat(aggregation.windowsAggregated()).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM hazard")).isEqualTo(2);

        // Clustering: two devices' events about 4 m apart form one hazard.
        Map<String, Object> h1 = hazardNear(73.8510);
        assertThat(h1.get("event_count")).isEqualTo(2);
        assertThat(h1.get("distinct_devices")).isEqualTo(2);
        assertThat(((Number) h1.get("way_id")).longValue()).isEqualTo(WAY_ID);
        // Fixed after 3 clean passes from 2 devices since the last report.
        assertThat(h1.get("status")).isEqualTo("fixed");
        assertThat(h1.get("clean_pass_count")).isEqualTo(3);
        assertThat(h1.get("fixed_at")).isNotNull();
        assertThat(((Number) h1.get("confidence")).doubleValue()).isBetween(0.6, 0.67);

        // Suppression next to the OSM speed bump.
        Map<String, Object> h2 = hazardNear(73.8525);
        assertThat(h2.get("suppressed_reason")).isEqualTo("osm_traffic_calming");
        assertThat(h2.get("status")).isEqualTo("active");

        // Segment statistics: every window counted once, distinct devices per segment.
        assertThat(count("SELECT count(*) FROM road_segment_stat WHERE way_id = " + WAY_ID)).isGreaterThan(0);
        assertThat(count("SELECT sum(sample_count) FROM road_segment_stat WHERE way_id = " + WAY_ID)).isEqualTo(6);
        assertThat(count("SELECT max(distinct_devices) FROM road_segment_stat WHERE way_id = " + WAY_ID))
                .isGreaterThanOrEqualTo(2);
        assertThat(count("SELECT count(*) FROM road_segment_stat WHERE score NOT BETWEEN 0 AND 100")).isZero();

        // Idempotent: running both jobs again changes nothing.
        matchingJob.runOnce();
        AggregationJob.Report second = aggregationJob.runOnce();
        assertThat(second.eventsClustered()).isZero();
        assertThat(second.windowsAggregated()).isZero();
        assertThat(count("SELECT sum(sample_count) FROM road_segment_stat WHERE way_id = " + WAY_ID)).isEqualTo(6);

        // The nightly refresh keeps the state consistent.
        aggregationJob.runNightly();
        assertThat(hazardNear(73.8510).get("distinct_devices")).isEqualTo(2);

        // "Delete my data" for device C: queue its hazards, delete the device (cascade), consume the queue.
        UUID h2Id = (UUID) h2.get("id");
        jdbc.update("""
                INSERT INTO hazard_recompute_queue (hazard_id)
                SELECT DISTINCT hazard_id FROM hazard_event WHERE device_id = :deviceId AND hazard_id IS NOT NULL
                ON CONFLICT DO NOTHING
                """, Map.of("deviceId", deviceC));
        jdbc.update("DELETE FROM device WHERE id = :id", Map.of("id", deviceC));

        assertThat(recomputeQueueJob.runOnce()).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM hazard WHERE id = :id", Map.of("id", h2Id), Long.class))
                .isZero();
        assertThat(count("SELECT count(*) FROM hazard_recompute_queue")).isZero();
        assertThat(count("SELECT count(*) FROM hazard")).isEqualTo(1);
    }

    @Test
    void newReportReactivatesFixedHazard() {
        bumpTrip(deviceA, 18.50000, 73.8510);
        bumpTrip(deviceB, 18.50004, 73.8510);
        cleanPass(deviceA, "3 hours");
        cleanPass(deviceB, "2 hours");
        cleanPass(deviceA, "1 hour");
        matchingJob.runOnce();
        aggregationJob.runOnce();
        assertThat(hazardNear(73.8510).get("status")).isEqualTo("fixed");

        UUID trip = UUID.randomUUID();
        insertWindow(deviceC, trip, 18.50002, 73.8506, 73.8511, 1.4, "10 minutes");
        insertEvent(deviceC, trip, 18.50002, 73.8510, "10 minutes 1 second");
        matchingJob.runOnce();
        aggregationJob.runOnce();

        Map<String, Object> h1 = hazardNear(73.8510);
        assertThat(h1.get("status")).isEqualTo("active");
        assertThat(h1.get("clean_pass_count")).isEqualTo(0);
        assertThat(h1.get("fixed_at")).isNull();
        assertThat(h1.get("distinct_devices")).isEqualTo(3);
    }

    @Test
    void deletingAContributorRevertsAFixedStatusThatLostItsEvidence() {
        bumpTrip(deviceA, 18.50000, 73.8510);
        bumpTrip(deviceB, 18.50004, 73.8510);
        cleanPass(deviceA, "3 hours");
        cleanPass(deviceB, "2 hours");
        cleanPass(deviceA, "1 hour");
        matchingJob.runOnce();
        aggregationJob.runOnce();
        Map<String, Object> fixed = hazardNear(73.8510);
        assertThat(fixed.get("status")).isEqualTo("fixed");
        UUID h1Id = (UUID) fixed.get("id");

        // "Delete my data" for device B, as DeviceRepository queues it: hazards from its events and its clean passes.
        jdbc.update("""
                INSERT INTO hazard_recompute_queue (hazard_id)
                SELECT hazard_id FROM hazard_event WHERE device_id = :deviceId AND hazard_id IS NOT NULL
                UNION
                SELECT cp.hazard_id FROM hazard_clean_pass cp
                JOIN segment_observation so ON so.id = cp.observation_id
                WHERE so.device_id = :deviceId
                ON CONFLICT DO NOTHING
                """, Map.of("deviceId", deviceB));
        jdbc.update("DELETE FROM device WHERE id = :id", Map.of("id", deviceB));
        recomputeQueueJob.runOnce();

        // Two clean passes remain (both from A), below the 3 needed: B's influence is gone (PRD PV-3).
        Map<String, Object> h1 = jdbc.queryForMap(
                "SELECT status, fixed_at, clean_pass_count, event_count FROM hazard WHERE id = :id",
                Map.of("id", h1Id));
        assertThat(h1.get("status")).isEqualTo("active");
        assertThat(h1.get("fixed_at")).isNull();
        assertThat(h1.get("clean_pass_count")).isEqualTo(2);
        assertThat(h1.get("event_count")).isEqualTo(1);

        // The nightly refresh must not flip it back.
        aggregationJob.runNightly();
        assertThat(hazardNear(73.8510).get("status")).isEqualTo("active");
    }
}
