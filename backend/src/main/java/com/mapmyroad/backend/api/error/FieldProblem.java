package com.mapmyroad.backend.api.error;

/**
 * One entry of the {@code errors} member of an INVALID_REQUEST problem (API_SPEC.md section 3).
 * {@code message} never echoes the submitted value, which may be a coordinate or a token.
 */
public record FieldProblem(String field, String message) {}
