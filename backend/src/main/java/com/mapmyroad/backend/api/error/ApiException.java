package com.mapmyroad.backend.api.error;

import java.util.Map;

/**
 * An expected failure that maps to an RFC 9457 problem response with a stable {@link ErrorCode}.
 * Never put coordinates, tokens or device IDs into {@code detail} (AGENTS.md section 6).
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> properties;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, Map.of());
    }

    /** {@code properties} become extra problem members, for example {@code retryAfterSec} or {@code errors}. */
    public ApiException(ErrorCode code, String detail, Map<String, Object> properties) {
        super(detail);
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
