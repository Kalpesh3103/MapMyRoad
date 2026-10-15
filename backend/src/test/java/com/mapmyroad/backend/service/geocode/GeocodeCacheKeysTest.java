package com.mapmyroad.backend.service.geocode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeocodeCacheKeysTest {

    @Test
    void queryNormalizationIgnoresCaseAndWhitespace() {
        assertThat(GeocodeCacheKeys.normalizeQuery("  Shaniwar \t  WADA\n")).isEqualTo("shaniwar wada");
        assertThat(GeocodeCacheKeys.normalizeQuery("Shaniwar Wada")).isEqualTo("shaniwar wada");
        assertThat(GeocodeCacheKeys.normalizeQuery("Pune\u0000Station")).isEqualTo("pune station");
        assertThat(GeocodeCacheKeys.collapse("  Pune   Station ")).isEqualTo("Pune Station");
    }

    @Test
    void equivalentQueriesShareAKey() {
        String a = GeocodeCacheKeys.searchKey(GeocodeCacheKeys.normalizeQuery("  Shaniwar   WADA"), "en", 5, null);
        String b = GeocodeCacheKeys.searchKey(GeocodeCacheKeys.normalizeQuery("shaniwar wada"), "en", 5, null);

        assertThat(a).isEqualTo(b).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void languageLimitAndBiasChangeTheKey() {
        String base = GeocodeCacheKeys.searchKey("pune", "en", 5, null);

        assertThat(GeocodeCacheKeys.searchKey("pune", null, 5, null)).isNotEqualTo(base);
        assertThat(GeocodeCacheKeys.searchKey("pune", "en", 6, null)).isNotEqualTo(base);
        assertThat(GeocodeCacheKeys.searchKey("pune", "en", 5, new GeoPoint(18.52, 73.86))).isNotEqualTo(base);
    }

    @Test
    void biasIsRoundedToTwoDecimals() {
        assertThat(GeocodeCacheKeys.round(18.5204, GeocodeCacheKeys.BIAS_DECIMALS)).isEqualTo(18.52);
        assertThat(GeocodeCacheKeys.round(18.5249, GeocodeCacheKeys.BIAS_DECIMALS)).isEqualTo(18.52);
        assertThat(GeocodeCacheKeys.round(18.525, GeocodeCacheKeys.BIAS_DECIMALS)).isEqualTo(18.53);
        assertThat(GeocodeCacheKeys.round(73.8567, GeocodeCacheKeys.BIAS_DECIMALS)).isEqualTo(73.86);
    }

    @Test
    void materialHasFixedScaleAndNoExactLocation() {
        String material = GeocodeCacheKeys.searchMaterial("pune", "en", 5, new GeoPoint(18.5, 73.86));

        assertThat(material).isEqualTo("search|v1|q=pune|lang=en|limit=5|bias=18.50,73.86");
        assertThat(GeocodeCacheKeys.searchMaterial("pune", null, 5, null))
                .isEqualTo("search|v1|q=pune|lang=|limit=5|bias=");
    }

    @Test
    void reverseKeyUsesFourDecimals() {
        GeoPoint rounded = new GeoPoint(
                GeocodeCacheKeys.round(18.52045, GeocodeCacheKeys.REVERSE_DECIMALS),
                GeocodeCacheKeys.round(73.85672, GeocodeCacheKeys.REVERSE_DECIMALS));

        assertThat(GeocodeCacheKeys.reverseMaterial(rounded, "en")).isEqualTo("reverse|v1|lang=en|at=18.5205,73.8567");
        assertThat(GeocodeCacheKeys.reverseKey(rounded, "en"))
                .isNotEqualTo(GeocodeCacheKeys.reverseKey(rounded, null))
                .isEqualTo(GeocodeCacheKeys.sha256Hex("reverse|v1|lang=en|at=18.5205,73.8567"));
    }

    @Test
    void negativeZeroFormatsAsZero() {
        assertThat(GeocodeCacheKeys.reverseMaterial(new GeoPoint(-0.0, 0.0), null))
                .isEqualTo("reverse|v1|lang=|at=0.0000,0.0000");
    }
}
