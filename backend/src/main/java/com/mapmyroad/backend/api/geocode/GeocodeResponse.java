package com.mapmyroad.backend.api.geocode;

import com.mapmyroad.backend.service.geocode.GeocodeLookup;
import java.util.List;

/**
 * Body of {@code GET /v1/geocode} and {@code GET /v1/geocode/reverse} (API_SPEC.md section 5.7). Reverse uses the
 * same shape with at most one entry, so the app parses both the same way.
 *
 * @param cached true when the answer came from the server cache
 */
public record GeocodeResponse(List<GeocodePlace> results, boolean cached) {

    static GeocodeResponse from(GeocodeLookup lookup) {
        return new GeocodeResponse(lookup.results().stream().map(GeocodePlace::from).toList(), lookup.cached());
    }
}
