package com.mapmyroad.backend.api.hazards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ApiExceptionHandler;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.service.hazards.FeedbackAction;
import com.mapmyroad.backend.service.hazards.FeedbackCommand;
import com.mapmyroad.backend.service.hazards.FeedbackResult;
import com.mapmyroad.backend.service.hazards.HazardQuery;
import com.mapmyroad.backend.service.hazards.HazardService;
import com.mapmyroad.backend.service.hazards.HazardStatus;
import com.mapmyroad.backend.service.hazards.PublishedHazard;
import java.time.Instant;
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
 * Hazards API contract with a standalone MockMvc (API_SPEC.md 5.5 and 5.6). The device principal comes from a
 * test resolver; security and rate limiting are tested elsewhere.
 */
class HazardControllerTest {

    private static final UUID DEVICE = UUID.fromString("5f0c7a2e-2b1d-4c8e-9a3f-111111111111");
    private static final UUID HAZARD = UUID.fromString("4b1e9c5a-2d7f-4a39-9f55-0c3d7a2e8b14");

    private static final PublishedHazard PUBLISHED = new PublishedHazard(
            HAZARD,
            73.8567,
            18.5204,
            3,
            0.78,
            HazardStatus.ACTIVE,
            Instant.parse("2026-09-02T00:00:00Z"),
            Instant.parse("2026-10-12T00:00:00Z"),
            4,
            0);

    private static final String FEEDBACK = """
            {"action": "confirm", "lat": 18.52045, "lon": 73.85672, "observedAt": "2026-10-13T09:02:00Z"}
            """;

    private final HazardService service = mock(HazardService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(DEVICE.toString());
        mvc = MockMvcBuilders.standaloneSetup(new HazardController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new FixedJwtResolver(jwt))
                .build();
    }

    @Test
    void listIsGeoJsonFeatureCollectionPerSpec() throws Exception {
        when(service.list(any(HazardQuery.class))).thenReturn(List.of(PUBLISHED));

        String body = mvc.perform(get("/v1/hazards").param("bbox", "73.80,18.50,73.85,18.55"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("FeatureCollection"))
                .andExpect(jsonPath("$.features[0].type").value("Feature"))
                .andExpect(jsonPath("$.features[0].geometry.type").value("Point"))
                .andExpect(jsonPath("$.features[0].geometry.coordinates[0]").value(73.8567))
                .andExpect(jsonPath("$.features[0].geometry.coordinates[1]").value(18.5204))
                .andExpect(jsonPath("$.features[0].properties.id").value(HAZARD.toString()))
                .andExpect(jsonPath("$.features[0].properties.severity").value(3))
                .andExpect(jsonPath("$.features[0].properties.confidence").value(0.78))
                .andExpect(jsonPath("$.features[0].properties.status").value("active"))
                .andExpect(jsonPath("$.features[0].properties.firstSeen").value("2026-09-02T00:00:00Z"))
                .andExpect(jsonPath("$.features[0].properties.lastSeen").value("2026-10-12T00:00:00Z"))
                .andExpect(jsonPath("$.features[0].properties.confirmCount").value(4))
                .andExpect(jsonPath("$.features[0].properties.dismissCount").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(DEVICE.toString()).doesNotContain("device").doesNotContain("trip");
        ArgumentCaptor<HazardQuery> captor = ArgumentCaptor.forClass(HazardQuery.class);
        verify(service).list(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(HazardStatus.ACTIVE);
        assertThat(captor.getValue().limit()).isEqualTo(200);
    }

    @Test
    void missingOrOversizedBboxIs400() throws Exception {
        mvc.perform(get("/v1/hazards")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("bbox"));
        mvc.perform(get("/v1/hazards").param("bbox", "73.0,18.0,74.0,19.0")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/hazards").param("bbox", "73.80,18.50,73.85,18.55").param("limit", "501"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/hazards").param("bbox", "73.80,18.50,73.85,18.55").param("minSeverity", "x"))
                .andExpect(status().isBadRequest());
        verify(service, never()).list(any());
    }

    @Test
    void detailIsSingleFeatureAnd404WhenUnknown() throws Exception {
        when(service.get(HAZARD)).thenReturn(PUBLISHED);
        mvc.perform(get("/v1/hazards/" + HAZARD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("Feature"))
                .andExpect(jsonPath("$.properties.id").value(HAZARD.toString()));

        UUID unknown = UUID.randomUUID();
        when(service.get(unknown)).thenThrow(new ApiException(ErrorCode.NOT_FOUND, "Hazard not found."));
        mvc.perform(get("/v1/hazards/" + unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void feedbackPassesDeviceAndReturnsSpecShape() throws Exception {
        when(service.feedback(any())).thenReturn(new FeedbackResult(HAZARD, 0.81, HazardStatus.ACTIVE));

        mvc.perform(post("/v1/hazards/" + HAZARD + "/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FEEDBACK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(HAZARD.toString()))
                .andExpect(jsonPath("$.confidence").value(0.81))
                .andExpect(jsonPath("$.status").value("active"));

        ArgumentCaptor<FeedbackCommand> captor = ArgumentCaptor.forClass(FeedbackCommand.class);
        verify(service).feedback(captor.capture());
        FeedbackCommand command = captor.getValue();
        assertThat(command.hazardId()).isEqualTo(HAZARD);
        assertThat(command.deviceId()).isEqualTo(DEVICE);
        assertThat(command.action()).isEqualTo(FeedbackAction.CONFIRM);
        assertThat(command.observedAt()).isEqualTo(Instant.parse("2026-10-13T09:02:00Z"));
        assertThat(command.toString()).doesNotContain("18.52").doesNotContain("73.85");
    }

    @Test
    void invalidFeedbackBodyIs400() throws Exception {
        for (String body : List.of(
                FEEDBACK.replace("\"confirm\"", "\"fixed\""),
                FEEDBACK.replace("\"lat\": 18.52045,", ""),
                FEEDBACK.replace("18.52045", "91"),
                FEEDBACK.replace(", \"observedAt\": \"2026-10-13T09:02:00Z\"", ""))) {
            mvc.perform(post("/v1/hazards/" + HAZARD + "/feedback")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(service, never()).feedback(any());
    }

    @Test
    void tooFarIs422() throws Exception {
        when(service.feedback(any())).thenThrow(new ApiException(ErrorCode.TOO_FAR, "Too far."));
        mvc.perform(post("/v1/hazards/" + HAZARD + "/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FEEDBACK))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("TOO_FAR"));
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
