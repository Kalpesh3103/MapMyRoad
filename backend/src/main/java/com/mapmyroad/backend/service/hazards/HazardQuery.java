package com.mapmyroad.backend.service.hazards;

import com.mapmyroad.backend.api.error.ApiException;

/** Validated parameters of {@code GET /v1/hazards} (API_SPEC.md 5.5). */
public record HazardQuery(BoundingBox bbox, int minSeverity, HazardStatus status, int limit) {

    public static final int DEFAULT_LIMIT = 200;
    public static final int MAX_LIMIT = 500;

    /**
     * Validates raw query parameters. {@code minSeverity}, {@code status} and {@code limit} may be null for the
     * defaults 1, {@code active} and 200.
     *
     * @throws ApiException 400 INVALID_REQUEST naming the first bad parameter
     */
    public static HazardQuery of(String bbox, Integer minSeverity, String status, Integer limit) {
        BoundingBox box = BoundingBox.parse(bbox);
        int severity = minSeverity == null ? 1 : minSeverity;
        if (severity < 1 || severity > 3) {
            throw InvalidRequests.field("minSeverity", "must be between 1 and 3");
        }
        HazardStatus parsedStatus = status == null
                ? HazardStatus.ACTIVE
                : HazardStatus.fromWireName(status)
                        .orElseThrow(() -> InvalidRequests.field("status", "must be active, stale or fixed"));
        int parsedLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (parsedLimit < 1 || parsedLimit > MAX_LIMIT) {
            throw InvalidRequests.field("limit", "must be between 1 and " + MAX_LIMIT);
        }
        return new HazardQuery(box, severity, parsedStatus, parsedLimit);
    }
}
