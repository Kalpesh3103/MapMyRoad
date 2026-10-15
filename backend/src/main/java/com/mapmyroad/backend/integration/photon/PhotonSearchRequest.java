package com.mapmyroad.backend.integration.photon;

import com.mapmyroad.backend.config.SupportedRegion;

/**
 * Parameters of {@code GET /api}. All values were validated and normalized by the caller.
 *
 * @param query search text
 * @param limit maximum results
 * @param lang language passed to Photon, or {@code null} to omit the parameter
 * @param biasLat bias latitude, or {@code null} for no bias (then {@code biasLon} is {@code null} too)
 * @param biasLon bias longitude
 * @param restrictTo bounding box sent as {@code bbox}, or {@code null} for no restriction
 */
public record PhotonSearchRequest(
        String query, int limit, String lang, Double biasLat, Double biasLon, SupportedRegion restrictTo) {

    public PhotonSearchRequest {
        if ((biasLat == null) != (biasLon == null)) {
            throw new IllegalArgumentException("Bias needs both latitude and longitude");
        }
    }

    @Override
    public String toString() {
        return "PhotonSearchRequest[query=***, limit=" + limit + ", lang=" + lang + ", bias=***]";
    }
}
