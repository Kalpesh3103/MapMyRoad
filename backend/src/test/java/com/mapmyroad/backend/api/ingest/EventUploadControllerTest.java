package com.mapmyroad.backend.api.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mapmyroad.backend.domain.ingest.EventObservation;
import com.mapmyroad.backend.domain.ingest.IngestResult;
import com.mapmyroad.backend.domain.ingest.ItemKind;
import com.mapmyroad.backend.domain.ingest.Rejection;
import com.mapmyroad.backend.domain.ingest.RejectionReason;
import com.mapmyroad.backend.domain.ingest.UploadBatch;
import com.mapmyroad.backend.domain.ingest.VehicleType;
import com.mapmyroad.backend.service.ingest.IngestService;
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
 * Controller contract for {@code POST /v1/events} with a standalone MockMvc (no Spring context). The device
 * principal is injected by a test resolver; security, rate limiting and problem rendering are tested elsewhere.
 */
class EventUploadControllerTest {

    private static final UUID DEVICE = UUID.fromString("5f0c7a2e-2b1d-4c8e-9a3f-111111111111");

    // Example from API_SPEC.md section 5.3, with a recent timestamp irrelevant here (the service is mocked).
    private static final String SPEC_EXAMPLE = """
            {
              "tripToken": "0b7a4c35-3a2e-4b8f-a6f3-8d4f1f1c7e11",
              "vehicleType": "scooter",
              "events": [
                {
                  "id": "d1f2c1c2-8b1e-4c2a-9a5b-1a0f9d8a7b61",
                  "lat": 18.5204,
                  "lon": 73.8567,
                  "observedAt": "2026-10-13T08:30:12Z",
                  "severity": 2,
                  "speedKmh": 32.5,
                  "peakScore": 2.8,
                  "headingDeg": 187
                }
              ],
              "windows": [
                {
                  "id": "a7c1b9d4-5f0e-4d8e-8c6b-2e7f3a9d1c20",
                  "startLat": 18.5201,
                  "startLon": 73.8565,
                  "endLat": 18.5206,
                  "endLon": 73.8568,
                  "observedAt": "2026-10-13T08:30:10Z",
                  "lengthM": 52,
                  "roughness": 0.42,
                  "speedKmh": 28.0
                }
              ]
            }
            """;

    private final IngestService service = mock(IngestService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(DEVICE.toString());
        mvc = MockMvcBuilders.standaloneSetup(new EventUploadController(service))
                .setCustomArgumentResolvers(new FixedJwtResolver(jwt))
                .build();
    }

    @Test
    void specExampleIsAcceptedWith202AndSpecResponseShape() throws Exception {
        UUID rejectedId = UUID.fromString("d1f2c1c2-8b1e-4c2a-9a5b-1a0f9d8a7b61");
        when(service.ingest(eq(DEVICE), any(UploadBatch.class))).thenReturn(new IngestResult(0, 1, 0, 0,
                List.of(new Rejection(rejectedId, ItemKind.EVENT, RejectionReason.IMPLAUSIBLE_SPEED))));

        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(SPEC_EXAMPLE))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted.events").value(0))
                .andExpect(jsonPath("$.accepted.windows").value(1))
                .andExpect(jsonPath("$.duplicates.events").value(0))
                .andExpect(jsonPath("$.duplicates.windows").value(0))
                .andExpect(jsonPath("$.rejected[0].id").value(rejectedId.toString()))
                .andExpect(jsonPath("$.rejected[0].kind").value("event"))
                .andExpect(jsonPath("$.rejected[0].reason").value("IMPLAUSIBLE_SPEED"));

        ArgumentCaptor<UploadBatch> captor = ArgumentCaptor.forClass(UploadBatch.class);
        verify(service).ingest(eq(DEVICE), captor.capture());
        UploadBatch batch = captor.getValue();
        assertThat(batch.vehicleType()).isEqualTo(VehicleType.SCOOTER);
        assertThat(batch.tripToken()).isEqualTo(UUID.fromString("0b7a4c35-3a2e-4b8f-a6f3-8d4f1f1c7e11"));
        assertThat(batch.events()).hasSize(1);
        EventObservation e = batch.events().get(0);
        assertThat(e.observedAt()).isEqualTo(Instant.parse("2026-10-13T08:30:12Z"));
        assertThat(e.headingDeg()).isEqualTo(187);
        assertThat(e.severity()).isEqualTo(2);
        assertThat(batch.windows()).hasSize(1);
        assertThat(batch.windows().get(0).lengthM()).isEqualTo(52.0);
    }

    @Test
    void unknownVehicleTypeIs400() throws Exception {
        String body = SPEC_EXAMPLE.replace("\"scooter\"", "\"truck\"");
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verify(service, never()).ingest(any(), any());
    }

    @Test
    void missingTripTokenIs400() throws Exception {
        String body = SPEC_EXAMPLE.replace("\"tripToken\": \"0b7a4c35-3a2e-4b8f-a6f3-8d4f1f1c7e11\",", "");
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verify(service, never()).ingest(any(), any());
    }

    @Test
    void missingItemIdIs400() throws Exception {
        String body = SPEC_EXAMPLE.replace("\"id\": \"d1f2c1c2-8b1e-4c2a-9a5b-1a0f9d8a7b61\",", "");
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verify(service, never()).ingest(any(), any());
    }

    @Test
    void missingRequiredEventFieldIs400() throws Exception {
        String body = SPEC_EXAMPLE.replace("\"speedKmh\": 32.5,", "");
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingWindowsListIs400() throws Exception {
        String body = """
                {"tripToken": "0b7a4c35-3a2e-4b8f-a6f3-8d4f1f1c7e11", "vehicleType": "car", "events": []}
                """;
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void headingIsOptional() throws Exception {
        when(service.ingest(eq(DEVICE), any(UploadBatch.class))).thenReturn(new IngestResult(1, 1, 0, 0, List.of()));
        String body = SPEC_EXAMPLE.replace(",\n      \"headingDeg\": 187", "");
        mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
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
