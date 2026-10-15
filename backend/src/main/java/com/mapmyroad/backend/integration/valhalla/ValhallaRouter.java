package com.mapmyroad.backend.integration.valhalla;

import java.util.List;

/**
 * Route requests with alternates (M4-14, ARCHITECTURE.md section 9.1). Implemented by the routing integration code.
 */
public interface ValhallaRouter {

    /**
     * Returns the main route first, then up to {@code maxAlternates} alternates. Empty when no route exists.
     * Throws {@link ValhallaUnavailableException} when the service cannot be reached.
     */
    List<RouteOption> route(
            double originLat,
            double originLon,
            double destinationLat,
            double destinationLon,
            ValhallaCosting costing,
            int maxAlternates);
}
