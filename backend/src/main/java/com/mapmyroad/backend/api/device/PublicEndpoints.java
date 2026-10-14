package com.mapmyroad.backend.api.device;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** The endpoints that need no access token (API_SPEC.md section 2), shared by the filter chain and token resolver. */
public final class PublicEndpoints {

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    /** Config, tiles, challenge, registration and refresh (the refresh token is its own credential). */
    public static final RequestMatcher MATCHER = new OrRequestMatcher(
            PATHS.matcher(HttpMethod.GET, "/v1/config"),
            PATHS.matcher(HttpMethod.GET, "/v1/tiles/**"),
            PATHS.matcher(HttpMethod.POST, "/v1/devices/challenge"),
            PATHS.matcher(HttpMethod.POST, "/v1/devices"),
            PATHS.matcher(HttpMethod.POST, "/v1/devices/token"));

    private PublicEndpoints() {}

    /**
     * Ignores the Authorization header on public endpoints. Otherwise an app that still sends its expired access
     * token while calling {@code POST /v1/devices/token} would get 401 TOKEN_EXPIRED from the bearer filter and
     * loop instead of refreshing.
     */
    public static BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return (HttpServletRequest request) -> MATCHER.matches(request) ? null : delegate.resolve(request);
    }
}
