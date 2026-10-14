package com.mapmyroad.backend.domain.device;

import java.util.UUID;

/** Access and refresh token pair for one device, returned by registration and refresh. */
public record DeviceTokens(UUID deviceId, IssuedToken accessToken, IssuedToken refreshToken) {

    @Override
    public String toString() {
        // No device ID or token values in logs (AGENTS.md section 6).
        return "DeviceTokens[redacted]";
    }
}
