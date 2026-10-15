package com.mapmyroad.backend.repo.aggregation;

import java.time.Instant;
import java.util.UUID;

/** A matched window waiting for aggregation. {@code wayId} and {@code bucket} are null when it has no way. */
public record WindowSample(UUID id, Long wayId, Integer bucket, double roughness, Instant observedAt) {
}
