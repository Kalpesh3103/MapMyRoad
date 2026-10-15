package com.mapmyroad.data.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class InterceptorsTest {
    private val server = MockWebServer()
    private val updateGate = UpdateGate()
    private val tokens = FakeAuthTokens(accessToken = "access-1")
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server.start()
        client = ApiClients.okHttpClient(appVersion = "0.1.0", updateGate = updateGate, authTokens = tokens)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun call(
        method: String,
        path: String,
        configure: Request.Builder.() -> Unit = {},
    ): Int {
        val body = if (method == "POST") "{}".toRequestBody("application/json".toMediaType()) else null
        val request =
            Request
                .Builder()
                .url(server.url(path))
                .method(method, body)
                .apply(configure)
                .build()
        return client.newCall(request).execute().use { it.code }
    }

    @Test
    fun everyRequest_carriesAppVersion_andAFreshRequestId() {
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())

        call("GET", "/v1/config")
        call("POST", "/v1/events")

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("0.1.0", first.getHeader("X-App-Version"))
        assertEquals("0.1.0", second.getHeader("X-App-Version"))
        val firstId = requireNotNull(first.getHeader("X-Request-Id"))
        val secondId = requireNotNull(second.getHeader("X-Request-Id"))
        UUID.fromString(firstId)
        UUID.fromString(secondId)
        assertNotEquals(firstId, secondId)
    }

    @Test
    fun callerSuppliedRequestId_isKept() {
        server.enqueue(MockResponse())

        call("POST", "/v1/events") { header("X-Request-Id", "upload-batch-7") }

        assertEquals("upload-batch-7", server.takeRequest().getHeader("X-Request-Id"))
    }

    @Test
    fun authorization_isSentOnlyToNonPublicEndpoints() {
        val cases =
            listOf(
                "GET" to "/v1/config" to false,
                "GET" to "/v1/tiles/14/1/2.mvt" to false,
                "POST" to "/v1/devices/challenge" to false,
                "POST" to "/v1/devices" to false,
                "POST" to "/v1/devices/token" to false,
                "DELETE" to "/v1/devices/me" to true,
                "POST" to "/v1/events" to true,
                "GET" to "/v1/hazards" to true,
                "POST" to "/v1/routes" to true,
            )
        cases.forEach { (request, expectAuth) ->
            server.enqueue(MockResponse())
            call(request.first, request.second)
            val recorded = server.takeRequest()
            val authorization = recorded.getHeader("Authorization")
            if (expectAuth) {
                assertEquals("${request.first} ${request.second}", "Bearer access-1", authorization)
            } else {
                assertNull("${request.first} ${request.second}", authorization)
            }
        }
    }

    @Test
    fun notRegistered_sendsNoAuthorization() {
        tokens.accessToken = null
        server.enqueue(MockResponse())

        call("GET", "/v1/hazards")

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun status426_raisesTheUpdateGate() {
        assertFalse(updateGate.updateRequired.value)
        server.enqueue(MockResponse().setResponseCode(426).setBody("""{"code":"APP_UPDATE_REQUIRED"}"""))

        assertEquals(426, call("GET", "/v1/config"))

        assertTrue(updateGate.updateRequired.value)
    }

    @Test
    fun otherErrors_leaveTheUpdateGateDown() {
        server.enqueue(MockResponse().setResponseCode(503))

        call("GET", "/v1/config")

        assertFalse(updateGate.updateRequired.value)
    }

    @Test
    fun appVersionHeaderValue_dropsBuildSuffix() {
        assertEquals("0.1.0", AppVersionInterceptor.headerValue("0.1.0-debug"))
        assertEquals("1.2.3", AppVersionInterceptor.headerValue("1.2.3"))
    }

    @Test
    fun baseUrl_getsExactlyOneTrailingSlash() {
        assertEquals("http://localhost:8080/", ApiClients.normalizedBaseUrl("http://localhost:8080"))
        assertEquals("http://localhost:8080/", ApiClients.normalizedBaseUrl("http://localhost:8080//"))
        assertNotNull(ApiClients.retrofit("http://localhost:8080", ApiClients.json(), client))
    }
}
