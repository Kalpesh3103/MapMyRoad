package com.mapmyroad.backend.integration.playintegrity;

import com.mapmyroad.backend.config.DeviceProperties;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Chooses the {@link PlayIntegrityVerifier}. Enforcement is configuration (CODING_STANDARDS.md section 4.7), with a
 * guard so the {@code prod} profile can never start with it off.
 */
@Configuration(proxyBeanMethods = false)
public class PlayIntegrityConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(PlayIntegrityConfig.class);

    @Bean
    PlayIntegrityVerifier playIntegrityVerifier(DeviceProperties properties, Environment environment, Clock clock) {
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        if (!properties.integrityEnforced()) {
            return notEnforced(prod);
        }
        String file = properties.playIntegrityServiceAccountFile();
        if (file == null || file.isBlank()) {
            throw new IllegalStateException(
                    "mmr.device.play-integrity-service-account-file (env PLAY_INTEGRITY_SA_FILE) is required"
                            + " when Play Integrity is enforced");
        }
        GoogleServiceAccount account = GoogleServiceAccount.load(Path.of(file));
        RestClient restClient = googleRestClient();
        return new GooglePlayIntegrityVerifier(
                restClient, new GoogleAccessTokenProvider(account, restClient, clock), properties.packageName());
    }

    /** Fails fast under {@code prod}; elsewhere returns the accept-all verifier and says so loudly. */
    static PlayIntegrityVerifier notEnforced(boolean prodProfileActive) {
        if (prodProfileActive) {
            throw new IllegalStateException("mmr.device.integrity-enforced must be true in the prod profile");
        }
        log.warn("Play Integrity is NOT enforced. Every registration is accepted. Development and tests only.");
        return new NoopPlayIntegrityVerifier();
    }

    /** Dedicated client with bounded timeouts so a slow Google never ties up request threads. */
    static RestClient googleRestClient() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
