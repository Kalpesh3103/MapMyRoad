package com.mapmyroad.backend.api.device;

import java.util.UUID;

/**
 * Response of {@code POST /v1/devices}. The device ID goes back only to the device itself; it never appears in a
 * public response (AGENTS.md section 8).
 */
public record RegisterDeviceResponse(
        UUID deviceId, String accessToken, long accessExpiresInSec, String refreshToken, long refreshExpiresInSec) {

    @Override
    public String toString() {
        return "RegisterDeviceResponse[redacted]";
    }
}
