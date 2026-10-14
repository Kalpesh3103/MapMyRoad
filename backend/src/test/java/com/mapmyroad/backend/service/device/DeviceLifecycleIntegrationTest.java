package com.mapmyroad.backend.service.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.domain.device.DeviceTokens;
import com.mapmyroad.backend.support.AbstractPostgisIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Registration, refresh, reuse detection and "delete my data" against real PostgreSQL + PostGIS.
 * The test profile disables Play Integrity enforcement, so the accept-all verifier is used.
 */
class DeviceLifecycleIntegrationTest extends AbstractPostgisIntegrationTest {

    @Autowired
    private DeviceRegistrationService registration;

    @Autowired
    private DeviceTokenService tokens;

    @Autowired
    private DeviceDeletionService deletion;

    @Autowired
    private KnownDeviceCache knownDevices;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private DeviceTokens register() {
        String nonce = registration.challenge().nonce();
        return registration.register(new DeviceRegistrationService.Registration("dev-token", nonce, "1.0.3", 34));
    }

    private int count(String sql, UUID id) {
        Integer n = jdbc.queryForObject(sql, new MapSqlParameterSource("id", id), Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void registrationStoresDeviceAndHashedRefreshToken() {
        DeviceTokens issued = register();

        assertThat(jwtDecoder.decode(issued.accessToken().value()).getSubject())
                .isEqualTo(issued.deviceId().toString());
        assertThat(count("SELECT count(*) FROM device WHERE id = :id", issued.deviceId())).isEqualTo(1);
        byte[] storedHash = jdbc.queryForObject(
                "SELECT token_hash FROM device_refresh_token WHERE device_id = :id",
                new MapSqlParameterSource("id", issued.deviceId()), byte[].class);
        assertThat(storedHash).isEqualTo(SecureTokens.sha256(issued.refreshToken().value()));
    }

    @Test
    void refreshRotatesAndReuseRevokesEverything() {
        DeviceTokens first = register();

        DeviceTokens second = tokens.refresh(first.refreshToken().value());
        assertThat(second.deviceId()).isEqualTo(first.deviceId());
        assertThat(second.refreshToken().value()).isNotEqualTo(first.refreshToken().value());

        // Replaying the rotated token: 401, and the reuse revocation must commit despite the exception.
        Throwable reuse = catchThrowable(() -> tokens.refresh(first.refreshToken().value()));
        assertThat(reuse).isInstanceOf(ApiException.class);
        assertThat(((ApiException) reuse).code()).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(count(
                        "SELECT count(*) FROM device_refresh_token WHERE device_id = :id AND revoked_at IS NULL",
                        first.deviceId()))
                .isZero();

        // The newest token was revoked too, so the app has to register again.
        Throwable afterRevocation = catchThrowable(() -> tokens.refresh(second.refreshToken().value()));
        assertThat(afterRevocation).isInstanceOf(ApiException.class);
    }

    @Test
    void deleteRemovesDeviceDataAndQueuesContributedHazards() {
        DeviceTokens issued = register();
        UUID deviceId = issued.deviceId();
        UUID hazardId = UUID.randomUUID();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("deviceId", deviceId)
                .addValue("hazardId", hazardId)
                .addValue("eventId", UUID.randomUUID());
        jdbc.update("""
                INSERT INTO hazard (id, geom, severity_max, severity_avg, first_seen_at, last_seen_at)
                VALUES (:hazardId, ST_GeogFromText('SRID=4326;POINT(73.85 18.52)'), 2, 2, now(), now())
                """, params);
        jdbc.update("""
                INSERT INTO hazard_event (id, device_id, hazard_id, geom, severity, vehicle_type, speed_kmh, observed_at)
                VALUES (:eventId, :deviceId, :hazardId, ST_GeogFromText('SRID=4326;POINT(73.85 18.52)'), 2, 1, 30, now())
                """, params);
        assertThat(knownDevices.exists(deviceId)).isTrue();

        deletion.deleteDevice(deviceId);

        assertThat(count("SELECT count(*) FROM device WHERE id = :id", deviceId)).isZero();
        assertThat(count("SELECT count(*) FROM device_refresh_token WHERE device_id = :id", deviceId)).isZero();
        assertThat(count("SELECT count(*) FROM hazard_event WHERE device_id = :id", deviceId)).isZero();
        assertThat(count("SELECT count(*) FROM hazard_recompute_queue WHERE hazard_id = :id", hazardId))
                .isEqualTo(1);
        assertThat(knownDevices.exists(deviceId)).isFalse();
        // The deleted device's refresh token is gone with it.
        assertThat(catchThrowable(() -> tokens.refresh(issued.refreshToken().value())))
                .isInstanceOf(ApiException.class);
    }
}
