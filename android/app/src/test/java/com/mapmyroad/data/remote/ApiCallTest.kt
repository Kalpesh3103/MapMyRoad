package com.mapmyroad.data.remote

import com.mapmyroad.domain.model.ApiError
import com.mapmyroad.domain.model.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class ApiCallTest {
    private fun problem(
        status: Int,
        code: String,
        extra: String = "",
    ): String = """{"type":"https://api.example.com/problems/x","title":"t","status":$status,"detail":"d","code":"$code"$extra}"""

    @Test
    fun httpError_mapsEveryDocumentedProblemCode() {
        assertEquals(ApiError.Rejected(400, "INVALID_REQUEST"), httpError(400, problem(400, "INVALID_REQUEST")))
        assertEquals(ApiError.Unauthorized, httpError(401, problem(401, "UNAUTHORIZED")))
        assertEquals(ApiError.Unauthorized, httpError(401, problem(401, "TOKEN_EXPIRED")))
        assertEquals(ApiError.IntegrityFailed, httpError(403, problem(403, "INTEGRITY_FAILED")))
        assertEquals(ApiError.NotFound, httpError(404, problem(404, "NOT_FOUND")))
        assertEquals(ApiError.Rejected(413, "PAYLOAD_TOO_LARGE"), httpError(413, problem(413, "PAYLOAD_TOO_LARGE")))
        assertEquals(ApiError.Rejected(422, "UNSUPPORTED_REGION"), httpError(422, problem(422, "UNSUPPORTED_REGION")))
        assertEquals(ApiError.Rejected(422, "TOO_FAR"), httpError(422, problem(422, "TOO_FAR")))
        assertEquals(ApiError.Rejected(422, "NO_ROUTE"), httpError(422, problem(422, "NO_ROUTE")))
        assertEquals(ApiError.UpdateRequired, httpError(426, problem(426, "APP_UPDATE_REQUIRED")))
        assertEquals(
            ApiError.RateLimited(120),
            httpError(429, problem(429, "RATE_LIMITED", extra = ""","retryAfterSec":120""")),
        )
        assertEquals(ApiError.Server(500), httpError(500, problem(500, "INTERNAL")))
        assertEquals(ApiError.Unavailable, httpError(503, problem(503, "UPSTREAM_UNAVAILABLE")))
    }

    @Test
    fun httpError_withoutReadableBody_mapsByStatus() {
        assertEquals(ApiError.Unauthorized, httpError(401, null))
        assertEquals(ApiError.Rejected(403, null), httpError(403, "<html>forbidden</html>"))
        assertEquals(ApiError.NotFound, httpError(404, ""))
        assertEquals(ApiError.UpdateRequired, httpError(426, null))
        assertEquals(ApiError.RateLimited(null), httpError(429, null))
        assertEquals(ApiError.Server(502), httpError(502, "Bad gateway"))
        assertEquals(ApiError.Unavailable, httpError(503, null))
    }

    @Test
    fun httpError_withUnexpectedFieldTypes_doesNotThrow() {
        assertEquals(ApiError.Rejected(400, null), httpError(400, """{"code":{"nested":true}}"""))
        assertEquals(ApiError.RateLimited(null), httpError(429, """{"code":"RATE_LIMITED","retryAfterSec":"soon"}"""))
        assertEquals(ApiError.Rejected(400, null), httpError(400, """["not","an","object"]"""))
    }

    @Test
    fun apiCall_mapsHttpExceptionThroughProblemBody() =
        runTest {
            val body = problem(403, "INTEGRITY_FAILED").toResponseBody("application/problem+json".toMediaType())
            val result = apiCall<Unit> { throw HttpException(Response.error<Unit>(403, body)) }
            assertEquals(ApiResult.Failure(ApiError.IntegrityFailed), result)
        }

    @Test
    fun apiCall_rateLimitedWithoutBodyValue_usesRetryAfterHeader() =
        runTest {
            val raw =
                okhttp3.Response
                    .Builder()
                    .code(429)
                    .message("Too Many Requests")
                    .protocol(Protocol.HTTP_1_1)
                    .request(Request.Builder().url("http://localhost/v1/events").build())
                    .header("Retry-After", "60")
                    .build()
            val body = problem(429, "RATE_LIMITED").toResponseBody("application/problem+json".toMediaType())
            val result = apiCall<Unit> { throw HttpException(Response.error<Unit>(body, raw)) }
            assertEquals(ApiResult.Failure(ApiError.RateLimited(60)), result)
        }

    @Test
    fun apiCall_mapsIoExceptionToNetwork() =
        runTest {
            assertEquals(ApiResult.Failure(ApiError.Network), apiCall<Unit> { throw IOException("offline") })
        }

    @Test
    fun apiCall_mapsUnreadableResponseToServerError() =
        runTest {
            assertEquals(ApiResult.Failure(ApiError.Server(null)), apiCall<Unit> { throw SerializationException("bad") })
        }

    @Test
    fun apiCall_returnsSuccessValue() =
        runTest {
            assertEquals(ApiResult.Success(42), apiCall { 42 })
        }

    @Test
    fun apiCall_rethrowsCancellation() =
        runTest {
            try {
                apiCall<Unit> { throw CancellationException("cancelled") }
                fail("CancellationException must propagate")
            } catch (expected: CancellationException) {
                // Structured concurrency keeps working.
            }
        }
}
