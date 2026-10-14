package com.mapmyroad.backend.service.device;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Builds the HS256 encoder and decoder for device access tokens from a {@link JwtKeyRing}.
 *
 * <p>Why a hand-built Nimbus processor: {@code NimbusJwtDecoder.withSecretKey(..)} accepts exactly one key, but key
 * rotation needs several to be valid at once. A {@link JWSVerificationKeySelector} over an {@link ImmutableJWKSet}
 * picks the key whose {@code kid} matches the token header and only allows HS256, so tokens signed with another
 * algorithm (including {@code none}) are rejected. Nimbus' own claim checks are switched off so that all time checks
 * happen in Spring's {@link JwtTimestampValidator}; that keeps one source of "expired" errors, which the entry point
 * maps to {@code TOKEN_EXPIRED}.
 */
public final class DeviceJwts {

    /** Leeway for clock differences between server instances and restarts. */
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private DeviceJwts() {}

    /** Signs with the ring's first key only, and writes its {@code kid} into the header. */
    public static JwtEncoder encoder(JwtKeyRing ring) {
        // Only the signing key is in this set: NimbusJwtEncoder refuses to choose between several matching keys.
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(toJwk(ring.signingKey()))));
    }

    /** Accepts tokens signed with any key in the ring, then runs the timestamp and device-claims validators. */
    public static JwtDecoder decoder(JwtKeyRing ring, Clock clock) {
        List<JWK> jwks = new ArrayList<>();
        for (JwtKeyRing.Key key : ring.verificationKeys()) {
            jwks.add(toJwk(key));
        }
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(
                new JWSVerificationKeySelector<>(JWSAlgorithm.HS256, new ImmutableJWKSet<>(new JWKSet(jwks))));
        processor.setJWTClaimsSetVerifier((claims, context) -> {
            // Intentionally empty: expiry and claim checks are done by the Spring validators below.
        });

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new DeviceClaimsValidator()));
        return decoder;
    }

    private static OctetSequenceKey toJwk(JwtKeyRing.Key key) {
        return new OctetSequenceKey.Builder(key.secret())
                .keyID(key.kid())
                .algorithm(JWSAlgorithm.HS256)
                .keyUse(KeyUse.SIGNATURE)
                .build();
    }
}
