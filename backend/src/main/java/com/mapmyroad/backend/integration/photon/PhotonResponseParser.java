package com.mapmyroad.backend.integration.photon;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses Photon's GeoJSON FeatureCollection ({@code features[].geometry.coordinates = [lon, lat]} and
 * {@code features[].properties}). Features without usable coordinates or without any name or address part are
 * skipped instead of failing the whole answer.
 */
public final class PhotonResponseParser {

    private PhotonResponseParser() {}

    /** Throws {@link PhotonUnavailableException} (MALFORMED) when the body is not a FeatureCollection. */
    public static List<PhotonPlace> parse(String body) {
        JsonNode root;
        try {
            root = JsonMapper.shared().readTree(body == null ? "" : body);
        } catch (JacksonException e) {
            throw new PhotonUnavailableException(
                    PhotonUnavailableException.Reason.MALFORMED, "Photon returned malformed JSON");
        }
        JsonNode features = root == null ? null : root.path("features");
        if (features == null || !features.isArray()) {
            throw new PhotonUnavailableException(
                    PhotonUnavailableException.Reason.MALFORMED, "Photon returned no features array");
        }
        List<PhotonPlace> places = new ArrayList<>(features.size());
        for (int i = 0; i < features.size(); i++) {
            PhotonPlace place = toPlace(features.path(i));
            if (place != null) {
                places.add(place);
            }
        }
        return places;
    }

    private static PhotonPlace toPlace(JsonNode feature) {
        JsonNode coordinates = feature.path("geometry").path("coordinates");
        if (!coordinates.isArray() || coordinates.size() < 2) {
            return null;
        }
        JsonNode lonNode = coordinates.path(0);
        JsonNode latNode = coordinates.path(1);
        if (!lonNode.isNumber() || !latNode.isNumber()) {
            return null;
        }
        double lon = lonNode.doubleValue();
        double lat = latNode.doubleValue();
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            return null;
        }
        JsonNode properties = feature.path("properties");
        String displayName = displayName(properties);
        if (displayName.isEmpty()) {
            return null;
        }
        String name = text(properties, "name");
        if (name == null) {
            int comma = displayName.indexOf(", ");
            name = comma < 0 ? displayName : displayName.substring(0, comma);
        }
        String type = text(properties, "osm_value");
        if (type == null) {
            type = text(properties, "type");
        }
        return new PhotonPlace(name, displayName, lat, lon, type);
    }

    /**
     * Unique non-empty parts in this order: name, street with house number, district (or locality), city, state.
     * Country is left out because the service covers one region. Duplicates are compared case-insensitively, so a
     * city feature named "Pune" reads "Pune, Maharashtra".
     */
    static String displayName(JsonNode properties) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> parts = new ArrayList<>(5);
        addPart(parts, seen, text(properties, "name"));
        addPart(parts, seen, street(properties));
        String district = text(properties, "district");
        addPart(parts, seen, district != null ? district : text(properties, "locality"));
        addPart(parts, seen, text(properties, "city"));
        addPart(parts, seen, text(properties, "state"));
        return String.join(", ", parts);
    }

    private static String street(JsonNode properties) {
        String street = text(properties, "street");
        String houseNumber = text(properties, "housenumber");
        if (street == null) {
            return null;
        }
        return houseNumber == null ? street : street + " " + houseNumber;
    }

    private static void addPart(List<String> parts, Set<String> seen, String part) {
        if (part != null && seen.add(part.toLowerCase(Locale.ROOT))) {
            parts.add(part);
        }
    }

    /** Trimmed string member, or {@code null} when missing, not a string or blank. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) {
            return null;
        }
        String text = value.stringValue().trim();
        return text.isEmpty() ? null : text;
    }
}
