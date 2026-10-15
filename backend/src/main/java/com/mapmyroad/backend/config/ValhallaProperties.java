package com.mapmyroad.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Valhalla routing and map-matching service (ARCHITECTURE.md section 9, ADR-0003). One self-hosted instance on the
 * internal Docker network; never a user-supplied URL (SECURITY.md T12).
 *
 * @param baseUrl for example {@code http://valhalla:8002} (env {@code VALHALLA_URL})
 * @param connectTimeout TCP connect timeout
 * @param readTimeout response timeout; routes with alternates are the slowest calls
 */
@ConfigurationProperties(prefix = "mmr.valhalla")
public record ValhallaProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public ValhallaProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8002";
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(2);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(10);
        }
    }
}
