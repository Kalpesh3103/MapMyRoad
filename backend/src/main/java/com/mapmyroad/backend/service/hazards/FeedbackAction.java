package com.mapmyroad.backend.service.hazards;

import java.util.Optional;

/** {@code feedbackAction} from API_SPEC.md 1.1, stored as the same text in {@code hazard_feedback.action}. */
public enum FeedbackAction {
    CONFIRM("confirm"),
    DISMISS("dismiss");

    /** Regex of all wire names, used by request validation. Keep in sync with the constants. */
    public static final String WIRE_PATTERN = "confirm|dismiss";

    private final String wireName;

    FeedbackAction(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<FeedbackAction> fromWireName(String wireName) {
        for (FeedbackAction action : values()) {
            if (action.wireName.equals(wireName)) {
                return Optional.of(action);
            }
        }
        return Optional.empty();
    }
}
