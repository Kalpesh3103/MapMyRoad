package com.mapmyroad.backend.service.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Random opaque values (refresh tokens, challenge nonces) and the hash used to store refresh tokens.
 * 256 random bits make a token unguessable, so an unsalted SHA-256 is enough to keep a database leak from
 * revealing usable tokens (SECURITY.md section 5); a slow password hash would only add latency.
 */
public final class SecureTokens {

    public static final int RANDOM_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private SecureTokens() {}

    /** 32 random bytes, Base64url without padding (43 characters). Safe as a Play Integrity nonce. */
    public static String randomToken() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return BASE64URL.encodeToString(bytes);
    }

    /** SHA-256 of the token's UTF-8 bytes, as stored in {@code device_refresh_token.token_hash}. */
    public static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform must provide SHA-256 (MessageDigest Javadoc), so this cannot happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
