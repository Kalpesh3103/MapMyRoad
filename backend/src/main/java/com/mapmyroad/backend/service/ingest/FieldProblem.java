package com.mapmyroad.backend.service.ingest;

/** One request-level problem, reported in the {@code errors} member of a 400 response (API_SPEC.md section 3). */
public record FieldProblem(String field, String message) {}
