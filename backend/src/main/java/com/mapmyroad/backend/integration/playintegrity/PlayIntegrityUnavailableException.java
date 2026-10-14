package com.mapmyroad.backend.integration.playintegrity;

/** Google's token or decode endpoint failed. Maps to 503 UPSTREAM_UNAVAILABLE; the app retries with backoff. */
public class PlayIntegrityUnavailableException extends RuntimeException {

    public PlayIntegrityUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public PlayIntegrityUnavailableException(String message) {
        super(message);
    }
}
