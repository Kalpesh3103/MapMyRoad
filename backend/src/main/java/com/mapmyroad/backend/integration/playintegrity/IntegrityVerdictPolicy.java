package com.mapmyroad.backend.integration.playintegrity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Turns a decoded verdict into accept or reject plus an initial trust score (SECURITY.md section 5: weaker device
 * verdicts get a lower trust score rather than a lockout). The scores are initial values to tune, not measurements.
 */
public final class IntegrityVerdictPolicy {

    static final double TRUST_DEVICE_INTEGRITY = 0.5;
    static final double TRUST_BASIC_INTEGRITY = 0.3;

    private static final String PLAY_RECOGNIZED = "PLAY_RECOGNIZED";
    private static final String MEETS_STRONG = "MEETS_STRONG_INTEGRITY";
    private static final String MEETS_DEVICE = "MEETS_DEVICE_INTEGRITY";
    private static final String MEETS_BASIC = "MEETS_BASIC_INTEGRITY";

    private final String expectedPackageName;

    public IntegrityVerdictPolicy(String expectedPackageName) {
        this.expectedPackageName = expectedPackageName;
    }

    public IntegrityVerdict evaluate(IntegrityPayload payload, String expectedNonce) {
        if (!nonceMatches(payload.nonce(), expectedNonce)) {
            return IntegrityVerdict.reject("NONCE_MISMATCH");
        }
        if (!expectedPackageName.equals(payload.requestPackageName())) {
            return IntegrityVerdict.reject("PACKAGE_MISMATCH");
        }
        if (!PLAY_RECOGNIZED.equals(payload.appRecognitionVerdict())) {
            return IntegrityVerdict.reject("APP_NOT_RECOGNIZED");
        }
        if (payload.deviceRecognitionVerdicts().isEmpty()) {
            return IntegrityVerdict.reject("NO_DEVICE_INTEGRITY");
        }
        if (payload.deviceRecognitionVerdicts().contains(MEETS_STRONG)
                || payload.deviceRecognitionVerdicts().contains(MEETS_DEVICE)) {
            return IntegrityVerdict.accept(TRUST_DEVICE_INTEGRITY, "DEVICE_INTEGRITY");
        }
        if (payload.deviceRecognitionVerdicts().contains(MEETS_BASIC)) {
            return IntegrityVerdict.accept(TRUST_BASIC_INTEGRITY, "BASIC_INTEGRITY");
        }
        // For example MEETS_VIRTUAL_INTEGRITY only (an emulator): not a phone that can drive.
        // accountDetails.appLicensingVerdict is parsed but deliberately unused: sideloaded or family-shared installs
        // should not be locked out. Revisit if abuse appears (ADR-0007).
        return IntegrityVerdict.reject("NO_DEVICE_INTEGRITY");
    }

    /**
     * Both values are Base64url strings; Google echoes the nonce as the app sent it, but trailing padding may differ.
     * Constant-time comparison, although the nonce is not a long-lived secret.
     */
    static boolean nonceMatches(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        byte[] a = stripPadding(actual).getBytes(StandardCharsets.US_ASCII);
        byte[] e = stripPadding(expected).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(a, e);
    }

    private static String stripPadding(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '=') {
            end--;
        }
        return value.substring(0, end);
    }
}
