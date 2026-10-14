package com.mapmyroad.backend.api.device;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class ProblemAuthenticationEntryPointTest {

    private final ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint();

    private static InvalidBearerTokenException wrapped(String description) {
        JwtValidationException validation = new JwtValidationException(
                "An error occurred while attempting to decode the Jwt: " + description,
                List.of(new OAuth2Error("invalid_token", description, null)));
        return new InvalidBearerTokenException(validation.getMessage(), validation);
    }

    @Test
    void expiredTokenGetsTokenExpiredProblem() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(
                new MockHttpServletRequest(), response, wrapped("Jwt expired at 2026-10-14T08:00:00Z"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getContentAsString())
                .contains("\"code\":\"TOKEN_EXPIRED\"")
                .contains("\"status\":401")
                .contains("\"type\":\"https://api.example.com/problems/token-expired\"")
                .contains("\"title\":")
                .contains("\"detail\":");
    }

    @Test
    void otherValidationFailuresGetUnauthorized() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(new MockHttpServletRequest(), response, wrapped("Missing device scope."));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void missingTokenAndDeletedDeviceGetUnauthorized() throws Exception {
        MockHttpServletResponse missing = new MockHttpServletResponse();
        MockHttpServletResponse deleted = new MockHttpServletResponse();

        entryPoint.commence(new MockHttpServletRequest(), missing,
                new InsufficientAuthenticationException("Full authentication is required"));
        entryPoint.commence(new MockHttpServletRequest(), deleted, new DeviceNotRegisteredException());

        assertThat(missing.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
        assertThat(deleted.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void accessDeniedLooksLikeNotFound() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ProblemAccessDeniedHandler().handle(new MockHttpServletRequest(), response,
                new org.springframework.security.access.AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("\"code\":\"NOT_FOUND\"");
    }
}
