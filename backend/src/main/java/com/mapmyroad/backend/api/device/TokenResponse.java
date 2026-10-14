package com.mapmyroad.backend.api.device;

/** Response of {@code POST /v1/devices/token}: a new access token and the rotated refresh token. */
public record TokenResponse(String accessToken, long accessExpiresInSec, String refreshToken, long refreshExpiresInSec) {

    @Override
    public String toString() {
        return "TokenResponse[redacted]";
    }
}
