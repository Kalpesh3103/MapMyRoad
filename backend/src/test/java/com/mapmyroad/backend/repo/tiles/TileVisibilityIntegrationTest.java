package com.mapmyroad.backend.repo.tiles;

import static org.assertj.core.api.Assertions.assertThat;

import com.mapmyroad.backend.api.hazards.HazardGeoJson;
import com.mapmyroad.backend.repo.hazards.HazardRepository;
import com.mapmyroad.backend.repo.hazards.HazardRow;
import com.mapmyroad.backend.repo.hazards.VisibilityRules;
import com.mapmyroad.backend.service.hazards.HazardQuery;
import com.mapmyroad.backend.service.hazards.HazardService;
import com.mapmyroad.backend.service.tiles.TileCoord;
import com.mapmyroad.backend.service.tiles.TileResult;
import com.mapmyroad.backend.service.tiles.TileService;
import com.mapmyroad.backend.support.AbstractPostgisIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Visibility rules (SECURITY.md T2 and 6.4, TESTING_STRATEGY.md 5.2 and 6) for tiles and the hazards API against
 * PostGIS. Synthetic coordinates around a made-up area; every way is placed in its own z14 tile so that a tile's
 * emptiness tells whether its only feature was published. The MVT is not decoded: layer names and hazard IDs are
 * plain UTF-8 strings inside the protobuf, so a byte search is enough here.
 */
class TileVisibilityIntegrationTest extends AbstractPostgisIntegrationTest {

    private static final int Z = 14;

    // Each way sits in its own z14 tile (about 0.022 degrees wide), 30 % in from the tile's west edge, so all
    // features placed up to 0.0025 degrees east of the base point stay inside that tile.
    private static final double PRIMARY_LON = lonInTile(11550);
    private static final double RESIDENTIAL_LON = lonInTile(11553);
    private static final double PRIVATE_LON = lonInTile(11556);
    private static final double EMPTY_LON = lonInTile(11559);
    private static final double LAT = 18.5010;

    private static final long PRIMARY_WAY = 1001;
    private static final long RESIDENTIAL_WAY = 1002;
    private static final long PRIVATE_WAY = 1003;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private TileRepository tileRepository;

    @Autowired
    private TileService tileService;

    @Autowired
    private HazardRepository hazardRepository;

    @Autowired
    private HazardService hazardService;

    @Autowired
    private VisibilityRules visibilityRules;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void clean() {
        for (String table : List.of(
                "hazard_feedback", "hazard_recompute_queue", "hazard_event", "hazard", "road_segment_stat", "osm_way")) {
            jdbc.getJdbcOperations().update("DELETE FROM " + table);
        }
        way(PRIMARY_WAY, "primary", PRIMARY_LON, null);
        way(RESIDENTIAL_WAY, "residential", RESIDENTIAL_LON, null);
        way(PRIVATE_WAY, "primary", PRIVATE_LON, "private");
    }

    @Test
    void segmentWithTwoDevicesIsShownOnPrimaryButHiddenOnResidential() {
        segment(PRIMARY_WAY, PRIMARY_LON, 5, 2);
        segment(RESIDENTIAL_WAY, RESIDENTIAL_LON, 5, 2);

        assertThat(roads(PRIMARY_LON)).isNotEmpty();
        assertThat(new String(roads(PRIMARY_LON), StandardCharsets.ISO_8859_1)).contains("roads");
        assertThat(roads(RESIDENTIAL_LON)).isEmpty();
        assertThat(visibleSegments()).isEqualTo(1);
    }

    @Test
    void residentialSegmentWithThreeDevicesIsShown() {
        segment(RESIDENTIAL_WAY, RESIDENTIAL_LON, 5, 3);
        assertThat(roads(RESIDENTIAL_LON)).isNotEmpty();
    }

    @Test
    void segmentBelowSampleThresholdOrOnPrivateWayIsHidden() {
        segment(PRIMARY_WAY, PRIMARY_LON, 2, 5);
        segment(PRIVATE_WAY, PRIVATE_LON, 10, 5);

        assertThat(roads(PRIMARY_LON)).isEmpty();
        assertThat(roads(PRIVATE_LON)).isEmpty();
        assertThat(visibleSegments()).isZero();
    }

    @Test
    void hazardRulesApplyToTilesAndApi() {
        UUID visible = hazard(PRIMARY_WAY, PRIMARY_LON, 2, 0.8, "active", null);
        UUID lowConfidence = hazard(PRIMARY_WAY, PRIMARY_LON + 0.0005, 5, 0.2, "active", null);
        UUID suppressed = hazard(PRIMARY_WAY, PRIMARY_LON + 0.0010, 5, 0.9, "active", "osm_traffic_calming");
        UUID stale = hazard(PRIMARY_WAY, PRIMARY_LON + 0.0015, 5, 0.9, "stale", null);
        UUID residentialTwoDevices = hazard(RESIDENTIAL_WAY, RESIDENTIAL_LON, 2, 0.9, "active", null);
        UUID unknownWayTwoDevices = hazard(null, PRIMARY_LON + 0.0020, 2, 0.9, "active", null);
        UUID unknownWayThreeDevices = hazard(null, PRIMARY_LON + 0.0025, 3, 0.9, "active", null);
        UUID privateWay = hazard(PRIVATE_WAY, PRIVATE_LON, 5, 0.9, "active", null);

        String tile = new String(hazards(PRIMARY_LON), StandardCharsets.ISO_8859_1);
        assertThat(tile).contains("hazards").contains(visible.toString()).contains(unknownWayThreeDevices.toString());
        assertThat(tile)
                .doesNotContain(lowConfidence.toString())
                .doesNotContain(suppressed.toString())
                .doesNotContain(stale.toString())
                .doesNotContain(unknownWayTwoDevices.toString());
        assertThat(hazards(RESIDENTIAL_LON)).isEmpty();
        assertThat(hazards(PRIVATE_LON)).isEmpty();

        List<UUID> active =
                findAround(PRIMARY_LON, "active").stream().map(HazardRow::id).toList();
        assertThat(active).containsExactlyInAnyOrder(visible, unknownWayThreeDevices);
        // Non-active statuses are listed on request, but the other rules still apply.
        assertThat(findAround(PRIMARY_LON, "stale"))
                .extracting(HazardRow::id)
                .containsExactly(stale);
        assertThat(hazardRepository.findVisible(lowConfidence)).isEmpty();
        assertThat(hazardRepository.findVisible(residentialTwoDevices)).isEmpty();
        assertThat(hazardRepository.findVisible(stale)).isPresent();
        assertThat(hazardRepository.findVisible(privateWay)).isEmpty();
    }

    @Test
    void responsesContainNoDeviceIdsOrTripTokens() {
        UUID device = UUID.randomUUID();
        UUID tripToken = UUID.randomUUID();
        jdbc.update("INSERT INTO device (id) VALUES (:id)", new MapSqlParameterSource("id", device));
        UUID hazardId = hazard(PRIMARY_WAY, PRIMARY_LON, 2, 0.8, "active", null);
        jdbc.update(
                """
                INSERT INTO hazard_event (id, device_id, trip_token, geom, severity, vehicle_type, speed_kmh,
                                          peak_score, observed_at, hazard_id)
                VALUES (:id, :device, :trip, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, 3, 2, 30, 3,
                        now() - interval '1 day', :hazard)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("device", device)
                        .addValue("trip", tripToken)
                        .addValue("lon", PRIMARY_LON)
                        .addValue("lat", LAT)
                        .addValue("hazard", hazardId));
        segment(PRIMARY_WAY, PRIMARY_LON, 5, 2);

        String tile = new String(tileService.tile(coordFor(PRIMARY_LON)).bytes(), StandardCharsets.ISO_8859_1);
        // Serialized exactly as the controller returns them.
        String list = jsonMapper.writeValueAsString(HazardGeoJson.FeatureCollection.of(
                hazardService.list(HazardQuery.of(bboxAround(PRIMARY_LON), null, null, null))));
        String detail = jsonMapper.writeValueAsString(HazardGeoJson.Feature.of(hazardService.get(hazardId)));

        for (String body : List.of(tile, list, detail)) {
            assertThat(body).doesNotContain(device.toString()).doesNotContain(tripToken.toString());
        }
        assertThat(list).contains(hazardId.toString());
    }

    @Test
    void emptyTileIsEmptyAndHasNoEtag() {
        TileResult result = tileService.tile(coordFor(EMPTY_LON));
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.etag()).isNull();
    }

    private byte[] roads(double lon) {
        TileCoord c = coordFor(lon);
        return tileRepository.roadsLayer(c.z(), c.x(), c.y(), Instant.now());
    }

    private byte[] hazards(double lon) {
        TileCoord c = coordFor(lon);
        return tileRepository.hazardsLayer(c.z(), c.x(), c.y(), Instant.now());
    }

    private List<HazardRow> findAround(double lon, String status) {
        return hazardRepository.findInBbox(lon - 0.005, LAT - 0.005, lon + 0.005, LAT + 0.005, 1, status, 500);
    }

    private static String bboxAround(double lon) {
        return (lon - 0.005) + "," + (LAT - 0.005) + "," + (lon + 0.005) + "," + (LAT + 0.005);
    }

    private int visibleSegments() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM " + VisibilityRules.SEGMENT_FROM + " WHERE " + VisibilityRules.SEGMENT_VISIBLE,
                visibilityRules.bind(new MapSqlParameterSource()),
                Integer.class);
        return count == null ? 0 : count;
    }

    private static double lonInTile(int x) {
        return (x + 0.3) / (1 << Z) * 360 - 180;
    }

    /** Standard XYZ tile of a point at zoom {@link #Z}. */
    private static TileCoord coordFor(double lon) {
        int n = 1 << Z;
        int x = (int) Math.floor((lon + 180) / 360 * n);
        double latRad = Math.toRadians(LAT);
        int y = (int) Math.floor((1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * n);
        return TileCoord.of(Z, x, y);
    }

    private void way(long wayId, String highway, double lon, String access) {
        jdbc.update(
                """
                INSERT INTO osm_way (way_id, highway, geom, length_m, access)
                VALUES (:id, :highway,
                        ST_SetSRID(ST_MakeLine(ST_MakePoint(:lon, :lat), ST_MakePoint(:lon + 0.008, :lat)), 4326),
                        850, :access)
                """,
                new MapSqlParameterSource()
                        .addValue("id", wayId)
                        .addValue("highway", highway)
                        .addValue("lon", lon)
                        .addValue("lat", LAT)
                        .addValue("access", access, Types.VARCHAR));
    }

    private void segment(long wayId, double lon, int samples, int devices) {
        jdbc.update(
                """
                INSERT INTO road_segment_stat (way_id, bucket, geom, roughness_avg, score, sample_count,
                                               distinct_devices, last_observed_at)
                VALUES (:way, 0,
                        ST_SetSRID(ST_MakeLine(ST_MakePoint(:lon, :lat), ST_MakePoint(:lon + 0.0005, :lat)),
                                   4326)::geography,
                        0.4, 72, :samples, :devices, now() - interval '3 days')
                """,
                new MapSqlParameterSource()
                        .addValue("way", wayId)
                        .addValue("lon", lon)
                        .addValue("lat", LAT)
                        .addValue("samples", samples)
                        .addValue("devices", devices));
    }

    private UUID hazard(Long wayId, double lon, int devices, double confidence, String status, String suppressed) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO hazard (id, geom, way_id, severity_max, severity_avg, event_count, distinct_devices,
                                    confidence, status, suppressed_reason, first_seen_at, last_seen_at)
                VALUES (:id, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :way, 3, 2.5, 6, :devices,
                        :confidence, :status, :suppressed, now() - interval '10 days', now() - interval '1 day')
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("lon", lon)
                        .addValue("lat", LAT)
                        .addValue("way", wayId, Types.BIGINT)
                        .addValue("devices", devices)
                        .addValue("confidence", confidence)
                        .addValue("status", status)
                        .addValue("suppressed", suppressed, Types.VARCHAR));
        return id;
    }
}
