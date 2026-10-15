package com.mapmyroad.backend.repo.routing;

import com.mapmyroad.backend.integration.polyline.LatLon;
import java.math.BigDecimal;
import java.util.List;

/** Builds WKT for route lines. The result holds coordinates: bind it as a parameter, never log it. */
public final class RouteWkt {

    private RouteWkt() {}

    /**
     * {@code LINESTRING(lon lat, ...)}: WKT and PostGIS use longitude first. Plain decimal notation, because
     * {@link Double#toString()} switches to exponents for small magnitudes, which WKT parsers reject.
     *
     * @throws IllegalArgumentException with fewer than two points
     */
    public static String lineString(List<LatLon> points) {
        if (points.size() < 2) {
            throw new IllegalArgumentException("A line needs at least two points");
        }
        StringBuilder wkt = new StringBuilder(points.size() * 24).append("LINESTRING(");
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) {
                wkt.append(',');
            }
            LatLon point = points.get(i);
            wkt.append(plain(point.lon())).append(' ').append(plain(point.lat()));
        }
        return wkt.append(')').toString();
    }

    private static String plain(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Coordinate is not finite");
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
