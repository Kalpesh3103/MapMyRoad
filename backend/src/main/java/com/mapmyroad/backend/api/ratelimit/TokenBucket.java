package com.mapmyroad.backend.api.ratelimit;

import java.time.Duration;

/**
 * Classic token bucket: holds up to {@code capacity} tokens and refills continuously at {@code capacity} per
 * {@code window}. A full bucket allows a burst of {@code capacity} requests, after which requests are admitted at
 * the steady rate. Thread-safe through a per-bucket monitor; contention is limited to one client key.
 */
final class TokenBucket {

    private final double capacity;
    private final double tokensPerMs;
    private double tokens;
    private long lastRefillMs;

    TokenBucket(int capacity, Duration window, long nowMs) {
        if (capacity <= 0 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("capacity and window must be positive");
        }
        this.capacity = capacity;
        this.tokensPerMs = capacity / (double) window.toMillis();
        this.tokens = capacity;
        this.lastRefillMs = nowMs;
    }

    /**
     * Takes one token if available.
     *
     * @return 0 when admitted, otherwise the milliseconds until one token will be available
     */
    synchronized long tryConsume(long nowMs) {
        refill(nowMs);
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return 0;
        }
        return Math.max(1L, (long) Math.ceil((1.0 - tokens) / tokensPerMs));
    }

    synchronized double availableTokens(long nowMs) {
        refill(nowMs);
        return tokens;
    }

    private void refill(long nowMs) {
        // A clock that steps backwards (NTP correction) must not mint or remove tokens.
        long elapsedMs = Math.max(0L, nowMs - lastRefillMs);
        if (elapsedMs > 0) {
            tokens = Math.min(capacity, tokens + elapsedMs * tokensPerMs);
            lastRefillMs = nowMs;
        }
    }
}
