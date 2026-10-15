package com.mapmyroad.backend.repo.matching;

import java.time.Instant;
import java.util.UUID;

/** An unmatched event of one trip. Contains a precise location, so {@link #toString()} omits it. */
public record PendingEvent(UUID id, double lat, double lon, Instant observedAt, int vehicleType) {

    @Override
    public String toString() {
        return "PendingEvent[id=" + id + "]";
    }
}
