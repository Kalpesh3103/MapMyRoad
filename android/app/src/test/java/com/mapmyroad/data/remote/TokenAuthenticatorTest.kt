package com.mapmyroad.data.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TokenAuthenticatorTest {
    private val server = MockWebServer()
    private val tokens = FakeAuthTokens(accessToken = "access-1", refreshedToken = "access-2")
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server.start()
        client = ApiClients.okHttpClient(appVersion = "0.1.0", updateGate = UpdateGate(), authTokens = tokens)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun unauthorized(code: String) =
        MockResponse()
            .setResponseCode(401)
            .setHeader("Content-Type", "application/problem+json")
            .setBody("""{"status":401,"code":"$code"}""")

    private fun get(path: String): Int =
        client
            .newCall(Request.Builder().url(server.url(path)).build())
            .execute()
            .use { it.code }

    @Test
    fun tokenExpired_refreshesOnce_andRetriesWithTheNewToken() {
        server.enqueue(unauthorized("TOKEN_EXPIRED"))
        server.enqueue(MockResponse().setBody("[]"))

        assertEquals(200, get("/v1/hazards"))

        assertEquals(listOf("access-1"), tokens.rejectedTokens)
        val first = server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("Bearer access-1", first.getHeader("Authorization"))
        assertEquals("Bearer access-2", retry.getHeader("Authorization"))
        // The retry is built from the sent request, so it keeps the headers of the application interceptors.
        assertEquals("0.1.0", retry.getHeader("X-App-Version"))
        assertEquals(first.getHeader("X-Request-Id"), retry.getHeader("X-Request-Id"))
    }

    @Test
    fun secondUnauthorized_isReturned_withoutAnotherRefresh() {
        server.enqueue(unauthorized("TOKEN_EXPIRED"))
        server.enqueue(unauthorized("UNAUTHORIZED"))
        server.enqueue(MockResponse())

        assertEquals(401, get("/v1/hazards"))

        assertEquals(1, tokens.rejectedTokens.size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun failedRefresh_returnsTheUnauthorizedResponse() {
        tokens.refreshedToken = null
        server.enqueue(unauthorized("TOKEN_EXPIRED"))

        assertEquals(401, get("/v1/hazards"))

        assertEquals(1, server.requestCount)
    }

    @Test
    fun withoutToken_noRefreshIsAttempted() {
        tokens.accessToken = null
        server.enqueue(unauthorized("UNAUTHORIZED"))

        assertEquals(401, get("/v1/hazards"))

        assertTrue(tokens.rejectedTokens.isEmpty())
    }

    @Test
    fun publicEndpoint_unauthorized_isNotRefreshed() {
        server.enqueue(unauthorized("UNAUTHORIZED"))
        val request =
            Request
                .Builder()
                .url(server.url("/v1/devices/token"))
                .post("""{"refreshToken":"x"}""".toRequestBody("application/json".toMediaType()))
                .build()

        val code = client.newCall(request).execute().use { it.code }

        assertEquals(401, code)
        assertTrue(tokens.rejectedTokens.isEmpty())
    }
}
