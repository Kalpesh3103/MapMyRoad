package com.mapmyroad.backend.repo.matching;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * Statements of the map-matching job (M4-9, DATABASE_SCHEMA.md A6).
 *
 * <p>Raw rows store {@code geography}; coordinates are read with {@code ST_X/ST_Y} on the {@code geometry} cast
 * (same SRID 4326, lossless). {@code osm_way.geom} is {@code geometry}: {@code ST_LineLocatePoint} needs geometry,
 * and its planar fraction in degrees is a good enough approximation of the along-way fraction over the length of a
 * single way. Every update is guarded by {@code matched_at IS NULL}, so a repeated or overlapping run cannot match
 * a row twice.
 */
@Repository
public class MatchingRepository {

    // Trips with unmatched rows, oldest first. A trip is taken only once its newest row is older than the settle
    // cutoff, so a trip uploaded in several requests is usually matched in one trace. Uses the partial
    // "pending" indexes (V3) and the partial trip-token indexes (V8).
    private static final String FIND_PENDING_TRIPS = """
            SELECT trip_token
            FROM (
              SELECT trip_token, received_at FROM hazard_event
              WHERE matched_at IS NULL AND trip_token IS NOT NULL
              UNION ALL
              SELECT trip_token, received_at FROM segment_observation
              WHERE matched_at IS NULL AND trip_token IS NOT NULL
            ) pending
            GROUP BY trip_token
            HAVING max(received_at) < :settledBefore
            ORDER BY min(received_at)
            LIMIT :limit
            """;

    private static final String FIND_TRIP_EVENTS = """
            SELECT id, ST_Y(geom::geometry) AS lat, ST_X(geom::geometry) AS lon, observed_at, vehicle_type
            FROM hazard_event
            WHERE trip_token = :tripToken AND matched_at IS NULL
            """;

    // ST_Length on geography is in meters.
    private static final String FIND_TRIP_WINDOWS = """
            SELECT id,
                   ST_Y(ST_StartPoint(geom::geometry)) AS start_lat, ST_X(ST_StartPoint(geom::geometry)) AS start_lon,
                   ST_Y(ST_EndPoint(geom::geometry))   AS end_lat,   ST_X(ST_EndPoint(geom::geometry))   AS end_lon,
                   observed_at, ST_Length(geom) AS length_m, speed_kmh, vehicle_type
            FROM segment_observation
            WHERE trip_token = :tripToken AND matched_at IS NULL
            """;

    // One row per window with at least one candidate way. The candidate list is a VALUES list expanded by
    // NamedParameterJdbcTemplate from List<Object[]>; the CASTs make the column types explicit.
    // When start and end were snapped to different ways, the way nearest to the window midpoint wins (planar
    // distance in degrees is fine for ranking two nearby ways). A way missing from osm_way (Valhalla tiles and the
    // osm_way import can be from different extracts) yields no row, so the window stays without way.
    private static final String CHOOSE_WINDOW_WAYS = """
            SELECT so.id AS window_id, w.way_id, w.length_m,
                   ST_LineLocatePoint(w.geom, ST_LineInterpolatePoint(so.geom::geometry, 0.5)) AS fraction
            FROM (VALUES :candidates) AS v(window_id, start_way_id, end_way_id)
            JOIN segment_observation so ON so.id = CAST(v.window_id AS uuid)
            CROSS JOIN LATERAL (
              SELECT ow.way_id, ow.geom, ow.length_m
              FROM osm_way ow
              WHERE ow.way_id = CAST(v.start_way_id AS bigint) OR ow.way_id = CAST(v.end_way_id AS bigint)
              ORDER BY ST_Distance(ow.geom, ST_LineInterpolatePoint(so.geom::geometry, 0.5)), ow.way_id
              LIMIT 1
            ) w
            """;

    private static final String UPDATE_EVENT = """
            UPDATE hazard_event SET way_id = :wayId, matched_at = :matchedAt, trip_token = NULL
            WHERE id = :id AND matched_at IS NULL
            """;

    private static final String UPDATE_WINDOW = """
            UPDATE segment_observation
            SET way_id = :wayId, bucket = :bucket, matched_at = :matchedAt, trip_token = NULL
            WHERE id = :id AND matched_at IS NULL
            """;

    // Rows whose trip token was already cleared (retention after 24 h) cannot be grouped into a trace any more.
    private static final String MARK_ORPHAN_EVENTS = """
            UPDATE hazard_event SET matched_at = :matchedAt
            WHERE id IN (
              SELECT id FROM hazard_event
              WHERE matched_at IS NULL AND trip_token IS NULL
              LIMIT :batchSize)
            """;

    private static final String MARK_ORPHAN_WINDOWS = """
            UPDATE segment_observation SET matched_at = :matchedAt
            WHERE id IN (
              SELECT id FROM segment_observation
              WHERE matched_at IS NULL AND trip_token IS NULL
              LIMIT :batchSize)
            """;

    private static final String COUNT_BACKLOG_EVENTS = "SELECT count(*) FROM hazard_event WHERE matched_at IS NULL";

    private static final String COUNT_BACKLOG_WINDOWS =
            "SELECT count(*) FROM segment_observation WHERE matched_at IS NULL";

    /** Stays well below PostgreSQL's limit of 65535 bind parameters per statement (3 per candidate). */
    private static final int CANDIDATES_PER_STATEMENT = 1000;

    private final NamedParameterJdbcTemplate jdbc;

    public MatchingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UUID> findPendingTrips(Instant settledBefore, int limit) {
        var params = new MapSqlParameterSource()
                .addValue("settledBefore", timestamp(settledBefore))
                .addValue("limit", limit);
        return jdbc.query(FIND_PENDING_TRIPS, params, (rs, i) -> rs.getObject("trip_token", UUID.class));
    }

    public List<PendingEvent> findTripEvents(UUID tripToken) {
        return jdbc.query(FIND_TRIP_EVENTS, new MapSqlParameterSource("tripToken", tripToken),
                (rs, i) -> new PendingEvent(
                        rs.getObject("id", UUID.class),
                        rs.getDouble("lat"),
                        rs.getDouble("lon"),
                        instant(rs, "observed_at"),
                        rs.getInt("vehicle_type")));
    }

    public List<PendingWindow> findTripWindows(UUID tripToken) {
        return jdbc.query(FIND_TRIP_WINDOWS, new MapSqlParameterSource("tripToken", tripToken),
                (rs, i) -> new PendingWindow(
                        rs.getObject("id", UUID.class),
                        rs.getDouble("start_lat"),
                        rs.getDouble("start_lon"),
                        rs.getDouble("end_lat"),
                        rs.getDouble("end_lon"),
                        instant(rs, "observed_at"),
                        rs.getDouble("length_m"),
                        rs.getDouble("speed_kmh"),
                        rs.getInt("vehicle_type")));
    }

    /** Chooses one way per window among its snapped candidates; windows without a usable way are absent. */
    public List<WindowWayChoice> chooseWindowWays(List<WindowWayCandidates> candidates) {
        List<WindowWayChoice> choices = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        for (WindowWayCandidates candidate : candidates) {
            if (candidate.hasCandidate()) {
                rows.add(new Object[] {candidate.windowId(), wayOrZero(candidate.startWayId()),
                    wayOrZero(candidate.endWayId())});
            }
        }
        for (int from = 0; from < rows.size(); from += CANDIDATES_PER_STATEMENT) {
            List<Object[]> chunk = rows.subList(from, Math.min(rows.size(), from + CANDIDATES_PER_STATEMENT));
            choices.addAll(jdbc.query(CHOOSE_WINDOW_WAYS, new MapSqlParameterSource("candidates", chunk),
                    (rs, i) -> new WindowWayChoice(
                            rs.getObject("window_id", UUID.class),
                            rs.getLong("way_id"),
                            rs.getDouble("length_m"),
                            rs.getDouble("fraction"))));
        }
        return choices;
    }

    public int updateEvents(List<MatchedRow> rows, Instant matchedAt) {
        if (rows.isEmpty()) {
            return 0;
        }
        SqlParameterSource[] batch = new SqlParameterSource[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            MatchedRow row = rows.get(i);
            batch[i] = new MapSqlParameterSource()
                    .addValue("id", row.id())
                    .addValue("wayId", row.wayId(), Types.BIGINT)
                    .addValue("matchedAt", timestamp(matchedAt));
        }
        return sum(jdbc.batchUpdate(UPDATE_EVENT, batch));
    }

    public int updateWindows(List<MatchedRow> rows, Instant matchedAt) {
        if (rows.isEmpty()) {
            return 0;
        }
        SqlParameterSource[] batch = new SqlParameterSource[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            MatchedRow row = rows.get(i);
            batch[i] = new MapSqlParameterSource()
                    .addValue("id", row.id())
                    .addValue("wayId", row.wayId(), Types.BIGINT)
                    .addValue("bucket", row.bucket(), Types.INTEGER)
                    .addValue("matchedAt", timestamp(matchedAt));
        }
        return sum(jdbc.batchUpdate(UPDATE_WINDOW, batch));
    }

    public int markOrphanEvents(Instant matchedAt, int batchSize) {
        return jdbc.update(MARK_ORPHAN_EVENTS, orphanParams(matchedAt, batchSize));
    }

    public int markOrphanWindows(Instant matchedAt, int batchSize) {
        return jdbc.update(MARK_ORPHAN_WINDOWS, orphanParams(matchedAt, batchSize));
    }

    public long countBacklogEvents() {
        Long n = jdbc.getJdbcOperations().queryForObject(COUNT_BACKLOG_EVENTS, Long.class);
        return n == null ? 0 : n;
    }

    public long countBacklogWindows() {
        Long n = jdbc.getJdbcOperations().queryForObject(COUNT_BACKLOG_WINDOWS, Long.class);
        return n == null ? 0 : n;
    }

    /**
     * Missing candidates are sent as 0 rather than NULL: a NULL would be bound without a type and PostgreSQL
     * could not unify it with the bigint values of other VALUES rows. OSM way IDs are positive, so 0 never matches.
     */
    private static long wayOrZero(Long wayId) {
        return wayId == null ? 0L : wayId;
    }

    private static MapSqlParameterSource orphanParams(Instant matchedAt, int batchSize) {
        return new MapSqlParameterSource()
                .addValue("matchedAt", timestamp(matchedAt))
                .addValue("batchSize", batchSize);
    }

    /** batchUpdate may report SUCCESS_NO_INFO (-2) with some driver settings; count those as one row. */
    private static int sum(int[] counts) {
        int total = 0;
        for (int count : counts) {
            total += count < 0 ? 1 : count;
        }
        return total;
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
