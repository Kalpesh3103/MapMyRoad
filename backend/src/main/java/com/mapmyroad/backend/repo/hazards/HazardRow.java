package com.mapmyroad.backend.repo.hazards;

import java.time.Instant;
import java.util.UUID;

/** Aggregated hazard columns read for the hazards API. Full precision; the service reduces it for publication. */
public record HazardRow(
        UUID id,
        double lon,
        double lat,
        int severityMax,
        double confidence,
        String status,
        Instant firstSeenAt,
        Instant lastSeenAt,
        int confirmCount,
        int dismissCount) {}
