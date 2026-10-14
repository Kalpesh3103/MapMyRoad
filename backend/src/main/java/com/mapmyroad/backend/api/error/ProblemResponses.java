package com.mapmyroad.backend.api.error;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds RFC 9457 problem responses with the stable {@code code} member (API_SPEC.md section 3).
 *
 * <p>Used by {@link ApiExceptionHandler} for errors raised inside Spring MVC, and by servlet filters, which run
 * before MVC and therefore cannot rely on {@code @RestControllerAdvice}. Both paths produce the same JSON shape.
 */
public final class ProblemResponses {

    /** Problem member carrying the retry delay for RATE_LIMITED, mirrored into the {@code Retry-After} header. */
    public static final String RETRY_AFTER_SEC = "retryAfterSec";

    private static final JsonMapper JSON = JsonMapper.shared();

    private ProblemResponses() {}

    /** Problem with the code's own HTTP status. */
    public static ProblemDetail problem(ErrorCode code, String detail, Map<String, Object> properties) {
        return problem(code, HttpStatusCode.valueOf(code.status()), detail, properties);
    }

    /**
     * Problem with an explicit status, for the few framework errors (405, 415, ...) that have no code of their own
     * in API_SPEC.md and reuse INVALID_REQUEST.
     */
    public static ProblemDetail problem(
            ErrorCode code, HttpStatusCode status, String detail, Map<String, Object> properties) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(code.typeUri()));
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        properties.forEach(problem::setProperty);
        return problem;
    }

    /** Value for the {@code Retry-After} header, or {@code null} when the problem carries no retry delay. */
    public static String retryAfterHeader(Map<String, Object> properties) {
        Object value = properties.get(RETRY_AFTER_SEC);
        return value instanceof Number number ? Long.toString(Math.max(1, number.longValue())) : null;
    }

    /** Writes a problem straight to the servlet response. For filters only. */
    public static void write(HttpServletResponse response, ErrorCode code, String detail) throws IOException {
        write(response, code, code.status(), detail, Map.of());
    }

    /** Writes a problem straight to the servlet response. For filters only. */
    public static void write(
            HttpServletResponse response, ErrorCode code, int status, String detail, Map<String, Object> properties)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", code.typeUri());
        body.put("title", code.title());
        body.put("status", status);
        body.put("detail", detail);
        body.put("code", code.name());
        body.putAll(properties);
        byte[] bytes = JSON.writeValueAsBytes(body);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(bytes.length);
        String retryAfter = retryAfterHeader(properties);
        if (retryAfter != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        response.getOutputStream().write(bytes);
        response.flushBuffer();
    }
}
