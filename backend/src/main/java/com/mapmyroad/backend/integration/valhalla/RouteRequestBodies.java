package com.mapmyroad.backend.integration.valhalla;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON bodies for Valhalla {@code /route} and {@code /trace_attributes}. Built from typed values only (numbers and
 * our own enum strings), never from user text. The bodies contain coordinates and are never logged.
 */
final class RouteRequestBodies {

    private RouteRequestBodies() {}

    /**
     * {@code directions_type: none} skips maneuver generation; the trip still carries the summary and leg shapes,
     * which is all candidates need. {@code units: kilometers} fixes the unit of {@code summary.length}.
     */
    static String route(
            double originLat,
            double originLon,
            double destinationLat,
            double destinationLon,
            ValhallaCosting costing,
            int alternates) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locations", List.of(location(originLat, originLon), location(destinationLat, destinationLon)));
        body.put("costing", costing.value());
        body.put("alternates", alternates);
        body.put("directions_options", Map.of("units", "kilometers"));
        body.put("directions_type", "none");
        return JsonMapper.shared().writeValueAsString(body);
    }

    /**
     * {@code edge_walk} follows the graph edges exactly, which only works for shapes Valhalla produced itself (our
     * route shapes). Only {@code edge.way_id} is requested to keep the response small.
     */
    static String traceAttributes(String encodedPolyline6, ValhallaCosting costing) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("encoded_polyline", encodedPolyline6);
        body.put("shape_match", "edge_walk");
        body.put("costing", costing.value());
        body.put("filters", Map.of("attributes", List.of("edge.way_id"), "action", "include"));
        return JsonMapper.shared().writeValueAsString(body);
    }

    private static Map<String, Object> location(double lat, double lon) {
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("lat", lat);
        location.put("lon", lon);
        return location;
    }
}
