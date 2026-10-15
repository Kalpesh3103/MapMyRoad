package com.mapmyroad.backend.repo.aggregation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Inputs of the confidence formula for one hazard.
 *
 * @param trustScores trust score of each distinct reporting device (devices with a cleared ID are not included)
 */
public record ConfidenceInput(
        UUID hazardId, Instant lastSeenAt, int confirmCount, int dismissCount, List<Double> trustScores) {

    public ConfidenceInput {
        trustScores = List.copyOf(trustScores);
    }
}
