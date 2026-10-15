package com.mapmyroad.backend.repo.geocode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code geocode_cache} (DATABASE_SCHEMA.md A7). Expired rows are ignored on read and deleted by the retention job
 * (A10). Times are parameters from the application clock rather than {@code now()}, so tests can pin time.
 */
@Repository
public class GeocodeCacheRepository {

    private static final String FIND_VALID = """
            SELECT response::text AS response, expires_at
            FROM geocode_cache
            WHERE cache_key = :cacheKey AND expires_at > :now
            """;

    private static final String UPSERT = """
            INSERT INTO geocode_cache (cache_key, response, created_at, expires_at)
            VALUES (:cacheKey, CAST(:response AS jsonb), :createdAt, :expiresAt)
            ON CONFLICT (cache_key) DO UPDATE
              SET response = EXCLUDED.response,
                  created_at = EXCLUDED.created_at,
                  expires_at = EXCLUDED.expires_at
            """;

    /** A stored answer that has not expired yet. */
    public record CachedRow(String responseJson, Instant expiresAt) {}

    private final NamedParameterJdbcTemplate jdbc;

    public GeocodeCacheRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CachedRow> findValid(String cacheKey, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("cacheKey", cacheKey)
                .addValue("now", utc(now));
        List<CachedRow> rows = jdbc.query(FIND_VALID, params, (rs, rowNum) -> new CachedRow(
                rs.getString("response"), rs.getObject("expires_at", OffsetDateTime.class).toInstant()));
        return rows.stream().findFirst();
    }

    /** Inserts or replaces the entry; concurrent misses for the same key simply overwrite each other. */
    public void upsert(String cacheKey, String responseJson, Instant createdAt, Instant expiresAt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("cacheKey", cacheKey)
                .addValue("response", responseJson)
                .addValue("createdAt", utc(createdAt))
                .addValue("expiresAt", utc(expiresAt));
        jdbc.update(UPSERT, params);
    }

    /** The PostgreSQL driver binds OffsetDateTime to timestamptz; it does not accept Instant directly. */
    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
