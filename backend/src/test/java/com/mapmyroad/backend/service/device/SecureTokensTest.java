package com.mapmyroad.backend.service.device;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecureTokensTest {

    @Test
    void randomTokenIs256BitsBase64UrlWithoutPadding() {
        String token = SecureTokens.randomToken();

        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void randomTokensDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            assertThat(seen.add(SecureTokens.randomToken())).isTrue();
        }
    }

    @Test
    void sha256MatchesKnownVector() {
        // FIPS 180-2 test vector for "abc".
        assertThat(HexFormat.of().formatHex(SecureTokens.sha256("abc")))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void sha256IsDeterministicAndDiffersPerToken() {
        String a = SecureTokens.randomToken();
        String b = SecureTokens.randomToken();

        assertThat(SecureTokens.sha256(a)).hasSize(32).isEqualTo(SecureTokens.sha256(a));
        assertThat(SecureTokens.sha256(a)).isNotEqualTo(SecureTokens.sha256(b));
    }
}
