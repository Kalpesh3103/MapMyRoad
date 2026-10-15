package com.mapmyroad.data.remote

import com.mapmyroad.domain.model.ApiError
import com.mapmyroad.domain.model.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * Runs one backend call and maps every failure to an [ApiError] (API_SPEC.md sections 3 and 6). The single place
 * where HTTP status codes and problem `code` values become app errors. Never logs bodies, tokens or coordinates.
 * Cancellation is rethrown so structured concurrency keeps working (CODING_STANDARDS.md section 3.4).
 */
suspend fun <T> apiCall(block: suspend () -> T): ApiResult<T> =
    try {
        ApiResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        val response = e.response()
        val error = httpError(e.code(), readErrorBody(response))
        // The problem body may omit retryAfterSec; the Retry-After header (seconds form) is always set on 429.
        val retryAfterHeaderSec =
            response
                ?.headers()
                ?.get("Retry-After")
                ?.trim()
                ?.toLongOrNull()
        if (error is ApiError.RateLimited && error.retryAfterSec == null && retryAfterHeaderSec != null) {
            ApiResult.Failure(ApiError.RateLimited(retryAfterHeaderSec))
        } else {
            ApiResult.Failure(error)
        }
    } catch (e: IOException) {
        ApiResult.Failure(ApiError.Network)
    } catch (e: SerializationException) {
        ApiResult.Failure(ApiError.Server(status = null))
    } catch (e: IllegalArgumentException) {
        // kotlinx.serialization reports some decoding problems as IllegalArgumentException.
        ApiResult.Failure(ApiError.Server(status = null))
    }

private val problemJson = Json { ignoreUnknownKeys = true }

// Reading the error body can itself fail (connection dropped mid-body); the status alone is still enough to map.
private fun readErrorBody(response: Response<*>?): String? =
    try {
        response?.errorBody()?.string()
    } catch (e: IOException) {
        null
    }

/** Maps an HTTP error status and its RFC 9457 problem body to an [ApiError]. */
internal fun httpError(
    status: Int,
    body: String?,
): ApiError {
    val problem = parseProblem(body)
    val code = (problem?.get("code") as? JsonPrimitive)?.contentOrNull
    return when {
        status == 401 -> ApiError.Unauthorized
        status == 426 || code == "APP_UPDATE_REQUIRED" -> ApiError.UpdateRequired
        status == 403 && code == "INTEGRITY_FAILED" -> ApiError.IntegrityFailed
        status == 404 -> ApiError.NotFound
        status == 429 -> ApiError.RateLimited((problem?.get("retryAfterSec") as? JsonPrimitive)?.longOrNull)
        status == 503 -> ApiError.Unavailable
        status in 400..499 -> ApiError.Rejected(status, code)
        else -> ApiError.Server(status)
    }
}

private fun parseProblem(body: String?): JsonObject? {
    if (body.isNullOrBlank()) return null
    return try {
        problemJson.parseToJsonElement(body).jsonObject
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
