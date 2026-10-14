package com.mapmyroad.backend.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Baseline response headers (SECURITY.md section 9): {@code X-Content-Type-Options: nosniff},
 * {@code Referrer-Policy: no-referrer} everywhere, and {@code Cache-Control: no-store} for {@code /v1/**} unless the
 * controller sets its own (for example {@code GET /v1/config} and tiles use {@code public, max-age=300}).
 * HSTS is added by Caddy (DEPLOYMENT.md) and CORS stays disabled.
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String NO_STORE = "no-store";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (ApiPaths.isApi(request)) {
            chain.doFilter(request, new DefaultCacheControlResponse(response));
        } else {
            chain.doFilter(request, response);
        }
    }

    /**
     * Starts with {@code Cache-Control: no-store} and lets the first explicit Cache-Control replace it. Without the
     * wrapper, Spring MVC adds the controller's value next to the default instead of replacing it.
     */
    static final class DefaultCacheControlResponse extends HttpServletResponseWrapper {

        private boolean defaultActive = true;

        DefaultCacheControlResponse(HttpServletResponse response) {
            super(response);
            response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
        }

        @Override
        public void setHeader(String name, String value) {
            if (HttpHeaders.CACHE_CONTROL.equalsIgnoreCase(name)) {
                defaultActive = false;
            }
            super.setHeader(name, value);
        }

        @Override
        public void addHeader(String name, String value) {
            if (HttpHeaders.CACHE_CONTROL.equalsIgnoreCase(name) && defaultActive) {
                defaultActive = false;
                super.setHeader(name, value);
                return;
            }
            super.addHeader(name, value);
        }
    }
}
