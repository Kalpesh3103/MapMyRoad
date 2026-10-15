package com.mapmyroad.backend.service.routing;

import com.mapmyroad.backend.domain.ingest.VehicleType;
import com.mapmyroad.backend.integration.polyline.LatLon;

/**
 * A validated route request. Exists only for the duration of the request; never persisted or logged.
 *
 * @param preference 0 prefers the fastest route, 1 the smoothest
 */
public record RouteQuery(LatLon origin, LatLon destination, VehicleType vehicleType, double preference) {

    @Override
    public String toString() {
        return "RouteQuery[vehicleType=" + vehicleType + ", preference=" + preference + "]";
    }
}
