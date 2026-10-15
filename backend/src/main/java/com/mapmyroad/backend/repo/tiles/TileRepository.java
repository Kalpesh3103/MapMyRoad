package com.mapmyroad.backend.repo.tiles;

import com.mapmyroad.backend.repo.hazards.VisibilityRules;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Builds the {@code roads} and {@code hazards} Mapbox Vector Tile layers with PostGIS (DATABASE_SCHEMA.md A9,
 * API_SPEC.md 5.4). Each method returns one encoded layer, or an empty array when the layer has no features.
 * MVT layers are protobuf messages, so concatenating two encoded layers yields a valid tile with both.
 *
 * <p>Geography versus geometry (DATABASE_SCHEMA.md A1): the aggregated tables store {@code geography}. The bbox
 * prefilter stays on geography ({@code &&} against the tile envelope transformed to 4326 and cast to geography)
 * so it uses the existing GiST indexes on {@code geom}; only the selected rows are cast to geometry and transformed
 * to Web Mercator (3857) for {@code ST_AsMVTGeom}. The prefilter envelope is widened by the same 64/4096 margin
 * as the MVT buffer, so features just outside the tile still render across tile borders.
 *
 * <p>Privacy (SECURITY.md 6.4): only aggregated columns are selected, never device or trip fields. Ages are whole
 * days. Hazard points are snapped to a 0.00001 degree grid (about 1 m) before encoding, the same precision as the
 * hazards API, because MVT itself would carry centimeter precision at zoom 18.
 */
@Repository
public class TileRepository {

    /** MVT extent and buffer, the PostGIS defaults used in DATABASE_SCHEMA.md A9. */
    static final int EXTENT = 4096;

    static final int BUFFER = 64;

    /** Published coordinate grid in degrees, about 1.1 m of latitude. Same rule as the hazards API. */
    public static final double PUBLISHED_GRID_DEG = 0.00001;

    private static final String TILE_CTE = """
            WITH tile AS (
              SELECT ST_TileEnvelope(:z, :x, :y) AS env,
                     ST_Transform(ST_TileEnvelope(:z, :x, :y, margin => :margin), 4326)::geography AS area
            )
            """;

    private static final String ROADS_LAYER = TILE_CTE + """
            SELECT COALESCE((
              SELECT ST_AsMVT(q, 'roads', 4096, 'geom')
              FROM (
                SELECT s.score::int AS score,
                       s.sample_count AS samples,
                       s.distinct_devices AS devices,
                       GREATEST(0, floor(EXTRACT(EPOCH FROM (CAST(:now AS timestamptz) - s.last_observed_at))
                                         / 86400))::int AS "ageDays",
                       ST_AsMVTGeom(ST_Transform(s.geom::geometry, 3857), tile.env, 4096, 64, true) AS geom
                FROM tile, %s
                WHERE s.geom && tile.area
                  AND %s
              ) q
              WHERE q.geom IS NOT NULL
            ), ''::bytea)
            """.formatted(VisibilityRules.SEGMENT_FROM, VisibilityRules.SEGMENT_VISIBLE);

    private static final String HAZARDS_LAYER = TILE_CTE + """
            SELECT COALESCE((
              SELECT ST_AsMVT(q, 'hazards', 4096, 'geom')
              FROM (
                SELECT h.id::text AS id,
                       h.severity_max::int AS sev,
                       round(h.confidence::numeric, 2)::float8 AS conf,
                       h.status AS status,
                       GREATEST(0, floor(EXTRACT(EPOCH FROM (CAST(:now AS timestamptz) - h.last_seen_at))
                                         / 86400))::int AS "seenDays",
                       ST_AsMVTGeom(ST_Transform(ST_SnapToGrid(h.geom::geometry, :grid), 3857),
                                    tile.env, 4096, 64, true) AS geom
                FROM tile, %s
                WHERE h.geom && tile.area
                  AND %s
              ) q
              WHERE q.geom IS NOT NULL
            ), ''::bytea)
            """.formatted(VisibilityRules.HAZARD_FROM, VisibilityRules.HAZARD_VISIBLE_IN_TILES);

    private final NamedParameterJdbcTemplate jdbc;
    private final VisibilityRules visibility;

    public TileRepository(NamedParameterJdbcTemplate jdbc, VisibilityRules visibility) {
        this.jdbc = jdbc;
        this.visibility = visibility;
    }

    /** Encoded {@code roads} layer for tile z/x/y, empty when no visible segment intersects it. */
    public byte[] roadsLayer(int z, int x, int y, Instant now) {
        return query(ROADS_LAYER, z, x, y, now);
    }

    /** Encoded {@code hazards} layer for tile z/x/y, empty when no visible active hazard lies in it. */
    public byte[] hazardsLayer(int z, int x, int y, Instant now) {
        return query(HAZARDS_LAYER, z, x, y, now);
    }

    private byte[] query(String sql, int z, int x, int y, Instant now) {
        MapSqlParameterSource params = visibility.bind(new MapSqlParameterSource()
                .addValue("z", z)
                .addValue("x", x)
                .addValue("y", y)
                .addValue("margin", (double) BUFFER / EXTENT)
                .addValue("grid", PUBLISHED_GRID_DEG)
                .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));
        byte[] bytes = jdbc.queryForObject(sql, params, byte[].class);
        return bytes == null ? new byte[0] : bytes;
    }
}
