package com.mapmyroad.backend.service.geocode;

/**
 * A validated WGS84 point (CODING_STANDARDS.md section 4.3). {@code toString} hides the coordinates so they cannot
 * leak into logs or exception messages (AGENTS.md section 6).
 */
public record GeoPoint(double lat, double lon) {

    public GeoPoint {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw new IllegalArgumentException("Coordinates out of range");
        }
    }

    @Override
    public String toString() {
        return "GeoPoint[***]";
    }
}
