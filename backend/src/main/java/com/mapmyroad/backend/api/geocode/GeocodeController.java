package com.mapmyroad.backend.api.geocode;

import com.mapmyroad.backend.api.auth.CurrentDevice;
import com.mapmyroad.backend.api.ratelimit.RateLimitPolicy;
import com.mapmyroad.backend.api.ratelimit.RateLimited;
import com.mapmyroad.backend.service.geocode.GeocodeService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Place search and reverse geocoding (API_SPEC.md section 5.7), authenticated. Both endpoints share the geocode
 * rate limits (5 per second burst, 300 per hour per device, section 4). Parameters are optional at the binding level
 * so {@link GeocodeRequestParser} can report every problem in one response.
 */
@RestController
@RequestMapping("/v1/geocode")
public class GeocodeController {

    private final GeocodeService geocodeService;

    public GeocodeController(GeocodeService geocodeService) {
        this.geocodeService = geocodeService;
    }

    @GetMapping
    @RateLimited({RateLimitPolicy.GEOCODE_BURST, RateLimitPolicy.GEOCODE})
    public GeocodeResponse search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lon,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String lang) {
        CurrentDevice.id(jwt); // the device ID is not used, but a token without a valid device subject is refused
        return GeocodeResponse.from(geocodeService.search(GeocodeRequestParser.search(q, lat, lon, limit, lang)));
    }

    @GetMapping("/reverse")
    @RateLimited({RateLimitPolicy.GEOCODE_BURST, RateLimitPolicy.GEOCODE})
    public GeocodeResponse reverse(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lon,
            @RequestParam(required = false) String lang) {
        CurrentDevice.id(jwt);
        return GeocodeResponse.from(geocodeService.reverse(GeocodeRequestParser.reverse(lat, lon, lang)));
    }
}
