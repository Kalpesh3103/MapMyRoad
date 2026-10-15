package com.mapmyroad.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * App-wide settings under {@code mmr.*} (DEPLOYMENT.md section 8). Feature-specific settings live in their own
 * classes ({@code mmr.device}, {@code mmr.ingest}, {@code mmr.rate-limit}, ...).
 *
 * @param supportedRegionBbox {@code minLon,minLat,maxLon,maxLat}, also returned by {@code GET /v1/config}
 * @param minSupportedAppVersion requests with a lower {@code X-App-Version} get 426 (API_SPEC.md section 7)
 * @param uploadsEnabled kill switch for ingestion (SECURITY.md section 7, env {@code UPLOADS_ENABLED})
 */
@ConfigurationProperties(prefix = "mmr")
public record MmrProperties(String supportedRegionBbox, String minSupportedAppVersion, Boolean uploadsEnabled) {

    public MmrProperties {
        if (supportedRegionBbox == null || supportedRegionBbox.isBlank()) {
            throw new IllegalArgumentException("mmr.supported-region-bbox is required");
        }
        if (minSupportedAppVersion == null || minSupportedAppVersion.isBlank()) {
            minSupportedAppVersion = "0.1.0";
        }
        if (uploadsEnabled == null) {
            uploadsEnabled = Boolean.TRUE;
        }
        SupportedRegion.parse(supportedRegionBbox); // fail fast on a malformed value
    }

    public SupportedRegion supportedRegion() {
        return SupportedRegion.parse(supportedRegionBbox);
    }
}
