package com.mapmyroad.backend.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mapmyroad.backend.api.error.ApiExceptionHandler;
import com.mapmyroad.backend.api.web.ClientIpResolver;
import com.mapmyroad.backend.config.WebLayerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class RateLimitInterceptorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RateLimiter limiter =
            new RateLimiter(new MutableClock(Instant.parse("2026-10-13T08:00:00Z")), WebLayerProperties.defaults());
    private final RateLimitInterceptor interceptor = new RateLimitInterceptor(
            limiter, new ClientIpResolver(WebLayerProperties.DEFAULT_TRUSTED_PROXIES), registry);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new LimitedController())
            .setControllerAdvice(new ApiExceptionHandler())
            .addInterceptors(interceptor)
            .build();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ipPolicyRejectsWith429AndRetryAfter() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/v1/register").with(remote("203.0.113.7"))).andExpect(status().isOk());
        }
        mvc.perform(get("/v1/register").with(remote("203.0.113.7")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "360"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSec").value(360))
                .andExpect(jsonPath("$.type").value("https://api.example.com/problems/rate-limited"));
        mvc.perform(get("/v1/register").with(remote("203.0.113.8"))).andExpect(status().isOk());
        assertThat(registry.get(RateLimitInterceptor.METRIC).tag("policy", "DEVICE_REGISTRATION").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void devicePolicyKeysByAuthenticatedName() throws Exception {
        for (int i = 0; i < 5; i++) {
            authenticateAs("device-a");
            mvc.perform(get("/v1/geocode").with(remote("203.0.113.7"))).andExpect(status().isOk());
        }
        authenticateAs("device-a");
        mvc.perform(get("/v1/geocode").with(remote("203.0.113.7"))).andExpect(status().isTooManyRequests());
        authenticateAs("device-b"); // same IP, different device
        mvc.perform(get("/v1/geocode").with(remote("203.0.113.7"))).andExpect(status().isOk());
    }

    @Test
    void unannotatedHandlersAreNotLimited() throws Exception {
        for (int i = 0; i < 50; i++) {
            mvc.perform(get("/v1/free")).andExpect(status().isOk());
        }
    }

    private static void authenticateAs(String name) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                name, null, List.of(new SimpleGrantedAuthority("SCOPE_device"))));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor remote(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @RestController
    static class LimitedController {

        @GetMapping("/v1/register")
        @RateLimited(RateLimitPolicy.DEVICE_REGISTRATION)
        String register() {
            return "ok";
        }

        @GetMapping("/v1/geocode")
        @RateLimited({RateLimitPolicy.GEOCODE_BURST, RateLimitPolicy.GEOCODE})
        String geocode() {
            return "ok";
        }

        @GetMapping("/v1/free")
        String free() {
            return "ok";
        }
    }
}
