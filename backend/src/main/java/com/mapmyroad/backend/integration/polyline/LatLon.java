package com.mapmyroad.backend.integration.polyline;

/**
 * A WGS 84 position in decimal degrees (API_SPEC.md section 1). Holds a precise location, so {@link #toString()}
 * is redacted and the value must never be logged (AGENTS.md section 6).
 */
public record LatLon(double lat, double lon) {

    private static final double EARTH_RADIUS_M = 6_371_008.8;

    /** Great-circle (haversine) distance in meters on a spherical earth, good to about 0.5 % for our use. */
    public double distanceMTo(LatLon other) {
        double phi1 = Math.toRadians(lat);
        double phi2 = Math.toRadians(other.lat);
        double dPhi = phi2 - phi1;
        double dLambda = Math.toRadians(other.lon - lon);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    @Override
    public String toString() {
        return "LatLon[redacted]";
    }
}
