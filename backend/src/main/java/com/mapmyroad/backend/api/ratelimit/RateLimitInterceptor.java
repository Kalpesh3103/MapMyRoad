package com.mapmyroad.backend.api.ratelimit;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.api.error.ProblemResponses;
import com.mapmyroad.backend.api.web.ClientIpResolver;
import com.mapmyroad.backend.api.web.IpAddresses;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies {@link RateLimited} policies before the controller runs. Rejections become 429 RATE_LIMITED with
 * {@code retryAfterSec} and a {@code Retry-After} header, and are counted in {@code mmr.ratelimit.rejected}
 * (SECURITY.md section 12). Runs inside Spring MVC, i.e. after Spring Security, so the device identity is known.
 *
 * <p>Device keys use {@link Authentication#getName()}, which is the JWT {@code sub} (the device ID) for bearer
 * tokens. The key is only held in memory and never logged.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    static final String METRIC = "mmr.ratelimit.rejected";

    private final RateLimiter limiter;
    private final ClientIpResolver clientIpResolver;
    private final Map<RateLimitPolicy, Counter> rejected = new EnumMap<>(RateLimitPolicy.class);

    public RateLimitInterceptor(RateLimiter limiter, ClientIpResolver clientIpResolver, MeterRegistry meterRegistry) {
        this.limiter = limiter;
        this.clientIpResolver = clientIpResolver;
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            rejected.put(policy, Counter.builder(METRIC)
                    .description("Requests rejected by an application rate limit")
                    .tag("policy", policy.name())
                    .register(meterRegistry));
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimited rateLimited = handlerMethod.getMethodAnnotation(RateLimited.class);
        if (rateLimited == null) {
            return true;
        }
        for (RateLimitPolicy policy : rateLimited.value()) {
            RateLimiter.Decision decision = limiter.tryAcquire(policy, clientKey(policy, request));
            if (!decision.allowed()) {
                rejected.get(policy).increment();
                throw new ApiException(
                        ErrorCode.RATE_LIMITED,
                        "Retry after " + decision.retryAfterSec() + " seconds.",
                        Map.of(ProblemResponses.RETRY_AFTER_SEC, decision.retryAfterSec()));
            }
        }
        return true;
    }

    private String clientKey(RateLimitPolicy policy, HttpServletRequest request) {
        if (policy.key() == RateLimitPolicy.Key.DEVICE) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null
                    && authentication.isAuthenticated()
                    && !(authentication instanceof AnonymousAuthenticationToken)
                    && authentication.getName() != null) {
                return "device:" + authentication.getName();
            }
        }
        return "ip:" + IpAddresses.rateLimitKey(clientIpResolver.resolve(request));
    }
}
