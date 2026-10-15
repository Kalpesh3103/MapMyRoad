package com.mapmyroad.backend.service.geocode;

import java.util.List;

/**
 * Answer of a search or reverse lookup.
 *
 * @param cached true when served from the memory or database cache without calling Photon
 */
public record GeocodeLookup(List<GeocodeResult> results, boolean cached) {

    public GeocodeLookup {
        results = List.copyOf(results);
    }
}
