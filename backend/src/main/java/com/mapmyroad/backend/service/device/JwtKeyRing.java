package com.mapmyroad.backend.service.device;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HMAC keys for access tokens, newest first (SECURITY.md sections 5 and 10, DEPLOYMENT.md section 12.7).
 * The first key signs; every key verifies, so a new key can be put in front of the old one and the old one removed
 * once all tokens signed with it have expired.
 */
public final class JwtKeyRing {

    /** HS256 needs a key at least as long as the hash output (RFC 7518 section 3.2). */
    public static final int MIN_SECRET_BYTES = 32;

    private static final Logger log = LoggerFactory.getLogger(JwtKeyRing.class);
    private static final Pattern KID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final List<Key> keys;

    private JwtKeyRing(List<Key> keys) {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("At least one JWT key is required");
        }
        this.keys = List.copyOf(keys);
    }

    /** A key ID and its secret. The secret array is never exposed outside this package. */
    public record Key(String kid, byte[] secret) {

        public Key {
            secret = secret.clone();
        }

        @Override
        public byte[] secret() {
            return secret.clone();
        }

        @Override
        public String toString() {
            // Never print the secret.
            return "Key[kid=" + kid + "]";
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key k && k.kid.equals(kid) && Arrays.equals(k.secret, secret);
        }

        @Override
        public int hashCode() {
            return kid.hashCode();
        }
    }

    /**
     * Builds the ring from configuration.
     *
     * @param raw value of {@code mmr.device.jwt-keys}, may be blank
     * @param allowEphemeral when {@code raw} is blank, generate a random key that lives only as long as this process
     *     ({@code local} and {@code test} profiles). Otherwise a blank value fails startup.
     */
    public static JwtKeyRing fromConfig(String raw, boolean allowEphemeral) {
        if (raw == null || raw.isBlank()) {
            if (!allowEphemeral) {
                throw new IllegalStateException(
                        "mmr.device.jwt-keys (env JWT_KEYS) is required, format kid:base64Secret[,kid:base64Secret]");
            }
            log.warn("No JWT keys configured; using an ephemeral signing key. Tokens become invalid on restart.");
            byte[] secret = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(secret);
            return new JwtKeyRing(List.of(new Key("ephemeral", secret)));
        }
        return parse(raw);
    }

    /** Parses {@code kid1:base64Secret,kid2:base64Secret}. Accepts standard and URL-safe Base64. */
    public static JwtKeyRing parse(String raw) {
        List<Key> keys = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                throw new IllegalStateException("mmr.device.jwt-keys entry must be kid:base64Secret");
            }
            String kid = trimmed.substring(0, colon).trim();
            if (!KID.matcher(kid).matches()) {
                throw new IllegalStateException("mmr.device.jwt-keys has an invalid kid (allowed: A-Z a-z 0-9 . _ -)");
            }
            if (!seen.add(kid)) {
                throw new IllegalStateException("mmr.device.jwt-keys has a duplicate kid: " + kid);
            }
            byte[] secret = decode(trimmed.substring(colon + 1).trim(), kid);
            if (secret.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException(
                        "mmr.device.jwt-keys secret for kid " + kid + " is shorter than 256 bits");
            }
            keys.add(new Key(kid, secret));
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException("mmr.device.jwt-keys contains no keys");
        }
        return new JwtKeyRing(keys);
    }

    private static byte[] decode(String value, String kid) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException notStandard) {
            try {
                return Base64.getUrlDecoder().decode(value);
            } catch (IllegalArgumentException notUrlSafe) {
                // The message names the kid only, never the value.
                throw new IllegalStateException("mmr.device.jwt-keys secret for kid " + kid + " is not valid Base64");
            }
        }
    }

    /** The key that signs new tokens. */
    public Key signingKey() {
        return keys.get(0);
    }

    /** All keys accepted for verification, signing key first. */
    public List<Key> verificationKeys() {
        return keys;
    }
}
