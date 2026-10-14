package com.mapmyroad.backend.api.device;

import com.mapmyroad.backend.api.error.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes RFC 9457 problem JSON from inside the security filter chain, where the MVC exception handler does not run.
 * Same shape as the API errors (API_SPEC.md section 3): {@code type, title, status, detail, code}. Kept here rather
 * than shared with the web layer so the security setup has no compile-order dependency on it.
 */
public final class SecurityProblemWriter {

    public static final String PROBLEM_JSON = "application/problem+json";

    private SecurityProblemWriter() {}

    public static void write(HttpServletResponse response, ErrorCode code, String detail) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", code.typeUri());
        body.put("title", code.title());
        body.put("status", code.status());
        body.put("detail", detail);
        body.put("code", code.name());
        response.setStatus(code.status());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(PROBLEM_JSON);
        if (code.status() == HttpServletResponse.SC_UNAUTHORIZED) {
            // RFC 6750 section 3; the error detail lives in the body, not in this header.
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        response.getOutputStream().write(JsonMapper.shared().writeValueAsBytes(body));
    }
}
