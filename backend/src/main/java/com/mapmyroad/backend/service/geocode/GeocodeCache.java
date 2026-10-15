package com.mapmyroad.backend.service.geocode;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mapmyroad.backend.config.GeocodeProperties;
import com.mapmyroad.backend.repo.geocode.GeocodeCacheRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two-level cache of geocoding answers: Caffeine in memory, then {@code geocode_cache} in PostgreSQL, so answers
 * survive restarts and the public Photon instance sees each distinct query about once per TTL.
 *
 * <p>Every entry carries its own expiry, so empty answers can live shorter ({@code negative-cache-ttl}) than
 * non-empty ones ({@code cache-ttl}); the memory cache's expire-after-write is only the upper bound. A database
 * failure degrades to a miss (read) or is skipped (write): the cache must never break place search. Logs carry
 * exception types only, never keys or queries.
 */
@Component
public class GeocodeCache {

    /** Where a hit came from, used as the {@code source} metric tag. */
    public enum Source {
        MEMORY,
        DB,
    }

    /** A cache hit. */
    public record Hit(List<GeocodeResult> results, Source source) {}

    /** JSON stored in {@code geocode_cache.response}. {@code v} allows changing the shape later. */
    record StoredResponse(int v, List<GeocodeResult> results) {}

    private record Entry(List<GeocodeResult> results, Instant expiresAt) {}

    static final int STORED_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(GeocodeCache.class);

    private final GeocodeCacheRepository repository;
    private final GeocodeProperties properties;
    private final Clock clock;
    private final Cache<String, Entry> memory;

    public GeocodeCache(GeocodeCacheRepository repository, GeocodeProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.memory = Caffeine.newBuilder()
                .expireAfterWrite(properties.cacheTtl())
                .maximumSize(properties.memoryCacheSize())
                .build();
    }

    public Optional<Hit> get(String key) {
        Instant now = clock.instant();
        Entry entry = memory.getIfPresent(key);
        if (entry != null) {
            if (entry.expiresAt().isAfter(now)) {
                return Optional.of(new Hit(entry.results(), Source.MEMORY));
            }
            memory.invalidate(key);
        }
        Optional<GeocodeCacheRepository.CachedRow> row;
        try {
            row = repository.findValid(key, now);
        } catch (DataAccessException e) {
            log.warn("Geocode cache read failed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (row.isEmpty()) {
            return Optional.empty();
        }
        List<GeocodeResult> results = decode(row.get().responseJson());
        if (results == null) {
            return Optional.empty();
        }
        memory.put(key, new Entry(results, row.get().expiresAt()));
        return Optional.of(new Hit(results, Source.DB));
    }

    public void put(String key, List<GeocodeResult> results) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(results.isEmpty() ? properties.negativeCacheTtl() : properties.cacheTtl());
        List<GeocodeResult> copy = List.copyOf(results);
        memory.put(key, new Entry(copy, expiresAt));
        String json = JsonMapper.shared().writeValueAsString(new StoredResponse(STORED_VERSION, copy));
        try {
            repository.upsert(key, json, now, expiresAt);
        } catch (DataAccessException e) {
            log.warn("Geocode cache write failed: {}", e.getClass().getSimpleName());
        }
    }

    /** {@code null} when the stored JSON is unreadable or from another version; the caller treats it as a miss. */
    private static List<GeocodeResult> decode(String json) {
        try {
            StoredResponse stored = JsonMapper.shared().readValue(json, StoredResponse.class);
            if (stored == null || stored.v() != STORED_VERSION || stored.results() == null) {
                return null;
            }
            return List.copyOf(stored.results());
        } catch (JacksonException e) {
            log.warn("Geocode cache entry unreadable: {}", e.getClass().getSimpleName());
            return null;
        }
    }
}
