package com.mapmyroad.backend.service.routing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Result of one route request.
 *
 * @param coverage coverage of the recommended candidate: that is the route the app presents first and attaches the
 *     "limited data" notice to (UI_UX_GUIDELINES.md 4.2); each candidate also carries its own coverage
 */
public record RouteCandidates(UUID requestId, List<RankedCandidate> candidates, Coverage coverage) {

    public RouteCandidates {
        candidates = List.copyOf(candidates);
    }

    public Optional<RankedCandidate> candidate(String candidateId) {
        return candidates.stream().filter(c -> c.id().equals(candidateId)).findFirst();
    }
}
