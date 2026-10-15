package com.mapmyroad.backend.repo.matching;

import java.time.Instant;
import java.util.UUID;

/**
 * An unmatched roughness window of one trip. {@code observedAt} is the wall time at the end of the window (the
 * app's {@code RoughnessWindow.wallTimeMs}). Contains precise locations, so {@link #toString()} omits them.
 */
public record PendingWindow(
        UUID id,
        double startLat,
        double startLon,
        double endLat,
        double endLon,
        Instant observedAt,
        double lengthM,
        double speedKmh,
        int vehicleType) {

    @Override
    public String toString() {
        return "PendingWindow[id=" + id + "]";
    }
}
