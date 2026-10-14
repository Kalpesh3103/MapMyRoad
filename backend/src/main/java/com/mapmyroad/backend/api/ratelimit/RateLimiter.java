package com.mapmyroad.backend.api.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mapmyroad.backend.config.WebLayerProperties;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * In-memory rate limiter for the policies in {@link RateLimitPolicy} (API_SPEC.md section 4). One Caffeine cache of
 * {@link TokenBucket}s per policy, keyed by client (device or IP). Replaces Bucket4j to avoid a dependency; the
 * backend runs as a single instance on one VM (ARCHITECTURE.md), so no shared state is needed.
 *
 * <p>Idle buckets expire after one window: by then they would have refilled completely, so dropping them changes
 * nothing. The size bound caps memory under an address-rotation flood; an evicted bucket starts full again, which
 * errs on the side of availability. Caddy applies a coarser limit in front (SECURITY.md T20).
 */
@Component
public class RateLimiter {

    private final Clock clock;
    private final Map<RateLimitPolicy, Cache<String, TokenBucket>> buckets = new EnumMap<>(RateLimitPolicy.class);

    public RateLimiter(Clock clock, WebLayerProperties properties) {
        this.clock = clock;
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            buckets.put(
                    policy,
                    Caffeine.newBuilder()
                            .expireAfterAccess(policy.window())
                            .maximumSize(properties.rateLimitMaxKeysPerPolicy())
                            .build());
        }
    }

    /** Takes one token for {@code clientKey} under {@code policy}. */
    public Decision tryAcquire(RateLimitPolicy policy, String clientKey) {
        long nowMs = clock.millis();
        TokenBucket bucket = buckets.get(policy)
                .get(clientKey, key -> new TokenBucket(policy.capacity(), policy.window(), nowMs));
        long waitMs = bucket.tryConsume(nowMs);
        return waitMs == 0 ? Decision.ALLOWED : new Decision(false, Math.max(1L, (waitMs + 999) / 1000));
    }

    /** Outcome of {@link #tryAcquire}; {@code retryAfterSec} is at least 1 when rejected and 0 when allowed. */
    public record Decision(boolean allowed, long retryAfterSec) {
        static final Decision ALLOWED = new Decision(true, 0);
    }
}
