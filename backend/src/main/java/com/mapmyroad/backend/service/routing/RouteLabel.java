package com.mapmyroad.backend.service.routing;

/** Candidate labels (API_SPEC.md 1.1 {@code routeLabel}). */
public enum RouteLabel {
    FASTEST("fastest"),
    SMOOTHEST("smoothest"),
    BALANCED("balanced");

    private final String wireName;

    RouteLabel(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
