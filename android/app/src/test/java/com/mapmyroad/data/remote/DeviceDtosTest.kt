package com.mapmyroad.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeviceDtosTest {
    private val json = ApiClients.json()

    @Test
    fun toString_neverContainsTokensOrNonces() {
        val values =
            listOf(
                ChallengeResponse(nonce = "nonce-secret", expiresInSec = 300),
                RegisterDeviceRequest("integrity-secret", "nonce-secret", "0.1.0", RegisterDeviceRequest.PLATFORM_ANDROID, 34),
                RegisterDeviceResponse("device-secret", "access-secret", 3600, "refresh-secret", 2_592_000),
                RefreshTokenRequest("refresh-secret"),
                TokenResponse("access-secret", 3600, "refresh-secret", 2_592_000),
            )
        values.forEach { value ->
            assertFalse(value.toString(), value.toString().contains("secret"))
        }
    }

    @Test
    fun registerRequest_usesTheBackendFieldNames() {
        val encoded =
            json.encodeToString(
                RegisterDeviceRequest.serializer(),
                RegisterDeviceRequest("itoken", "bm9uY2U", "0.1.0", RegisterDeviceRequest.PLATFORM_ANDROID, 34),
            )
        val fields = Json.parseToJsonElement(encoded).jsonObject

        assertEquals(setOf("integrityToken", "nonce", "appVersion", "platform", "osApiLevel"), fields.keys)
        assertEquals("android", fields.getValue("platform").jsonPrimitive.content)
        assertEquals(34, fields.getValue("osApiLevel").jsonPrimitive.int)
    }

    @Test
    fun responses_ignoreUnknownFields() {
        val decoded =
            json.decodeFromString(
                TokenResponse.serializer(),
                """{"accessToken":"a","accessExpiresInSec":3600,"refreshToken":"r","refreshExpiresInSec":60,"newField":true}""",
            )

        assertEquals(TokenResponse("a", 3600, "r", 60), decoded)
    }
}
