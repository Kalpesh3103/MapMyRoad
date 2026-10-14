package com.mapmyroad.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits and plausibility bounds for {@code POST /v1/events} (API_SPEC.md section 5.3, SECURITY.md section 7).
 * All values are initial values to tune with pilot data, not measured facts.
 *
 * @param maxEventsPerRequest array cap for events (SECURITY.md T11)
 * @param maxWindowsPerRequest array cap for roughness windows (SECURITY.md T11)
 * @param minEventSpeedKmh events below this speed are rejected: the detector is unreliable when crawling
 * @param maxSpeedKmh upper plausibility bound for events and windows
 * @param maxFutureSkew how far {@code observedAt} may lie in the future (phone clock drift)
 * @param maxAge how old {@code observedAt} may be
 * @param minWindowLengthM shortest accepted roughness window
 * @param maxWindowLengthM longest accepted roughness window
 */
@ConfigurationProperties(prefix = "mmr.ingest")
public record IngestProperties(
        Integer maxEventsPerRequest,
        Integer maxWindowsPerRequest,
        Double minEventSpeedKmh,
        Double maxSpeedKmh,
        Duration maxFutureSkew,
        Duration maxAge,
        Double minWindowLengthM,
        Double maxWindowLengthM) {

    public IngestProperties {
        maxEventsPerRequest = maxEventsPerRequest == null ? 500 : maxEventsPerRequest;
        maxWindowsPerRequest = maxWindowsPerRequest == null ? 1000 : maxWindowsPerRequest;
        minEventSpeedKmh = minEventSpeedKmh == null ? 10.0 : minEventSpeedKmh;
        maxSpeedKmh = maxSpeedKmh == null ? 200.0 : maxSpeedKmh;
        maxFutureSkew = maxFutureSkew == null ? Duration.ofMinutes(5) : maxFutureSkew;
        maxAge = maxAge == null ? Duration.ofDays(14) : maxAge;
        minWindowLengthM = minWindowLengthM == null ? 20.0 : minWindowLengthM;
        maxWindowLengthM = maxWindowLengthM == null ? 120.0 : maxWindowLengthM;
        if (maxEventsPerRequest < 1 || maxWindowsPerRequest < 1) {
            throw new IllegalArgumentException("mmr.ingest request caps must be positive");
        }
        if (minEventSpeedKmh < 0 || maxSpeedKmh <= minEventSpeedKmh) {
            throw new IllegalArgumentException("mmr.ingest speed bounds are inconsistent");
        }
        if (maxFutureSkew.isNegative() || maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("mmr.ingest timestamp bounds are inconsistent");
        }
        if (minWindowLengthM <= 0 || maxWindowLengthM < minWindowLengthM) {
            throw new IllegalArgumentException("mmr.ingest window length bounds are inconsistent");
        }
    }

    /** Defaults from API_SPEC.md section 5.3, for tests and for code that runs without Spring. */
    public static IngestProperties defaults() {
        return new IngestProperties(null, null, null, null, null, null, null, null);
    }
}
