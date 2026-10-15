package com.mapmyroad.backend.api.hazards;

import com.mapmyroad.backend.service.hazards.FeedbackResult;
import java.util.UUID;

/**
 * Response of {@code POST /v1/hazards/{id}/feedback} (API_SPEC.md 5.6). {@code confidence} is the stored value;
 * the recompute that includes this vote runs asynchronously.
 */
public record FeedbackResponse(UUID id, double confidence, String status) {

    static FeedbackResponse from(FeedbackResult result) {
        return new FeedbackResponse(result.id(), result.confidence(), result.status().wireName());
    }
}
