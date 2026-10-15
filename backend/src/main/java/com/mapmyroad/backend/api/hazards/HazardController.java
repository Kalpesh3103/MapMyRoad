package com.mapmyroad.backend.api.hazards;

import com.mapmyroad.backend.api.auth.CurrentDevice;
import com.mapmyroad.backend.api.ratelimit.RateLimitPolicy;
import com.mapmyroad.backend.api.ratelimit.RateLimited;
import com.mapmyroad.backend.service.hazards.HazardQuery;
import com.mapmyroad.backend.service.hazards.HazardService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hazards list, detail and feedback (API_SPEC.md 5.5 and 5.6, M4-12). All endpoints need a device token
 * (SecurityConfig). Responses carry hazard IDs only, never device IDs or trip tokens.
 */
@RestController
@RequestMapping("/v1/hazards")
public class HazardController {

    private final HazardService hazardService;

    public HazardController(HazardService hazardService) {
        this.hazardService = hazardService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(RateLimitPolicy.HAZARDS)
    public HazardGeoJson.FeatureCollection list(
            @RequestParam(required = false) String bbox,
            @RequestParam(required = false) Integer minSeverity,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit) {
        HazardQuery query = HazardQuery.of(bbox, minSeverity, status, limit);
        return HazardGeoJson.FeatureCollection.of(hazardService.list(query));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(RateLimitPolicy.HAZARDS)
    public HazardGeoJson.Feature get(@PathVariable UUID id) {
        return HazardGeoJson.Feature.of(hazardService.get(id));
    }

    @PostMapping(
            value = "/{id}/feedback",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(RateLimitPolicy.FEEDBACK)
    public FeedbackResponse feedback(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody FeedbackRequest request) {
        UUID deviceId = CurrentDevice.id(jwt);
        return FeedbackResponse.from(hazardService.feedback(request.toCommand(id, deviceId)));
    }
}
