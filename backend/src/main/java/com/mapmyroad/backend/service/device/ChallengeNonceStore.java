package com.mapmyroad.backend.service.device;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mapmyroad.backend.config.DeviceProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Single-use registration challenge nonces (SECURITY.md T8). Kept in memory only (DATABASE_SCHEMA.md section A4):
 * a restart simply makes pending registrations ask for a new challenge. This assumes one backend instance; several
 * instances would need a shared store or sticky routing.
 */
@Component
public class ChallengeNonceStore {

    /** Upper bound on outstanding challenges; registration is rate limited per IP, so this is generous. */
    static final long MAX_OUTSTANDING = 100_000;

    private final Cache<String, Instant> nonces;
    private final Duration ttl;
    private final Clock clock;

    public ChallengeNonceStore(DeviceProperties properties, Clock clock) {
        this.ttl = properties.nonceTtl();
        this.clock = clock;
        this.nonces = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(MAX_OUTSTANDING)
                .build();
    }

    /** Creates and remembers a fresh nonce. */
    public String issue() {
        String nonce = SecureTokens.randomToken();
        nonces.put(nonce, clock.instant().plus(ttl));
        return nonce;
    }

    public Duration ttl() {
        return ttl;
    }

    /**
     * Removes the nonce and reports whether it was outstanding and unexpired. Atomic, so two registrations racing
     * with the same nonce cannot both succeed. The explicit expiry check backs up Caffeine's lazy eviction and makes
     * the rule testable with a fixed {@link Clock}.
     */
    public boolean consume(String nonce) {
        if (nonce == null) {
            return false;
        }
        Instant expiresAt = nonces.asMap().remove(nonce);
        return expiresAt != null && clock.instant().isBefore(expiresAt);
    }
}
