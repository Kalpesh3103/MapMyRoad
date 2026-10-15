package com.mapmyroad.backend.integration.valhalla;

/**
 * Map-matching result for the input point with the same index.
 *
 * @param wayId OSM way ID of the matched edge, or null when the point could not be matched
 * @param lat snapped latitude (the input latitude when unmatched)
 * @param lon snapped longitude (the input longitude when unmatched)
 * @param distanceFromTraceM distance between the input point and the snapped point, null when unmatched
 */
public record MatchedPoint(Long wayId, double lat, double lon, Double distanceFromTraceM) {

    public boolean matched() {
        return wayId != null;
    }

    @Override
    public String toString() {
        return "MatchedPoint[matched=" + matched() + "]";
    }
}
