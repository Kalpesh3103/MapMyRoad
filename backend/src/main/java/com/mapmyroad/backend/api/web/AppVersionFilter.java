package com.mapmyroad.backend.api.web;

import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.api.error.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces {@code X-App-Version} on every {@code /v1/**} request (API_SPEC.md sections 1 and 7): missing or malformed
 * gives 400 INVALID_REQUEST, older than {@code mmr.min-supported-app-version} gives 426 APP_UPDATE_REQUIRED, which the
 * app answers with its update screen. Runs before authentication so outdated apps get 426 rather than 401.
 */
public class AppVersionFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-App-Version";

    private final AppVersion minSupported;

    public AppVersionFilter(String minSupportedAppVersion) {
        this.minSupported = AppVersion.parseRequired(minSupportedAppVersion);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ApiPaths.isApi(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            ProblemResponses.write(response, ErrorCode.INVALID_REQUEST, "Missing X-App-Version header.");
            return;
        }
        Optional<AppVersion> version = AppVersion.parse(header);
        if (version.isEmpty()) {
            ProblemResponses.write(response, ErrorCode.INVALID_REQUEST, "Malformed X-App-Version header.");
            return;
        }
        if (version.get().isOlderThan(minSupported)) {
            ProblemResponses.write(
                    response,
                    ErrorCode.APP_UPDATE_REQUIRED,
                    ErrorCode.APP_UPDATE_REQUIRED.status(),
                    "This app version is no longer supported.",
                    Map.of("minSupportedAppVersion", minSupported.toString()));
            return;
        }
        chain.doFilter(request, response);
    }
}
