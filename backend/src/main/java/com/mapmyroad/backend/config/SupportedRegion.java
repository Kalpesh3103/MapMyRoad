package com.mapmyroad.backend.config;

/** Axis-aligned bounding box of the supported region (API_SPEC.md sections 1 and 5.1). */
public record SupportedRegion(double minLon, double minLat, double maxLon, double maxLat) {

    public SupportedRegion {
        if (minLon >= maxLon || minLat >= maxLat || minLon < -180 || maxLon > 180 || minLat < -90 || maxLat > 90) {
            throw new IllegalArgumentException("Invalid supported region bounding box");
        }
    }

    /** Parses {@code minLon,minLat,maxLon,maxLat}. */
    public static SupportedRegion parse(String bbox) {
        String[] parts = bbox.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Supported region must be minLon,minLat,maxLon,maxLat");
        }
        try {
            return new SupportedRegion(
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()),
                    Double.parseDouble(parts[3].trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Supported region must contain four numbers", e);
        }
    }

    public boolean contains(double lat, double lon) {
        return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
    }

    /** {@code [minLon, minLat, maxLon, maxLat]} as in the config response. */
    public double[] toArray() {
        return new double[] {minLon, minLat, maxLon, maxLat};
    }
}
