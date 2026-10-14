package com.mapmyroad.backend.domain.device;

import java.time.Instant;
import java.util.UUID;

/** A stored refresh token row. Only the SHA-256 hash of the token is ever stored, so the value is not here. */
public record RefreshTokenRecord(UUID id, UUID deviceId, Instant expiresAt, Instant revokedAt) {

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
