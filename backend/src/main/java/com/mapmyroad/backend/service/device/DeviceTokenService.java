package com.mapmyroad.backend.service.device;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.config.DeviceProperties;
import com.mapmyroad.backend.domain.device.DeviceTokens;
import com.mapmyroad.backend.domain.device.IssuedToken;
import com.mapmyroad.backend.domain.device.NewDevice;
import com.mapmyroad.backend.domain.device.RefreshTokenRecord;
import com.mapmyroad.backend.repo.device.DeviceRepository;
import com.mapmyroad.backend.repo.device.RefreshTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates devices with their first token pair and rotates refresh tokens (SECURITY.md section 5, threat T7).
 * Refresh tokens are 256-bit random values stored only as SHA-256 hashes and are single-use.
 */
@Service
public class DeviceTokenService {

    private static final Logger log = LoggerFactory.getLogger(DeviceTokenService.class);

    private final DeviceRepository devices;
    private final RefreshTokenRepository refreshTokens;
    private final AccessTokenService accessTokens;
    private final Duration refreshTtl;
    private final Clock clock;

    public DeviceTokenService(
            DeviceRepository devices,
            RefreshTokenRepository refreshTokens,
            AccessTokenService accessTokens,
            DeviceProperties properties,
            Clock clock) {
        this.devices = devices;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.refreshTtl = properties.refreshTokenTtl();
        this.clock = clock;
    }

    /** Inserts the device and its first refresh token in one transaction, then signs an access token. */
    @Transactional
    public DeviceTokens createDevice(NewDevice device) {
        UUID deviceId = devices.insert(device);
        IssuedToken refresh = newRefreshToken(deviceId, clock.instant());
        return new DeviceTokens(deviceId, accessTokens.issue(deviceId), refresh);
    }

    /**
     * Exchanges a refresh token for a new pair. The presented token is revoked and a new one stored in the same
     * transaction. Presenting a token that was already rotated or revoked means it was copied (or the client lost
     * a response): every token of that device is revoked and the app must register again.
     *
     * <p>{@code noRollbackFor}: the reuse branch writes the revocations and then throws 401; those writes must commit.
     * No other branch writes before throwing.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public DeviceTokens refresh(String presentedToken) {
        Instant now = clock.instant();
        Optional<RefreshTokenRecord> found = refreshTokens.findByHashForUpdate(SecureTokens.sha256(presentedToken));
        if (found.isEmpty()) {
            throw unauthorized();
        }
        RefreshTokenRecord record = found.get();
        if (record.isRevoked()) {
            int revoked = refreshTokens.revokeAllForDevice(record.deviceId(), now);
            log.warn("Refresh token reuse detected; revoked {} outstanding token(s) of the device", revoked);
            throw unauthorized();
        }
        if (record.isExpiredAt(now)) {
            throw unauthorized();
        }
        refreshTokens.revoke(record.id(), now);
        IssuedToken refresh = newRefreshToken(record.deviceId(), now);
        devices.touchLastSeen(record.deviceId(), now);
        return new DeviceTokens(record.deviceId(), accessTokens.issue(record.deviceId()), refresh);
    }

    private IssuedToken newRefreshToken(UUID deviceId, Instant now) {
        String value = SecureTokens.randomToken();
        refreshTokens.insert(deviceId, SecureTokens.sha256(value), now.plus(refreshTtl));
        return new IssuedToken(value, refreshTtl.toSeconds());
    }

    private static ApiException unauthorized() {
        // Same answer for unknown, expired and reused tokens: the caller learns nothing about which it was.
        return new ApiException(ErrorCode.UNAUTHORIZED, "Refresh token is invalid. Register again.");
    }
}
