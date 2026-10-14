package com.mapmyroad.backend.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cross-cutting web settings under {@code mmr.web}.
 *
 * @param maxDecompressedBodyBytes request body limit after gzip decompression (API_SPEC.md section 5.3, 256 KB)
 * @param trustedProxies CIDRs whose {@code X-Forwarded-For} is honored: loopback and the private ranges Docker uses
 *     for Caddy's network (DEPLOYMENT.md)
 * @param rateLimitMaxKeysPerPolicy bound on in-memory rate-limit buckets per policy
 */
@ConfigurationProperties(prefix = "mmr.web")
public record WebLayerProperties(
        Long maxDecompressedBodyBytes, List<String> trustedProxies, Long rateLimitMaxKeysPerPolicy) {

    public static final long DEFAULT_MAX_DECOMPRESSED_BODY_BYTES = 262_144L;
    public static final List<String> DEFAULT_TRUSTED_PROXIES =
            List.of("127.0.0.1/32", "::1/128", "172.16.0.0/12", "10.0.0.0/8");
    public static final long DEFAULT_RATE_LIMIT_MAX_KEYS = 100_000L;

    public WebLayerProperties {
        if (maxDecompressedBodyBytes == null) {
            maxDecompressedBodyBytes = DEFAULT_MAX_DECOMPRESSED_BODY_BYTES;
        }
        if (maxDecompressedBodyBytes <= 0) {
            throw new IllegalArgumentException("mmr.web.max-decompressed-body-bytes must be positive");
        }
        trustedProxies = trustedProxies == null ? DEFAULT_TRUSTED_PROXIES : List.copyOf(trustedProxies);
        if (rateLimitMaxKeysPerPolicy == null) {
            rateLimitMaxKeysPerPolicy = DEFAULT_RATE_LIMIT_MAX_KEYS;
        }
        if (rateLimitMaxKeysPerPolicy <= 0) {
            throw new IllegalArgumentException("mmr.web.rate-limit-max-keys-per-policy must be positive");
        }
    }

    /** All defaults, for tests and for wiring without configuration. */
    public static WebLayerProperties defaults() {
        return new WebLayerProperties(null, null, null);
    }
}
