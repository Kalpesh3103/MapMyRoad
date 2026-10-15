package com.mapmyroad.backend.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Publication thresholds for aggregated data (API_SPEC.md section 5.4, SECURITY.md T2 and section 6.4). Shared by
 * tiles and the hazards API through {@code repo.hazards.VisibilityRules}. Initial values, not measured facts.
 * Lowering any threshold weakens a privacy control and needs an ADR (AGENTS.md section 6).
 *
 * @param minSamples road segments need at least this many samples
 * @param minDevices distinct devices needed on ordinary roads (segments and hazards)
 * @param minDevicesLowTraffic distinct devices needed on {@code lowTrafficHighways} and on unknown ways (T2)
 * @param lowTrafficHighways OSM {@code highway} values that get the stricter device threshold
 * @param excludedAccessValues OSM {@code access} values whose ways are never published (SECURITY.md 6.4)
 * @param hazardMinConfidence hazards below this confidence are not published
 */
@ConfigurationProperties(prefix = "mmr.visibility")
public record VisibilityProperties(
        Integer minSamples,
        Integer minDevices,
        Integer minDevicesLowTraffic,
        List<String> lowTrafficHighways,
        List<String> excludedAccessValues,
        Double hazardMinConfidence) {

    public VisibilityProperties {
        minSamples = minSamples == null ? 3 : minSamples;
        minDevices = minDevices == null ? 2 : minDevices;
        minDevicesLowTraffic = minDevicesLowTraffic == null ? 3 : minDevicesLowTraffic;
        lowTrafficHighways = lowTrafficHighways == null
                ? List.of("residential", "service", "living_street", "unclassified")
                : List.copyOf(lowTrafficHighways);
        excludedAccessValues =
                excludedAccessValues == null ? List.of("private", "no") : List.copyOf(excludedAccessValues);
        hazardMinConfidence = hazardMinConfidence == null ? 0.3 : hazardMinConfidence;
        if (minSamples < 1 || minDevices < 1 || minDevicesLowTraffic < minDevices) {
            throw new IllegalArgumentException("mmr.visibility thresholds are inconsistent");
        }
        if (hazardMinConfidence < 0 || hazardMinConfidence > 1) {
            throw new IllegalArgumentException("mmr.visibility.hazard-min-confidence must be within 0..1");
        }
        requireTagValues(lowTrafficHighways, "low-traffic-highways");
        requireTagValues(excludedAccessValues, "excluded-access-values");
    }

    /** Values from SECURITY.md T2 and API_SPEC.md 5.4, for tests and code that runs without Spring. */
    public static VisibilityProperties defaults() {
        return new VisibilityProperties(null, null, null, null, null, null);
    }

    // The lists are bound to SQL as one comma-separated parameter (see VisibilityRules), so a comma inside a
    // value would split it. OSM tag values never contain commas in practice.
    private static void requireTagValues(List<String> values, String name) {
        for (String value : values) {
            if (value == null || value.isBlank() || value.contains(",")) {
                throw new IllegalArgumentException("mmr.visibility." + name + " has an invalid entry");
            }
        }
    }
}
