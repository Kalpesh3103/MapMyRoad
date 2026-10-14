package com.mapmyroad.backend.repo.device;

import com.mapmyroad.backend.domain.device.RefreshTokenRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL for {@code device_refresh_token}. Tokens are looked up by their SHA-256 hash only. */
@Repository
public class RefreshTokenRepository {

    private static final String INSERT = """
            INSERT INTO device_refresh_token (device_id, token_hash, expires_at)
            VALUES (:deviceId, :tokenHash, :expiresAt)
            """;

    /** Row lock so two concurrent refreshes with the same token are serialized: the second one sees it revoked. */
    private static final String FIND_BY_HASH_FOR_UPDATE = """
            SELECT id, device_id, expires_at, revoked_at
            FROM device_refresh_token
            WHERE token_hash = :tokenHash
            FOR UPDATE
            """;

    private static final String REVOKE = """
            UPDATE device_refresh_token SET revoked_at = :now
            WHERE id = :id AND revoked_at IS NULL
            """;

    private static final String REVOKE_ALL_FOR_DEVICE = """
            UPDATE device_refresh_token SET revoked_at = :now
            WHERE device_id = :deviceId AND revoked_at IS NULL
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RefreshTokenRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID deviceId, byte[] tokenHash, Instant expiresAt) {
        jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("deviceId", deviceId)
                .addValue("tokenHash", tokenHash)
                .addValue("expiresAt", DeviceRepository.utc(expiresAt)));
    }

    /** Must run inside a transaction; the row stays locked until it ends. */
    public Optional<RefreshTokenRecord> findByHashForUpdate(byte[] tokenHash) {
        List<RefreshTokenRecord> rows = jdbc.query(
                FIND_BY_HASH_FOR_UPDATE, new MapSqlParameterSource("tokenHash", tokenHash), RefreshTokenRepository::map);
        return rows.stream().findFirst();
    }

    public void revoke(UUID id, Instant now) {
        jdbc.update(REVOKE, new MapSqlParameterSource().addValue("id", id).addValue("now", DeviceRepository.utc(now)));
    }

    /** Returns the number of tokens revoked. */
    public int revokeAllForDevice(UUID deviceId, Instant now) {
        return jdbc.update(REVOKE_ALL_FOR_DEVICE, new MapSqlParameterSource()
                .addValue("deviceId", deviceId)
                .addValue("now", DeviceRepository.utc(now)));
    }

    private static RefreshTokenRecord map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime revokedAt = rs.getObject("revoked_at", OffsetDateTime.class);
        return new RefreshTokenRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("device_id", UUID.class),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                revokedAt == null ? null : revokedAt.toInstant());
    }
}
