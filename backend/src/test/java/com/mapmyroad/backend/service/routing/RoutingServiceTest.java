package com.mapmyroad.backend.service.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.config.MmrProperties;
import com.mapmyroad.backend.config.RoutingProperties;
import com.mapmyroad.backend.domain.ingest.VehicleType;
import com.mapmyroad.backend.integration.polyline.LatLon;
import com.mapmyroad.backend.integration.polyline.Polyline;
import com.mapmyroad.backend.integration.valhalla.RouteOption;
import com.mapmyroad.backend.integration.valhalla.ValhallaCosting;
import com.mapmyroad.backend.integration.valhalla.ValhallaRouter;
import com.mapmyroad.backend.integration.valhalla.ValhallaUnavailableException;
import com.mapmyroad.backend.repo.routing.HazardNearRoute;
import com.mapmyroad.backend.repo.routing.RouteDataRepository;
import com.mapmyroad.backend.repo.routing.RouteWkt;
import com.mapmyroad.backend.repo.routing.SegmentNearRoute;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoutingServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T08:00:00Z");
    // Synthetic shapes around Pune (same as src/test/resources/valhalla-route).
    private static final String FAST = "_tkib@wjzzkC_wQvr_@oh\\~qy@_jZ~hl@";
    private static final String SMOOTH = "_tkib@wjzzkCnpIvcs@oyo@njcAobd@n_O";
    private static final LatLon ORIGIN = new LatLon(18.5204, 73.8567);
    private static final LatLon DESTINATION = new LatLon(18.5590, 73.7868);

    private final ValhallaRouter router = mock(ValhallaRouter.class);
    private final RouteDataRepository repository = mock(RouteDataRepository.class);
    private final RoutingProperties properties = RoutingProperties.defaults();
    private final RouteCandidateStore store = new RouteCandidateStore(properties);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private RoutingService service;

    @BeforeEach
    void setUp() {
        service = new RoutingService(
                router,
                repository,
                store,
                properties,
                new MmrProperties("72.6,17.9,75.1,19.6", null, null),
                meters,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.hazardsNear(anyString(), anyList(), anyDouble(), anyDouble())).thenReturn(List.of());
        when(repository.segmentsNear(anyString(), anyList(), anyDouble())).thenReturn(List.of());
    }

    private static String wkt(String shape) {
        return RouteWkt.lineString(Polyline.decode6(shape));
    }

    private static RouteQuery query(double preference) {
        return new RouteQuery(ORIGIN, DESTINATION, VehicleType.SCOOTER, preference);
    }

    private void routerReturns(RouteOption... options) {
        when(router.route(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(ValhallaCosting.MOTOR_SCOOTER), anyInt()))
                .thenReturn(List.of(options));
    }

    private double requests(String outcome) {
        return meters.counter(RoutingService.METRIC_REQUESTS, "outcome", outcome).count();
    }

    @Test
    void scoresLabelsRecommendsAndStoresCandidates() {
        routerReturns(new RouteOption(FAST, 9200, 1380, List.of()), new RouteOption(SMOOTH, 10100, 1620, List.of()));
        // The fast route has three severe confident hazards over 9.2 km; the smooth one is fully covered and good.
        when(repository.hazardsNear(eq(wkt(FAST)), anyList(), anyDouble(), anyDouble())).thenReturn(List.of(
                new HazardNearRoute(3, 1.0, NOW), new HazardNearRoute(3, 1.0, NOW), new HazardNearRoute(3, 1.0, NOW)));
        when(repository.segmentsNear(eq(wkt(SMOOTH)), anyList(), anyDouble()))
                .thenReturn(List.of(new SegmentNearRoute(90, 12, 8000)));

        RouteCandidates smoothFirst = service.candidates(query(1.0));
        RouteCandidates fastFirst = service.candidates(query(0.0));

        RankedCandidate c1 = smoothFirst.candidates().get(0);
        RankedCandidate c2 = smoothFirst.candidates().get(1);
        assertThat(c1.id()).isEqualTo("c1");
        // c1 comfort 90 (24 / 9.2 km * 3.75), c2 comfort 94 (roughness 16 / 10.1 km * 3.75); at p = 0.5 the
        // shorter c1 wins the combined score.
        assertThat(c1.labels()).containsExactly(RouteLabel.FASTEST, RouteLabel.BALANCED);
        assertThat(c1.score().comfortScore()).isEqualTo(90);
        assertThat(c1.score().hazards()).isEqualTo(new HazardCounts(3, 0, 0));
        assertThat(c1.score().comfortScore()).isLessThan(c2.score().comfortScore());
        assertThat(c2.labels()).containsExactly(RouteLabel.SMOOTHEST);
        assertThat(c2.score().comfortScore()).isEqualTo(94);
        assertThat(c2.recommended()).isTrue();
        assertThat(smoothFirst.coverage().level()).isEqualTo(CoverageLevel.GOOD);
        assertThat(fastFirst.candidates().get(0).recommended()).isTrue();
        assertThat(fastFirst.coverage().level()).isEqualTo(CoverageLevel.NONE);

        assertThat(store.find(smoothFirst.requestId())).hasValueSatisfying(stored -> {
            assertThat(stored.vehicleType()).isEqualTo(VehicleType.SCOOTER);
            assertThat(stored.candidates().candidate("c2")).isPresent();
        });
        assertThat(smoothFirst.requestId()).isNotEqualTo(fastFirst.requestId());
        assertThat(requests("ok")).isEqualTo(2);
        assertThat(meters.timer(RoutingService.METRIC_DURATION).count()).isEqualTo(2);
    }

    @Test
    void wayIdsSelectTheWiderSegmentBufferAndArePassedThrough() {
        routerReturns(new RouteOption(FAST, 9200, 1380, List.of(1L, 2L)), new RouteOption(SMOOTH, 10100, 1620, List.of()));

        service.candidates(query(0.5));

        verify(repository).hazardsNear(wkt(FAST), List.of(1L, 2L), 15.0, 0.3);
        verify(repository).segmentsNear(wkt(FAST), List.of(1L, 2L), 10.0);
        verify(repository).segmentsNear(wkt(SMOOTH), List.of(), 8.0);
    }

    @Test
    void nearDuplicateAlternatesAreDropped() {
        routerReturns(new RouteOption(FAST, 9200, 1380, List.of()), new RouteOption(SMOOTH, 9210, 1385, List.of()));

        assertThat(service.candidates(query(0.5)).candidates()).hasSize(1);
    }

    @Test
    void outsideTheRegionIs422AndValhallaIsNotCalled() {
        RouteQuery outside = new RouteQuery(ORIGIN, new LatLon(28.6, 77.2), VehicleType.CAR, 0.5);

        assertThatThrownBy(() -> service.candidates(outside))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UNSUPPORTED_REGION));
        verifyNoInteractions(router);
        assertThat(requests("unsupported_region")).isEqualTo(1);
    }

    @Test
    void pointsCloserThanTheMinimumAre400() {
        RouteQuery close = new RouteQuery(ORIGIN, new LatLon(18.5206, 73.8568), VehicleType.CAR, 0.5);

        assertThatThrownBy(() -> service.candidates(close))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(e.properties()).containsKey("errors");
                    assertThat(e.getMessage()).doesNotContain("18.52");
                });
        verifyNoInteractions(router);
    }

    @Test
    void noRouteIs422() {
        routerReturns();

        assertThatThrownBy(() -> service.candidates(query(0.5)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NO_ROUTE));
        assertThat(requests("no_route")).isEqualTo(1);
    }

    @Test
    void undecodableShapesAreSkippedAndAllBadIsNoRoute() {
        routerReturns(new RouteOption("_izlh", 9200, 1380, List.of()));

        assertThatThrownBy(() -> service.candidates(query(0.5)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NO_ROUTE));
        verify(repository, never()).hazardsNear(anyString(), anyList(), anyDouble(), anyDouble());
    }

    @Test
    void valhallaDownIs503() {
        when(router.route(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(ValhallaCosting.MOTOR_SCOOTER), anyInt()))
                .thenThrow(new ValhallaUnavailableException("down"));

        assertThatThrownBy(() -> service.candidates(query(0.5)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));
        assertThat(requests("upstream_unavailable")).isEqualTo(1);
    }

    @Test
    void vehicleTypeSelectsTheCostingAndAlternatesComeFromProperties() {
        when(router.route(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(ValhallaCosting.AUTO), eq(2)))
                .thenReturn(List.of(new RouteOption(FAST, 9200, 1380, List.of())));

        RouteCandidates result =
                service.candidates(new RouteQuery(ORIGIN, DESTINATION, VehicleType.AUTO_RICKSHAW, 0.5));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).labels())
                .containsExactly(RouteLabel.FASTEST, RouteLabel.SMOOTHEST, RouteLabel.BALANCED);
    }
}
