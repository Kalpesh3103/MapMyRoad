package com.mapmyroad.backend.repo.routing;

import java.time.Instant;

/** A hazard close to a candidate route, with only the fields scoring needs (no location, no ID). */
public record HazardNearRoute(int severityMax, double confidence, Instant lastSeenAt) {}
