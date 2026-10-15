package com.mapmyroad.backend.integration.valhalla;

import java.util.List;

/**
 * One route returned by Valhalla (the main route or an alternate).
 *
 * @param encodedPolyline6 the route shape as an encoded polyline with precision 6 (TECH_STACK.md section 5)
 * @param wayIds OSM way IDs along the route when available (empty if the router did not return them)
 */
public record RouteOption(String encodedPolyline6, double distanceM, double durationSec, List<Long> wayIds) {

    public RouteOption {
        wayIds = wayIds == null ? List.of() : List.copyOf(wayIds);
    }

    @Override
    public String toString() {
        return "RouteOption[distanceM=" + Math.round(distanceM) + ", durationSec=" + Math.round(durationSec) + "]";
    }
}
