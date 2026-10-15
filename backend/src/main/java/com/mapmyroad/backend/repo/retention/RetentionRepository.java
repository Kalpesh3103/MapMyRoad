package com.mapmyroad.backend.repo.retention;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Batched statements of the nightly retention job (DATABASE_SCHEMA.md A10). Every method touches at most
 * {@code batchSize} rows, so the caller can commit after each batch instead of holding one huge transaction and
 * its locks. Batches are selected by primary key in a subquery ({@code id IN (SELECT ... LIMIT n)}), because
 * PostgreSQL has no {@code UPDATE ... LIMIT}. Each statement only matches rows that still need the change, so
 * repeating it is harmless and the caller loops until a batch affects 0 rows.
 *
 * <p>Cutoffs are parameters computed from the application clock rather than {@code now()} in SQL, so tests can
 * pin time and all steps of one run use the same reference instant.
 */
@Repository
public class RetentionRepository {

    private static final String CLEAR_EVENT_DEVICE_IDS = """
            UPDATE hazard_event SET device_id = NULL
            WHERE id IN (
              SELECT id FROM hazard_event
              WHERE device_id IS NOT NULL AND received_at < :cutoff
              LIMIT :batchSize)
            """;

    private static final String CLEAR_OBSERVATION_DEVICE_IDS = """
            UPDATE segment_observation SET device_id = NULL
            WHERE id IN (
              SELECT id FROM segment_observation
              WHERE device_id IS NOT NULL AND received_at < :cutoff
              LIMIT :batchSize)
            """;

    private static final String CLEAR_EVENT_TRIP_TOKENS = """
            UPDATE hazard_event SET trip_token = NULL
            WHERE id IN (
              SELECT id FROM hazard_event
              WHERE trip_token IS NOT NULL AND received_at < :cutoff
              LIMIT :batchSize)
            """;

    private static final String CLEAR_OBSERVATION_TRIP_TOKENS = """
            UPDATE segment_observation SET trip_token = NULL
            WHERE id IN (
              SELECT id FROM segment_observation
              WHERE trip_token IS NOT NULL AND received_at < :cutoff
              LIMIT :batchSize)
            """;

    private static final String DELETE_EVENTS = """
            DELETE FROM hazard_event
            WHERE id IN (
              SELECT id FROM hazard_event
              WHERE received_at < :cutoff
              LIMIT :batchSize)
            """;

    private static final String DELETE_OBSERVATIONS = """
            DELETE FROM segment_observation
            WHERE id IN (
              SELECT id FROM segment_observation
              WHERE received_at < :cutoff
              LIMIT :batchSize)
            """;

    private static final String DELETE_EXPIRED_GEOCODE = """
            DELETE FROM geocode_cache
            WHERE cache_key IN (
              SELECT cache_key FROM geocode_cache
              WHERE expires_at < :cutoff
              LIMIT :batchSize)
            """;

    private static final String FIND_INACTIVE_DEVICES = """
            SELECT id FROM device
            WHERE last_seen_at < :cutoff
            LIMIT :batchSize
            """;

    // Same as "delete my data" (DATABASE_SCHEMA.md A10): hazards influenced by the device are queued for
    // recomputation before the cascade removes its feedback votes, its clean passes (V8, through its windows) and
    // any events still linked to it.
    private static final String ENQUEUE_HAZARDS_OF_DEVICES = """
            INSERT INTO hazard_recompute_queue (hazard_id)
            SELECT hazard_id FROM hazard_event WHERE device_id IN (:deviceIds) AND hazard_id IS NOT NULL
            UNION
            SELECT hazard_id FROM hazard_feedback WHERE device_id IN (:deviceIds)
            UNION
            SELECT cp.hazard_id FROM hazard_clean_pass cp
            JOIN segment_observation so ON so.id = cp.observation_id
            WHERE so.device_id IN (:deviceIds)
            ON CONFLICT DO NOTHING
            """;

    // last_seen_at is checked again so a device that uploaded since FIND_INACTIVE_DEVICES ran is kept.
    private static final String DELETE_DEVICES = """
            DELETE FROM device WHERE id IN (:deviceIds) AND last_seen_at < :cutoff
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RetentionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int clearEventDeviceIds(Instant cutoff, int batchSize) {
        return jdbc.update(CLEAR_EVENT_DEVICE_IDS, params(cutoff, batchSize));
    }

    public int clearObservationDeviceIds(Instant cutoff, int batchSize) {
        return jdbc.update(CLEAR_OBSERVATION_DEVICE_IDS, params(cutoff, batchSize));
    }

    public int clearEventTripTokens(Instant cutoff, int batchSize) {
        return jdbc.update(CLEAR_EVENT_TRIP_TOKENS, params(cutoff, batchSize));
    }

    public int clearObservationTripTokens(Instant cutoff, int batchSize) {
        return jdbc.update(CLEAR_OBSERVATION_TRIP_TOKENS, params(cutoff, batchSize));
    }

    public int deleteEvents(Instant cutoff, int batchSize) {
        return jdbc.update(DELETE_EVENTS, params(cutoff, batchSize));
    }

    public int deleteObservations(Instant cutoff, int batchSize) {
        return jdbc.update(DELETE_OBSERVATIONS, params(cutoff, batchSize));
    }

    public int deleteExpiredGeocodeEntries(Instant now, int batchSize) {
        return jdbc.update(DELETE_EXPIRED_GEOCODE, params(now, batchSize));
    }

    public List<UUID> findInactiveDevices(Instant cutoff, int batchSize) {
        return jdbc.query(FIND_INACTIVE_DEVICES, params(cutoff, batchSize), (rs, i) -> rs.getObject("id", UUID.class));
    }

    /** Must run in the same transaction as {@link #deleteDevices}. */
    public int enqueueHazardsOfDevices(List<UUID> deviceIds) {
        return jdbc.update(ENQUEUE_HAZARDS_OF_DEVICES, new MapSqlParameterSource("deviceIds", deviceIds));
    }

    public int deleteDevices(List<UUID> deviceIds, Instant cutoff) {
        var params = new MapSqlParameterSource()
                .addValue("deviceIds", deviceIds)
                .addValue("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
        return jdbc.update(DELETE_DEVICES, params);
    }

    /** The PostgreSQL driver binds OffsetDateTime to timestamptz; it does not accept Instant directly. */
    private static MapSqlParameterSource params(Instant cutoff, int batchSize) {
        return new MapSqlParameterSource()
                .addValue("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .addValue("batchSize", batchSize);
    }
}
