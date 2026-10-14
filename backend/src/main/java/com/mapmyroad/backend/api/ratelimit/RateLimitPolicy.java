package com.mapmyroad.backend.api.ratelimit;

import java.time.Duration;

/** Rate limits from API_SPEC.md section 4. Initial values, tune with real traffic. */
public enum RateLimitPolicy {
    DEVICE_REGISTRATION(10, Duration.ofHours(1), Key.IP),
    TOKEN_REFRESH(30, Duration.ofHours(1), Key.IP),
    EVENTS(60, Duration.ofHours(1), Key.DEVICE),
    TILES(600, Duration.ofMinutes(1), Key.IP),
    HAZARDS(60, Duration.ofMinutes(1), Key.DEVICE),
    FEEDBACK(30, Duration.ofHours(1), Key.DEVICE),
    GEOCODE_BURST(5, Duration.ofSeconds(1), Key.DEVICE),
    GEOCODE(300, Duration.ofHours(1), Key.DEVICE),
    ROUTES(60, Duration.ofHours(1), Key.DEVICE),
    REPORTS(20, Duration.ofDays(1), Key.DEVICE),
    CONFIG(120, Duration.ofMinutes(1), Key.IP);

    /** What a bucket is keyed by. DEVICE falls back to IP when the request is not authenticated. */
    public enum Key {
        IP,
        DEVICE,
    }

    private final int capacity;
    private final Duration window;
    private final Key key;

    RateLimitPolicy(int capacity, Duration window, Key key) {
        this.capacity = capacity;
        this.window = window;
        this.key = key;
    }

    public int capacity() {
        return capacity;
    }

    public Duration window() {
        return window;
    }

    public Key key() {
        return key;
    }
}
