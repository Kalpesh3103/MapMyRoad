package com.mapmyroad.backend.integration.playintegrity;

/**
 * Outcome of checking a Play Integrity token.
 *
 * @param accepted whether the device may register
 * @param trustScore initial {@code device.trust_score} (0 to 1) when accepted, 0 otherwise
 * @param reason short, stable code for logs and metrics (never the verdict payload)
 */
public record IntegrityVerdict(boolean accepted, double trustScore, String reason) {

    public static IntegrityVerdict accept(double trustScore, String reason) {
        return new IntegrityVerdict(true, trustScore, reason);
    }

    public static IntegrityVerdict reject(String reason) {
        return new IntegrityVerdict(false, 0.0, reason);
    }
}
