package com.mapmyroad.backend.integration.playintegrity;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The parts of the decoded Play Integrity verdict we use. Field names follow Google's
 * {@code decodeIntegrityToken} response ({@code tokenPayloadExternal}):
 *
 * <pre>
 * requestDetails.requestPackageName, requestDetails.nonce, requestDetails.timestampMillis
 * appIntegrity.appRecognitionVerdict            PLAY_RECOGNIZED | UNRECOGNIZED_VERSION | UNEVALUATED
 * deviceIntegrity.deviceRecognitionVerdict[]    MEETS_STRONG_INTEGRITY, MEETS_DEVICE_INTEGRITY,
 *                                               MEETS_BASIC_INTEGRITY, MEETS_VIRTUAL_INTEGRITY (may be empty)
 * accountDetails.appLicensingVerdict            LICENSED | UNLICENSED | UNEVALUATED (optional)
 * </pre>
 */
public record IntegrityPayload(
        String nonce,
        String requestPackageName,
        String appRecognitionVerdict,
        List<String> deviceRecognitionVerdicts,
        String appLicensingVerdict) {

    public IntegrityPayload {
        deviceRecognitionVerdicts = deviceRecognitionVerdicts == null ? List.of() : List.copyOf(deviceRecognitionVerdicts);
    }

    /** Reads {@code tokenPayloadExternal}. Missing fields become {@code null} (or an empty list) and fail the policy. */
    public static IntegrityPayload fromTokenPayloadExternal(JsonNode payload) {
        JsonNode requestDetails = payload.path("requestDetails");
        List<String> deviceVerdicts = new ArrayList<>();
        JsonNode verdictArray = payload.path("deviceIntegrity").path("deviceRecognitionVerdict");
        if (verdictArray.isArray()) {
            for (JsonNode verdict : verdictArray) {
                String value = text(verdict);
                if (value != null) {
                    deviceVerdicts.add(value);
                }
            }
        }
        return new IntegrityPayload(
                text(requestDetails.path("nonce")),
                text(requestDetails.path("requestPackageName")),
                text(payload.path("appIntegrity").path("appRecognitionVerdict")),
                deviceVerdicts,
                text(payload.path("accountDetails").path("appLicensingVerdict")));
    }

    private static String text(JsonNode node) {
        return node.isString() ? node.stringValue() : null;
    }

    @Override
    public String toString() {
        // The nonce stays out of logs.
        return "IntegrityPayload[app=" + appRecognitionVerdict + ", device=" + deviceRecognitionVerdicts + "]";
    }
}
