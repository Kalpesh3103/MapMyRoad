package com.mapmyroad.data.device

import com.mapmyroad.data.remote.ApiClients
import com.mapmyroad.data.remote.DeviceApi
import com.mapmyroad.data.remote.DeviceAuthApi
import com.mapmyroad.data.remote.UpdateGate
import com.mapmyroad.domain.model.ApiError
import com.mapmyroad.domain.model.ApiResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultDeviceSessionTest {
    private val testServer = TestServer()
    private val updateGate = UpdateGate()
    private val store = FakeCredentialStore()
    private val integrity = FakeIntegrityTokenProvider()
    private val clock = EpochClock { NOW_MS }
    private val json = ApiClients.json()
    private val unauthenticated =
        ApiClients.retrofit(testServer.baseUrl, json, ApiClients.okHttpClient("0.1.0", updateGate, authTokens = null))
    private val authApi = unauthenticated.create(DeviceAuthApi::class.java)
    private val tokenManager = DeviceTokenManager(store, authApi, clock)
    private val deviceApi =
        ApiClients
            .retrofit(testServer.baseUrl, json, ApiClients.okHttpClient("0.1.0", updateGate, authTokens = tokenManager))
            .create(DeviceApi::class.java)
    private val session =
        DefaultDeviceSession(
            store = store,
            authApi = authApi,
            deviceApi = deviceApi,
            integrity = integrity,
            updateGate = updateGate,
            deviceInfo = DeviceInfo(appVersion = "0.1.0", osApiLevel = 34),
            clock = clock,
        )

    @After
    fun tearDown() {
        testServer.shutdown()
    }

    private fun registrationServer() {
        testServer.handler = { request ->
            when (request.method to request.path) {
                "POST" to "/v1/devices/challenge" -> jsonResponse("""{"nonce":"bm9uY2UtMQ","expiresInSec":300}""")
                "POST" to "/v1/devices" -> jsonResponse(REGISTRATION_RESPONSE, status = 201)
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Test
    fun ensureRegistered_runsChallengeIntegrityAndRegistration() =
        runTest {
            registrationServer()

            assertEquals(ApiResult.Success(Unit), session.ensureRegistered())

            assertEquals(listOf("bm9uY2UtMQ"), integrity.nonces)
            val stored = requireNotNull(store.value)
            assertEquals("6f1f7a52-1e0e-4f0d-9d3c-1b2f6d9b8a10", stored.deviceId)
            assertEquals("access-1", stored.accessToken)
            assertEquals(NOW_MS + 3_600_000L, stored.accessExpiresAtMs)
            assertEquals("refresh-1", stored.refreshToken)
            assertEquals(NOW_MS + 2_592_000_000L, stored.refreshExpiresAtMs)
            assertTrue(session.isRegistered())

            val (challenge, registration) = testServer.requests
            assertEquals("/v1/devices/challenge", challenge.path)
            assertNull(challenge.authorization)
            assertEquals("0.1.0", challenge.appVersion)
            assertEquals("/v1/devices", registration.path)
            assertNull(registration.authorization)
            val body = Json.parseToJsonElement(registration.body).jsonObject
            assertEquals("integrity-token", body.getValue("integrityToken").jsonPrimitive.content)
            assertEquals("bm9uY2UtMQ", body.getValue("nonce").jsonPrimitive.content)
            assertEquals("0.1.0", body.getValue("appVersion").jsonPrimitive.content)
            assertEquals("android", body.getValue("platform").jsonPrimitive.content)
            assertEquals(34, body.getValue("osApiLevel").jsonPrimitive.int)
        }

    @Test
    fun ensureRegistered_whenRegistered_makesNoRequest() =
        runTest {
            store.save(testCredentials())

            assertEquals(ApiResult.Success(Unit), session.ensureRegistered())

            assertTrue(testServer.requests.isEmpty())
        }

    @Test
    fun concurrentEnsureRegistered_registersOnce() =
        runTest {
            registrationServer()

            val results = List(5) { async { session.ensureRegistered() } }.awaitAll()

            assertTrue(results.all { it == ApiResult.Success(Unit) })
            assertEquals(1, testServer.count("/v1/devices/challenge"))
            assertEquals(1, testServer.count("/v1/devices"))
        }

    @Test
    fun integrityUnavailable_failsWithoutRegistering() =
        runTest {
            registrationServer()
            integrity.result = ApiResult.Failure(ApiError.IntegrityFailed)

            assertEquals(ApiResult.Failure(ApiError.IntegrityFailed), session.ensureRegistered())

            assertEquals(0, testServer.count("/v1/devices"))
            assertFalse(session.isRegistered())
        }

    @Test
    fun serverRejectsVerdict_isIntegrityFailed() =
        runTest {
            testServer.handler = { request ->
                if (request.path == "/v1/devices/challenge") {
                    jsonResponse("""{"nonce":"bm9uY2UtMQ","expiresInSec":300}""")
                } else {
                    problemResponse(403, "INTEGRITY_FAILED")
                }
            }

            assertEquals(ApiResult.Failure(ApiError.IntegrityFailed), session.ensureRegistered())
            assertFalse(session.isRegistered())
        }

    @Test
    fun updateRequired_raisesTheGate() =
        runTest {
            testServer.handler = { problemResponse(426, "APP_UPDATE_REQUIRED") }

            assertEquals(ApiResult.Failure(ApiError.UpdateRequired), session.ensureRegistered())

            assertTrue(session.updateRequired.value)
        }

    @Test
    fun ensureRegistered_offline_isNetworkFailure() =
        runTest {
            testServer.shutdown()

            assertEquals(ApiResult.Failure(ApiError.Network), session.ensureRegistered())
        }

    @Test
    fun deleteRemoteData_notRegistered_succeedsWithoutRequest() =
        runTest {
            assertEquals(ApiResult.Success(Unit), session.deleteRemoteData())

            assertTrue(testServer.requests.isEmpty())
        }

    @Test
    fun deleteRemoteData_204_clearsCredentials() =
        runTest {
            store.save(testCredentials())
            testServer.handler = { MockResponse().setResponseCode(204) }

            assertEquals(ApiResult.Success(Unit), session.deleteRemoteData())

            assertNull(store.value)
            val request = testServer.requests.single()
            assertEquals("DELETE", request.method)
            assertEquals("/v1/devices/me", request.path)
            assertEquals("Bearer access-1", request.authorization)
        }

    @Test
    fun deleteRemoteData_404_meansAlreadyGone() =
        runTest {
            store.save(testCredentials())
            testServer.handler = { problemResponse(404, "NOT_FOUND") }

            assertEquals(ApiResult.Success(Unit), session.deleteRemoteData())

            assertNull(store.value)
        }

    @Test
    fun deleteRemoteData_401WithRejectedRefresh_meansAlreadyGone() =
        runTest {
            store.save(testCredentials())
            testServer.handler = { request ->
                val code = if (request.path == "/v1/devices/token") "UNAUTHORIZED" else "TOKEN_EXPIRED"
                problemResponse(401, code)
            }

            assertEquals(ApiResult.Success(Unit), session.deleteRemoteData())

            assertNull(store.value)
        }

    @Test
    fun deleteRemoteData_expiredToken_refreshesAndDeletes() =
        runTest {
            store.save(testCredentials())
            testServer.handler = { request ->
                when {
                    request.path == "/v1/devices/token" -> jsonResponse(TOKEN_RESPONSE_2)
                    request.authorization == "Bearer access-2" -> MockResponse().setResponseCode(204)
                    else -> problemResponse(401, "TOKEN_EXPIRED")
                }
            }

            assertEquals(ApiResult.Success(Unit), session.deleteRemoteData())

            assertNull(store.value)
            assertEquals(3, testServer.requests.size)
        }

    @Test
    fun deleteRemoteData_401WithUnreachableRefresh_keepsCredentials() =
        runTest {
            store.save(testCredentials())
            testServer.handler = { request ->
                if (request.path == "/v1/devices/token") {
                    problemResponse(503, "UPSTREAM_UNAVAILABLE")
                } else {
                    problemResponse(401, "TOKEN_EXPIRED")
                }
            }

            assertEquals(ApiResult.Failure(ApiError.Unauthorized), session.deleteRemoteData())

            assertEquals(testCredentials(), store.value)
        }

    @Test
    fun deleteRemoteData_serverError_keepsCredentials() =
        runTest {
            store.save(testCredentials())
            testServer.handler = { problemResponse(500, "INTERNAL") }

            assertEquals(ApiResult.Failure(ApiError.Server(500)), session.deleteRemoteData())

            assertEquals(testCredentials(), store.value)
        }

    @Test
    fun deleteRemoteData_offline_keepsCredentials() =
        runTest {
            store.save(testCredentials())
            testServer.shutdown()

            assertEquals(ApiResult.Failure(ApiError.Network), session.deleteRemoteData())

            assertEquals(testCredentials(), store.value)
        }

    @Test
    fun clearCredentials_forgetsTheInstall() =
        runTest {
            store.save(testCredentials())

            session.clearCredentials()

            assertFalse(session.isRegistered())
        }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
        const val REGISTRATION_RESPONSE =
            """{"deviceId":"6f1f7a52-1e0e-4f0d-9d3c-1b2f6d9b8a10","accessToken":"access-1","accessExpiresInSec":3600,""" +
                """"refreshToken":"refresh-1","refreshExpiresInSec":2592000,"unknownFutureField":"ignored"}"""
    }
}
