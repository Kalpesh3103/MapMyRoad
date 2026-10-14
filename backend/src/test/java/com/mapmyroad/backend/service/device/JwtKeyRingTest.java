package com.mapmyroad.backend.service.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtKeyRingTest {

    static String secret(int bytes, int fill) {
        byte[] value = new byte[bytes];
        java.util.Arrays.fill(value, (byte) fill);
        return Base64.getEncoder().encodeToString(value);
    }

    @Test
    void firstKeySignsAndAllKeysVerify() {
        JwtKeyRing ring = JwtKeyRing.parse("new:" + secret(32, 1) + ", old:" + secret(48, 2));

        assertThat(ring.signingKey().kid()).isEqualTo("new");
        assertThat(ring.verificationKeys()).extracting(JwtKeyRing.Key::kid).containsExactly("new", "old");
        assertThat(ring.verificationKeys().get(1).secret()).hasSize(48);
    }

    @Test
    void acceptsUrlSafeBase64() {
        byte[] value = new byte[32];
        java.util.Arrays.fill(value, (byte) 0xFB); // encodes with '-' and '_' in URL-safe form
        String urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(value);

        assertThat(JwtKeyRing.parse("k1:" + urlSafe).signingKey().secret()).isEqualTo(value);
    }

    @Test
    void rejectsSecretsShorterThan256Bits() {
        assertThatThrownBy(() -> JwtKeyRing.parse("k1:" + secret(31, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("256 bits");
    }

    @Test
    void rejectsDuplicateKidMalformedEntriesAndInvalidBase64() {
        assertThatThrownBy(() -> JwtKeyRing.parse("a:" + secret(32, 1) + ",a:" + secret(32, 2)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtKeyRing.parse(secret(32, 1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtKeyRing.parse("k1:***not base64***")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtKeyRing.parse("bad kid:" + secret(32, 1))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void errorMessagesNeverContainTheSecret() {
        String shortSecret = secret(16, 7);

        Throwable thrown = catchThrowable(() -> JwtKeyRing.parse("k1:" + shortSecret));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage()).doesNotContain(shortSecret);
    }

    @Test
    void blankConfigFailsUnlessEphemeralAllowed() {
        assertThatThrownBy(() -> JwtKeyRing.fromConfig("", false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtKeyRing.fromConfig(null, false)).isInstanceOf(IllegalStateException.class);

        JwtKeyRing ephemeral = JwtKeyRing.fromConfig(" ", true);
        assertThat(ephemeral.signingKey().secret()).hasSize(JwtKeyRing.MIN_SECRET_BYTES);
    }

    @Test
    void keyToStringHidesSecret() {
        JwtKeyRing ring = JwtKeyRing.parse("k1:" + secret(32, 1));

        assertThat(ring.signingKey().toString()).isEqualTo("Key[kid=k1]");
    }
}
