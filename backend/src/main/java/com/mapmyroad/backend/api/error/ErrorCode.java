package com.mapmyroad.backend.api.error;

/**
 * Stable error codes from API_SPEC.md section 3. The app branches on {@link #name()}, so names never change.
 * Each code maps to one HTTP status and a problem type slug.
 */
public enum ErrorCode {
    INVALID_REQUEST(400, "invalid-request", "Invalid request"),
    UNAUTHORIZED(401, "unauthorized", "Unauthorized"),
    TOKEN_EXPIRED(401, "token-expired", "Access token expired"),
    INTEGRITY_FAILED(403, "integrity-failed", "Integrity check failed"),
    NOT_FOUND(404, "not-found", "Not found"),
    PAYLOAD_TOO_LARGE(413, "payload-too-large", "Payload too large"),
    UNSUPPORTED_REGION(422, "unsupported-region", "Unsupported region"),
    TOO_FAR(422, "too-far", "Too far from the hazard"),
    NO_ROUTE(422, "no-route", "No route found"),
    APP_UPDATE_REQUIRED(426, "app-update-required", "App update required"),
    RATE_LIMITED(429, "rate-limited", "Too many requests"),
    INTERNAL(500, "internal", "Internal error"),
    UPSTREAM_UNAVAILABLE(503, "upstream-unavailable", "Upstream service unavailable");

    /** Base of the problem `type` URI. Placeholder domain until the API domain is chosen (PROGRESS.md section 7). */
    public static final String TYPE_BASE = "https://api.example.com/problems/";

    private final int status;
    private final String slug;
    private final String title;

    ErrorCode(int status, String slug, String title) {
        this.status = status;
        this.slug = slug;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public String typeUri() {
        return TYPE_BASE + slug;
    }

    public String title() {
        return title;
    }
}
