package com.mapmyroad.backend.service.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.config.DeviceProperties;
import com.mapmyroad.backend.domain.device.NewDevice;
import com.mapmyroad.backend.integration.playintegrity.IntegrityVerdict;
import com.mapmyroad.backend.integration.playintegrity.PlayIntegrityUnavailableException;
import com.mapmyroad.backend.integration.playintegrity.PlayIntegrityVerifier;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeviceRegistrationServiceTest {

    private final ChallengeNonceStore nonces = new ChallengeNonceStore(
            new DeviceProperties(false, null, null, null, null, null, null),
            new MutableClock(Instant.parse("2026-10-14T08:00:00Z")));
    private final PlayIntegrityVerifier verifier = mock(PlayIntegrityVerifier.class);
    private final DeviceTokenService tokens = mock(DeviceTokenService.class);
    private final DeviceRegistrationService service = new DeviceRegistrationService(nonces, verifier, tokens);

    private DeviceRegistrationService.Registration registration(String nonce) {
        return new DeviceRegistrationService.Registration("integrity-token", nonce, "1.0.3", 34);
    }

    @Test
    void challengeReturnsFreshNonceWithTtl() {
        DeviceRegistrationService.Challenge challenge = service.challenge();

        assertThat(challenge.nonce()).hasSize(43);
        assertThat(challenge.expiresInSec()).isEqualTo(300);
    }

    @Test
    void acceptedVerdictCreatesDeviceWithItsTrustScore() {
        String nonce = service.challenge().nonce();
        when(verifier.verify("integrity-token", nonce)).thenReturn(IntegrityVerdict.accept(0.3, "BASIC_INTEGRITY"));

        service.register(registration(nonce));

        verify(tokens).createDevice(new NewDevice("1.0.3", 34, 0.3));
    }

    @Test
    void unknownNonceFailsBeforeCallingGoogle() {
        assertCode(catchThrowable(() -> service.register(registration("never-issued"))), ErrorCode.INTEGRITY_FAILED);
        verifyNoInteractions(verifier, tokens);
    }

    @Test
    void nonceCannotBeReusedEvenAfterAFailedVerdict() {
        String nonce = service.challenge().nonce();
        when(verifier.verify(any(), any())).thenReturn(IntegrityVerdict.reject("APP_NOT_RECOGNIZED"));

        assertCode(catchThrowable(() -> service.register(registration(nonce))), ErrorCode.INTEGRITY_FAILED);
        assertCode(catchThrowable(() -> service.register(registration(nonce))), ErrorCode.INTEGRITY_FAILED);
        verifyNoInteractions(tokens);
    }

    @Test
    void upstreamFailureMapsTo503() {
        String nonce = service.challenge().nonce();
        when(verifier.verify(any(), any())).thenThrow(new PlayIntegrityUnavailableException("down"));

        assertCode(catchThrowable(() -> service.register(registration(nonce))), ErrorCode.UPSTREAM_UNAVAILABLE);
        verifyNoInteractions(tokens);
    }

    private static void assertCode(Throwable thrown, ErrorCode code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).code()).isEqualTo(code);
    }
}
