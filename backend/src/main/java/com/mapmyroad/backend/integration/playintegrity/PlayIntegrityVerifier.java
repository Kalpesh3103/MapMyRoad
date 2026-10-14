package com.mapmyroad.backend.integration.playintegrity;

/** Verifies a Play Integrity token produced by the app for a server-issued nonce (API_SPEC.md section 2). */
public interface PlayIntegrityVerifier {

    /**
     * @param integrityToken the token from the app, opaque to us
     * @param expectedNonce the challenge nonce the app was given and has just consumed
     * @return the verdict; a rejected verdict means 403 INTEGRITY_FAILED
     * @throws PlayIntegrityUnavailableException when Google cannot be reached or answers with a server-side error
     */
    IntegrityVerdict verify(String integrityToken, String expectedNonce);
}
