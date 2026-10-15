package com.mapmyroad.backend.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Geocoding proxy to Photon (M4-13, ARCHITECTURE.md section 9.3, TECH_STACK.md section 5). The upstream host comes
 * only from this configuration, never from a request (SECURITY.md T12).
 *
 * @param enabled when false Photon is never called; cached answers are still served and misses get 503
 * @param baseUrl Photon base URL (env {@code PHOTON_URL}). Defaults to the public fair-use instance
 *     {@code https://photon.komoot.io}; point it at the self-hosted index when usage grows
 * @param userAgent identifying User-Agent, asked for by the public instance's fair-use terms
 * @param connectTimeout TCP connect timeout
 * @param readTimeout response timeout
 * @param cacheTtl lifetime of cached non-empty answers (memory and {@code geocode_cache})
 * @param negativeCacheTtl lifetime of cached empty answers, shorter so new places appear sooner
 * @param memoryCacheSize maximum entries in the in-process cache
 * @param upstreamMaxConcurrent concurrent Photon calls allowed, to stay a good citizen of the public instance
 * @param upstreamQueueTimeout how long a request waits for a free upstream slot before answering 503
 * @param supportedLanguages {@code lang} values the configured Photon accepts; any other requested language is
 *     omitted from the upstream call (Photon then answers in its default, local names). The public instance has
 *     historically accepted only a few languages (default, en, de, fr, it), so only {@code en} is listed until a
 *     self-hosted index with more languages exists
 */
@ConfigurationProperties(prefix = "mmr.geocode")
public record GeocodeProperties(
        Boolean enabled,
        String baseUrl,
        String userAgent,
        Duration connectTimeout,
        Duration readTimeout,
        Duration cacheTtl,
        Duration negativeCacheTtl,
        Integer memoryCacheSize,
        Integer upstreamMaxConcurrent,
        Duration upstreamQueueTimeout,
        List<String> supportedLanguages) {

    public static final String DEFAULT_BASE_URL = "https://photon.komoot.io";
    public static final String DEFAULT_USER_AGENT = "MapMyRoad/0.1 (+contact placeholder)";

    public GeocodeProperties {
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        baseUrl = normalizeBaseUrl(baseUrl);
        if (userAgent == null || userAgent.isBlank()) {
            userAgent = DEFAULT_USER_AGENT;
        }
        connectTimeout = positiveOr(connectTimeout, Duration.ofSeconds(5), "connect-timeout");
        readTimeout = positiveOr(readTimeout, Duration.ofSeconds(10), "read-timeout");
        cacheTtl = positiveOr(cacheTtl, Duration.ofDays(7), "cache-ttl");
        negativeCacheTtl = positiveOr(negativeCacheTtl, Duration.ofHours(1), "negative-cache-ttl");
        upstreamQueueTimeout = positiveOr(upstreamQueueTimeout, Duration.ofSeconds(1), "upstream-queue-timeout");
        if (memoryCacheSize == null) {
            memoryCacheSize = 10_000;
        }
        if (memoryCacheSize < 0) {
            throw new IllegalArgumentException("mmr.geocode.memory-cache-size must not be negative");
        }
        if (upstreamMaxConcurrent == null) {
            upstreamMaxConcurrent = 4;
        }
        if (upstreamMaxConcurrent < 1) {
            throw new IllegalArgumentException("mmr.geocode.upstream-max-concurrent must be at least 1");
        }
        if (supportedLanguages == null || supportedLanguages.isEmpty()) {
            supportedLanguages = List.of("en");
        } else {
            supportedLanguages = supportedLanguages.stream()
                    .map(lang -> lang.trim().toLowerCase(Locale.ROOT))
                    .filter(lang -> !lang.isEmpty())
                    .distinct()
                    .toList();
        }
    }

    /** True when {@code lang} (already lowercased) may be passed to Photon. */
    public boolean supportsLanguage(String lang) {
        return lang != null && supportedLanguages.contains(lang);
    }

    /** Only an absolute http(s) URL without query or fragment is accepted, so requests cannot change the host. */
    private static String normalizeBaseUrl(String value) {
        String url = value == null || value.isBlank() ? DEFAULT_BASE_URL : value.trim();
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mmr.geocode.base-url is not a valid URL", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https"))
                || uri.getHost() == null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(
                    "mmr.geocode.base-url must be an absolute http(s) URL without user info, query or fragment");
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static Duration positiveOr(Duration value, Duration fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("mmr.geocode." + name + " must be positive");
        }
        return value;
    }
}
