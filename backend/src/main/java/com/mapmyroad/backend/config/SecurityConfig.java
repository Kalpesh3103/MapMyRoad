package com.mapmyroad.backend.config;

import com.mapmyroad.backend.api.device.DeviceJwtAuthenticationConverter;
import com.mapmyroad.backend.api.device.ProblemAccessDeniedHandler;
import com.mapmyroad.backend.api.device.ProblemAuthenticationEntryPoint;
import com.mapmyroad.backend.api.device.PublicEndpoints;
import com.mapmyroad.backend.service.device.DeviceJwts;
import com.mapmyroad.backend.service.device.JwtKeyRing;
import com.mapmyroad.backend.service.device.KnownDeviceCache;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * HTTP security (SECURITY.md section 5). Stateless bearer-token API for one kind of client, the Android app:
 * no sessions, no CSRF (no cookies or browser clients), no CORS, no login forms. Public endpoints are listed in
 * {@link PublicEndpoints}; the rest of {@code /v1/**} needs a valid device access token; everything else is denied.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    JwtKeyRing jwtKeyRing(DeviceProperties properties, Environment environment) {
        boolean allowEphemeral = environment.acceptsProfiles(Profiles.of("local | test"))
                && !environment.acceptsProfiles(Profiles.of("prod"));
        return JwtKeyRing.fromConfig(properties.jwtKeys(), allowEphemeral);
    }

    @Bean
    JwtEncoder jwtEncoder(JwtKeyRing keyRing) {
        return DeviceJwts.encoder(keyRing);
    }

    @Bean
    JwtDecoder jwtDecoder(JwtKeyRing keyRing, Clock clock) {
        return DeviceJwts.decoder(keyRing, clock);
    }

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http, KnownDeviceCache knownDevices, JwtDecoder jwtDecoder, Environment environment)
            throws Exception {
        ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint();
        ProblemAccessDeniedHandler accessDenied = new ProblemAccessDeniedHandler();
        RequestMatcher managementPort = managementPortMatcher(environment);

        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Error dispatches carry the original failure; the original request was already authorized.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(PublicEndpoints.MATCHER).permitAll()
                        .requestMatchers(managementPort).permitAll()
                        .requestMatchers("/v1/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(PublicEndpoints.bearerTokenResolver())
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied)
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(new DeviceJwtAuthenticationConverter(knownDevices))));
        return http.build();
    }

    /**
     * Actuator runs on its own port that is never published or proxied (ADR-0015), but Spring Boot applies this
     * filter chain there too. Requests that arrived on that port are allowed; if the management port is not
     * separate, actuator stays denied rather than becoming public on the API port.
     */
    static RequestMatcher managementPortMatcher(Environment environment) {
        Integer managementPort = environment.getProperty("management.server.port", Integer.class);
        Integer serverPort = environment.getProperty("server.port", Integer.class, 8080);
        if (managementPort == null || managementPort <= 0 || managementPort.equals(serverPort)) {
            return request -> false;
        }
        int port = managementPort;
        return request -> request.getLocalPort() == port;
    }
}
