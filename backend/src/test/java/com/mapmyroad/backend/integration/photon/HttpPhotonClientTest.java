package com.mapmyroad.backend.integration.photon;

import static org.assertj.core.api.Assertions.assertThat;

import com.mapmyroad.backend.config.GeocodeProperties;
import com.mapmyroad.backend.config.SupportedRegion;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** URI construction only; no network. Encoding is the SSRF and parameter-injection guard (SECURITY.md T12). */
class HttpPhotonClientTest {

    private static final SupportedRegion REGION = SupportedRegion.parse("72.6,17.9,75.1,19.6");

    private final HttpPhotonClient client =
            new HttpPhotonClient(RestClient.builder(), properties("https://photon.example.org/"));

    @Test
    void searchUriCarriesAllParametersOnTheConfiguredHost() {
        URI uri = client.searchUri(new PhotonSearchRequest("shaniwar wada", 5, "en", 18.52, 73.86, REGION));

        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("photon.example.org");
        assertThat(uri.getPath()).isEqualTo("/api");
        assertThat(uri.getRawQuery())
                .isEqualTo("q=shaniwar%20wada&limit=5&lang=en&lat=18.52&lon=73.86&bbox=72.6%2C17.9%2C75.1%2C19.6");
    }

    @Test
    void searchTextCannotInjectParametersOrChangeTheHost() {
        URI uri = client.searchUri(
                new PhotonSearchRequest("a&lang=de+b={x}#@evil.example/?q=1", 3, null, null, null, null));

        assertThat(uri.getHost()).isEqualTo("photon.example.org");
        assertThat(uri.getRawFragment()).isNull();
        assertThat(uri.getRawQuery())
                .isEqualTo("q=a%26lang%3Dde%2Bb%3D%7Bx%7D%23%40evil.example%2F%3Fq%3D1&limit=3");
    }

    @Test
    void languageAndBiasAreOmittedWhenAbsent() {
        URI uri = client.searchUri(new PhotonSearchRequest("pune", 5, null, null, null, null));

        assertThat(uri.getRawQuery()).isEqualTo("q=pune&limit=5");
    }

    @Test
    void reverseUriUsesPlainDecimals() {
        URI uri = client.reverseUri(new PhotonReverseRequest(18.5204, 0.0001, "en"));

        assertThat(uri.toString()).isEqualTo("https://photon.example.org/reverse?lat=18.5204&lon=0.0001&lang=en");
    }

    @Test
    void reverseUriOmitsUnsetLanguage() {
        URI uri = client.reverseUri(new PhotonReverseRequest(18.5204, 73.8567, null));

        assertThat(uri.getRawQuery()).isEqualTo("lat=18.5204&lon=73.8567");
    }

    @Test
    void baseUrlWithPathPrefixIsKept() {
        HttpPhotonClient prefixed = new HttpPhotonClient(RestClient.builder(), properties("http://photon:2322/photon"));

        URI uri = prefixed.reverseUri(new PhotonReverseRequest(18.5, 73.8, null));

        assertThat(uri.toString()).isEqualTo("http://photon:2322/photon/reverse?lat=18.5&lon=73.8");
    }

    static GeocodeProperties properties(String baseUrl) {
        return new GeocodeProperties(null, baseUrl, null, null, null, null, null, null, null, null, null);
    }
}
