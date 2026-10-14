package com.mapmyroad.backend.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.mapmyroad.backend.config.WebLayerProperties;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-13T08:00:00Z"));
    private final RateLimiter limiter = new RateLimiter(clock, WebLayerProperties.defaults());

    @Test
    void bucketAllowsBurstThenRefillsContinuously() {
        TokenBucket bucket = new TokenBucket(2, Duration.ofSeconds(10), 0);
        assertThat(bucket.tryConsume(0)).isEqualTo(0);
        assertThat(bucket.tryConsume(0)).isEqualTo(0);
        assertThat(bucket.tryConsume(0)).isEqualTo(5_000);
        assertThat(bucket.tryConsume(4_999)).isEqualTo(1);
        assertThat(bucket.tryConsume(5_000)).isEqualTo(0);
        assertThat(bucket.tryConsume(5_000)).isGreaterThan(0);
    }

    @Test
    void bucketNeverExceedsCapacity() {
        TokenBucket bucket = new TokenBucket(3, Duration.ofMinutes(1), 0);
        assertThat(bucket.availableTokens(Duration.ofHours(5).toMillis())).isEqualTo(3.0);
    }

    @Test
    void clockGoingBackwardsDoesNotMintTokens() {
        TokenBucket bucket = new TokenBucket(1, Duration.ofMinutes(1), 10_000);
        assertThat(bucket.tryConsume(10_000)).isEqualTo(0);
        assertThat(bucket.tryConsume(0)).isGreaterThan(0);
    }

    @Test
    void policyCapacityIsEnforcedPerKey() {
        RateLimitPolicy policy = RateLimitPolicy.DEVICE_REGISTRATION; // 10 per hour
        for (int i = 0; i < policy.capacity(); i++) {
            assertThat(limiter.tryAcquire(policy, "ip:a").allowed()).isTrue();
        }
        RateLimiter.Decision rejected = limiter.tryAcquire(policy, "ip:a");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSec()).isEqualTo(360); // one token per 6 minutes
        assertThat(limiter.tryAcquire(policy, "ip:b").allowed()).isTrue();
    }

    @Test
    void rejectedClientRecoversAfterRetryAfter() {
        RateLimitPolicy policy = RateLimitPolicy.GEOCODE_BURST; // 5 per second
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire(policy, "device:x");
        }
        RateLimiter.Decision rejected = limiter.tryAcquire(policy, "device:x");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSec()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(rejected.retryAfterSec()));
        assertThat(limiter.tryAcquire(policy, "device:x").allowed()).isTrue();
    }

    @Test
    void policiesHaveIndependentBuckets() {
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire(RateLimitPolicy.DEVICE_REGISTRATION, "ip:a");
        }
        assertThat(limiter.tryAcquire(RateLimitPolicy.DEVICE_REGISTRATION, "ip:a").allowed()).isFalse();
        assertThat(limiter.tryAcquire(RateLimitPolicy.TOKEN_REFRESH, "ip:a").allowed()).isTrue();
    }
}
