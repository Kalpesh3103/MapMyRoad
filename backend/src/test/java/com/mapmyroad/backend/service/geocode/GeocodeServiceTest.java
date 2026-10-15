package com.mapmyroad.backend.service.geocode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.config.GeocodeProperties;
import com.mapmyroad.backend.config.MmrProperties;
import com.mapmyroad.backend.integration.photon.PhotonClient;
import com.mapmyroad.backend.integration.photon.PhotonPlace;
import com.mapmyroad.backend.integration.photon.PhotonReverseRequest;
import com.mapmyroad.backend.integration.photon.PhotonSearchRequest;
import com.mapmyroad.backend.integration.photon.PhotonUnavailableException;
import com.mapmyroad.backend.repo.geocode.GeocodeCacheRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

class GeocodeServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");
    private static final MmrProperties MMR = new MmrProperties("72.6,17.9,75.1,19.6", "1.0.0", true);

    private static final PhotonPlace WADA = new PhotonPlace(
            "Shaniwar Wada", "Shaniwar Wada, Kasba Peth, Pune, Maharashtra", 18.5195, 73.8553, "attraction");
    private static final PhotonPlace BENGALURU =
            new PhotonPlace("Bengaluru", "Bengaluru, Karnataka", 12.9716, 77.5946, "city");

    private final PhotonClient photon = mock(PhotonClient.class);
    private final GeocodeCacheRepository repository = mock(GeocodeCacheRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private GeocodeService service;

    @BeforeEach
    void setUp() {
        when(repository.findValid(anyString(), any())).thenReturn(Optional.empty());
        service = service(properties(true));
    }

    @Test
    void missCallsPhotonFiltersRegionAndStoresForCacheTtl() {
        when(photon.search(any())).thenReturn(List.of(BENGALURU, WADA));

        GeocodeLookup lookup = service.search(new SearchQuery("Shaniwar  Wada", 5, "en", null));

        assertThat(lookup.cached()).isFalse();
        assertThat(lookup.results()).extracting(GeocodeResult::name).containsExactly("Shaniwar Wada");
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(repository).upsert(anyString(), json.capture(), eq(NOW), eq(NOW.plus(Duration.ofDays(7))));
        assertThat(json.getValue()).contains("\"v\":1").contains("Shaniwar Wada").doesNotContain("Bengaluru");
        assertThat(meters.counter(GeocodeService.REQUESTS_METRIC, "kind", "search", "source", "upstream").count())
                .isEqualTo(1.0);
    }

    @Test
    void upstreamRequestIsNormalizedRoundedAndRestrictedToRegion() {
        when(photon.search(any())).thenReturn(List.of());

        service.search(new SearchQuery("Shaniwar  WADA", 3, "en", new GeoPoint(18.52049, 73.85672)));

        ArgumentCaptor<PhotonSearchRequest> captor = ArgumentCaptor.forClass(PhotonSearchRequest.class);
        verify(photon).search(captor.capture());
        PhotonSearchRequest request = captor.getValue();
        assertThat(request.query()).isEqualTo("shaniwar wada");
        assertThat(request.limit()).isEqualTo(3);
        assertThat(request.lang()).isEqualTo("en");
        assertThat(request.biasLat()).isEqualTo(18.52);
        assertThat(request.biasLon()).isEqualTo(73.86);
        assertThat(request.restrictTo()).isEqualTo(MMR.supportedRegion());
    }

    @Test
    void unsupportedLanguageAndOutOfRegionBiasAreOmitted() {
        when(photon.search(any())).thenReturn(List.of());

        service.search(new SearchQuery("pune station", 5, "mr", new GeoPoint(12.97, 77.59)));

        ArgumentCaptor<PhotonSearchRequest> captor = ArgumentCaptor.forClass(PhotonSearchRequest.class);
        verify(photon).search(captor.capture());
        assertThat(captor.getValue().lang()).isNull();
        assertThat(captor.getValue().biasLat()).isNull();
        assertThat(captor.getValue().biasLon()).isNull();
    }

    @Test
    void emptyAnswerIsCachedForTheNegativeTtl() {
        when(photon.search(any())).thenReturn(List.of());

        GeocodeLookup lookup = service.search(new SearchQuery("nowhere at all", 5, null, null));

        assertThat(lookup.results()).isEmpty();
        verify(repository).upsert(anyString(), anyString(), eq(NOW), eq(NOW.plus(Duration.ofHours(1))));
    }

    @Test
    void secondIdenticalSearchIsServedFromMemory() {
        when(photon.search(any())).thenReturn(List.of(WADA));

        service.search(new SearchQuery("Shaniwar Wada", 5, "en", new GeoPoint(18.5204, 73.8567)));
        GeocodeLookup second =
                service.search(new SearchQuery("shaniwar   wada", 5, "en", new GeoPoint(18.5210, 73.8571)));

        assertThat(second.cached()).isTrue();
        assertThat(second.results()).extracting(GeocodeResult::name).containsExactly("Shaniwar Wada");
        verify(photon, times(1)).search(any());
        verify(repository, times(1)).findValid(anyString(), any());
        assertThat(meters.counter(GeocodeService.REQUESTS_METRIC, "kind", "search", "source", "memory").count())
                .isEqualTo(1.0);
    }

    @Test
    void databaseHitIsServedWithoutPhoton() {
        String stored = """
                {"v":1,"results":[{"name":"Shaniwar Wada","displayName":"Shaniwar Wada, Pune",
                "lat":18.5195,"lon":73.8553,"type":"attraction"}]}
                """;
        when(repository.findValid(anyString(), eq(NOW)))
                .thenReturn(Optional.of(new GeocodeCacheRepository.CachedRow(stored, NOW.plusSeconds(60))));

        GeocodeLookup lookup = service.search(new SearchQuery("shaniwar wada", 5, "en", null));

        assertThat(lookup.cached()).isTrue();
        assertThat(lookup.results()).hasSize(1);
        GeocodeResult result = lookup.results().get(0);
        assertThat(result.displayName()).isEqualTo("Shaniwar Wada, Pune");
        assertThat(result.lat()).isEqualTo(18.5195);
        assertThat(result.type()).isEqualTo("attraction");
        verify(photon, never()).search(any());
        assertThat(meters.counter(GeocodeService.REQUESTS_METRIC, "kind", "search", "source", "db").count())
                .isEqualTo(1.0);
    }

    @Test
    void unreadableDatabaseEntryIsAMiss() {
        when(repository.findValid(anyString(), any()))
                .thenReturn(Optional.of(new GeocodeCacheRepository.CachedRow("{\"v\":99}", NOW.plusSeconds(60))));
        when(photon.search(any())).thenReturn(List.of(WADA));

        assertThat(service.search(new SearchQuery("shaniwar wada", 5, "en", null)).cached()).isFalse();
    }

    @Test
    void databaseFailureDoesNotBreakSearch() {
        when(repository.findValid(anyString(), any())).thenThrow(new DataAccessResourceFailureException("down"));
        doThrow(new DataAccessResourceFailureException("down"))
                .when(repository)
                .upsert(anyString(), anyString(), any(), any());
        when(photon.search(any())).thenReturn(List.of(WADA));

        assertThat(service.search(new SearchQuery("shaniwar wada", 5, "en", null)).results()).hasSize(1);
    }

    @Test
    void photonFailureIs503AndCounted() {
        when(photon.search(any())).thenThrow(
                new PhotonUnavailableException(PhotonUnavailableException.Reason.TIMEOUT, "Photon unreachable"));

        assertThatThrownBy(() -> service.search(new SearchQuery("shaniwar wada", 5, "en", null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(meters.counter(GeocodeService.UPSTREAM_ERRORS_METRIC, "kind", "search", "reason", "timeout").count())
                .isEqualTo(1.0);
        verify(repository, never()).upsert(anyString(), anyString(), any(), any());
    }

    @Test
    void disabledServesCacheButNeverCallsPhoton() {
        GeocodeService disabled = service(properties(false));

        assertThatThrownBy(() -> disabled.search(new SearchQuery("shaniwar wada", 5, "en", null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.UPSTREAM_UNAVAILABLE);
        verify(photon, never()).search(any());
    }

    @Test
    void reverseRoundsToFourDecimalsAndReturnsAtMostOne() {
        when(photon.reverse(any())).thenReturn(List.of(WADA, WADA));

        GeocodeLookup lookup = service.reverse(new ReverseQuery(new GeoPoint(18.520449, 73.856721), "en"));

        assertThat(lookup.results()).hasSize(1);
        ArgumentCaptor<PhotonReverseRequest> captor = ArgumentCaptor.forClass(PhotonReverseRequest.class);
        verify(photon).reverse(captor.capture());
        assertThat(captor.getValue().lat()).isEqualTo(18.5204);
        assertThat(captor.getValue().lon()).isEqualTo(73.8567);
    }

    @Test
    void reverseOutsideRegionIs422WithoutUpstreamCall() {
        assertThatThrownBy(() -> service.reverse(new ReverseQuery(new GeoPoint(12.9716, 77.5946), null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.UNSUPPORTED_REGION);
        verify(photon, never()).reverse(any());
    }

    @Test
    void reverseResultOutsideRegionIsDropped() {
        when(photon.reverse(any())).thenReturn(List.of(BENGALURU));

        assertThat(service.reverse(new ReverseQuery(new GeoPoint(18.52, 73.85), null)).results()).isEmpty();
    }

    private GeocodeService service(GeocodeProperties properties) {
        return new GeocodeService(photon, new GeocodeCache(repository, properties, clock), properties, MMR, meters);
    }

    private static GeocodeProperties properties(boolean enabled) {
        return new GeocodeProperties(enabled, null, null, null, null, null, null, null, null, null, List.of("en"));
    }
}
