package com.mapmyroad.backend.service.tiles;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TileEtagTest {

    @Test
    void isWeakStableAndContentDependent() {
        String a = TileEtag.of(new byte[] {1, 2, 3});
        assertThat(a).startsWith("W/\"").endsWith("\"").hasSize(4 + 32);
        assertThat(TileEtag.of(new byte[] {1, 2, 3})).isEqualTo(a);
        assertThat(TileEtag.of(new byte[] {1, 2, 4})).isNotEqualTo(a);
    }

    @Test
    void matchesWithWeakComparisonListsAndWildcard() {
        String etag = TileEtag.of(new byte[] {9});
        String opaque = etag.substring(2);
        assertThat(TileEtag.matches(etag, etag)).isTrue();
        assertThat(TileEtag.matches(opaque, etag)).isTrue();
        assertThat(TileEtag.matches("\"other\", " + etag, etag)).isTrue();
        assertThat(TileEtag.matches("*", etag)).isTrue();
        assertThat(TileEtag.matches("\"other\"", etag)).isFalse();
        assertThat(TileEtag.matches(null, etag)).isFalse();
        assertThat(TileEtag.matches(" ", etag)).isFalse();
    }
}
