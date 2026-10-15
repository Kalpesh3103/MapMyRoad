package com.mapmyroad.backend.service.geocode;

/**
 * One place in a geocoding answer (API_SPEC.md section 5.7). Also the shape stored in {@code geocode_cache.response}.
 *
 * @param type OSM value such as {@code attraction}; may be {@code null}
 */
public record GeocodeResult(String name, String displayName, double lat, double lon, String type) {

    @Override
    public String toString() {
        return "GeocodeResult[name=" + name + ", type=" + type + ", lat=***, lon=***]";
    }
}
