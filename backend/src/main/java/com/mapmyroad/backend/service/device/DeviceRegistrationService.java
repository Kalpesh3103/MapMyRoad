package com.mapmyroad.backend.service.device;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.domain.device.DeviceTokens;
import com.mapmyroad.backend.domain.device.NewDevice;
import com.mapmyroad.backend.integration.playintegrity.IntegrityVerdict;
import com.mapmyroad.backend.integration.playintegrity.PlayIntegrityUnavailableException;
import com.mapmyroad.backend.integration.playintegrity.PlayIntegrityVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Challenge and registration flow (API_SPEC.md sections 2 and 5.2). Deliberately not transactional: the Play
 * Integrity call is a network round trip and must not hold a database transaction open. Only the final insert runs
 * in a transaction ({@link DeviceTokenService#createDevice}).
 */
@Service
public class DeviceRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistrationService.class);

    private final ChallengeNonceStore nonces;
    private final PlayIntegrityVerifier integrityVerifier;
    private final DeviceTokenService tokens;

    public DeviceRegistrationService(
            ChallengeNonceStore nonces, PlayIntegrityVerifier integrityVerifier, DeviceTokenService tokens) {
        this.nonces = nonces;
        this.integrityVerifier = integrityVerifier;
        this.tokens = tokens;
    }

    /** A new single-use challenge. */
    public Challenge challenge() {
        return new Challenge(nonces.issue(), nonces.ttl().toSeconds());
    }

    /**
     * Consumes the nonce first, so a nonce can be tried once even if verification fails (no retry with the same
     * nonce, which would let a captured integrity token be replayed).
     */
    public DeviceTokens register(Registration registration) {
        if (!nonces.consume(registration.nonce())) {
            throw new ApiException(ErrorCode.INTEGRITY_FAILED, "Challenge expired or already used.");
        }
        IntegrityVerdict verdict;
        try {
            verdict = integrityVerifier.verify(registration.integrityToken(), registration.nonce());
        } catch (PlayIntegrityUnavailableException e) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Integrity service unavailable. Retry later.");
        }
        if (!verdict.accepted()) {
            log.info("Device registration refused: {}", verdict.reason());
            throw new ApiException(ErrorCode.INTEGRITY_FAILED, "Integrity verdict not acceptable.");
        }
        return tokens.createDevice(
                new NewDevice(registration.appVersion(), registration.osApiLevel(), verdict.trustScore()));
    }

    /** A challenge nonce and its lifetime. */
    public record Challenge(String nonce, long expiresInSec) {}

    /** Validated registration input (validation happens on the API DTO). */
    public record Registration(String integrityToken, String nonce, String appVersion, int osApiLevel) {

        @Override
        public String toString() {
            return "Registration[appVersion=" + appVersion + ", osApiLevel=" + osApiLevel + "]";
        }
    }
}
