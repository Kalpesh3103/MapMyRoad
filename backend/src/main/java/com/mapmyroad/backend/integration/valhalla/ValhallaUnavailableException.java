package com.mapmyroad.backend.integration.valhalla;

/** Valhalla could not be reached or returned a server error. Maps to 503 UPSTREAM_UNAVAILABLE in request paths. */
public class ValhallaUnavailableException extends RuntimeException {

    public ValhallaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public ValhallaUnavailableException(String message) {
        super(message);
    }
}
