package com.mapmyroad.backend.api.web;

import java.io.IOException;

/**
 * Thrown by the size-limited request body stream when the (decompressed) body exceeds the configured limit
 * (API_SPEC.md section 5.3, SECURITY.md T11). It is an {@link IOException} so body readers propagate it, and
 * {@code ApiExceptionHandler} maps it to 413 PAYLOAD_TOO_LARGE when it arrives wrapped by a message converter.
 */
public class BodyTooLargeException extends IOException {

    public static final String DETAIL = "Request body exceeds the size limit.";

    public BodyTooLargeException() {
        super(DETAIL);
    }
}
