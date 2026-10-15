package com.mapmyroad.backend.service.hazards;

import java.util.UUID;

/**
 * Hazard state after a vote. Confidence is the value stored at the time of the vote: the aggregation job
 * recomputes it asynchronously from the recompute queue, so the effect of this vote shows up a little later.
 */
public record FeedbackResult(UUID id, double confidence, HazardStatus status) {}
