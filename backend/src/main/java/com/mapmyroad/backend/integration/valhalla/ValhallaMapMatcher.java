package com.mapmyroad.backend.integration.valhalla;

import java.util.List;

/**
 * Map-matching with Valhalla {@code trace_attributes} (M4-9, DATABASE_SCHEMA.md A6).
 * Implemented by the matching job's integration code. Throws {@link ValhallaUnavailableException} when the service
 * cannot be reached, so callers can retry later.
 */
public interface ValhallaMapMatcher {

    /**
     * Matches a time-ordered trace. The result has exactly one entry per input point, in the same order.
     *
     * @param points at least 2 points of one trip
     */
    List<MatchedPoint> match(List<TracePoint> points, ValhallaCosting costing);
}
