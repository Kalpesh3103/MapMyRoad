package com.mapmyroad.backend.service.hazards;

import java.util.Optional;

/** {@code hazardStatus} from API_SPEC.md 1.1, stored as the same text in {@code hazard.status}. */
public enum HazardStatus {
    ACTIVE("active"),
    STALE("stale"),
    FIXED("fixed");

    private final String wireName;

    HazardStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<HazardStatus> fromWireName(String wireName) {
        for (HazardStatus status : values()) {
            if (status.wireName.equals(wireName)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
