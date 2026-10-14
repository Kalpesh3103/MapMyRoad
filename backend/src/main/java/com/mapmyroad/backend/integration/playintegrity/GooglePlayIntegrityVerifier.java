package com.mapmyroad.backend.integration.playintegrity;

import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies integrity tokens with Google's server-side decode endpoint
 * ({@code POST https://playintegrity.googleapis.com/v1/{packageName}:decodeIntegrityToken}) and applies
 * {@link IntegrityVerdictPolicy}. Logs carry reason codes only, never tokens, nonces or the verdict payload.
 */
public class GooglePlayIntegrityVerifier implements PlayIntegrityVerifier {

    static final String API_BASE = "https://playintegrity.googleapis.com/v1/";

    private static final Logger log = LoggerFactory.getLogger(GooglePlayIntegrityVerifier.class);

    private final RestClient restClient;
    private final GoogleAccessTokenProvider tokenProvider;
    private final IntegrityVerdictPolicy policy;
    private final URI decodeUri;

    public GooglePlayIntegrityVerifier(
            RestClient restClient, GoogleAccessTokenProvider tokenProvider, String packageName) {
        if (!packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
            throw new IllegalStateException("mmr.device.package-name is not a valid Android application ID");
        }
        this.restClient = restClient;
        this.tokenProvider = tokenProvider;
        this.policy = new IntegrityVerdictPolicy(packageName);
        // Built once from a validated value, so no template expansion or encoding questions.
        this.decodeUri = URI.create(API_BASE + packageName + ":decodeIntegrityToken");
    }

    @Override
    public IntegrityVerdict verify(String integrityToken, String expectedNonce) {
        String requestBody = JsonMapper.shared().writeValueAsString(Map.of("integrity_token", integrityToken));
        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri(decodeUri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(tokenProvider.accessToken()))
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 400) {
                // Google answers 400 INVALID_ARGUMENT for tokens it cannot decode: the client's fault, not ours.
                log.info("Play Integrity rejected the token (HTTP 400)");
                return IntegrityVerdict.reject("TOKEN_REJECTED");
            }
            log.warn("Play Integrity decode failed with HTTP {}", e.getStatusCode().value());
            throw new PlayIntegrityUnavailableException("Play Integrity decode failed", e);
        } catch (RestClientException e) {
            log.warn("Play Integrity decode unreachable: {}", e.getClass().getSimpleName());
            throw new PlayIntegrityUnavailableException("Play Integrity decode unreachable", e);
        }
        return evaluate(responseBody, expectedNonce);
    }

    IntegrityVerdict evaluate(String responseBody, String expectedNonce) {
        JsonNode root;
        try {
            root = JsonMapper.shared().readTree(responseBody == null ? "" : responseBody);
        } catch (JacksonException e) {
            throw new PlayIntegrityUnavailableException("Play Integrity decode returned malformed JSON");
        }
        JsonNode payload = root.path("tokenPayloadExternal");
        if (!payload.isObject()) {
            throw new PlayIntegrityUnavailableException("Play Integrity decode returned no tokenPayloadExternal");
        }
        IntegrityVerdict verdict = policy.evaluate(IntegrityPayload.fromTokenPayloadExternal(payload), expectedNonce);
        if (!verdict.accepted()) {
            log.info("Play Integrity verdict rejected: {}", verdict.reason());
        }
        return verdict;
    }
}
