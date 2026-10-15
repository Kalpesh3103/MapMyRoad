package com.mapmyroad.backend.integration.valhalla;

import com.mapmyroad.backend.integration.polyline.LatLon;
import com.mapmyroad.backend.integration.polyline.Polyline;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses Valhalla {@code /route} responses (Valhalla API reference, "Turn-by-turn API"). Field names used:
 * {@code trip.summary.length} (kilometers, because the request sets {@code units: kilometers}),
 * {@code trip.summary.time} (seconds), {@code trip.legs[].shape} (polyline precision 6) and
 * {@code alternates[].trip} with the same structure. Error bodies carry {@code error_code} and {@code error}.
 */
final class RouteResponseParser {

    /**
     * Valhalla error codes that mean "no route between these points" rather than a fault: 442 no path found,
     * 170 locations in unconnected regions, 171 no suitable edges near a location, 172 exceeded breakage distance,
     * 154 path distance exceeds the service limit. Other 4xx codes indicate a malformed request (our bug).
     */
    static final Set<Integer> NO_ROUTE_CODES = Set.of(154, 170, 171, 172, 442);

    private static final double METERS_PER_KM = 1000.0;

    private RouteResponseParser() {}

    /** One parsed trip without way IDs. */
    record ParsedTrip(String encodedPolyline6, double distanceM, double durationSec) {

        @Override
        public String toString() {
            return "ParsedTrip[distanceM=" + Math.round(distanceM) + "]";
        }
    }

    /**
     * Main trip first, then alternates in Valhalla's order. Trips without a usable shape or summary are skipped.
     *
     * @throws ValhallaUnavailableException when the body is not JSON
     */
    static List<ParsedTrip> parse(String body) {
        JsonNode root = readTree(body);
        List<ParsedTrip> trips = new ArrayList<>();
        parseTrip(root.path("trip"), trips);
        JsonNode alternates = root.path("alternates");
        if (alternates.isArray()) {
            for (JsonNode alternate : alternates) {
                parseTrip(alternate.path("trip"), trips);
            }
        }
        return trips;
    }

    /** True when an error body carries one of {@link #NO_ROUTE_CODES}. */
    static boolean isNoRouteError(String errorBody) {
        Integer code = errorCode(errorBody);
        return code != null && NO_ROUTE_CODES.contains(code);
    }

    /** The Valhalla {@code error_code}, or null when the body has none. Safe to log (no coordinates). */
    static Integer errorCode(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return null;
        }
        try {
            JsonNode code = JsonMapper.shared().readTree(errorBody).path("error_code");
            return code.isIntegralNumber() ? code.asInt() : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    /** Way IDs of {@code trace_attributes} edges in route order, consecutive duplicates removed. */
    static List<Long> parseWayIds(String body) {
        JsonNode edges = readTree(body).path("edges");
        List<Long> wayIds = new ArrayList<>();
        if (!edges.isArray()) {
            return wayIds;
        }
        for (JsonNode edge : edges) {
            JsonNode wayId = edge.path("way_id");
            if (wayId.isIntegralNumber()) {
                long id = wayId.asLong();
                if (wayIds.isEmpty() || wayIds.get(wayIds.size() - 1) != id) {
                    wayIds.add(id);
                }
            }
        }
        return wayIds;
    }

    private static void parseTrip(JsonNode trip, List<ParsedTrip> out) {
        JsonNode summary = trip.path("summary");
        JsonNode length = summary.path("length");
        JsonNode time = summary.path("time");
        JsonNode legs = trip.path("legs");
        if (!length.isNumber() || !time.isNumber() || !legs.isArray() || legs.isEmpty()) {
            return;
        }
        List<String> shapes = new ArrayList<>();
        for (JsonNode leg : legs) {
            String shape = leg.path("shape").asString("");
            if (shape.isEmpty()) {
                return;
            }
            shapes.add(shape);
        }
        out.add(new ParsedTrip(joinLegs(shapes), length.asDouble() * METERS_PER_KM, time.asDouble()));
    }

    /** Concatenates leg shapes, dropping the duplicated junction point where one leg ends and the next starts. */
    static String joinLegs(List<String> shapes) {
        if (shapes.size() == 1) {
            return shapes.get(0);
        }
        List<LatLon> points = new ArrayList<>();
        for (String shape : shapes) {
            List<LatLon> leg = Polyline.decode6(shape);
            int start = !points.isEmpty() && !leg.isEmpty() && points.get(points.size() - 1).equals(leg.get(0)) ? 1 : 0;
            points.addAll(leg.subList(start, leg.size()));
        }
        return Polyline.encode6(points);
    }

    private static JsonNode readTree(String body) {
        try {
            return JsonMapper.shared().readTree(body == null ? "" : body);
        } catch (JacksonException e) {
            throw new ValhallaUnavailableException("Valhalla returned malformed JSON");
        }
    }
}
