package com.mapmyroad.backend.integration.valhalla;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a Valhalla {@code trace_attributes} response into one {@link MatchedPoint} per input point.
 *
 * <p>Expected shape (only the attributes requested by {@link ValhallaHttpMapMatcher}):
 * <pre>
 * { "edges": [ { "way_id": 123 }, ... ],
 *   "matched_points": [ { "lat": .., "lon": .., "type": "matched|interpolated|unmatched",
 *                         "edge_index": 0, "distance_from_trace_point": 1.5 }, ... ] }
 * </pre>
 * {@code matched_points} has the same length and order as the request shape. Interpolated points lie on an edge
 * too, so they count as matched. Unmatched points (or points whose {@code edge_index} does not resolve to an edge
 * with a {@code way_id}; Valhalla has used a sentinel index for "no edge" in some versions) become unmatched
 * results that keep the input coordinates.
 */
final class TraceAttributesResponseParser {

    private TraceAttributesResponseParser() {
    }

    /** Thrown when the body is not the expected JSON; the caller decides how to treat it. */
    static final class MalformedResponseException extends RuntimeException {
        MalformedResponseException(String message) {
            super(message);
        }
    }

    static List<MatchedPoint> parse(String body, List<TracePoint> input) {
        JsonNode root;
        try {
            root = JsonMapper.shared().readTree(body == null ? "" : body);
        } catch (JacksonException e) {
            throw new MalformedResponseException("trace_attributes returned malformed JSON");
        }
        if (root == null || !root.isObject()) {
            throw new MalformedResponseException("trace_attributes returned no JSON object");
        }
        JsonNode matchedPoints = root.path("matched_points");
        if (!matchedPoints.isArray() || matchedPoints.size() != input.size()) {
            throw new MalformedResponseException("trace_attributes matched_points missing or of unexpected length");
        }
        List<Long> edgeWayIds = edgeWayIds(root.path("edges"));
        List<MatchedPoint> result = new ArrayList<>(input.size());
        for (int i = 0; i < input.size(); i++) {
            result.add(toMatchedPoint(matchedPoints.get(i), edgeWayIds, input.get(i)));
        }
        return result;
    }

    /** All points unmatched, keeping the input coordinates. */
    static List<MatchedPoint> allUnmatched(List<TracePoint> input) {
        List<MatchedPoint> result = new ArrayList<>(input.size());
        for (TracePoint point : input) {
            result.add(unmatched(point));
        }
        return result;
    }

    private static List<Long> edgeWayIds(JsonNode edges) {
        List<Long> wayIds = new ArrayList<>();
        if (!edges.isArray()) {
            return wayIds;
        }
        for (JsonNode edge : edges) {
            JsonNode wayId = edge.path("way_id");
            // OSM way IDs are positive; 0 or a missing value means the edge has no OSM way (for example a ferry).
            wayIds.add(wayId.isIntegralNumber() && wayId.longValue() > 0 ? wayId.longValue() : null);
        }
        return wayIds;
    }

    private static MatchedPoint toMatchedPoint(JsonNode point, List<Long> edgeWayIds, TracePoint input) {
        JsonNode type = point.path("type");
        String typeValue = type.isString() ? type.stringValue() : "";
        if (!"matched".equals(typeValue) && !"interpolated".equals(typeValue)) {
            return unmatched(input);
        }
        JsonNode edgeIndex = point.path("edge_index");
        if (!edgeIndex.isIntegralNumber()) {
            return unmatched(input);
        }
        long index = edgeIndex.longValue();
        if (index < 0 || index >= edgeWayIds.size() || edgeWayIds.get((int) index) == null) {
            return unmatched(input);
        }
        JsonNode lat = point.path("lat");
        JsonNode lon = point.path("lon");
        if (!lat.isNumber() || !lon.isNumber()) {
            return unmatched(input);
        }
        JsonNode distance = point.path("distance_from_trace_point");
        Double distanceM = distance.isNumber() ? distance.doubleValue() : null;
        return new MatchedPoint(edgeWayIds.get((int) index), lat.doubleValue(), lon.doubleValue(), distanceM);
    }

    private static MatchedPoint unmatched(TracePoint input) {
        return new MatchedPoint(null, input.lat(), input.lon(), null);
    }
}
