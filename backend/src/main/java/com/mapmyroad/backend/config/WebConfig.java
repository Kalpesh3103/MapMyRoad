package com.mapmyroad.backend.config;

import com.mapmyroad.backend.api.ratelimit.RateLimitInterceptor;
import com.mapmyroad.backend.api.web.AppVersionFilter;
import com.mapmyroad.backend.api.web.ClientIpResolver;
import com.mapmyroad.backend.api.web.GzipRequestFilter;
import com.mapmyroad.backend.api.web.RequestIdFilter;
import com.mapmyroad.backend.api.web.SecurityHeadersFilter;
import jakarta.servlet.Filter;
import java.time.Clock;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cross-cutting web layer (task M4-15): servlet filters, the rate-limit interceptor and shared beans.
 *
 * <p>Filter order (all before Spring Security's filter chain, which Boot registers at order -100):
 * <ol>
 *   <li>{@link RequestIdFilter}: request ID into MDC and response, so every later log line and error carries it
 *   <li>{@link SecurityHeadersFilter}: nosniff, no-referrer, default no-store; also covers problems written by later
 *       filters
 *   <li>{@link AppVersionFilter}: 400/426 before authentication, so an outdated app sees 426 rather than 401
 *   <li>{@link GzipRequestFilter}: body size limits and gzip decoding before anything reads the body
 * </ol>
 * Rate limiting runs as a Spring MVC interceptor after security, because DEVICE policies need the authenticated
 * device and policies are declared per handler method with {@code @RateLimited}. Security rules live in
 * {@code SecurityConfig}; nothing here touches them.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    static final int REQUEST_ID_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
    static final int SECURITY_HEADERS_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;
    static final int APP_VERSION_ORDER = Ordered.HIGHEST_PRECEDENCE + 30;
    static final int BODY_LIMIT_ORDER = Ordered.HIGHEST_PRECEDENCE + 40;

    private final RateLimitInterceptor rateLimitInterceptor;

    public WebConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    /**
     * The application's only {@link Clock} (system UTC). Static so it does not need this configuration instance
     * (which depends on the interceptor, which depends on the clock). Deliberately unconditional:
     * {@code @ConditionalOnMissingBean} on a user configuration depends on bean registration order. Tests that need
     * a fixed clock replace it with {@code @MockitoBean} or a {@code @Primary} bean.
     */
    @Bean
    static Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    static ClientIpResolver clientIpResolver(WebLayerProperties properties) {
        return new ClientIpResolver(properties.trustedProxies());
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        return register(new RequestIdFilter(), REQUEST_ID_ORDER);
    }

    @Bean
    FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilterRegistration() {
        return register(new SecurityHeadersFilter(), SECURITY_HEADERS_ORDER);
    }

    @Bean
    FilterRegistrationBean<AppVersionFilter> appVersionFilterRegistration(MmrProperties mmr) {
        return register(new AppVersionFilter(mmr.minSupportedAppVersion()), APP_VERSION_ORDER);
    }

    @Bean
    FilterRegistrationBean<GzipRequestFilter> gzipRequestFilterRegistration(WebLayerProperties properties) {
        return register(new GzipRequestFilter(properties.maxDecompressedBodyBytes()), BODY_LIMIT_ORDER);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/v1/**");
    }

    private static <F extends Filter> FilterRegistrationBean<F> register(F filter, int order) {
        FilterRegistrationBean<F> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(order);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
