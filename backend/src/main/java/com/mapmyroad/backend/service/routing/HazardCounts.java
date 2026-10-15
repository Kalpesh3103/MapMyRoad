package com.mapmyroad.backend.service.routing;

/** Hazards counted in a candidate's comfort penalty, by severity. */
public record HazardCounts(int severe, int moderate, int minor) {

    public static final HazardCounts NONE = new HazardCounts(0, 0, 0);
}
