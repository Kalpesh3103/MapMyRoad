package com.mapmyroad.backend.api.config;

import com.mapmyroad.backend.api.ratelimit.RateLimitPolicy;
import com.mapmyroad.backend.api.ratelimit.RateLimited;
import com.mapmyroad.backend.config.ConfigEndpointProperties;
import com.mapmyroad.backend.config.IngestProperties;
import com.mapmyroad.backend.config.MmrProperties;
import com.mapmyroad.backend.config.SupportedRegion;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/config} (API_SPEC.md section 5.1, task M4-6): public, rate limited per IP, cacheable for 5 minutes.
 * Also the target of the external uptime monitor (API_SPEC.md section 5.13). The response is assembled once because
 * every input is immutable configuration.
 */
@RestController
public class ConfigController {

    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final ConfigResponse response;

    public ConfigController(MmrProperties mmr, ConfigEndpointProperties client, IngestProperties ingest) {
        this.response = build(mmr, client, ingest);
    }

    @GetMapping("/v1/config")
    @RateLimited(RateLimitPolicy.CONFIG)
    public ResponseEntity<ConfigResponse> config() {
        return ResponseEntity.ok().cacheControl(CACHE).body(response);
    }

    static ConfigResponse build(MmrProperties mmr, ConfigEndpointProperties client, IngestProperties ingest) {
        SupportedRegion region = mmr.supportedRegion();
        ConfigEndpointProperties.Features flags = client.features();
        return new ConfigResponse(
                mmr.minSupportedAppVersion(),
                new ConfigResponse.SupportedRegionDto(
                        List.of(region.minLon(), region.minLat(), region.maxLon(), region.maxLat())),
                client.tileJsonUrl(),
                client.baseMapStyleUrl(),
                client.retentionDays(),
                new ConfigResponse.Limits(ingest.maxEventsPerRequest(), ingest.maxWindowsPerRequest()),
                new ConfigResponse.Features(flags.navigation(), flags.reports(), flags.areas()));
    }
}
