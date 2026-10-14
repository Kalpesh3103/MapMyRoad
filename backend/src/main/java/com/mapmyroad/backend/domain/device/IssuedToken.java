package com.mapmyroad.backend.domain.device;

/**
 * A token handed to the app together with its lifetime. {@link #toString()} hides the value so it cannot end up in
 * logs by accident.
 */
public record IssuedToken(String value, long expiresInSec) {

    @Override
    public String toString() {
        return "IssuedToken[expiresInSec=" + expiresInSec + "]";
    }
}
