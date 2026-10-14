package com.mapmyroad.backend.service.device;

import com.mapmyroad.backend.config.DeviceProperties;
import com.mapmyroad.backend.domain.device.IssuedToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues short-lived device access tokens (JWT HS256, claims sub, iat, exp, scope; header kid). */
@Service
public class AccessTokenService {

    private final JwtEncoder encoder;
    private final String signingKid;
    private final Duration ttl;
    private final Clock clock;

    public AccessTokenService(JwtEncoder encoder, JwtKeyRing keyRing, DeviceProperties properties, Clock clock) {
        this.encoder = encoder;
        this.signingKid = keyRing.signingKey().kid();
        this.ttl = properties.accessTokenTtl();
        this.clock = clock;
    }

    public IssuedToken issue(UUID deviceId) {
        // JWT timestamps have second precision; truncating keeps iat/exp and expiresInSec consistent.
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(ttl);
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).keyId(signingKid).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(deviceId.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(DeviceClaimsValidator.SCOPE_CLAIM, DeviceClaimsValidator.DEVICE_SCOPE)
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, ttl.toSeconds());
    }
}
