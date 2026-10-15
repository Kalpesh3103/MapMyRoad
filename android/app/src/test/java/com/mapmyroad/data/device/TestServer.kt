package com.mapmyroad.data.device

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

/** One request as the server saw it; the body is read once, here. */
data class SeenRequest(
    val method: String,
    val path: String,
    val authorization: String?,
    val appVersion: String?,
    val body: String,
)

/** A MockWebServer answering through [handler] and recording every request. */
class TestServer {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<SeenRequest>()

    @Volatile
    var handler: (SeenRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val seen =
                        SeenRequest(
                            method = request.method.orEmpty(),
                            path = request.path.orEmpty(),
                            authorization = request.getHeader("Authorization"),
                            appVersion = request.getHeader("X-App-Version"),
                            body = request.body.readUtf8(),
                        )
                    requests += seen
                    return handler(seen)
                }
            }
        server.start()
    }

    val baseUrl: String
        get() = server.url("/").toString()

    fun count(path: String): Int = requests.count { it.path == path }

    private var isShutDown = false

    fun shutdown() {
        if (isShutDown) return
        isShutDown = true
        server.shutdown()
    }
}

fun jsonResponse(
    body: String,
    status: Int = 200,
): MockResponse =
    MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

fun problemResponse(
    status: Int,
    code: String,
): MockResponse =
    MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/problem+json")
        .setBody("""{"type":"https://api.example.com/problems/x","title":"t","status":$status,"detail":"d","code":"$code"}""")

const val TOKEN_RESPONSE_2 =
    """{"accessToken":"access-2","accessExpiresInSec":3600,"refreshToken":"refresh-2","refreshExpiresInSec":2592000}"""
