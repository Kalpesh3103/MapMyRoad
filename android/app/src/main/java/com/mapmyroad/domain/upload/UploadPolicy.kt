package com.mapmyroad.domain.upload

import com.mapmyroad.domain.model.ApiError

/** What the upload worker does after a failed call (API_SPEC.md section 6). */
sealed interface UploadAction {
    /** Transient failure: keep the trip queued and let WorkManager retry with exponential backoff. */
    data class RetryLater(
        val category: String,
        /** Earliest retry from now, from `Retry-After`; 0 when the server gave none. */
        val notBeforeDelayMs: Long = 0L,
    ) : UploadAction

    /** Permanent for this trip (4xx other than 401, 426, 429): mark it failed and continue with the next trip. */
    data class FailTrip(
        val category: String,
    ) : UploadAction

    /** 401 after the token refresh failed: register again once, then repeat the request. */
    data object Reregister : UploadAction

    /** Stop the run without retrying: 426 (the update screen takes over) or a registration that cannot succeed. */
    data class Stop(
        val category: String,
    ) : UploadAction
}

/**
 * Pure mapping from [ApiError] to [UploadAction]. Categories are short stable strings stored in
 * `upload_queue.last_error`; they never contain response bodies, coordinates or identifiers.
 */
object UploadDecision {
    /** After this many transient failures of one trip it is marked failed (initial value, not tuned). */
    const val MAX_ATTEMPTS = 15

    const val CATEGORY_RETRIES_EXHAUSTED = "retries_exhausted"
    const val CATEGORY_UNKNOWN_VEHICLE = "unknown_vehicle"

    /** For `POST /v1/events`. */
    fun forUploadError(error: ApiError): UploadAction =
        when (error) {
            ApiError.Network, ApiError.Unavailable, is ApiError.Server -> UploadAction.RetryLater(category(error))
            is ApiError.RateLimited ->
                UploadAction.RetryLater(
                    category = category(error),
                    notBeforeDelayMs = (error.retryAfterSec ?: 0L).coerceAtLeast(0L) * MILLIS_PER_SECOND,
                )
            ApiError.Unauthorized -> UploadAction.Reregister
            ApiError.UpdateRequired -> UploadAction.Stop(category(error))
            ApiError.IntegrityFailed, ApiError.NotFound, is ApiError.Rejected -> UploadAction.FailTrip(category(error))
        }

    /** For `DeviceSession.ensureRegistered()`. Nothing can be uploaded without a registration, so no FailTrip. */
    fun forRegistrationError(error: ApiError): UploadAction =
        when (error) {
            ApiError.Network, ApiError.Unavailable, is ApiError.Server -> UploadAction.RetryLater(category(error))
            is ApiError.RateLimited ->
                UploadAction.RetryLater(
                    category = category(error),
                    notBeforeDelayMs = (error.retryAfterSec ?: 0L).coerceAtLeast(0L) * MILLIS_PER_SECOND,
                )
            ApiError.UpdateRequired,
            ApiError.Unauthorized,
            ApiError.IntegrityFailed,
            ApiError.NotFound,
            is ApiError.Rejected,
            -> UploadAction.Stop(category(error))
        }

    fun category(error: ApiError): String =
        when (error) {
            ApiError.Network -> "network"
            ApiError.Unauthorized -> "unauthorized"
            ApiError.UpdateRequired -> "update_required"
            ApiError.IntegrityFailed -> "integrity_failed"
            is ApiError.RateLimited -> "rate_limited"
            ApiError.Unavailable -> "unavailable"
            ApiError.NotFound -> "not_found"
            is ApiError.Rejected -> "rejected:" + (error.code?.takeIf { it.isSafeCode() } ?: error.status.toString())
            is ApiError.Server -> "server:" + (error.status?.toString() ?: "unreadable")
        }

    // Problem codes are stable upper-case identifiers (API_SPEC.md section 3). Anything else is not stored.
    private fun String.isSafeCode(): Boolean = length in 1..MAX_CODE_LENGTH && all { it.isUpperCase() || it == '_' || it.isDigit() }

    private const val MILLIS_PER_SECOND = 1_000L
    private const val MAX_CODE_LENGTH = 40
}

/** Network an upload may use (API_SPEC.md section 6: Wi-Fi only by default). */
enum class UploadNetwork {
    /** Wi-Fi or another unmetered network. The default. */
    UNMETERED,

    /** Any connection, including mobile data. Only when the user allowed it. */
    CONNECTED,
    ;

    companion object {
        fun forSettings(uploadOnMobileData: Boolean): UploadNetwork = if (uploadOnMobileData) CONNECTED else UNMETERED
    }
}
