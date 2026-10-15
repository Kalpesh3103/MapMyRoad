package com.mapmyroad.data.remote

import kotlinx.serialization.Serializable

/**
 * Response of `POST /v1/devices/challenge`. [nonce] is base64url and is passed to Play Integrity unchanged.
 *
 * The wire types in this file match API_SPEC.md section 5.2 and the backend records in api/device field for field.
 * Every toString() redacts nonces and tokens so they cannot reach a log or crash report (AGENTS.md section 6).
 */
@Serializable
data class ChallengeResponse(
    val nonce: String,
    val expiresInSec: Long,
) {
    override fun toString(): String = "ChallengeResponse(expiresInSec=$expiresInSec, nonce=***)"
}

/** Body of `POST /v1/devices`. */
@Serializable
data class RegisterDeviceRequest(
    val integrityToken: String,
    val nonce: String,
    val appVersion: String,
    val platform: String,
    val osApiLevel: Int,
) {
    override fun toString(): String = "RegisterDeviceRequest(appVersion=$appVersion, osApiLevel=$osApiLevel, tokens=***)"

    companion object {
        const val PLATFORM_ANDROID = "android"
    }
}

/** Response `201` of `POST /v1/devices`. */
@Serializable
data class RegisterDeviceResponse(
    val deviceId: String,
    val accessToken: String,
    val accessExpiresInSec: Long,
    val refreshToken: String,
    val refreshExpiresInSec: Long,
) {
    override fun toString(): String = "RegisterDeviceResponse(***)"
}

/** Body of `POST /v1/devices/token`. */
@Serializable
data class RefreshTokenRequest(
    val refreshToken: String,
) {
    override fun toString(): String = "RefreshTokenRequest(***)"
}

/** Response of `POST /v1/devices/token`: a new access token and the rotated refresh token. */
@Serializable
data class TokenResponse(
    val accessToken: String,
    val accessExpiresInSec: Long,
    val refreshToken: String,
    val refreshExpiresInSec: Long,
) {
    override fun toString(): String = "TokenResponse(***)"
}
