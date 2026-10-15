package com.mapmyroad.backend.api.hazards;

import com.mapmyroad.backend.service.hazards.FeedbackAction;
import com.mapmyroad.backend.service.hazards.FeedbackCommand;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;

/**
 * Body of {@code POST /v1/hazards/{id}/feedback} (API_SPEC.md 5.6). Boxed numbers so a missing field fails
 * {@code @NotNull}. The location is for the proximity check only; {@code toString()} omits it.
 */
public record FeedbackRequest(
        @NotNull @Pattern(regexp = FeedbackAction.WIRE_PATTERN) String action,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double lon,
        @NotNull Instant observedAt) {

    FeedbackCommand toCommand(UUID hazardId, UUID deviceId) {
        FeedbackAction parsed = FeedbackAction.fromWireName(action)
                .orElseThrow(() -> new IllegalStateException("action passed validation but is unknown"));
        return new FeedbackCommand(hazardId, deviceId, parsed, lat, lon, observedAt);
    }

    @Override
    public String toString() {
        return "FeedbackRequest[action=" + action + "]";
    }
}
