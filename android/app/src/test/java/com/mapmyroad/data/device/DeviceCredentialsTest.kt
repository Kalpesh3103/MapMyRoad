package com.mapmyroad.data.device

import com.mapmyroad.data.remote.RegisterDeviceResponse
import com.mapmyroad.data.remote.TokenResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeviceCredentialsTest {
    @Test
    fun toString_redactsDeviceIdAndTokens() {
        val text = testCredentials(accessToken = "access-secret", refreshToken = "refresh-secret").toString()

        assertFalse(text.contains("secret"))
        assertFalse(text.contains("6f1f7a52"))
    }

    @Test
    fun fromRegistration_computesExpiryFromResponseTime() {
        val credentials =
            DeviceCredentials.fromRegistration(
                RegisterDeviceResponse("device-1", "access-1", 3600, "refresh-1", 2_592_000),
                nowMs = 10_000L,
            )

        assertEquals("device-1", credentials.deviceId)
        assertEquals(10_000L + 3_600_000L, credentials.accessExpiresAtMs)
        assertEquals(10_000L + 2_592_000_000L, credentials.refreshExpiresAtMs)
    }

    @Test
    fun refreshed_replacesBothTokens_keepsDevice() {
        val refreshed = testCredentials().refreshed(TokenResponse("access-2", 60, "refresh-2", 120), nowMs = 5_000L)

        assertEquals(testCredentials().deviceId, refreshed.deviceId)
        assertEquals("access-2", refreshed.accessToken)
        assertEquals(65_000L, refreshed.accessExpiresAtMs)
        assertEquals("refresh-2", refreshed.refreshToken)
        assertEquals(125_000L, refreshed.refreshExpiresAtMs)
    }
}
