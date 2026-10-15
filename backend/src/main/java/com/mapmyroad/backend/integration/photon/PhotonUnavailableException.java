package com.mapmyroad.backend.integration.photon;

/**
 * Photon could not answer: connection failure, timeout, an error status (5xx, 429, unexpected 4xx), malformed JSON
 * or all upstream slots busy. Mapped to 503 UPSTREAM_UNAVAILABLE. The message never contains the query or
 * coordinates.
 */
public class PhotonUnavailableException extends RuntimeException {

    /** Low-cardinality reason, used as a metric tag. */
    public enum Reason {
        UNREACHABLE,
        TIMEOUT,
        RATE_LIMITED,
        HTTP_ERROR,
        MALFORMED,
        BUSY,
    }

    private final Reason reason;

    public PhotonUnavailableException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public PhotonUnavailableException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
