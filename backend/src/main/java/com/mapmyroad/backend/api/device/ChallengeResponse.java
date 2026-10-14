package com.mapmyroad.backend.api.device;

/** Response of {@code POST /v1/devices/challenge}. */
public record ChallengeResponse(String nonce, long expiresInSec) {

    @Override
    public String toString() {
        return "ChallengeResponse[expiresInSec=" + expiresInSec + "]";
    }
}
