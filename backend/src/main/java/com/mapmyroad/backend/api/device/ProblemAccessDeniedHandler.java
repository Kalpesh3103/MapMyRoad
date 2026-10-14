package com.mapmyroad.backend.api.device;

import com.mapmyroad.backend.api.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Authenticated but not allowed. With one role, the only rule that denies a valid device is the catch-all
 * {@code denyAll} for paths outside the API, so this answers 404 NOT_FOUND rather than revealing that something
 * exists there (API_SPEC.md section 3 has no generic 403 code).
 */
public final class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        SecurityProblemWriter.write(response, ErrorCode.NOT_FOUND, "Not found.");
    }
}
