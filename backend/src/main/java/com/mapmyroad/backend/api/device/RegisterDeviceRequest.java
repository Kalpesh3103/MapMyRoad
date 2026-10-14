package com.mapmyroad.backend.api.device;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /v1/devices} (API_SPEC.md section 5.2). Every string has an explicit maximum. */
public record RegisterDeviceRequest(
        @NotBlank @Size(max = MAX_INTEGRITY_TOKEN_CHARS) String integrityToken,
        @NotBlank @Size(max = 128) String nonce,
        @NotBlank @Size(max = 32) @Pattern(regexp = "^\\d+\\.\\d+\\.\\d+.*$") String appVersion,
        @NotNull @Pattern(regexp = "^android$") String platform,
        @NotNull @Min(26) @Max(100) Integer osApiLevel) {

    /** 8 KB. Play Integrity tokens are a few KB of JWE; this bounds what we forward to Google. */
    public static final int MAX_INTEGRITY_TOKEN_CHARS = 8192;

    @Override
    public String toString() {
        // No token or nonce in logs.
        return "RegisterDeviceRequest[appVersion=" + appVersion + ", osApiLevel=" + osApiLevel + "]";
    }
}
