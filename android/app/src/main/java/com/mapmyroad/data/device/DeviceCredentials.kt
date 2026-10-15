package com.mapmyroad.data.device

import com.mapmyroad.data.remote.RegisterDeviceResponse
import com.mapmyroad.data.remote.TokenResponse

/**
 * The backend identity of this install (API_SPEC.md section 2). Expiry times are epoch milliseconds computed from
 * the server's `*ExpiresInSec` at the time the response arrived; they are informational, the server decides.
 * [toString] redacts everything: the device ID and both tokens are sensitive (AGENTS.md section 6).
 */
data class DeviceCredentials(
    val deviceId: String,
    val accessToken: String,
    val accessExpiresAtMs: Long,
    val refreshToken: String,
    val refreshExpiresAtMs: Long,
) {
    /** The credentials after a successful refresh: new access token, rotated refresh token, same device. */
    fun refreshed(
        response: TokenResponse,
        nowMs: Long,
    ): DeviceCredentials =
        copy(
            accessToken = response.accessToken,
            accessExpiresAtMs = nowMs + response.accessExpiresInSec * MS_PER_S,
            refreshToken = response.refreshToken,
            refreshExpiresAtMs = nowMs + response.refreshExpiresInSec * MS_PER_S,
        )

    override fun toString(): String = "DeviceCredentials(***)"

    companion object {
        private const val MS_PER_S = 1_000L

        fun fromRegistration(
            response: RegisterDeviceResponse,
            nowMs: Long,
        ): DeviceCredentials =
            DeviceCredentials(
                deviceId = response.deviceId,
                accessToken = response.accessToken,
                accessExpiresAtMs = nowMs + response.accessExpiresInSec * MS_PER_S,
                refreshToken = response.refreshToken,
                refreshExpiresAtMs = nowMs + response.refreshExpiresInSec * MS_PER_S,
            )
    }
}
