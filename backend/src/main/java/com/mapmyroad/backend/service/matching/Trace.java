package com.mapmyroad.backend.service.matching;

import com.mapmyroad.backend.integration.valhalla.TracePoint;
import java.util.List;

/**
 * A time-ordered trace for one trip plus, for every event and window, the index of its point(s) in the trace.
 *
 * @param points trace points in time order
 * @param eventPoint {@code eventPoint[i]} is the trace index of event {@code i} (input order)
 * @param windowStartPoint trace index of the start point of window {@code i}
 * @param windowEndPoint trace index of the end point of window {@code i}
 */
public record Trace(List<TracePoint> points, int[] eventPoint, int[] windowStartPoint, int[] windowEndPoint) {

    @Override
    public String toString() {
        return "Trace[points=" + points.size() + "]";
    }
}
