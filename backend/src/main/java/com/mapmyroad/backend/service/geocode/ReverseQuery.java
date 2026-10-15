package com.mapmyroad.backend.service.geocode;

/**
 * A validated reverse lookup.
 *
 * @param lang lowercase language code as requested, or {@code null}
 */
public record ReverseQuery(GeoPoint point, String lang) {}
