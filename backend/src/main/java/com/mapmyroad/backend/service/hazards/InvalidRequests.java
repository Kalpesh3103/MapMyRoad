package com.mapmyroad.backend.service.hazards;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.api.error.FieldProblem;
import java.util.List;
import java.util.Map;

/** 400 INVALID_REQUEST with the same {@code errors} shape as Bean Validation failures (API_SPEC.md 3). */
final class InvalidRequests {

    static final String DETAIL = "One or more fields are invalid.";

    private InvalidRequests() {}

    /** Messages never echo the submitted value, which may be a coordinate. */
    static ApiException field(String field, String message) {
        return new ApiException(
                ErrorCode.INVALID_REQUEST, DETAIL, Map.of("errors", List.of(new FieldProblem(field, message))));
    }
}
