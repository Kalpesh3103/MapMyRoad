package com.mapmyroad.backend.service.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.config.DeviceProperties;
import com.mapmyroad.backend.domain.device.DeviceTokens;
import com.mapmyroad.backend.domain.device.IssuedToken;
import com.mapmyroad.backend.domain.device.NewDevice;
import com.mapmyroad.backend.domain.device.RefreshTokenRecord;
import com.mapmyroad.backend.repo.device.DeviceRepository;
import com.mapmyroad.backend.repo.device.RefreshTokenRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Refresh rotation and reuse detection with the repositories mocked (SECURITY.md section 5, threat T7). */
class DeviceTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T08:00:00Z");
    private static final Duration REFRESH_TTL = Duration.ofDays(30);

    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final AccessTokenService accessTokens = mock(AccessTokenService.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final UUID deviceId = UUID.randomUUID();
    private DeviceTokenService service;

    @BeforeEach
    void setUp() {
        DeviceProperties properties = new DeviceProperties(true, null, null, null, null, REFRESH_TTL, null);
        service = new DeviceTokenService(devices, refreshTokens, accessTokens, properties, clock);
        when(accessTokens.issue(any())).thenReturn(new IssuedToken("access-jwt", 3600));
    }

    @Test
    void createDeviceStoresOnlyTheHashOfTheRefreshToken() {
        NewDevice device = new NewDevice("1.0.3", 34, 0.5);
        when(devices.insert(device)).thenReturn(deviceId);

        DeviceTokens tokens = service.createDevice(device);

        ArgumentCaptor<byte[]> hash = ArgumentCaptor.forClass(byte[].class);
        verify(refreshTokens).insert(eq(deviceId), hash.capture(), eq(NOW.plus(REFRESH_TTL)));
        assertThat(hash.getValue()).isEqualTo(SecureTokens.sha256(tokens.refreshToken().value()));
        assertThat(tokens.deviceId()).isEqualTo(deviceId);
        assertThat(tokens.accessToken().value()).isEqualTo("access-jwt");
        assertThat(tokens.refreshToken().expiresInSec()).isEqualTo(REFRESH_TTL.toSeconds());
    }

    @Test
    void refreshRevokesPresentedTokenAndStoresANewOne() {
        String presented = SecureTokens.randomToken();
        UUID tokenId = UUID.randomUUID();
        when(refreshTokens.findByHashForUpdate(any()))
                .thenReturn(Optional.of(new RefreshTokenRecord(tokenId, deviceId, NOW.plusSeconds(60), null)));

        DeviceTokens tokens = service.refresh(presented);

        ArgumentCaptor<byte[]> lookedUp = ArgumentCaptor.forClass(byte[].class);
        verify(refreshTokens).findByHashForUpdate(lookedUp.capture());
        assertThat(lookedUp.getValue()).isEqualTo(SecureTokens.sha256(presented));
        verify(refreshTokens).revoke(tokenId, NOW);
        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
        verify(refreshTokens).insert(eq(deviceId), stored.capture(), eq(NOW.plus(REFRESH_TTL)));
        assertThat(stored.getValue()).isEqualTo(SecureTokens.sha256(tokens.refreshToken().value()));
        assertThat(tokens.refreshToken().value()).isNotEqualTo(presented);
        verify(devices).touchLastSeen(deviceId, NOW);
        verify(refreshTokens, never()).revokeAllForDevice(any(), any());
    }

    @Test
    void reuseOfRotatedTokenRevokesAllTokensOfTheDevice() {
        when(refreshTokens.findByHashForUpdate(any())).thenReturn(Optional.of(
                new RefreshTokenRecord(UUID.randomUUID(), deviceId, NOW.plusSeconds(60), NOW.minusSeconds(5))));

        Throwable thrown = catchThrowable(() -> service.refresh("rotated-token"));

        assertUnauthorized(thrown);
        verify(refreshTokens).revokeAllForDevice(deviceId, NOW);
        verify(refreshTokens, never()).insert(any(), any(), any());
        verifyNoInteractions(accessTokens);
    }

    @Test
    void expiredTokenIsRejectedWithoutWrites() {
        when(refreshTokens.findByHashForUpdate(any()))
                .thenReturn(Optional.of(new RefreshTokenRecord(UUID.randomUUID(), deviceId, NOW, null)));

        assertUnauthorized(catchThrowable(() -> service.refresh("expired-token")));
        verify(refreshTokens, never()).revoke(any(), any());
        verify(refreshTokens, never()).revokeAllForDevice(any(), any());
        verify(refreshTokens, never()).insert(any(), any(), any());
    }

    @Test
    void unknownTokenIsRejected() {
        when(refreshTokens.findByHashForUpdate(any())).thenReturn(Optional.empty());

        assertUnauthorized(catchThrowable(() -> service.refresh("unknown-token")));
        verify(refreshTokens, never()).insert(any(), any(), any());
        verifyNoInteractions(accessTokens);
    }

    private static void assertUnauthorized(Throwable thrown) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).code()).isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}
