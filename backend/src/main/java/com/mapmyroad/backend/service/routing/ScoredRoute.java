package com.mapmyroad.backend.service.routing;

/** A candidate before labelling. {@code geometry} is polyline precision 6. */
public record ScoredRoute(String id, String geometry, double distanceM, double durationSec, CandidateScore score) {

    @Override
    public String toString() {
        return "ScoredRoute[id=" + id + ", comfort=" + score.comfortScore() + "]";
    }
}
