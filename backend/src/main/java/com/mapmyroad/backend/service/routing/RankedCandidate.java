package com.mapmyroad.backend.service.routing;

import java.util.List;

/**
 * A scored and labelled candidate. {@code geometry} is the route as polyline precision 6; it implies origin and
 * destination, so it is never logged.
 *
 * @param id {@code c1}, {@code c2}, ... in Valhalla's order (main route first)
 * @param labels in the order fastest, smoothest, balanced; may be empty
 */
public record RankedCandidate(
        String id,
        String geometry,
        double distanceM,
        double durationSec,
        CandidateScore score,
        List<RouteLabel> labels,
        boolean recommended) {

    public RankedCandidate {
        labels = List.copyOf(labels);
    }

    @Override
    public String toString() {
        return "RankedCandidate[id=" + id + ", comfort=" + score.comfortScore() + ", labels=" + labels
                + ", recommended=" + recommended + "]";
    }
}
