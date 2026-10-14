package com.mapmyroad.backend.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every request an ID (API_SPEC.md section 1): the client's {@code X-Request-Id} when it is well formed,
 * otherwise a fresh UUID. The ID goes into the SLF4J MDC for structured logs and is echoed in the response.
 * Runs first so every later filter and log line can use it.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    /** Strict pattern so clients cannot inject log content or oversized values through the header. */
    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String resolve(String candidate) {
        return candidate != null && VALID_ID.matcher(candidate).matches() ? candidate : UUID.randomUUID().toString();
    }
}
