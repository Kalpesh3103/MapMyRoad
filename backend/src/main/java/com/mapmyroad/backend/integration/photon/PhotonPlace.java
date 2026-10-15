package com.mapmyroad.backend.integration.photon;

/**
 * One place parsed from a Photon GeoJSON feature. {@code toString} hides the coordinates so a stray log line
 * cannot leak where a user searched (AGENTS.md section 6).
 *
 * @param name the place name, or the first address part when Photon has no name
 * @param displayName comma-joined unique address parts, for example "Shaniwar Wada, Kasba Peth, Pune, Maharashtra"
 * @param type OSM value (for example {@code attraction}) or Photon's own type; may be {@code null}
 */
public record PhotonPlace(String name, String displayName, double lat, double lon, String type) {

    @Override
    public String toString() {
        return "PhotonPlace[name=" + name + ", type=" + type + ", lat=***, lon=***]";
    }
}
