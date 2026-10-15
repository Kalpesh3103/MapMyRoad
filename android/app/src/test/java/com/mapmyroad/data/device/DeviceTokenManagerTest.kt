package com.mapmyroad.data.device

import com.mapmyroad.data.remote.ApiClients
import com.mapmyroad.data.remote.DeviceAuthApi
import com.mapmyroad.data.remote.UpdateGate
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeviceTokenManagerTest {
    private val testServer = TestServer()
    private val updateGate = UpdateGate()
    private val store = FakeCredentialStore(testCredentials())
    private val json = ApiClients.json()
    private val authApi =
        ApiClients
            .retrofit(testServer.baseUrl, json, ApiClients.okHttpClient("0.1.0", updateGate, authTokens = null))
            .create(DeviceAuthApi::class.java)
    private val manager = DeviceTokenManager(store, authApi, clock = { NOW_MS })

    @After
    fun tearDown() {
        testServer.shutdown()
    }

    @Test
    fun refresh_storesTheRotatedPair_andReturnsTheNewAccessToken() =
        runTest {
            testServer.handler = { jsonResponse(TOKEN_RESPONSE_2) }

            assertEquals("access-2", manager.refresh("access-1"))

            val stored = requireNotNull(store.value)
            assertEquals("access-2", stored.accessToken)
            assertEquals("refresh-2", stored.refreshToken)
            assertEquals(NOW_MS + 3_600_000L, stored.accessExpiresAtMs)
            val request = testServer.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/v1/devices/token", request.path)
            assertEquals("""{"refreshToken":"refresh-1"}""", request.body)
            // The refresh token is its own credential: no access token goes with it.
            assertNull(request.authorization)
        }

    @Test
    fun refreshRejected_clearsCredentials() =
        runTest {
            testServer.handler = { problemResponse(401, "UNAUTHORIZED") }

            assertNull(manager.refresh("access-1"))

            assertNull(store.value)
        }

    @Test
    fun refreshServerError_keepsCredentials() =
        runTest {
            testServer.handler = { problemResponse(500, "INTERNAL") }

            assertNull(manager.refresh("access-1"))

            assertEquals(testCredentials(), store.value)
        }

    @Test
    fun refreshOffline_keepsCredentials() =
        runTest {
            testServer.shutdown()

            assertNull(manager.refresh("access-1"))

            assertEquals(testCredentials(), store.value)
        }

    @Test
    fun staleRejectedToken_reusesTheStoredToken_withoutARefresh() =
        runTest {
            store.save(testCredentials(accessToken = "access-2", refreshToken = "refresh-2"))

            assertEquals("access-2", manager.refresh("access-1"))

            assertTrue(testServer.requests.isEmpty())
        }

    @Test
    fun notRegistered_returnsNull() =
        runTest {
            store.clear()

            assertNull(manager.refresh("access-1"))
            assertNull(manager.currentAccessToken())
        }

    @Test
    fun concurrentExpiredRequests_shareASingleRefresh() {
        testServer.handler = { request ->
            when {
                request.path == "/v1/devices/token" -> jsonResponse(TOKEN_RESPONSE_2).setBodyDelay(200, TimeUnit.MILLISECONDS)
                request.authorization == "Bearer access-2" -> jsonResponse("[]")
                else -> problemResponse(401, "TOKEN_EXPIRED")
            }
        }
        val client = ApiClients.okHttpClient("0.1.0", updateGate, authTokens = manager)
        val executor = Executors.newFixedThreadPool(PARALLEL_REQUESTS)
        try {
            val calls =
                List(PARALLEL_REQUESTS) {
                    Callable {
                        val request = Request.Builder().url(testServer.server.url("/v1/hazards")).build()
                        client.newCall(request).execute().use { it.code }
                    }
                }

            val codes = executor.invokeAll(calls).map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(List(PARALLEL_REQUESTS) { 200 }, codes)
            assertEquals(1, testServer.count("/v1/devices/token"))
            assertEquals("refresh-2", store.value?.refreshToken)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun expiredRequestWithRejectedRefresh_failsWith401_andClearsCredentials() {
        testServer.handler = { request ->
            val code = if (request.path == "/v1/devices/token") "UNAUTHORIZED" else "TOKEN_EXPIRED"
            problemResponse(401, code)
        }
        val client = ApiClients.okHttpClient("0.1.0", updateGate, authTokens = manager)

        val code =
            client
                .newCall(Request.Builder().url(testServer.server.url("/v1/hazards")).build())
                .execute()
                .use { it.code }

        assertEquals(401, code)
        assertNull(store.value)
        // One original request and one refresh; no retry without a token, no loop.
        assertEquals(2, testServer.requests.size)
    }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
        const val PARALLEL_REQUESTS = 8
    }
}
