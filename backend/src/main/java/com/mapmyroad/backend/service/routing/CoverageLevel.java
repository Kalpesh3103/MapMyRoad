package com.mapmyroad.backend.service.routing;

/** How much of a route has road-quality data (API_SPEC.md 5.8). */
public enum CoverageLevel {
    NONE("none"),
    PARTIAL("partial"),
    GOOD("good");

    private final String wireName;

    CoverageLevel(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
