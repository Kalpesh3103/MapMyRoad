package com.mapmyroad.backend.repo.hazards;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Hazard reads for {@code GET /v1/hazards} and the feedback writes for {@code POST /v1/hazards/{id}/feedback}
 * (API_SPEC.md 5.5 and 5.6). Every read applies {@link VisibilityRules}.
 *
 * <p>Geography versus geometry (DATABASE_SCHEMA.md A1): {@code hazard.geom} is geography. The bbox filter uses
 * {@code &&} against the envelope cast to geography, which can use the GiST index, then an exact planar
 * {@code ST_Intersects} on geometry, because the API box is a lon/lat rectangle and not a geodesic polygon. The
 * feedback proximity check uses {@code ST_DWithin} on geography so the radius is in meters.
 */
@Repository
public class HazardRepository {

    private static final String COLUMNS = """
            h.id, ST_X(h.geom::geometry) AS lon, ST_Y(h.geom::geometry) AS lat, h.severity_max, h.confidence,
            h.status, h.first_seen_at, h.last_seen_at, h.confirm_count, h.dismiss_count""";

    private static final String LIST_IN_BBOX = """
            SELECT %s
            FROM %s
            WHERE h.geom && ST_MakeEnvelope(:minLon, :minLat, :maxLon, :maxLat, 4326)::geography
              AND ST_Intersects(h.geom::geometry, ST_MakeEnvelope(:minLon, :minLat, :maxLon, :maxLat, 4326))
              AND h.severity_max >= :minSeverity
              AND h.status = :status
              AND %s
            ORDER BY h.severity_max DESC, h.confidence DESC, h.id
            LIMIT :limit
            """.formatted(COLUMNS, VisibilityRules.HAZARD_FROM, VisibilityRules.HAZARD_VISIBLE_ANY_STATUS);

    private static final String FIND_VISIBLE = """
            SELECT %s
            FROM %s
            WHERE h.id = :id
              AND %s
            """.formatted(COLUMNS, VisibilityRules.HAZARD_FROM, VisibilityRules.HAZARD_VISIBLE_ANY_STATUS);

    // Locks the hazard row so concurrent votes on the same hazard serialize: the recount below then always sees
    // the other transaction's committed vote. Feedback is allowed on hazards that pass the privacy rules and are
    // active, stale, or fixed recently (post-trip review lags the drive, ADR-0013). The confidence floor is not
    // applied, so a hazard that dropped just below it can still be confirmed back.
    private static final String FEEDBACK_TARGET = """
            SELECT ST_DWithin(h.geom, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :maxDistanceM) AS near
            FROM %s
            WHERE h.id = :id
              AND (h.status <> 'fixed' OR h.fixed_at >= :fixedSince)
              AND %s
            FOR UPDATE OF h
            """.formatted(VisibilityRules.HAZARD_FROM, VisibilityRules.HAZARD_PRIVACY);

    // One vote per device and hazard, the latest submission wins. Selecting from device turns a vote from a
    // deleted device into zero rows instead of a foreign key error.
    private static final String UPSERT_FEEDBACK = """
            INSERT INTO hazard_feedback (hazard_id, device_id, action, observed_at)
            SELECT CAST(:hazardId AS uuid), d.id, CAST(:action AS text), CAST(:observedAt AS timestamptz)
            FROM device d WHERE d.id = :deviceId
            ON CONFLICT (hazard_id, device_id) DO UPDATE
              SET action = EXCLUDED.action, observed_at = EXCLUDED.observed_at, created_at = now()
            """;

    // Exact counts from the votes, not increments, so replaced votes and deletions cannot make them drift.
    private static final String RECOUNT_FEEDBACK = """
            UPDATE hazard h
            SET confirm_count = c.confirms, dismiss_count = c.dismisses, updated_at = now()
            FROM (
              SELECT count(*) FILTER (WHERE f.action = 'confirm') AS confirms,
                     count(*) FILTER (WHERE f.action = 'dismiss') AS dismisses
              FROM hazard_feedback f
              WHERE f.hazard_id = :id
            ) c
            WHERE h.id = :id
            RETURNING h.confidence, h.status
            """;

    private static final String ENQUEUE_RECOMPUTE = """
            INSERT INTO hazard_recompute_queue (hazard_id) VALUES (:id) ON CONFLICT DO NOTHING
            """;

    private static final RowMapper<HazardRow> ROW_MAPPER = HazardRepository::mapRow;

    private final NamedParameterJdbcTemplate jdbc;
    private final VisibilityRules visibility;

    public HazardRepository(NamedParameterJdbcTemplate jdbc, VisibilityRules visibility) {
        this.jdbc = jdbc;
        this.visibility = visibility;
    }

    /** Visible hazards with the given status inside the box, most severe and confident first. */
    public List<HazardRow> findInBbox(
            double minLon, double minLat, double maxLon, double maxLat, int minSeverity, String status, int limit) {
        MapSqlParameterSource params = visibility.bind(new MapSqlParameterSource()
                .addValue("minLon", minLon)
                .addValue("minLat", minLat)
                .addValue("maxLon", maxLon)
                .addValue("maxLat", maxLat)
                .addValue("minSeverity", minSeverity)
                .addValue("status", status)
                .addValue("limit", limit));
        return jdbc.query(LIST_IN_BBOX, params, ROW_MAPPER);
    }

    /** The hazard if it exists and passes the visibility rules (any status). */
    public Optional<HazardRow> findVisible(UUID id) {
        MapSqlParameterSource params = visibility.bind(new MapSqlParameterSource("id", id));
        return jdbc.query(FIND_VISIBLE, params, ROW_MAPPER).stream().findFirst();
    }

    /**
     * Locks a hazard that may receive feedback and reports whether the point lies within {@code maxDistanceM}.
     * Empty when the hazard is unknown or not eligible. Must run inside a transaction.
     */
    public Optional<Boolean> lockFeedbackTarget(
            UUID id, double lat, double lon, double maxDistanceM, Instant fixedSince) {
        MapSqlParameterSource params = visibility.bind(new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("lat", lat)
                .addValue("lon", lon)
                .addValue("maxDistanceM", maxDistanceM)
                .addValue("fixedSince", toTimestamptz(fixedSince)));
        return jdbc.query(FEEDBACK_TARGET, params, (rs, i) -> rs.getBoolean("near")).stream().findFirst();
    }

    /** Stores or replaces the device's vote. Returns false when the device no longer exists. */
    public boolean upsertFeedback(UUID hazardId, UUID deviceId, String action, Instant observedAt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("hazardId", hazardId)
                .addValue("deviceId", deviceId)
                .addValue("action", action)
                .addValue("observedAt", toTimestamptz(observedAt));
        return jdbc.update(UPSERT_FEEDBACK, params) > 0;
    }

    /** Recomputes confirm and dismiss counts from the votes and returns the stored confidence and status. */
    public HazardState recountFeedback(UUID hazardId) {
        List<HazardState> states = jdbc.query(
                RECOUNT_FEEDBACK,
                new MapSqlParameterSource("id", hazardId),
                (rs, i) -> new HazardState(rs.getDouble("confidence"), rs.getString("status")));
        if (states.isEmpty()) {
            throw new IllegalStateException("Locked hazard disappeared during feedback");
        }
        return states.get(0);
    }

    /** Asks the aggregation job to recompute the hazard's confidence. */
    public void enqueueRecompute(UUID hazardId) {
        jdbc.update(ENQUEUE_RECOMPUTE, new MapSqlParameterSource("id", hazardId));
    }

    private static HazardRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new HazardRow(
                rs.getObject("id", UUID.class),
                rs.getDouble("lon"),
                rs.getDouble("lat"),
                rs.getInt("severity_max"),
                rs.getDouble("confidence"),
                rs.getString("status"),
                rs.getObject("first_seen_at", OffsetDateTime.class).toInstant(),
                rs.getObject("last_seen_at", OffsetDateTime.class).toInstant(),
                rs.getInt("confirm_count"),
                rs.getInt("dismiss_count"));
    }

    private static OffsetDateTime toTimestamptz(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
