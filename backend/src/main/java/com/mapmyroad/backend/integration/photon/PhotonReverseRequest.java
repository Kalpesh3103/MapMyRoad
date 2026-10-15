package com.mapmyroad.backend.integration.photon;

/**
 * Parameters of {@code GET /reverse}.
 *
 * @param lang language passed to Photon, or {@code null} to omit the parameter
 */
public record PhotonReverseRequest(double lat, double lon, String lang) {

    @Override
    public String toString() {
        return "PhotonReverseRequest[lat=***, lon=***, lang=" + lang + "]";
    }
}
