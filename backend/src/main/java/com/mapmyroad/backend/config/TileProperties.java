package com.mapmyroad.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Vector tiles and TileJSON (API_SPEC.md section 5.4).
 *
 * @param publicBaseUrl external origin of the API used in the TileJSON tile template, without {@code /v1}
 *     (env {@code PUBLIC_BASE_URL}; placeholder until the domain is chosen, PROGRESS.md section 7)
 * @param cacheMaxAgeSeconds {@code Cache-Control: public, max-age} for tiles and TileJSON
 * @param serverCacheTtl lifetime of a tile in the in-process cache. Invalidation is time-based only: aggregation
 *     changes become visible after at most this TTL plus the client max-age.
 * @param serverCacheMaxBytes upper bound of the in-process cache, weighed by tile size
 */
@ConfigurationProperties(prefix = "mmr.tiles")
public record TileProperties(
        String publicBaseUrl, Integer cacheMaxAgeSeconds, Duration serverCacheTtl, Long serverCacheMaxBytes) {

    public TileProperties {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            publicBaseUrl = "https://api.example.com";
        }
        while (publicBaseUrl.endsWith("/")) {
            publicBaseUrl = publicBaseUrl.substring(0, publicBaseUrl.length() - 1);
        }
        cacheMaxAgeSeconds = cacheMaxAgeSeconds == null ? 300 : cacheMaxAgeSeconds;
        serverCacheTtl = serverCacheTtl == null ? Duration.ofSeconds(60) : serverCacheTtl;
        serverCacheMaxBytes = serverCacheMaxBytes == null ? 64L * 1024 * 1024 : serverCacheMaxBytes;
        if (cacheMaxAgeSeconds < 0 || serverCacheTtl.isNegative() || serverCacheMaxBytes < 0) {
            throw new IllegalArgumentException("mmr.tiles cache settings must not be negative");
        }
    }

    public static TileProperties defaults() {
        return new TileProperties(null, null, null, null);
    }
}
