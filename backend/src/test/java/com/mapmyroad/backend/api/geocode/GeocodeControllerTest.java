package com.mapmyroad.backend.api.geocode;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ApiExceptionHandler;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.service.geocode.GeocodeLookup;
import com.mapmyroad.backend.service.geocode.GeocodeResult;
import com.mapmyroad.backend.service.geocode.GeocodeService;
import com.mapmyroad.backend.service.geocode.ReverseQuery;
import com.mapmyroad.backend.service.geocode.SearchQuery;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Controller contract for {@code GET /v1/geocode} and {@code GET /v1/geocode/reverse} (API_SPEC.md section 5.7)
 * with a standalone MockMvc. The device principal is injected by a test resolver; security and rate limiting are
 * tested elsewhere.
 */
class GeocodeControllerTest {

    private static final UUID DEVICE = UUID.fromString("5f0c7a2e-2b1d-4c8e-9a3f-111111111111");
    private static final GeocodeResult WADA = new GeocodeResult(
            "Shaniwar Wada", "Shaniwar Wada, Kasba Peth, Pune, Maharashtra", 18.5195, 73.8553, "attraction");

    private final GeocodeService service = mock(GeocodeService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(DEVICE.toString());
        mvc = MockMvcBuilders.standaloneSetup(new GeocodeController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new FixedJwtResolver(jwt))
                .build();
    }

    @Test
    void searchReturnsTheSpecShape() throws Exception {
        when(service.search(any())).thenReturn(new GeocodeLookup(List.of(WADA), true));

        mvc.perform(get("/v1/geocode").param("q", "shaniwar wada").param("lat", "18.52").param("lon", "73.85")
                        .param("limit", "3").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].name").value("Shaniwar Wada"))
                .andExpect(jsonPath("$.results[0].displayName").value("Shaniwar Wada, Kasba Peth, Pune, Maharashtra"))
                .andExpect(jsonPath("$.results[0].lat").value(18.5195))
                .andExpect(jsonPath("$.results[0].lon").value(73.8553))
                .andExpect(jsonPath("$.results[0].type").value("attraction"))
                .andExpect(jsonPath("$.cached").value(true));

        ArgumentCaptor<SearchQuery> captor = ArgumentCaptor.forClass(SearchQuery.class);
        verify(service).search(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().limit()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().bias()).isNotNull();
    }

    @Test
    void shortQueryIs400WithFieldProblem() throws Exception {
        mvc.perform(get("/v1/geocode").param("q", " ab "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("q"));
        verify(service, never()).search(any());
    }

    @Test
    void missingQueryIs400() throws Exception {
        mvc.perform(get("/v1/geocode"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("q"));
    }

    @Test
    void nonNumericLatitudeIs400() throws Exception {
        mvc.perform(get("/v1/geocode").param("q", "pune").param("lat", "north").param("lon", "73.85"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void upstreamFailureIs503() throws Exception {
        when(service.search(any())).thenThrow(new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "unavailable"));

        mvc.perform(get("/v1/geocode").param("q", "pune"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
    }

    @Test
    void reverseReturnsAtMostOneResultInTheSameShape() throws Exception {
        when(service.reverse(any())).thenReturn(new GeocodeLookup(List.of(WADA), false));

        mvc.perform(get("/v1/geocode/reverse").param("lat", "18.5195").param("lon", "73.8553"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].name").value("Shaniwar Wada"))
                .andExpect(jsonPath("$.cached").value(false));

        ArgumentCaptor<ReverseQuery> captor = ArgumentCaptor.forClass(ReverseQuery.class);
        verify(service).reverse(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().point().lat()).isEqualTo(18.5195);
    }

    @Test
    void reverseWithoutCoordinatesIs400() throws Exception {
        mvc.perform(get("/v1/geocode/reverse").param("lat", "18.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lon"));
        verify(service, never()).reverse(any());
    }

    @Test
    void reverseOutsideRegionIs422() throws Exception {
        when(service.reverse(any())).thenThrow(new ApiException(ErrorCode.UNSUPPORTED_REGION, "outside"));

        mvc.perform(get("/v1/geocode/reverse").param("lat", "12.97").param("lon", "77.59"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_REGION"));
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
