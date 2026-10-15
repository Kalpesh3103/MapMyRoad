package com.mapmyroad.backend.service.hazards;

import java.time.Instant;
import java.util.UUID;

/** One confirm or dismiss vote. The location is used only for the proximity check and is never stored. */
public record FeedbackCommand(
        UUID hazardId, UUID deviceId, FeedbackAction action, double lat, double lon, Instant observedAt) {

    @Override
    public String toString() {
        return "FeedbackCommand[hazardId=" + hazardId + ", action=" + action + "]";
    }
}
