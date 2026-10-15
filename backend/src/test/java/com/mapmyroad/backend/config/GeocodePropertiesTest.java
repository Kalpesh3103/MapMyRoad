package com.mapmyroad.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeocodePropertiesTest {

    @Test
    void defaultsMatchTheDocumentedValues() {
        GeocodeProperties p = new GeocodeProperties(null, null, null, null, null, null, null, null, null, null, null);

        assertThat(p.enabled()).isTrue();
        assertThat(p.baseUrl()).isEqualTo("https://photon.komoot.io");
        assertThat(p.userAgent()).startsWith("MapMyRoad/");
        assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.readTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.cacheTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(p.negativeCacheTtl()).isEqualTo(Duration.ofHours(1));
        assertThat(p.memoryCacheSize()).isEqualTo(10_000);
        assertThat(p.upstreamMaxConcurrent()).isEqualTo(4);
        assertThat(p.supportedLanguages()).containsExactly("en");
    }

    @Test
    void trailingSlashIsRemovedAndLanguagesAreNormalized() {
        GeocodeProperties p = new GeocodeProperties(
                null, "http://photon:2322/", null, null, null, null, null, null, null, null,
                List.of(" EN ", "de", "en"));

        assertThat(p.baseUrl()).isEqualTo("http://photon:2322");
        assertThat(p.supportedLanguages()).containsExactly("en", "de");
        assertThat(p.supportsLanguage("de")).isTrue();
        assertThat(p.supportsLanguage("mr")).isFalse();
        assertThat(p.supportsLanguage(null)).isFalse();
    }

    @Test
    void rejectsBaseUrlsThatAreNotPlainHttpOrigins() {
        List<String> bad = List.of(
                "ftp://photon", "photon.komoot.io", "https://a/?x=1", "https://user@host", "https://h/#f");
        for (String url : bad) {
            assertThatThrownBy(() -> new GeocodeProperties(
                            null, url, null, null, null, null, null, null, null, null, null))
                    .as(url)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsNonPositiveDurationsAndConcurrency() {
        assertThatThrownBy(() -> new GeocodeProperties(
                        null, null, null, Duration.ZERO, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeocodeProperties(null, null, null, null, null, null, null, null, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
