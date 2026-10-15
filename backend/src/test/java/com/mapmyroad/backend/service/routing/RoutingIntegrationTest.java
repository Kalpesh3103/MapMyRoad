package com.mapmyroad.backend.service.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mapmyroad.backend.domain.ingest.VehicleType;
import com.mapmyroad.backend.integration.polyline.LatLon;
import com.mapmyroad.backend.integration.polyline.Polyline;
import com.mapmyroad.backend.integration.valhalla.RouteOption;
import com.mapmyroad.backend.integration.valhalla.ValhallaRouter;
import com.mapmyroad.backend.support.AbstractPostgisIntegrationTest;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Comfort ordering against real PostGIS with seeded hazards and segments (TESTING_STRATEGY.md 7). Valhalla is
 * replaced by fixed synthetic lines: "fast" runs straight east along latitude 18.5000 (about 2.1 km), "smooth"
 * detours north to latitude 18.5050 and back (about 2.7 km).
 */
class RoutingIntegrationTest extends AbstractPostgisIntegrationTest {

    static final String FAST = Polyline.encode6(List.of(new LatLon(18.5000, 73.8000), new LatLon(18.5000, 73.8200)));
    static final String SMOOTH = Polyline.encode6(List.of(
            new LatLon(18.5000, 73.8000),
            new LatLon(18.5050, 73.8000),
            new LatLon(18.5050, 73.8200),
            new LatLon(18.5000, 73.8200)));
    static final long SMOOTH_WAY = 900L;
    static final long PARALLEL_WAY = 901L;

    @TestConfiguration
    static class FakeValhalla {

        @Bean
        @Primary
        ValhallaRouter fakeValhallaRouter() {
            return (originLat, originLon, destinationLat, destinationLon, costing, maxAlternates) -> List.of(
                    new RouteOption(FAST, 2100, 300, List.of()),
                    new RouteOption(SMOOTH, 2700, 420, List.of(SMOOTH_WAY)));
        }
    }

    @Autowired
    private RoutingService routingService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.getJdbcOperations().update("DELETE FROM hazard");
        jdbc.getJdbcOperations().update("DELETE FROM road_segment_stat");
        // On the fast route: two severe confident hazards that count, and three that must be ignored.
        hazard(18.5000, 73.8050, 3, 1.0, "active", null);
        hazard(18.5001, 73.8150, 3, 1.0, "active", null);
        hazard(18.5000, 73.8100, 3, 1.0, "fixed", null);
        hazard(18.5000, 73.8120, 3, 1.0, "active", "osm_traffic_calming");
        hazard(18.5000, 73.8080, 3, 0.1, "active", null);
        // Along the smooth route's northern edge: a good, well-sampled segment on its way, and a rough one on a
        // different way (for example a parallel service road) that the way filter must exclude.
        segment(SMOOTH_WAY, 95, 12);
        segment(PARALLEL_WAY, 0, 12);
    }

    private void hazard(double lat, double lon, int severity, double confidence, String status, String suppressed) {
        jdbc.update("""
                INSERT INTO hazard (geom, severity_max, severity_avg, confidence, status, suppressed_reason,
                                    first_seen_at, last_seen_at)
                VALUES (ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :severity, :severity, :confidence,
                        :status, :suppressed, now() - interval '10 days', now())
                """,
                new MapSqlParameterSource()
                        .addValue("lat", lat)
                        .addValue("lon", lon)
                        .addValue("severity", severity)
                        .addValue("confidence", confidence)
                        .addValue("status", status)
                        .addValue("suppressed", suppressed, Types.VARCHAR));
    }

    private void segment(long wayId, int score, int samples) {
        jdbc.update("""
                INSERT INTO road_segment_stat (way_id, bucket, geom, roughness_avg, score, sample_count,
                                               last_observed_at)
                VALUES (:wayId, 0, ST_GeogFromText('LINESTRING(73.8 18.505,73.82 18.505)'), 0.2, :score, :samples,
                        now())
                """,
                Map.of("wayId", wayId, "score", score, "samples", samples));
    }

    private RouteCandidates candidates(double preference) {
        return routingService.candidates(
                new RouteQuery(new LatLon(18.5000, 73.8000), new LatLon(18.5000, 73.8200), VehicleType.SCOOTER,
                        preference));
    }

    @Test
    void comfortOrderingFollowsSeededData() {
        RouteCandidates result = candidates(1.0);

        RankedCandidate fast = result.candidate("c1").orElseThrow();
        RankedCandidate smooth = result.candidate("c2").orElseThrow();
        assertThat(fast.score().hazards()).isEqualTo(new HazardCounts(2, 0, 0));
        // 2 * 8 / 2.1 km * 3.75 = 28.6 -> about 71.
        assertThat(fast.score().comfortScore()).isBetween(65, 75);
        assertThat(fast.score().coverage().level()).isEqualTo(CoverageLevel.NONE);
        // Only the score-95 segment counts (the score-0 one is on another way): about 97, about 78 % covered.
        assertThat(smooth.score().comfortScore()).isGreaterThanOrEqualTo(95);
        assertThat(smooth.score().coverage().level()).isEqualTo(CoverageLevel.GOOD);
        assertThat(smooth.score().coverage().fractionWithData()).isBetween(0.7, 0.85);

        assertThat(fast.labels()).contains(RouteLabel.FASTEST);
        assertThat(smooth.labels()).contains(RouteLabel.SMOOTHEST);
        assertThat(smooth.recommended()).isTrue();
        assertThat(result.coverage().level()).isEqualTo(CoverageLevel.GOOD);
    }

    @Test
    void preferenceZeroRecommendsTheFastRoute() {
        RouteCandidates result = candidates(0.0);

        assertThat(result.candidate("c1").orElseThrow().recommended()).isTrue();
        assertThat(result.coverage().level()).isEqualTo(CoverageLevel.NONE);
    }
}
