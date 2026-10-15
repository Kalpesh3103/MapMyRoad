package com.mapmyroad.backend.service.hazards;

import java.time.Instant;
import java.util.UUID;

/**
 * A hazard as the API may show it: aggregated fields only, already reduced to {@link PublicationRules} precision.
 * There is deliberately no device, trip or event field.
 */
public record PublishedHazard(
        UUID id,
        double lon,
        double lat,
        int severity,
        double confidence,
        HazardStatus status,
        Instant firstSeenDay,
        Instant lastSeenDay,
        int confirmCount,
        int dismissCount) {}
