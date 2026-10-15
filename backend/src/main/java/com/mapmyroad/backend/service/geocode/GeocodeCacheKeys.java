package com.mapmyroad.backend.service.geocode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cache keys for {@code geocode_cache.cache_key} and the in-memory cache.
 *
 * <p>A key is the SHA-256 hex of a normalized description of the upstream request, for example
 * {@code search|v1|q=shaniwar wada|lang=en|limit=5|bias=18.52,73.86} or {@code reverse|v1|lang=|at=18.5204,73.8567}:
 * <ul>
 *   <li>Search text is Unicode NFKC-normalized, lowercased, trimmed, with control characters and whitespace runs
 *       collapsed to one space, so "  Shaniwar   WADA" and "shaniwar wada" share an entry.</li>
 *   <li>The search bias is rounded to 2 decimals (about 1 km). Nearby users share entries and the exact user
 *       location is never part of the key. The same rounded point is what is sent to Photon, so the key fully
 *       describes the upstream request.</li>
 *   <li>Reverse coordinates are rounded to 4 decimals (about 11 m), enough to name a dropped pin.</li>
 *   <li>{@code lang} is the language actually sent to Photon (empty when omitted).</li>
 * </ul>
 * Hashing keeps the table free of readable search text. It is not anonymization: common queries could be guessed by
 * hashing candidates, but the table holds no device link, so a key says only that someone searched for something.
 * Bump {@code v1} when the key material or the stored response shape changes.
 */
public final class GeocodeCacheKeys {

    static final String VERSION = "v1";

    /** Search bias precision: 2 decimals, about 1.1 km of latitude. */
    static final int BIAS_DECIMALS = 2;

    /** Reverse lookup precision: 4 decimals, about 11 m of latitude. */
    static final int REVERSE_DECIMALS = 4;

    private static final Pattern SPACE_RUNS = Pattern.compile("[\\s\\p{Cntrl}\\p{Z}]+");

    private GeocodeCacheKeys() {}

    /** NFKC, control characters and whitespace runs to one space, trimmed. Case is kept. */
    public static String collapse(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        return SPACE_RUNS.matcher(normalized).replaceAll(" ").trim();
    }

    /** {@link #collapse} plus lowercasing, the form used in the key and sent to Photon. */
    public static String normalizeQuery(String text) {
        return collapse(text).toLowerCase(Locale.ROOT);
    }

    /** {@code value} rounded half-up to {@code decimals} places. */
    public static double round(double value, int decimals) {
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * @param normalizedQuery output of {@link #normalizeQuery}
     * @param lang language sent to Photon, or {@code null}
     * @param roundedBias bias already rounded with {@link #BIAS_DECIMALS}, or {@code null}
     */
    static String searchMaterial(String normalizedQuery, String lang, int limit, GeoPoint roundedBias) {
        String bias = roundedBias == null
                ? ""
                : format(roundedBias.lat(), BIAS_DECIMALS) + "," + format(roundedBias.lon(), BIAS_DECIMALS);
        return "search|" + VERSION + "|q=" + normalizedQuery + "|lang=" + nullToEmpty(lang) + "|limit=" + limit
                + "|bias=" + bias;
    }

    /** @param roundedPoint point already rounded with {@link #REVERSE_DECIMALS} */
    static String reverseMaterial(GeoPoint roundedPoint, String lang) {
        return "reverse|" + VERSION + "|lang=" + nullToEmpty(lang) + "|at="
                + format(roundedPoint.lat(), REVERSE_DECIMALS) + "," + format(roundedPoint.lon(), REVERSE_DECIMALS);
    }

    static String searchKey(String normalizedQuery, String lang, int limit, GeoPoint roundedBias) {
        return sha256Hex(searchMaterial(normalizedQuery, lang, limit, roundedBias));
    }

    static String reverseKey(GeoPoint roundedPoint, String lang) {
        return sha256Hex(reverseMaterial(roundedPoint, lang));
    }

    static String sha256Hex(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    /** Fixed scale so 18.5 and 18.50 give the same text; adding 0.0 turns -0.0 into 0.0. */
    private static String format(double value, int decimals) {
        return BigDecimal.valueOf(value + 0.0).setScale(decimals, RoundingMode.HALF_UP).toPlainString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
