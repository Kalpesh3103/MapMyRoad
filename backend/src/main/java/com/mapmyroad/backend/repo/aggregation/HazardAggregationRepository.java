package com.mapmyroad.backend.repo.aggregation;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * Hazard aggregation statements (M4-10, ARCHITECTURE.md section 8, DATABASE_SCHEMA.md A9).
 *
 * <p>Distances are computed on {@code geography} (meters on the spheroid, DATABASE_SCHEMA.md A1), which also uses
 * the GiST indexes on {@code hazard.geom}, {@code hazard_event.geom} and {@code segment_observation.geom}. The OSM
 * tables store {@code geometry}; for calming points the index is used through a bounding-box prefilter in degrees
 * before the exact geography distance. Coordinates never leave the database here: new hazards are inserted with
 * {@code INSERT ... SELECT} from the event row.
 *
 * <p>Methods that take ID lists return 0 or an empty result for an empty list without running SQL, because
 * {@code IN ()} is invalid SQL.
 */
@Repository
public class HazardAggregationRepository {

    // hazard_id IS NULL is the "not yet clustered" marker (partial index in V8). Events whose hazard was deleted
    // (ON DELETE SET NULL) are clustered again, which is the desired behavior.
    private static final String FIND_UNCLUSTERED_EVENTS = """
            SELECT id FROM hazard_event
            WHERE matched_at IS NOT NULL AND hazard_id IS NULL
            ORDER BY matched_at, id
            LIMIT :limit
            """;

    // DATABASE_SCHEMA.md A9: nearest hazard within the radius, on the same way when both ways are known. Hazards of
    // every status qualify, so a report at a fixed or stale hazard reactivates it instead of creating a duplicate.
    private static final String FIND_NEAREST_HAZARD = """
            SELECT h.id
            FROM hazard_event e
            JOIN hazard h ON ST_DWithin(h.geom, e.geom, :radiusM)
            WHERE e.id = :eventId
              AND (e.way_id IS NULL OR h.way_id IS NULL OR h.way_id = e.way_id)
            ORDER BY ST_Distance(h.geom, e.geom), h.id
            LIMIT 1
            """;

    // Statistics are filled by RECOMPUTE_STATS in the same transaction.
    private static final String INSERT_HAZARD_FROM_EVENT = """
            INSERT INTO hazard (geom, way_id, severity_max, severity_avg, event_count, distinct_devices,
                                first_seen_at, last_seen_at, updated_at)
            SELECT e.geom, e.way_id, e.severity, e.severity, 0, 0, e.observed_at, e.observed_at, :now
            FROM hazard_event e
            WHERE e.id = :eventId
            RETURNING id
            """;

    private static final String ASSIGN_EVENT = """
            UPDATE hazard_event SET hazard_id = :hazardId WHERE id = :eventId AND hazard_id IS NULL
            """;

    // ARCHITECTURE.md 8.3: a new report at a fixed or stale hazard makes it active again. "New" means observed
    // after the latest clean pass, so a late upload of an old bump does not undo a repair.
    private static final String REACTIVATE_HAZARDS = """
            UPDATE hazard h SET status = 'active', clean_pass_count = 0, fixed_at = NULL, updated_at = :now
            WHERE h.status IN ('fixed', 'stale')
              AND EXISTS (
                SELECT 1 FROM hazard_event e
                WHERE e.id IN (:eventIds) AND e.hazard_id = h.id
                  AND (h.last_clean_pass_at IS NULL OR e.observed_at > h.last_clean_pass_at))
            """;

    // DATABASE_SCHEMA.md A9 "recompute hazard statistics", extended: the position becomes the centroid of the
    // remaining events (so a deleted contributor's exact location does not stay behind as the hazard position),
    // first_seen_at follows the remaining events, and a missing way is filled from the events. The centroid is
    // computed planar on geometry 4326, which is exact enough within a 12 m cluster.
    private static final String RECOMPUTE_STATS = """
            UPDATE hazard h SET
              event_count      = s.n,
              distinct_devices = s.d,
              severity_max     = s.smax,
              severity_avg     = s.savg,
              first_seen_at    = s.first_seen,
              last_seen_at     = s.last_seen,
              geom             = s.centroid,
              way_id           = COALESCE(h.way_id, s.any_way_id),
              updated_at       = :now
            FROM (
              SELECT hazard_id,
                     COUNT(*)                                        AS n,
                     COUNT(DISTINCT device_id)                       AS d,   -- NULL device IDs are ignored
                     MAX(severity)                                   AS smax,
                     AVG(severity)                                   AS savg,
                     MIN(observed_at)                                AS first_seen,
                     MAX(observed_at)                                AS last_seen,
                     ST_Centroid(ST_Collect(geom::geometry))::geography AS centroid,
                     MAX(way_id)                                     AS any_way_id
              FROM hazard_event
              WHERE hazard_id IN (:hazardIds)
              GROUP BY hazard_id
            ) s
            WHERE h.id = s.hazard_id
            """;

    // Hazards whose events are all gone but that still have feedback votes: counts drop to zero (confidence 0).
    private static final String RESET_STATS_WITHOUT_EVENTS = """
            UPDATE hazard h SET event_count = 0, distinct_devices = 0, updated_at = :now
            WHERE h.id IN (:hazardIds)
              AND (h.event_count <> 0 OR h.distinct_devices <> 0)
              AND NOT EXISTS (SELECT 1 FROM hazard_event e WHERE e.hazard_id = h.id)
            """;

    // hazard_feedback is the source of truth (one vote per device, latest wins); after a device deletion its votes
    // are gone by cascade, so the counts are recomputed rather than decremented.
    private static final String RECOMPUTE_FEEDBACK_COUNTS = """
            UPDATE hazard h SET confirm_count = f.confirms, dismiss_count = f.dismisses, updated_at = :now
            FROM (
              SELECT h2.id,
                     COUNT(hf.id) FILTER (WHERE hf.action = 'confirm') AS confirms,
                     COUNT(hf.id) FILTER (WHERE hf.action = 'dismiss') AS dismisses
              FROM hazard h2
              LEFT JOIN hazard_feedback hf ON hf.hazard_id = h2.id
              WHERE h2.id IN (:hazardIds)
              GROUP BY h2.id
            ) f
            WHERE h.id = f.id AND (h.confirm_count <> f.confirms OR h.dismiss_count <> f.dismisses)
            """;

    // TESTING_STRATEGY.md 5.2 / PV-3: a hazard with no remaining evidence disappears.
    private static final String DELETE_HAZARDS_WITHOUT_EVIDENCE = """
            DELETE FROM hazard h
            WHERE h.id IN (:hazardIds)
              AND NOT EXISTS (SELECT 1 FROM hazard_event e WHERE e.hazard_id = h.id)
              AND NOT EXISTS (SELECT 1 FROM hazard_feedback f WHERE f.hazard_id = h.id)
            RETURNING h.id
            """;

    // One trust score per distinct reporting device; devices whose ID was cleared by retention do not count.
    private static final String LOAD_CONFIDENCE_INPUTS = """
            SELECT h.id, h.last_seen_at, h.confirm_count, h.dismiss_count,
                   COALESCE(array_agg(d.trust_score) FILTER (WHERE d.id IS NOT NULL), '{}') AS trust_scores
            FROM hazard h
            LEFT JOIN (
              SELECT DISTINCT hazard_id, device_id FROM hazard_event
              WHERE hazard_id IN (:hazardIds) AND device_id IS NOT NULL
            ) e ON e.hazard_id = h.id
            LEFT JOIN device d ON d.id = e.device_id
            WHERE h.id IN (:hazardIds)
            GROUP BY h.id, h.last_seen_at, h.confirm_count, h.dismiss_count
            """;

    private static final String UPDATE_CONFIDENCE = """
            UPDATE hazard SET confidence = CAST(:confidence AS real), updated_at = :now
            WHERE id = :id AND confidence IS DISTINCT FROM CAST(:confidence AS real)
            """;

    // DATABASE_SCHEMA.md A9 "suppress known speed breakers and crossings". The && prefilter on the calming point's
    // geometry index uses a box of radius / (111320 m * cos(lat)) degrees, which is at least the radius in both
    // directions; ST_DWithin on geography then applies the exact distance in meters.
    private static final String SUPPRESSION_CONDITION = """
              h.suppressed_reason IS NULL
              AND c.geom && ST_Expand(h.geom::geometry,
                    :radiusM / (111320.0 * GREATEST(cos(radians(ST_Y(h.geom::geometry))), 0.01)))
              AND ST_DWithin(h.geom, c.geom::geography, :radiusM)
            """;

    private static final String SUPPRESS_HAZARDS = """
            UPDATE hazard h SET
              suppressed_reason = CASE c.kind WHEN 'level_crossing' THEN 'level_crossing'
                                              ELSE 'osm_traffic_calming' END,
              updated_at = :now
            FROM osm_calming_point c
            WHERE h.id IN (:hazardIds) AND
            """ + SUPPRESSION_CONDITION;

    // ARCHITECTURE.md 8.3 clean pass: a window from a known device, rough less than the limit, passing within the
    // radius of an active (or stale) hazard on the same way (or either way unknown), with no event from the same
    // device within the time window and distance of the window. The primary key makes it idempotent.
    private static final String RECORD_CLEAN_PASSES = """
            INSERT INTO hazard_clean_pass (hazard_id, observation_id, passed_at)
            SELECT h.id, w.id, w.observed_at
            FROM segment_observation w
            JOIN hazard h ON ST_DWithin(h.geom, w.geom, :radiusM)
            WHERE w.id IN (:windowIds)
              AND w.device_id IS NOT NULL
              AND w.roughness < :roughnessMax
              AND h.status IN ('active', 'stale')
              AND (w.way_id IS NULL OR h.way_id IS NULL OR h.way_id = w.way_id)
              AND NOT EXISTS (
                SELECT 1 FROM hazard_event e
                WHERE e.device_id = w.device_id
                  AND e.observed_at BETWEEN w.observed_at - :eventWindowSec * interval '1 second'
                                        AND w.observed_at + :eventWindowSec * interval '1 second'
                  AND ST_DWithin(e.geom, w.geom, :eventRadiusM))
            ON CONFLICT DO NOTHING
            RETURNING hazard_id
            """;

    // Clean passes count only after the latest report (last_seen_at), so a bump reported again resets the count.
    // A hazard becomes fixed with enough such passes from enough distinct devices (SECURITY.md T6).
    // With :allowRevert (recompute queue only, after a deletion or new feedback), a fixed hazard whose remaining
    // clean passes fall below :fixedAfter becomes active again, so a deleted device's influence disappears
    // (PRD PV-3). The revert counts rows, not devices: retention blanks device IDs after 90 days, which must not
    // undo a repair. The nightly refresh never reverts, because raw rows (and their clean passes) are deleted on
    // schedule after 365 days.
    private static final String REFRESH_CLEAN_PASS_STATE = """
            UPDATE hazard h SET
              clean_pass_count   = s.n,
              last_clean_pass_at = s.last_pass,
              status   = CASE WHEN h.status <> 'fixed' AND s.n >= :fixedAfter AND s.d >= :minDevices
                                THEN 'fixed'
                              WHEN :allowRevert AND h.status = 'fixed' AND s.n < :fixedAfter
                                THEN 'active'
                              ELSE h.status END,
              fixed_at = CASE WHEN h.status <> 'fixed' AND s.n >= :fixedAfter AND s.d >= :minDevices
                                THEN CAST(:now AS timestamptz)
                              WHEN :allowRevert AND h.status = 'fixed' AND s.n < :fixedAfter
                                THEN NULL
                              ELSE h.fixed_at END,
              updated_at = :now
            FROM (
              SELECT h2.id AS hazard_id,
                     COUNT(cp.observation_id) FILTER (WHERE cp.passed_at > h2.last_seen_at) AS n,
                     COUNT(DISTINCT so.device_id) FILTER (WHERE cp.passed_at > h2.last_seen_at) AS d,
                     MAX(cp.passed_at) AS last_pass
              FROM hazard h2
              LEFT JOIN hazard_clean_pass cp ON cp.hazard_id = h2.id
              LEFT JOIN segment_observation so ON so.id = cp.observation_id
              WHERE h2.id IN (:hazardIds)
              GROUP BY h2.id
            ) s
            WHERE h.id = s.hazard_id
              AND (h.clean_pass_count <> s.n
                   OR h.last_clean_pass_at IS DISTINCT FROM s.last_pass
                   OR (h.status <> 'fixed' AND s.n >= :fixedAfter AND s.d >= :minDevices)
                   OR (:allowRevert AND h.status = 'fixed' AND s.n < :fixedAfter))
            """;

    // ARCHITECTURE.md 8.3: no report and no clean pass for staleAfterDays. Batched by id (no UPDATE ... LIMIT).
    private static final String MARK_STALE = """
            UPDATE hazard SET status = 'stale', updated_at = :now
            WHERE id IN (
              SELECT id FROM hazard
              WHERE status = 'active'
                AND last_seen_at < :cutoff
                AND (last_clean_pass_at IS NULL OR last_clean_pass_at < :cutoff)
              LIMIT :batchSize)
            """;

    // Oldest entries first; SKIP LOCKED so a second instance (if one ever runs) takes different rows. The rows are
    // deleted in the caller's transaction, so a failed recompute puts them back by rolling back.
    private static final String POLL_RECOMPUTE_QUEUE = """
            DELETE FROM hazard_recompute_queue
            WHERE hazard_id IN (
              SELECT hazard_id FROM hazard_recompute_queue
              ORDER BY queued_at, hazard_id
              LIMIT :batchSize
              FOR UPDATE SKIP LOCKED)
            RETURNING hazard_id
            """;

    private static final String FIND_HAZARD_IDS_AFTER = """
            SELECT id FROM hazard WHERE id > :afterId ORDER BY id LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public HazardAggregationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UUID> findUnclusteredEvents(int limit) {
        return jdbc.query(FIND_UNCLUSTERED_EVENTS, new MapSqlParameterSource("limit", limit),
                (rs, i) -> rs.getObject("id", UUID.class));
    }

    public Optional<UUID> findNearestHazard(UUID eventId, double radiusM) {
        var params = new MapSqlParameterSource().addValue("eventId", eventId).addValue("radiusM", radiusM);
        List<UUID> ids = jdbc.query(FIND_NEAREST_HAZARD, params, (rs, i) -> rs.getObject("id", UUID.class));
        return ids.stream().findFirst();
    }

    public Optional<UUID> insertHazardFromEvent(UUID eventId, Instant now) {
        var params = new MapSqlParameterSource().addValue("eventId", eventId).addValue("now", timestamp(now));
        List<UUID> ids = jdbc.query(INSERT_HAZARD_FROM_EVENT, params, (rs, i) -> rs.getObject("id", UUID.class));
        return ids.stream().findFirst();
    }

    public int assignEvent(UUID eventId, UUID hazardId) {
        return jdbc.update(ASSIGN_EVENT, new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("hazardId", hazardId));
    }

    public int reactivateHazards(Collection<UUID> eventIds, Instant now) {
        if (eventIds.isEmpty()) {
            return 0;
        }
        return jdbc.update(REACTIVATE_HAZARDS, new MapSqlParameterSource()
                .addValue("eventIds", eventIds)
                .addValue("now", timestamp(now)));
    }

    public int recomputeStats(Collection<UUID> hazardIds, Instant now) {
        return updateHazards(RECOMPUTE_STATS, hazardIds, now);
    }

    public int resetStatsWithoutEvents(Collection<UUID> hazardIds, Instant now) {
        return updateHazards(RESET_STATS_WITHOUT_EVENTS, hazardIds, now);
    }

    public int recomputeFeedbackCounts(Collection<UUID> hazardIds, Instant now) {
        return updateHazards(RECOMPUTE_FEEDBACK_COUNTS, hazardIds, now);
    }

    public List<UUID> deleteHazardsWithoutEvidence(Collection<UUID> hazardIds) {
        if (hazardIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(DELETE_HAZARDS_WITHOUT_EVIDENCE, new MapSqlParameterSource("hazardIds", hazardIds),
                (rs, i) -> rs.getObject("id", UUID.class));
    }

    public List<ConfidenceInput> loadConfidenceInputs(Collection<UUID> hazardIds) {
        if (hazardIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(LOAD_CONFIDENCE_INPUTS, new MapSqlParameterSource("hazardIds", hazardIds),
                (rs, i) -> new ConfidenceInput(
                        rs.getObject("id", UUID.class),
                        instant(rs, "last_seen_at"),
                        rs.getInt("confirm_count"),
                        rs.getInt("dismiss_count"),
                        doubles(rs.getArray("trust_scores"))));
    }

    public int updateConfidence(Map<UUID, Double> confidenceByHazard, Instant now) {
        if (confidenceByHazard.isEmpty()) {
            return 0;
        }
        List<SqlParameterSource> batch = new ArrayList<>(confidenceByHazard.size());
        confidenceByHazard.forEach((id, confidence) -> batch.add(new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("confidence", confidence)
                .addValue("now", timestamp(now))));
        int total = 0;
        for (int count : jdbc.batchUpdate(UPDATE_CONFIDENCE, batch.toArray(SqlParameterSource[]::new))) {
            total += Math.max(count, 0);
        }
        return total;
    }

    /**
     * Suppresses the given hazards near a calming point. Used for touched hazards and, by the nightly pass, for
     * all hazards in keyset batches (so calming points added by an OSM refresh also apply to older hazards).
     */
    public int suppress(Collection<UUID> hazardIds, double radiusM, Instant now) {
        if (hazardIds.isEmpty()) {
            return 0;
        }
        return jdbc.update(SUPPRESS_HAZARDS, new MapSqlParameterSource()
                .addValue("hazardIds", hazardIds)
                .addValue("radiusM", radiusM)
                .addValue("now", timestamp(now)));
    }

    /** Records clean passes of the given windows; returns the IDs of hazards that got a new clean pass. */
    public List<UUID> recordCleanPasses(
            Collection<UUID> windowIds,
            double radiusM,
            double roughnessMax,
            double eventWindowSec,
            double eventRadiusM) {
        if (windowIds.isEmpty()) {
            return List.of();
        }
        var params = new MapSqlParameterSource()
                .addValue("windowIds", windowIds)
                .addValue("radiusM", radiusM)
                .addValue("roughnessMax", roughnessMax)
                .addValue("eventWindowSec", eventWindowSec)
                .addValue("eventRadiusM", eventRadiusM);
        return jdbc.query(RECORD_CLEAN_PASSES, params, (rs, i) -> rs.getObject("hazard_id", UUID.class))
                .stream().distinct().toList();
    }

    /**
     * Recounts clean passes and applies the fixed transition. {@code allowRevert} lets a fixed hazard return to
     * active when its evidence was removed; pass it only from the recompute-queue path.
     */
    public int refreshCleanPassState(
            Collection<UUID> hazardIds, int fixedAfter, int minDevices, boolean allowRevert, Instant now) {
        if (hazardIds.isEmpty()) {
            return 0;
        }
        return jdbc.update(REFRESH_CLEAN_PASS_STATE, new MapSqlParameterSource()
                .addValue("hazardIds", hazardIds)
                .addValue("fixedAfter", fixedAfter)
                .addValue("minDevices", minDevices)
                .addValue("allowRevert", allowRevert)
                .addValue("now", timestamp(now)));
    }

    public int markStale(Instant cutoff, int batchSize, Instant now) {
        return jdbc.update(MARK_STALE, new MapSqlParameterSource()
                .addValue("cutoff", timestamp(cutoff))
                .addValue("batchSize", batchSize)
                .addValue("now", timestamp(now)));
    }

    /** Must run inside the transaction that recomputes the returned hazards. */
    public List<UUID> pollRecomputeQueue(int batchSize) {
        return jdbc.query(POLL_RECOMPUTE_QUEUE, new MapSqlParameterSource("batchSize", batchSize),
                (rs, i) -> rs.getObject("hazard_id", UUID.class));
    }

    /** Keyset iteration over all hazards for the nightly refresh. */
    public List<UUID> findHazardIdsAfter(UUID afterId, int limit) {
        return jdbc.query(FIND_HAZARD_IDS_AFTER, new MapSqlParameterSource()
                .addValue("afterId", afterId)
                .addValue("limit", limit), (rs, i) -> rs.getObject("id", UUID.class));
    }

    private int updateHazards(String sql, Collection<UUID> hazardIds, Instant now) {
        if (hazardIds.isEmpty()) {
            return 0;
        }
        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("hazardIds", hazardIds)
                .addValue("now", timestamp(now)));
    }

    private static List<Double> doubles(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        try {
            Object[] values = (Object[]) array.getArray();
            List<Double> result = new ArrayList<>(values.length);
            for (Object value : values) {
                if (value instanceof Number number) {
                    result.add(number.doubleValue());
                }
            }
            return result;
        } finally {
            array.free();
        }
    }

    /** The PostgreSQL driver binds OffsetDateTime to timestamptz; it does not accept Instant directly. */
    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
