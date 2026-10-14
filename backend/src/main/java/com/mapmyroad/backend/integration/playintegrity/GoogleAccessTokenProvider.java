package com.mapmyroad.backend.integration.playintegrity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Obtains a Google OAuth2 access token for the Play Integrity API with the service-account JWT bearer flow
 * (RFC 7523), signing the assertion with Nimbus, which the resource-server starter already brings in. This avoids
 * adding the Google auth library. Tokens are cached until shortly before they expire.
 */
public class GoogleAccessTokenProvider {

    static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    static final String SCOPE = "https://www.googleapis.com/auth/playintegrity";
    static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";

    /** Google rejects assertions valid for more than one hour. */
    private static final Duration ASSERTION_TTL = Duration.ofHours(1);
    /** Refresh this long before expiry so a token never expires in flight. */
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    private final GoogleServiceAccount account;
    private final RestClient restClient;
    private final Clock clock;

    /**
     * Not {@code synchronized}: the fetch is a blocking HTTP call, and on Java 21 a virtual thread blocked inside a
     * monitor pins its carrier thread ({@code spring.threads.virtual.enabled}). A lock does not.
     */
    private final ReentrantLock lock = new ReentrantLock();

    private String cachedToken;
    private Instant cachedUntil = Instant.MIN;

    public GoogleAccessTokenProvider(GoogleServiceAccount account, RestClient restClient, Clock clock) {
        this.account = account;
        this.restClient = restClient;
        this.clock = clock;
    }

    /** Returns a valid access token, fetching a new one when needed. Only one fetch runs at a time. */
    public String accessToken() {
        lock.lock();
        try {
            return accessTokenLocked();
        } finally {
            lock.unlock();
        }
    }

    private String accessTokenLocked() {
        Instant now = clock.instant();
        if (cachedToken != null && now.isBefore(cachedUntil)) {
            return cachedToken;
        }
        String response;
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", GRANT_TYPE);
            form.add("assertion", signedAssertion(now));
            response = restClient.post()
                    .uri(TOKEN_URI)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw new PlayIntegrityUnavailableException("Google token endpoint failed", e);
        }
        JsonNode json;
        try {
            json = JsonMapper.shared().readTree(response == null ? "" : response);
        } catch (JacksonException e) {
            throw new PlayIntegrityUnavailableException("Google token endpoint returned malformed JSON");
        }
        String token = json.path("access_token").stringValue(null);
        long expiresInSec = json.path("expires_in").asLong(0);
        if (token == null || expiresInSec <= 0) {
            throw new PlayIntegrityUnavailableException("Google token endpoint returned no access token");
        }
        cachedToken = token;
        cachedUntil = now.plusSeconds(expiresInSec).minus(REFRESH_MARGIN);
        return token;
    }

    String signedAssertion(Instant now) {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(account.privateKeyId())
                .build();
        // Nimbus uses java.util.Date for JWT times; this is the only place Date appears.
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(account.clientEmail())
                .audience(TOKEN_URI)
                .claim("scope", SCOPE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ASSERTION_TTL)))
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new RSASSASigner(account.privateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign the Google service-account assertion", e);
        }
        return jwt.serialize();
    }
}
