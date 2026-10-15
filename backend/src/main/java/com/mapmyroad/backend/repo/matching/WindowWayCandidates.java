package com.mapmyroad.backend.repo.matching;

import java.util.UUID;

/**
 * Ways that the start and end point of a window were snapped to (either may be null). When they differ, the way
 * closest to the window midpoint wins.
 */
public record WindowWayCandidates(UUID windowId, Long startWayId, Long endWayId) {

    public boolean hasCandidate() {
        return startWayId != null || endWayId != null;
    }
}
