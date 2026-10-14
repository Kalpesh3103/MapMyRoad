package com.mapmyroad.backend.api.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /v1/devices/token}. Our refresh tokens are 43 characters; 128 leaves room for a format change. */
public record RefreshTokenRequest(@NotBlank @Size(max = 128) String refreshToken) {

    @Override
    public String toString() {
        return "RefreshTokenRequest[redacted]";
    }
}
