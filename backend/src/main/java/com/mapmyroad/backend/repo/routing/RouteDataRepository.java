package com.mapmyroad.backend.repo.routing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Hazards and road segment statistics along a candidate route (DATABASE_SCHEMA.md A9, ARCHITECTURE.md 8.4).
 *
 * <p>All distances use {@code geography} so buffers are in meters anywhere (DATABASE_SCHEMA.md A1);
 * {@code ST_DWithin} on geography uses the GiST indexes on {@code hazard.geom} and {@code road_segment_stat.geom}.
 * The route arrives as WKT in a bound parameter. Way IDs come from Valhalla, are bound as a {@code bigint[]} array
 * literal parameter, and are never concatenated into SQL.
 *
 * <p>Filters here are deliberately conservative and independent of the map visibility rules (owned by the hazards
 * API): active, not suppressed, confidence at least the configured minimum.
 */
@Repository
public class RouteDataRepository {

    private static final String HAZARDS = """
            SELECT h.severity_max, h.confidence, h.last_seen_at
            FROM hazard h
            WHERE h.status = 'active'
              AND h.suppressed_reason IS NULL
              AND h.confidence >= :minConfidence
              AND ST_DWithin(h.geom, ST_GeogFromText(:routeWkt), :bufferM)
            """;

    // A hazard without a way ID (not matched yet) still counts; one on another way (an overpass, a parallel
    // service road) does not.
    private static final String HAZARDS_ON_WAYS = HAZARDS + """
              AND (h.way_id IS NULL OR h.way_id = ANY(CAST(:wayIds AS bigint[])))
            """;

    // length_m is the part of the segment inside the route corridor, not ST_Length(s.geom) as in the A9 sketch:
    // a segment that only touches the route (a crossing street, a bucket that ends just past the route) would
    // otherwise count with its full length. ST_Buffer and ST_Intersection on geography run in a best-fit planar
    // projection chosen by PostGIS, which is accurate at route scale. TODO(M4-14): check with EXPLAIN ANALYZE on
    // realistic volumes (CODING_STANDARDS.md 4.4).
    private static final String SEGMENTS = """
            WITH corridor AS (
              SELECT ST_GeogFromText(:routeWkt) AS line,
                     ST_Buffer(ST_GeogFromText(:routeWkt), :bufferM) AS area
            )
            SELECT s.score, s.sample_count, ST_Length(ST_Intersection(s.geom, c.area)) AS length_m
            FROM road_segment_stat s
            CROSS JOIN corridor c
            WHERE ST_DWithin(s.geom, c.line, :bufferM)
            """;

    private static final String SEGMENTS_ON_WAYS = SEGMENTS + """
              AND s.way_id = ANY(CAST(:wayIds AS bigint[]))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RouteDataRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Hazards within {@code bufferM} of the route. With a non-empty {@code wayIds}, hazards on other ways are
     * excluded.
     */
    public List<HazardNearRoute> hazardsNear(String routeWkt, List<Long> wayIds, double bufferM, double minConfidence) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("routeWkt", routeWkt)
                .addValue("bufferM", bufferM)
                .addValue("minConfidence", minConfidence);
        String sql = HAZARDS;
        if (!wayIds.isEmpty()) {
            params.addValue("wayIds", arrayLiteral(wayIds));
            sql = HAZARDS_ON_WAYS;
        }
        return jdbc.query(sql, params, RouteDataRepository::hazard);
    }

    /**
     * Segment statistics within {@code bufferM} of the route. With a non-empty {@code wayIds}, only segments of
     * those ways; without way IDs the caller passes a tighter buffer and accepts some parallel-road noise.
     */
    public List<SegmentNearRoute> segmentsNear(String routeWkt, List<Long> wayIds, double bufferM) {
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("routeWkt", routeWkt).addValue("bufferM", bufferM);
        String sql = SEGMENTS;
        if (!wayIds.isEmpty()) {
            params.addValue("wayIds", arrayLiteral(wayIds));
            sql = SEGMENTS_ON_WAYS;
        }
        return jdbc.query(sql, params, RouteDataRepository::segment);
    }

    /** PostgreSQL array literal such as {@code {1,2,3}}, bound as a text parameter and cast in SQL. */
    static String arrayLiteral(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }

    private static HazardNearRoute hazard(ResultSet rs, int rowNum) throws SQLException {
        return new HazardNearRoute(
                rs.getInt("severity_max"),
                rs.getDouble("confidence"),
                rs.getObject("last_seen_at", OffsetDateTime.class).toInstant());
    }

    private static SegmentNearRoute segment(ResultSet rs, int rowNum) throws SQLException {
        return new SegmentNearRoute(rs.getInt("score"), rs.getInt("sample_count"), rs.getDouble("length_m"));
    }
}
