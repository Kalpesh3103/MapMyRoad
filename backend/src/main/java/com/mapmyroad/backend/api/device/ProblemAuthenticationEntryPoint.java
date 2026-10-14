package com.mapmyroad.backend.api.device;

import com.mapmyroad.backend.api.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * 401 for missing, invalid or expired access tokens, as problem JSON. Expired tokens get {@code TOKEN_EXPIRED} so
 * the app refreshes once and retries (API_SPEC.md section 6); everything else gets {@code UNAUTHORIZED}.
 *
 * <p>How "expired" is detected: the only component that checks expiry is Spring's {@code JwtTimestampValidator}
 * (Nimbus' own claim checks are disabled in {@code DeviceJwts}). It reports {@code "Jwt expired at ..."} in an
 * {@link OAuth2Error} inside a {@link JwtValidationException}, which the resource server wraps into an
 * {@code InvalidBearerTokenException}. We walk the cause chain and look for "expired" in those error descriptions.
 * Validation runs only after the signature has been verified, so a forged token never earns {@code TOKEN_EXPIRED}.
 */
public final class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        if (isExpiredToken(exception)) {
            SecurityProblemWriter.write(response, ErrorCode.TOKEN_EXPIRED, "Access token expired. Refresh and retry.");
        } else {
            SecurityProblemWriter.write(response, ErrorCode.UNAUTHORIZED, "Missing or invalid access token.");
        }
    }

    /** Whether the failure is an otherwise valid token past its {@code exp}. Public for tests. */
    public static boolean isExpiredToken(Throwable exception) {
        int depth = 0;
        for (Throwable t = exception; t != null && depth < 10; t = t.getCause(), depth++) {
            if (t instanceof JwtValidationException validation) {
                for (OAuth2Error error : validation.getErrors()) {
                    if (mentionsExpiry(error.getDescription())) {
                        return true;
                    }
                }
            }
            if (t instanceof OAuth2AuthenticationException oauth && oauth.getError() != null
                    && mentionsExpiry(oauth.getError().getDescription())) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionsExpiry(String description) {
        return description != null && description.toLowerCase(Locale.ROOT).contains("expired");
    }
}
