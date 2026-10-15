package com.mapmyroad.domain.model

/**
 * Result of a backend call (API_SPEC.md sections 3 and 6). Expected failures are values, not exceptions
 * (CODING_STANDARDS.md section 3.5). The error mapping lives in one place: data/remote/ApiCall.kt.
 */
sealed interface ApiResult<out T> {
    data class Success<T>(
        val value: T,
    ) : ApiResult<T>

    data class Failure(
        val error: ApiError,
    ) : ApiResult<Nothing>
}

/** Failure categories the UI and workers branch on. Mirrors the stable problem `code` values of API_SPEC.md 3. */
sealed interface ApiError {
    /** No connection, DNS failure, timeout. Safe to retry later. */
    data object Network : ApiError

    /** Token missing, invalid or not refreshable; the app registers again. */
    data object Unauthorized : ApiError

    /** 426 APP_UPDATE_REQUIRED: the app shows the update screen (UI_UX_GUIDELINES.md section 8). */
    data object UpdateRequired : ApiError

    /** 403 INTEGRITY_FAILED at registration. */
    data object IntegrityFailed : ApiError

    /** 429 RATE_LIMITED. */
    data class RateLimited(
        val retryAfterSec: Long?,
    ) : ApiError

    /** 503 UPSTREAM_UNAVAILABLE (Valhalla or Photon down, uploads paused). Retry with backoff. */
    data object Unavailable : ApiError

    /** 404 NOT_FOUND. */
    data object NotFound : ApiError

    /** Other 4xx with a stable problem `code` (INVALID_REQUEST, UNSUPPORTED_REGION, TOO_FAR, NO_ROUTE...). Do not retry. */
    data class Rejected(
        val status: Int,
        val code: String?,
    ) : ApiError

    /** 5xx other than 503, or an unreadable response. */
    data class Server(
        val status: Int?,
    ) : ApiError
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> =
    when (this) {
        is ApiResult.Success -> ApiResult.Success(transform(value))
        is ApiResult.Failure -> this
    }
