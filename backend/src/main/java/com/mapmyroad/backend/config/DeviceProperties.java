package com.mapmyroad.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Device identity and token settings under {@code mmr.device} (API_SPEC.md section 2, SECURITY.md section 5).
 *
 * @param integrityEnforced verify Play Integrity verdicts at registration. Only the {@code local} and {@code test}
 *     profiles may turn this off; the {@code prod} profile refuses to start without it.
 * @param packageName Android application ID expected in the integrity verdict
 * @param playIntegrityServiceAccountFile path of the Google service-account JSON (env {@code PLAY_INTEGRITY_SA_FILE})
 * @param jwtKeys access-token signing keys as {@code kid1:base64Secret,kid2:base64Secret}, newest first
 *     (env {@code JWT_KEYS}). Tokens are signed with the first key and verified with any of them.
 * @param accessTokenTtl lifetime of an access token
 * @param refreshTokenTtl lifetime of a refresh token
 * @param nonceTtl lifetime of a registration challenge nonce
 */
@ConfigurationProperties(prefix = "mmr.device")
public record DeviceProperties(
        Boolean integrityEnforced,
        String packageName,
        String playIntegrityServiceAccountFile,
        String jwtKeys,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration nonceTtl) {

    public DeviceProperties {
        if (integrityEnforced == null) {
            integrityEnforced = Boolean.TRUE;
        }
        if (packageName == null || packageName.isBlank()) {
            packageName = "com.mapmyroad";
        }
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofHours(1);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(30);
        }
        if (nonceTtl == null) {
            nonceTtl = Duration.ofMinutes(5);
        }
        requirePositive(accessTokenTtl, "access-token-ttl");
        requirePositive(refreshTokenTtl, "refresh-token-ttl");
        requirePositive(nonceTtl, "nonce-ttl");
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("mmr.device." + name + " must be positive");
        }
    }
}
