package com.mapmyroad.backend.integration.playintegrity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Verdict mapping for each Play Integrity case, fed with parsed payloads (no HTTP). */
class IntegrityVerdictPolicyTest {

    private static final String NONCE = "Yk3xQ2h0c1R0ZXN0Tm9uY2VWYWx1ZUZvclRlc3RzMTI";
    private static final String PACKAGE = "com.mapmyroad";

    private final IntegrityVerdictPolicy policy = new IntegrityVerdictPolicy(PACKAGE);

    private static IntegrityPayload payload(String nonce, String pkg, String app, List<String> device) {
        return new IntegrityPayload(nonce, pkg, app, device, "LICENSED");
    }

    @Test
    void deviceIntegrityIsAcceptedWithFullTrust() {
        IntegrityVerdict verdict = policy.evaluate(
                payload(NONCE, PACKAGE, "PLAY_RECOGNIZED", List.of("MEETS_DEVICE_INTEGRITY")), NONCE);

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.trustScore()).isEqualTo(0.5);
    }

    @Test
    void strongIntegrityIsAcceptedWithFullTrust() {
        IntegrityVerdict verdict = policy.evaluate(payload(NONCE, PACKAGE, "PLAY_RECOGNIZED",
                List.of("MEETS_BASIC_INTEGRITY", "MEETS_DEVICE_INTEGRITY", "MEETS_STRONG_INTEGRITY")), NONCE);

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.trustScore()).isEqualTo(0.5);
    }

    @Test
    void basicIntegrityOnlyIsAcceptedWithLowerTrust() {
        IntegrityVerdict verdict = policy.evaluate(
                payload(NONCE, PACKAGE, "PLAY_RECOGNIZED", List.of("MEETS_BASIC_INTEGRITY")), NONCE);

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.trustScore()).isEqualTo(0.3);
    }

    @Test
    void emptyDeviceVerdictIsRejected() {
        IntegrityVerdict verdict = policy.evaluate(payload(NONCE, PACKAGE, "PLAY_RECOGNIZED", List.of()), NONCE);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("NO_DEVICE_INTEGRITY");
    }

    @Test
    void virtualIntegrityOnlyIsRejected() {
        IntegrityVerdict verdict = policy.evaluate(
                payload(NONCE, PACKAGE, "PLAY_RECOGNIZED", List.of("MEETS_VIRTUAL_INTEGRITY")), NONCE);

        assertThat(verdict.accepted()).isFalse();
    }

    @Test
    void unrecognizedAppIsRejected() {
        for (String app : new String[] {"UNRECOGNIZED_VERSION", "UNEVALUATED", null}) {
            IntegrityVerdict verdict =
                    policy.evaluate(payload(NONCE, PACKAGE, app, List.of("MEETS_DEVICE_INTEGRITY")), NONCE);

            assertThat(verdict.accepted()).isFalse();
            assertThat(verdict.reason()).isEqualTo("APP_NOT_RECOGNIZED");
        }
    }

    @Test
    void nonceMismatchIsRejected() {
        IntegrityVerdict verdict = policy.evaluate(
                payload("other-nonce", PACKAGE, "PLAY_RECOGNIZED", List.of("MEETS_DEVICE_INTEGRITY")), NONCE);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("NONCE_MISMATCH");
    }

    @Test
    void missingNonceIsRejected() {
        IntegrityVerdict verdict = policy.evaluate(
                payload(null, PACKAGE, "PLAY_RECOGNIZED", List.of("MEETS_DEVICE_INTEGRITY")), NONCE);

        assertThat(verdict.accepted()).isFalse();
    }

    @Test
    void nonceComparisonIgnoresBase64Padding() {
        IntegrityVerdict verdict = policy.evaluate(
                payload(NONCE + "=", PACKAGE, "PLAY_RECOGNIZED", List.of("MEETS_DEVICE_INTEGRITY")), NONCE);

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    void packageMismatchIsRejected() {
        IntegrityVerdict verdict = policy.evaluate(
                payload(NONCE, "com.example.clone", "PLAY_RECOGNIZED", List.of("MEETS_DEVICE_INTEGRITY")), NONCE);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).isEqualTo("PACKAGE_MISMATCH");
    }
}
