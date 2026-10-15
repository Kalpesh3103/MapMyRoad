package com.mapmyroad.backend.api.routes;

import com.mapmyroad.backend.api.auth.CurrentDevice;
import com.mapmyroad.backend.api.ratelimit.RateLimitPolicy;
import com.mapmyroad.backend.api.ratelimit.RateLimited;
import com.mapmyroad.backend.service.routing.RoutingService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/routes}: route candidates with comfort scores and coverage (API_SPEC.md 5.8). The device ID is
 * only checked (authentication and the per-device rate limit); it is not passed on, so nothing links a device to a
 * route.
 */
@RestController
@RequestMapping("/v1/routes")
public class RouteController {

    private final RoutingService routingService;

    public RouteController(RoutingService routingService) {
        this.routingService = routingService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(RateLimitPolicy.ROUTES)
    public RouteResponse candidates(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RouteRequest request) {
        CurrentDevice.id(jwt);
        return RouteResponse.from(routingService.candidates(request.toQuery()));
    }
}
