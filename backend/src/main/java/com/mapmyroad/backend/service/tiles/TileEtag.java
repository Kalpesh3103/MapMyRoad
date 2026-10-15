package com.mapmyroad.backend.service.tiles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Weak ETags for tile bytes and the {@code If-None-Match} comparison (RFC 9110 sections 8.8.3 and 13.1.2).
 * Weak because the same data may be re-encoded with different bytes after an aggregation run, and a cache only
 * needs "semantically the same tile".
 */
public final class TileEtag {

    private TileEtag() {}

    /** {@code W/"<first 128 bits of SHA-256 in hex>"}. */
    public static String of(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return "W/\"" + HexFormat.of().formatHex(digest, 0, 16) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    /** ETag for a JSON document, see {@link #of(byte[])}. */
    public static String of(String text) {
        return of(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * True when {@code ifNoneMatch} (the raw header, possibly a list) matches {@code etag} with the weak comparison,
     * or is {@code *}.
     */
    public static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        String target = opaque(etag);
        for (String candidate : ifNoneMatch.split(",")) {
            String trimmed = candidate.trim();
            if (trimmed.equals("*") || opaque(trimmed).equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static String opaque(String tag) {
        return tag.startsWith("W/") ? tag.substring(2) : tag;
    }
}
