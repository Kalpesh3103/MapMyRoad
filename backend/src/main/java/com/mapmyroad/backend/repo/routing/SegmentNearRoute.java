package com.mapmyroad.backend.repo.routing;

/**
 * A road segment statistic along a candidate route.
 *
 * @param score road quality 0 to 100, higher is smoother
 * @param sampleCount observations behind the score
 * @param lengthM length of the segment inside the route corridor, in meters
 */
public record SegmentNearRoute(int score, int sampleCount, double lengthM) {}
