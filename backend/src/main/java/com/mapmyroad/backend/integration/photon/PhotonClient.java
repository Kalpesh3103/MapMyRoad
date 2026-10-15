package com.mapmyroad.backend.integration.photon;

import java.util.List;

/**
 * Thin interface over the geocoder so the public Photon instance can be swapped for a self-hosted index or another
 * service (AGENTS.md section 8). Implementations throw {@link PhotonUnavailableException} when the upstream cannot
 * answer.
 */
public interface PhotonClient {

    /** Forward search. Returns places in Photon's order, possibly empty. */
    List<PhotonPlace> search(PhotonSearchRequest request);

    /** Reverse lookup of the nearest named place. Returns at most {@code 1} place. */
    List<PhotonPlace> reverse(PhotonReverseRequest request);
}
