package com.mapmyroad.backend.api.config;

import java.util.List;

/** Body of {@code GET /v1/config}, field for field as in API_SPEC.md section 5.1. */
public record ConfigResponse(
        String minSupportedAppVersion,
        SupportedRegionDto supportedRegion,
        String tileJsonUrl,
        String baseMapStyleUrl,
        int retentionDays,
        Limits limits,
        Features features) {

    /** {@code bbox} is {@code [minLon, minLat, maxLon, maxLat]}. */
    public record SupportedRegionDto(List<Double> bbox) {}

    public record Limits(int maxEventsPerRequest, int maxWindowsPerRequest) {}

    public record Features(boolean navigation, boolean reports, boolean areas) {}
}
