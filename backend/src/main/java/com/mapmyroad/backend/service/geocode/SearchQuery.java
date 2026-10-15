package com.mapmyroad.backend.service.geocode;

/**
 * A validated forward search.
 *
 * @param text trimmed search text with whitespace runs collapsed, 3 to 200 characters
 * @param limit 1 to 10
 * @param lang lowercase language code as requested, or {@code null}; dropped later when Photon does not support it
 * @param bias optional bias point, {@code null} when not given
 */
public record SearchQuery(String text, int limit, String lang, GeoPoint bias) {

    @Override
    public String toString() {
        return "SearchQuery[text=***, limit=" + limit + ", lang=" + lang + ", bias=" + (bias == null ? "none" : "***")
                + "]";
    }
}
