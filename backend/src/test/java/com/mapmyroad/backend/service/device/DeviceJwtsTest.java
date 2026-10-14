package com.mapmyroad.backend.service.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.mapmyroad.backend.api.device.ProblemAuthenticationEntryPoint;
import com.mapmyroad.backend.config.DeviceProperties;
import com.mapmyroad.backend.domain.device.IssuedToken;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/** Round trips through the real Nimbus-backed encoder and decoder, including key rotation and expiry. */
class DeviceJwtsTest {

    private static final String NEW_SECRET = JwtKeyRingTest.secret(32, 1);
    private static final String OLD_SECRET = JwtKeyRingTest.secret(32, 2);
    private static final Instant NOW = Instant.parse("2026-10-14T08:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final DeviceProperties properties = new DeviceProperties(true, null, null, null, null, null, null);

    private AccessTokenService issuer(JwtKeyRing ring) {
        return new AccessTokenService(DeviceJwts.encoder(ring), ring, properties, clock);
    }

    @Test
    void issuedTokenCarriesDeviceClaimsAndKid() {
        JwtKeyRing ring = JwtKeyRing.parse("k-new:" + NEW_SECRET);
        UUID deviceId = UUID.randomUUID();

        IssuedToken token = issuer(ring).issue(deviceId);
        Jwt jwt = DeviceJwts.decoder(ring, clock).decode(token.value());

        assertThat(token.expiresInSec()).isEqualTo(3600);
        assertThat(jwt.getSubject()).isEqualTo(deviceId.toString());
        assertThat(jwt.getClaimAsString("scope")).isEqualTo("device");
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(jwt.getHeaders()).containsEntry("kid", "k-new").containsEntry("alg", "HS256");
    }

    @Test
    void tokenSignedWithOldKeyStillValidAfterRotation() {
        JwtKeyRing before = JwtKeyRing.parse("k-old:" + OLD_SECRET);
        JwtKeyRing after = JwtKeyRing.parse("k-new:" + NEW_SECRET + ",k-old:" + OLD_SECRET);
        UUID deviceId = UUID.randomUUID();

        String oldToken = issuer(before).issue(deviceId).value();
        String newToken = issuer(after).issue(deviceId).value();
        JwtDecoder decoder = DeviceJwts.decoder(after, clock);

        assertThat(decoder.decode(oldToken).getSubject()).isEqualTo(deviceId.toString());
        assertThat(decoder.decode(newToken).getHeaders()).containsEntry("kid", "k-new");
    }

    @Test
    void tokenSignedWithRemovedKeyIsRejected() {
        JwtKeyRing before = JwtKeyRing.parse("k-old:" + OLD_SECRET);
        JwtKeyRing afterRemoval = JwtKeyRing.parse("k-new:" + NEW_SECRET);

        String oldToken = issuer(before).issue(UUID.randomUUID()).value();

        assertThatThrownBy(() -> DeviceJwts.decoder(afterRemoval, clock).decode(oldToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void tokenWithForgedKidButWrongSecretIsRejected() {
        JwtKeyRing attacker = JwtKeyRing.parse("k-new:" + OLD_SECRET);
        JwtKeyRing server = JwtKeyRing.parse("k-new:" + NEW_SECRET);

        String forged = issuer(attacker).issue(UUID.randomUUID()).value();

        assertThatThrownBy(() -> DeviceJwts.decoder(server, clock).decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejectedAndRecognizedAsExpired() {
        JwtKeyRing ring = JwtKeyRing.parse("k1:" + NEW_SECRET);
        String token = issuer(ring).issue(UUID.randomUUID()).value();

        clock.advance(Duration.ofHours(1).plus(DeviceJwts.CLOCK_SKEW).plusSeconds(1));
        Throwable thrown = catchThrowable(() -> DeviceJwts.decoder(ring, clock).decode(token));

        assertThat(thrown).isInstanceOf(JwtValidationException.class);
        assertThat(ProblemAuthenticationEntryPoint.isExpiredToken(thrown)).isTrue();
    }

    @Test
    void tokenWithinClockSkewIsAccepted() {
        JwtKeyRing ring = JwtKeyRing.parse("k1:" + NEW_SECRET);
        String token = issuer(ring).issue(UUID.randomUUID()).value();

        clock.advance(Duration.ofHours(1).plusSeconds(30));

        assertThat(DeviceJwts.decoder(ring, clock).decode(token).getSubject()).isNotNull();
    }

    @Test
    void tokenWithoutDeviceScopeIsRejectedButNotAsExpired() {
        JwtKeyRing ring = JwtKeyRing.parse("k1:" + NEW_SECRET);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(UUID.randomUUID().toString())
                .issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(600))
                .claim("scope", "admin")
                .build();
        String token = DeviceJwts.encoder(ring)
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).keyId("k1").build(), claims))
                .getTokenValue();

        Throwable thrown = catchThrowable(() -> DeviceJwts.decoder(ring, clock).decode(token));

        assertThat(thrown).isInstanceOf(JwtValidationException.class);
        assertThat(ProblemAuthenticationEntryPoint.isExpiredToken(thrown)).isFalse();
    }

    @Test
    void tokenWithNonUuidSubjectIsRejected() {
        JwtKeyRing ring = JwtKeyRing.parse("k1:" + NEW_SECRET);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("not-a-uuid")
                .issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(600))
                .claim("scope", "device")
                .build();
        String token = DeviceJwts.encoder(ring)
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).keyId("k1").build(), claims))
                .getTokenValue();

        assertThatThrownBy(() -> DeviceJwts.decoder(ring, clock).decode(token))
                .isInstanceOf(JwtValidationException.class);
    }
}
