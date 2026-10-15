package com.mapmyroad.backend.service.hazards;

import com.mapmyroad.backend.api.error.ApiException;

/**
 * A query box in WGS 84 degrees for {@code GET /v1/hazards} (API_SPEC.md 5.5). Boxes crossing the antimeridian
 * are not supported (the service region is far from it). The area cap limits bulk downloads (SECURITY.md T9).
 */
public record BoundingBox(double minLon, double minLat, double maxLon, double maxLat) {

    /** Largest accepted area: about 0.1 by 0.1 degrees (API_SPEC.md 5.5). */
    public static final double MAX_AREA_DEG2 = 0.1 * 0.1;

    // Tolerance for decimal inputs like 73.80,18.50,73.90,18.60 whose product is not exactly 0.01 in binary.
    private static final double AREA_EPSILON = 1e-9;

    static final String FIELD = "bbox";

    /**
     * Parses {@code minLon,minLat,maxLon,maxLat}.
     *
     * @throws ApiException 400 INVALID_REQUEST when malformed, out of range, inverted or too large
     */
    public static BoundingBox parse(String text) {
        if (text == null || text.isBlank()) {
            throw InvalidRequests.field(FIELD, "is required");
        }
        String[] parts = text.split(",", -1);
        if (parts.length != 4) {
            throw InvalidRequests.field(FIELD, "must be minLon,minLat,maxLon,maxLat");
        }
        double[] values = new double[4];
        for (int i = 0; i < 4; i++) {
            try {
                values[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                throw InvalidRequests.field(FIELD, "must contain four numbers");
            }
            if (!Double.isFinite(values[i])) {
                throw InvalidRequests.field(FIELD, "must contain four numbers");
            }
        }
        BoundingBox box = new BoundingBox(values[0], values[1], values[2], values[3]);
        if (box.minLon < -180 || box.maxLon > 180 || box.minLat < -90 || box.maxLat > 90) {
            throw InvalidRequests.field(FIELD, "is outside valid coordinates");
        }
        if (box.minLon >= box.maxLon || box.minLat >= box.maxLat) {
            throw InvalidRequests.field(FIELD, "minimum must be below maximum");
        }
        if (box.areaDeg2() > MAX_AREA_DEG2 + AREA_EPSILON) {
            throw InvalidRequests.field(FIELD, "area must be at most 0.1 by 0.1 degrees");
        }
        return box;
    }

    public double areaDeg2() {
        return (maxLon - minLon) * (maxLat - minLat);
    }

    /** Coordinates are user input but not personal; still keep them out of logs (AGENTS.md section 6). */
    @Override
    public String toString() {
        return "BoundingBox[redacted]";
    }
}
