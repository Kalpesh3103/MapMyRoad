package com.mapmyroad.backend.service.device;

import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Checks the claims every device access token must carry (API_SPEC.md section 2): a UUID {@code sub}, an
 * {@code exp} (the timestamp validator lets tokens without one through) and {@code scope = device}.
 */
public final class DeviceClaimsValidator implements OAuth2TokenValidator<Jwt> {

    public static final String SCOPE_CLAIM = "scope";
    public static final String DEVICE_SCOPE = "device";

    private static final String INVALID_TOKEN = "invalid_token";

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (jwt.getExpiresAt() == null) {
            return failure("Missing exp claim.");
        }
        if (!DEVICE_SCOPE.equals(jwt.getClaimAsString(SCOPE_CLAIM))) {
            return failure("Missing device scope.");
        }
        String subject = jwt.getSubject();
        if (subject == null) {
            return failure("Missing sub claim.");
        }
        try {
            UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            return failure("Malformed sub claim.");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(INVALID_TOKEN, description, null));
    }
}
