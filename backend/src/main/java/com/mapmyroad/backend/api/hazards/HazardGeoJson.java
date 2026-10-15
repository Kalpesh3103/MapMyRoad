package com.mapmyroad.backend.api.hazards;

import com.mapmyroad.backend.service.hazards.PublishedHazard;
import java.util.List;
import java.util.UUID;

/**
 * GeoJSON shapes of the hazards API (API_SPEC.md 5.5, RFC 7946). Coordinates are {@code [lon, lat]}.
 * Built only from {@link PublishedHazard}, which already carries publication precision and no identifiers other
 * than the hazard ID.
 */
public final class HazardGeoJson {

    private HazardGeoJson() {}

    public record FeatureCollection(String type, List<Feature> features) {

        public static FeatureCollection of(List<PublishedHazard> hazards) {
            return new FeatureCollection("FeatureCollection", hazards.stream().map(Feature::of).toList());
        }
    }

    public record Feature(String type, Point geometry, Properties properties) {

        public static Feature of(PublishedHazard hazard) {
            return new Feature(
                    "Feature",
                    new Point("Point", List.of(hazard.lon(), hazard.lat())),
                    new Properties(
                            hazard.id(),
                            hazard.severity(),
                            hazard.confidence(),
                            hazard.status().wireName(),
                            hazard.firstSeenDay().toString(),
                            hazard.lastSeenDay().toString(),
                            hazard.confirmCount(),
                            hazard.dismissCount()));
        }
    }

    public record Point(String type, List<Double> coordinates) {}

    /** {@code firstSeen} and {@code lastSeen} are ISO-8601 instants truncated to midnight UTC (SECURITY.md 6.4). */
    public record Properties(
            UUID id,
            int severity,
            double confidence,
            String status,
            String firstSeen,
            String lastSeen,
            int confirmCount,
            int dismissCount) {}
}
