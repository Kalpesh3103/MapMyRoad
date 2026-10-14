package com.mapmyroad.backend.api.auth;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Reads the device ID from the access token (`sub` claim, API_SPEC.md section 2). */
public final class CurrentDevice {

    private CurrentDevice() {}

    /** Use with {@code @AuthenticationPrincipal Jwt jwt} in controllers. */
    public static UUID id(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Missing device identity.");
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Invalid device identity.");
        }
    }
}
