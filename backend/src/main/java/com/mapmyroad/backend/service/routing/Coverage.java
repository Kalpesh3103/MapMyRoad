package com.mapmyroad.backend.service.routing;

/**
 * Data coverage of one route.
 *
 * @param fractionWithData share of the route length covered by segments with enough samples, 0 to 1
 */
public record Coverage(CoverageLevel level, double fractionWithData) {}
