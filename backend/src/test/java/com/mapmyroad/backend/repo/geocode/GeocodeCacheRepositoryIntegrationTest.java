package com.mapmyroad.backend.repo.geocode;

import static org.assertj.core.api.Assertions.assertThat;

import com.mapmyroad.backend.support.AbstractPostgisIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** {@code geocode_cache} against real PostgreSQL: JSONB round trip, upsert and expiry filtering. */
class GeocodeCacheRepositoryIntegrationTest extends AbstractPostgisIntegrationTest {

    private static final String KEY = "a".repeat(64);
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Autowired
    private GeocodeCacheRepository repository;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.getJdbcOperations().update("DELETE FROM geocode_cache");
    }

    @Test
    void storedEntryIsReadBackUntilItExpires() {
        repository.upsert(KEY, "{\"v\":1,\"results\":[]}", NOW, NOW.plus(Duration.ofHours(1)));

        Optional<GeocodeCacheRepository.CachedRow> row = repository.findValid(KEY, NOW);

        assertThat(row).isPresent();
        assertThat(row.get().responseJson()).contains("\"results\"");
        assertThat(row.get().expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(repository.findValid(KEY, NOW.plus(Duration.ofHours(2)))).isEmpty();
    }

    @Test
    void upsertReplacesAnExistingEntry() {
        repository.upsert(KEY, "{\"v\":1,\"results\":[]}", NOW, NOW.plusSeconds(10));
        repository.upsert(KEY, "{\"v\":1,\"results\":[{\"name\":\"Pune\"}]}", NOW, NOW.plus(Duration.ofDays(7)));

        GeocodeCacheRepository.CachedRow row = repository.findValid(KEY, NOW.plusSeconds(60)).orElseThrow();

        assertThat(row.responseJson()).contains("Pune");
        Integer count = jdbc.getJdbcOperations().queryForObject("SELECT count(*) FROM geocode_cache", Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
