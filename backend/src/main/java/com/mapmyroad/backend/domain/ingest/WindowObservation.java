package com.mapmyroad.backend.domain.ingest;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One roughness window of about 50 m (maps to {@code segment_observation}, start and end become a line,
 * DATABASE_SCHEMA.md B4). Contains precise locations, so {@link #toString()} omits them.
 */
public record WindowObservation(
        UUID id,
        double startLat,
        double startLon,
        double endLat,
        double endLon,
        Instant observedAt,
        double lengthM,
        double roughness,
        double speedKmh) {

    public WindowObservation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(observedAt, "observedAt");
    }

    @Override
    public String toString() {
        return "WindowObservation[id=" + id + "]"; // never print coordinates (AGENTS.md section 6)
    }
}
