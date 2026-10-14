package com.mapmyroad.backend.integration.playintegrity;

/**
 * Accepts every token. Only for the {@code local} and {@code test} profiles, where there is no Play-signed build
 * (API_SPEC.md section 2: "never in production"). {@link PlayIntegrityConfig} refuses it under {@code prod}.
 */
public final class NoopPlayIntegrityVerifier implements PlayIntegrityVerifier {

    /** Same score a fully verified device gets, so local behavior matches production for genuine devices. */
    static final double TRUST_SCORE = IntegrityVerdictPolicy.TRUST_DEVICE_INTEGRITY;

    @Override
    public IntegrityVerdict verify(String integrityToken, String expectedNonce) {
        return IntegrityVerdict.accept(TRUST_SCORE, "NOT_ENFORCED");
    }
}
