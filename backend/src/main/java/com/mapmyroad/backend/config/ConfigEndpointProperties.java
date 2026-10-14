package com.mapmyroad.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Client-facing values returned by {@code GET /v1/config} (API_SPEC.md section 5.1), under {@code mmr.client}.
 * Region and minimum app version come from {@link MmrProperties}; upload limits come from {@link IngestProperties}
 * so the advertised caps can never drift from the enforced ones.
 *
 * @param tileJsonUrl absolute URL of the TileJSON document; the default uses the placeholder API domain
 *     (PROGRESS.md, API domain not chosen yet) and must be overridden in production
 * @param baseMapStyleUrl MapLibre style for the base map (OpenFreeMap, no key needed)
 * @param retentionDays retention period the app shows in its privacy text; keep in line with {@code mmr.retention}
 * @param features feature flags for later phases (v3 navigation and reports, v4 areas)
 */
@ConfigurationProperties(prefix = "mmr.client")
public record ConfigEndpointProperties(
        String tileJsonUrl, String baseMapStyleUrl, Integer retentionDays, Features features) {

    public static final String DEFAULT_TILE_JSON_URL = "https://api.example.com/v1/tiles/tilejson.json";
    public static final String DEFAULT_BASE_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";
    public static final int DEFAULT_RETENTION_DAYS = 90;

    public ConfigEndpointProperties {
        tileJsonUrl = isBlank(tileJsonUrl) ? DEFAULT_TILE_JSON_URL : tileJsonUrl;
        baseMapStyleUrl = isBlank(baseMapStyleUrl) ? DEFAULT_BASE_MAP_STYLE_URL : baseMapStyleUrl;
        retentionDays = retentionDays == null ? DEFAULT_RETENTION_DAYS : retentionDays;
        if (retentionDays <= 0) {
            throw new IllegalArgumentException("mmr.client.retention-days must be positive");
        }
        features = features == null ? new Features(null, null, null) : features;
    }

    public static ConfigEndpointProperties defaults() {
        return new ConfigEndpointProperties(null, null, null, null);
    }

    /** Feature flags, all off until the matching phase ships. */
    public record Features(Boolean navigation, Boolean reports, Boolean areas) {
        public Features {
            navigation = Boolean.TRUE.equals(navigation);
            reports = Boolean.TRUE.equals(reports);
            areas = Boolean.TRUE.equals(areas);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
