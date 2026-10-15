package com.mapmyroad.backend.api.routes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ApiExceptionHandler;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.domain.ingest.VehicleType;
import com.mapmyroad.backend.service.routing.CandidateScore;
import com.mapmyroad.backend.service.routing.Coverage;
import com.mapmyroad.backend.service.routing.CoverageLevel;
import com.mapmyroad.backend.service.routing.HazardCounts;
import com.mapmyroad.backend.service.routing.RankedCandidate;
import com.mapmyroad.backend.service.routing.RouteCandidates;
import com.mapmyroad.backend.service.routing.RouteLabel;
import com.mapmyroad.backend.service.routing.RouteQuery;
import com.mapmyroad.backend.service.routing.RoutingService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Controller contract for {@code POST /v1/routes} (API_SPEC.md 5.8) with a standalone MockMvc. The device
 * principal is injected by a test resolver; security and rate limiting are tested elsewhere.
 */
class RouteControllerTest {

    private static final UUID DEVICE = UUID.fromString("5f0c7a2e-2b1d-4c8e-9a3f-111111111111");
    private static final UUID REQUEST_ID = UUID.fromString("e8a3c0de-7b4a-4f55-8b62-0b2e4c1d9a77");

    private static final String SPEC_EXAMPLE = """
            {
              "origin": { "lat": 18.5204, "lon": 73.8567 },
              "destination": { "lat": 18.5590, "lon": 73.7868 },
              "vehicleType": "scooter",
              "preference": 0.5
            }
            """;

    private final RoutingService service = mock(RoutingService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(DEVICE.toString());
        mvc = MockMvcBuilders.standaloneSetup(new RouteController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new FixedJwtResolver(jwt))
                .build();
    }

    private static RouteCandidates specLikeResult() {
        Coverage partial = new Coverage(CoverageLevel.PARTIAL, 0.6449);
        RankedCandidate c1 = new RankedCandidate("c1", "geom1", 9200.4, 1380.4,
                new CandidateScore(62, new HazardCounts(3, 7, 12), new Coverage(CoverageLevel.GOOD, 0.8)),
                List.of(RouteLabel.FASTEST), false);
        RankedCandidate c2 = new RankedCandidate("c2", "geom2", 10100, 1620,
                new CandidateScore(81, new HazardCounts(0, 4, 9), partial),
                List.of(RouteLabel.SMOOTHEST, RouteLabel.BALANCED), true);
        return new RouteCandidates(REQUEST_ID, List.of(c1, c2), partial);
    }

    @Test
    void specExampleReturnsTheSpecResponseShape() throws Exception {
        when(service.candidates(any(RouteQuery.class))).thenReturn(specLikeResult());

        mvc.perform(post("/v1/routes").contentType(MediaType.APPLICATION_JSON).content(SPEC_EXAMPLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID.toString()))
                .andExpect(jsonPath("$.candidates", hasSize(2)))
                .andExpect(jsonPath("$.candidates[0].id").value("c1"))
                .andExpect(jsonPath("$.candidates[0].labels[0]").value("fastest"))
                .andExpect(jsonPath("$.candidates[0].durationSec").value(1380))
                .andExpect(jsonPath("$.candidates[0].distanceM").value(9200))
                .andExpect(jsonPath("$.candidates[0].comfortScore").value(62))
                .andExpect(jsonPath("$.candidates[0].hazards.severe").value(3))
                .andExpect(jsonPath("$.candidates[0].hazards.moderate").value(7))
                .andExpect(jsonPath("$.candidates[0].hazards.minor").value(12))
                .andExpect(jsonPath("$.candidates[0].geometry").value("geom1"))
                .andExpect(jsonPath("$.candidates[0].recommended").value(false))
                .andExpect(jsonPath("$.candidates[0].coverage.level").value("good"))
                .andExpect(jsonPath("$.candidates[1].labels[0]").value("smoothest"))
                .andExpect(jsonPath("$.candidates[1].labels[1]").value("balanced"))
                .andExpect(jsonPath("$.candidates[1].recommended").value(true))
                .andExpect(jsonPath("$.coverage.level").value("partial"))
                .andExpect(jsonPath("$.coverage.fractionWithData").value(0.64));

        ArgumentCaptor<RouteQuery> captor = ArgumentCaptor.forClass(RouteQuery.class);
        verify(service).candidates(captor.capture());
        RouteQuery query = captor.getValue();
        assertThat(query.vehicleType()).isEqualTo(VehicleType.SCOOTER);
        assertThat(query.preference()).isEqualTo(0.5);
        assertThat(query.origin().lat()).isEqualTo(18.5204);
        assertThat(query.destination().lon()).isEqualTo(73.7868);
    }

    @Test
    void preferenceDefaultsToBalanced() throws Exception {
        when(service.candidates(any(RouteQuery.class))).thenReturn(specLikeResult());
        String body = SPEC_EXAMPLE.replace(",\n  \"preference\": 0.5", "");

        mvc.perform(post("/v1/routes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<RouteQuery> captor = ArgumentCaptor.forClass(RouteQuery.class);
        verify(service).candidates(captor.capture());
        assertThat(captor.getValue().preference()).isEqualTo(0.5);
    }

    @Test
    void preferenceOutOfRangeIs400() throws Exception {
        expectInvalid(SPEC_EXAMPLE.replace("\"preference\": 0.5", "\"preference\": 1.5"));
    }

    @Test
    void unknownVehicleTypeIs400() throws Exception {
        expectInvalid(SPEC_EXAMPLE.replace("\"scooter\"", "\"truck\""));
    }

    @Test
    void missingDestinationIs400() throws Exception {
        expectInvalid("""
                {"origin": {"lat": 18.5204, "lon": 73.8567}, "vehicleType": "car"}
                """);
    }

    @Test
    void latitudeOutOfRangeIs400() throws Exception {
        expectInvalid(SPEC_EXAMPLE.replace("\"lat\": 18.5590", "\"lat\": 91.0"));
    }

    @Test
    void missingLongitudeIs400() throws Exception {
        expectInvalid(SPEC_EXAMPLE.replace(", \"lon\": 73.7868", ""));
    }

    @Test
    void serviceErrorsMapToProblemCodes() throws Exception {
        when(service.candidates(any(RouteQuery.class)))
                .thenThrow(new ApiException(ErrorCode.NO_ROUTE, "No route found between the points."));

        mvc.perform(post("/v1/routes").contentType(MediaType.APPLICATION_JSON).content(SPEC_EXAMPLE))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NO_ROUTE"));
    }

    @Test
    void upstreamUnavailableIs503() throws Exception {
        when(service.candidates(any(RouteQuery.class)))
                .thenThrow(new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Routing is temporarily unavailable."));

        mvc.perform(post("/v1/routes").contentType(MediaType.APPLICATION_JSON).content(SPEC_EXAMPLE))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
    }

    @Test
    void requestToStringOmitsCoordinates() {
        RouteRequest request = new RouteRequest(
                new RouteRequest.Position(18.5204, 73.8567), new RouteRequest.Position(18.559, 73.7868), "car", 0.2);

        assertThat(request.toString()).doesNotContain("18.52").doesNotContain("73.85");
    }

    private void expectInvalid(String body) throws Exception {
        mvc.perform(post("/v1/routes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(service, never()).candidates(any());
    }

    /** Stands in for Spring Security's {@code @AuthenticationPrincipal} resolution. */
    private record FixedJwtResolver(Jwt jwt) implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return Jwt.class.equals(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer mavContainer,
                NativeWebRequest webRequest,
                WebDataBinderFactory binderFactory) {
            return jwt;
        }
    }
}
