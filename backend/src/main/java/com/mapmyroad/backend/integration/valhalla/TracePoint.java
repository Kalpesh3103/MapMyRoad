package com.mapmyroad.backend.integration.valhalla;

import java.time.Instant;

/** One input point for map-matching, in time order. Never logged (contains a precise location). */
public record TracePoint(double lat, double lon, Instant time) {

    @Override
    public String toString() {
        return "TracePoint[redacted]";
    }
}
