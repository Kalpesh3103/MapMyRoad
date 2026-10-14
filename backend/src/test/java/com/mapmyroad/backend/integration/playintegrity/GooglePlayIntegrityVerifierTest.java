package com.mapmyroad.backend.integration.playintegrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Parsing of the decodeIntegrityToken response shape, without HTTP. */
class GooglePlayIntegrityVerifierTest {

    private static final String NONCE = "Yk3xQ2h0c1R0ZXN0Tm9uY2VWYWx1ZUZvclRlc3RzMTI";

    private final GooglePlayIntegrityVerifier verifier = new GooglePlayIntegrityVerifier(
            RestClient.builder().build(), mock(GoogleAccessTokenProvider.class), "com.mapmyroad");

    private static String response(String deviceVerdicts) {
        return """
                {
                  "tokenPayloadExternal": {
                    "requestDetails": {
                      "requestPackageName": "com.mapmyroad",
                      "timestampMillis": "1760428800000",
                      "nonce": "%s"
                    },
                    "appIntegrity": {
                      "appRecognitionVerdict": "PLAY_RECOGNIZED",
                      "packageName": "com.mapmyroad",
                      "certificateSha256Digest": ["abc"],
                      "versionCode": "42"
                    },
                    "deviceIntegrity": { "deviceRecognitionVerdict": [%s] },
                    "accountDetails": { "appLicensingVerdict": "LICENSED" }
                  }
                }
                """.formatted(NONCE, deviceVerdicts);
    }

    @Test
    void parsesAcceptedVerdict() {
        IntegrityVerdict verdict = verifier.evaluate(response("\"MEETS_DEVICE_INTEGRITY\""), NONCE);

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.trustScore()).isEqualTo(0.5);
    }

    @Test
    void parsesBasicOnlyVerdict() {
        IntegrityVerdict verdict = verifier.evaluate(response("\"MEETS_BASIC_INTEGRITY\""), NONCE);

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.trustScore()).isEqualTo(0.3);
    }

    @Test
    void parsesEmptyDeviceVerdictAsRejected() {
        assertThat(verifier.evaluate(response(""), NONCE).accepted()).isFalse();
    }

    @Test
    void wrongNonceInResponseIsRejected() {
        assertThat(verifier.evaluate(response("\"MEETS_DEVICE_INTEGRITY\""), "another").accepted()).isFalse();
    }

    @Test
    void malformedResponseIsAnUpstreamFailure() {
        assertThatThrownBy(() -> verifier.evaluate("not json", NONCE))
                .isInstanceOf(PlayIntegrityUnavailableException.class);
        assertThatThrownBy(() -> verifier.evaluate("{}", NONCE))
                .isInstanceOf(PlayIntegrityUnavailableException.class);
    }

    @Test
    void rejectsInvalidPackageNameAtStartup() {
        assertThatThrownBy(() -> new GooglePlayIntegrityVerifier(
                        RestClient.builder().build(), mock(GoogleAccessTokenProvider.class), "com/evil"))
                .isInstanceOf(IllegalStateException.class);
    }
}
