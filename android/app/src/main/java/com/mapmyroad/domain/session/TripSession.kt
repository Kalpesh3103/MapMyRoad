package com.mapmyroad.domain.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class GpsStatus {
    /** No fix yet, or the last fix is too old. Events are not recorded (UI_UX section 8). */
    WAITING,

    /** Accuracy good enough for detection. */
    GOOD,

    /** Fixes arrive but accuracy is too poor for detection. */
    WEAK,
}

enum class PauseReason {
    MANUAL,

    /** Auto-pause while stationary (PRD TS-7). Resumes by itself when moving again. */
    STATIONARY,
}

/** What the live trip screen shows (UI_UX section 5, S3). */
sealed interface TripSessionState {
    data object Idle : TripSessionState

    data class Recording(
        val tripId: String,
        /** Wall time the trip started, for the elapsed clock. */
        val startedAtMs: Long,
        val distanceM: Double,
        val minorCount: Int,
        val moderateCount: Int,
        val severeCount: Int,
        val gpsStatus: GpsStatus,
        /** Non-null while paused. */
        val pauseReason: PauseReason?,
    ) : TripSessionState

    /** The trip is being closed and summarized. */
    data class Finishing(
        val tripId: String,
    ) : TripSessionState
}

/**
 * Controls the foreground tracking service. Implemented in the sensing layer, used by the UI.
 * Commands are fire-and-forget; observe [state] for the result.
 */
interface TripSessionController {
    val state: StateFlow<TripSessionState>

    /** Emits the ID of each trip once it has been finished and summarized, so the UI can open its summary. */
    val finishedTrips: Flow<String>

    /** Starts a manual trip with the vehicle type from settings. No-op unless [state] is Idle. */
    fun startTrip()

    fun stopTrip()

    fun pauseTrip()

    fun resumeTrip()
}
