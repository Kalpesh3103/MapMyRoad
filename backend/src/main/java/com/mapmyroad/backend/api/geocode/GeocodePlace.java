package com.mapmyroad.backend.api.geocode;

import com.mapmyroad.backend.service.geocode.GeocodeResult;

/** One entry of {@code results} (API_SPEC.md section 5.7). */
public record GeocodePlace(String name, String displayName, double lat, double lon, String type) {

    static GeocodePlace from(GeocodeResult result) {
        return new GeocodePlace(result.name(), result.displayName(), result.lat(), result.lon(), result.type());
    }
}
