package com.mapmyroad.backend.domain.ingest;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One detected bump as uploaded by a device (maps to {@code hazard_event}). Values are not yet checked for
 * plausibility; see {@code UploadValidator}. Contains a precise location, so {@link #toString()} omits it.
 *
 * @param headingDeg optional, {@code null} when the client did not send it
 */
public record EventObservation(
        UUID id,
        double lat,
        double lon,
        Instant observedAt,
        int severity,
        double speedKmh,
        double peakScore,
        Integer headingDeg) {

    public EventObservation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(observedAt, "observedAt");
    }

    /** Copy with a different heading, used to drop an out-of-range optional heading. */
    public EventObservation withHeadingDeg(Integer newHeadingDeg) {
        return new EventObservation(id, lat, lon, observedAt, severity, speedKmh, peakScore, newHeadingDeg);
    }

    @Override
    public String toString() {
        return "EventObservation[id=" + id + "]"; // never print coordinates (AGENTS.md section 6)
    }
}
