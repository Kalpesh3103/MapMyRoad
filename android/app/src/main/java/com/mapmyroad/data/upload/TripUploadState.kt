package com.mapmyroad.data.upload

import com.mapmyroad.data.local.UploadQueueState

/** Upload status of one trip, for the trip summary line (UI_UX_GUIDELINES.md S4). */
enum class TripUploadState {
    /** Contribution is off and the trip was not shared. Show nothing. */
    NOT_SHARED,

    /** Queued: "Waiting for Wi-Fi to share" (or "Waiting to share" when mobile data is allowed). */
    WAITING,

    /** "Shared anonymously". */
    SHARED,

    /** Nothing to share after trimming (trip under about 1 km, or too old). */
    SKIPPED,

    /** The server rejected the trip or retries ran out. */
    FAILED,
}

/**
 * Pure mapping to [TripUploadState]. [queueState] and [lastError] come from the trip's queue row (null when it has
 * none). A trip that started before the last opt-in ([contributionEnabledAtMs], null while contribution is off) is
 * never shared, so it shows nothing rather than "Waiting".
 */
fun tripUploadStateOf(
    queueState: String?,
    lastError: String?,
    tripStartedAtMs: Long?,
    contributionEnabledAtMs: Long?,
): TripUploadState {
    val isEligible = contributionEnabledAtMs != null && tripStartedAtMs != null && tripStartedAtMs >= contributionEnabledAtMs
    return when (queueState) {
        UploadQueueState.DONE -> TripUploadState.SHARED
        UploadQueueState.SKIPPED ->
            if (lastError == UploadQueueState.CATEGORY_BEFORE_OPT_IN) TripUploadState.NOT_SHARED else TripUploadState.SKIPPED
        UploadQueueState.FAILED -> TripUploadState.FAILED
        else -> if (isEligible) TripUploadState.WAITING else TripUploadState.NOT_SHARED
    }
}
