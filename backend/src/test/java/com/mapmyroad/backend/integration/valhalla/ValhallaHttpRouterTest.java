package com.mapmyroad.backend.integration.valhalla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.mapmyroad.backend.integration.polyline.Polyline;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Valhalla /route and /trace_attributes handling against recorded-shape fixtures (valhalla-route/*.json). */
class ValhallaHttpRouterTest {

    private static final String BASE = "http://valhalla.test";

    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    static String fixture(String name) {
        try (InputStream in = ValhallaHttpRouterTest.class.getResourceAsStream("/valhalla-route/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ValhallaHttpRouter router(boolean resolveWayIds) {
        return new ValhallaHttpRouter(builder.build(), resolveWayIds);
    }

    private List<RouteOption> route(ValhallaHttpRouter router) {
        return router.route(18.5204, 73.8567, 18.5590, 73.7868, ValhallaCosting.MOTOR_SCOOTER, 2);
    }

    @Test
    void parsesMainRouteAndAlternatesInOrderAndSendsExpectedRequest() {
        server.expect(requestTo(BASE + "/route"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.costing").value("motor_scooter"))
                .andExpect(jsonPath("$.alternates").value(2))
                .andExpect(jsonPath("$.directions_type").value("none"))
                .andExpect(jsonPath("$.directions_options.units").value("kilometers"))
                .andExpect(jsonPath("$.locations[0].lat").value(18.5204))
                .andExpect(jsonPath("$.locations[1].lon").value(73.7868))
                .andRespond(withSuccess(fixture("main-and-two-alternates.json"), MediaType.APPLICATION_JSON));

        List<RouteOption> options = route(router(false));

        server.verify();
        assertThat(options).hasSize(3);
        assertThat(options.get(0).distanceM()).isCloseTo(9200, within(0.01));
        assertThat(options.get(0).durationSec()).isCloseTo(1380.4, within(0.01));
        assertThat(options.get(0).encodedPolyline6()).isEqualTo("_tkib@wjzzkC_wQvr_@oh\\~qy@_jZ~hl@");
        assertThat(options.get(1).distanceM()).isCloseTo(10100, within(0.01));
        assertThat(options.get(2).durationSec()).isCloseTo(1500.2, within(0.01));
        assertThat(options).allSatisfy(o -> assertThat(o.wayIds()).isEmpty());
    }

    @Test
    void resolvesWayIdsWithEdgeWalkPerCandidate() {
        server.expect(requestTo(BASE + "/route"))
                .andRespond(withSuccess(fixture("main-and-two-alternates.json"), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.times(3), requestTo(BASE + "/trace_attributes"))
                .andExpect(jsonPath("$.shape_match").value("edge_walk"))
                .andExpect(jsonPath("$.costing").value("motor_scooter"))
                .andExpect(jsonPath("$.filters.attributes[0]").value("edge.way_id"))
                .andRespond(withSuccess(fixture("trace-attributes.json"), MediaType.APPLICATION_JSON));

        List<RouteOption> options = route(router(true));

        server.verify();
        assertThat(options.get(0).wayIds()).containsExactly(101L, 202L, 303L);
    }

    @Test
    void wayIdFailureFallsBackToEmptyWayIds() {
        server.expect(requestTo(BASE + "/route"))
                .andRespond(withSuccess(fixture("multi-leg.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/trace_attributes")).andRespond(withServerError());

        List<RouteOption> options = route(router(true));

        assertThat(options).hasSize(1);
        assertThat(options.get(0).wayIds()).isEmpty();
    }

    @Test
    void joinsMultipleLegsIntoOneShape() {
        server.expect(requestTo(BASE + "/route"))
                .andRespond(withSuccess(fixture("multi-leg.json"), MediaType.APPLICATION_JSON));

        List<RouteOption> options = route(router(false));

        assertThat(options).hasSize(1);
        assertThat(options.get(0).distanceM()).isCloseTo(8000, within(0.01));
        // Two legs share their junction point, which appears once in the joined shape.
        assertThat(options.get(0).encodedPolyline6()).isEqualTo("_tkib@wjzzkC_wQvr_@osw@~{fB");
        assertThat(Polyline.decode6(options.get(0).encodedPolyline6())).hasSize(3);
    }

    @Test
    void noPathFoundIsAnEmptyList() {
        server.expect(requestTo(BASE + "/route"))
                .andRespond(withBadRequest().body(fixture("no-route.json")).contentType(MediaType.APPLICATION_JSON));

        assertThat(route(router(true))).isEmpty();
    }

    @Test
    void unexpectedBadRequestIsUnavailable() {
        server.expect(requestTo(BASE + "/route"))
                .andRespond(withBadRequest().body(fixture("bad-request.json")).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> route(router(false))).isInstanceOf(ValhallaUnavailableException.class);
    }

    @Test
    void serverErrorIsUnavailable() {
        server.expect(requestTo(BASE + "/route")).andRespond(withServerError());

        assertThatThrownBy(() -> route(router(false))).isInstanceOf(ValhallaUnavailableException.class);
    }

    @Test
    void malformedJsonIsUnavailable() {
        server.expect(requestTo(BASE + "/route")).andRespond(withSuccess("<html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> route(router(false))).isInstanceOf(ValhallaUnavailableException.class);
    }

    @Test
    void parserSkipsTripsWithoutShapeOrSummary() {
        String body = """
                {"trip": {"legs": [{"shape": ""}], "summary": {"length": 1.0, "time": 60}},
                 "alternates": [{"trip": {"legs": [{"shape": "_izlhA~rlgdF_{geC~ywl@"}],
                                          "summary": {"length": 2.5, "time": 120}}},
                                {"trip": {"legs": [{"shape": "_izlhA~rlgdF"}]}}]}
                """;
        List<RouteResponseParser.ParsedTrip> trips = RouteResponseParser.parse(body);

        assertThat(trips).hasSize(1);
        assertThat(trips.get(0).distanceM()).isCloseTo(2500, within(0.01));
    }

    @Test
    void errorCodeParsing() {
        assertThat(RouteResponseParser.isNoRouteError(fixture("no-route.json"))).isTrue();
        assertThat(RouteResponseParser.isNoRouteError(fixture("bad-request.json"))).isFalse();
        assertThat(RouteResponseParser.errorCode("not json")).isNull();
        assertThat(RouteResponseParser.errorCode(null)).isNull();
    }
}
