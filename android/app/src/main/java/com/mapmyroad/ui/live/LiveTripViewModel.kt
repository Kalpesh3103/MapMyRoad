package com.mapmyroad.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapmyroad.domain.session.GpsStatus
import com.mapmyroad.domain.session.PauseReason
import com.mapmyroad.domain.session.TripSessionController
import com.mapmyroad.domain.session.TripSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** What the live trip screen (S3) shows. */
sealed interface LiveTripUiState {
    /** No trip is running, for example after it was stopped from the notification. The screen closes itself. */
    data object NotRecording : LiveTripUiState

    data class Recording(
        val startedAtMs: Long,
        val distanceM: Double,
        val minorCount: Int,
        val moderateCount: Int,
        val severeCount: Int,
        val gpsStatus: GpsStatus,
        val pauseReason: PauseReason?,
    ) : LiveTripUiState

    /** The trip is being saved; the shell opens its summary when done. */
    data object Finishing : LiveTripUiState
}

@HiltViewModel
class LiveTripViewModel
    @Inject
    constructor(
        private val tripSessionController: TripSessionController,
    ) : ViewModel() {
        val uiState: StateFlow<LiveTripUiState> =
            tripSessionController.state
                .map { it.toUiState() }
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    tripSessionController.state.value.toUiState(),
                )

        fun onPause() = tripSessionController.pauseTrip()

        fun onResume() = tripSessionController.resumeTrip()

        fun onStop() = tripSessionController.stopTrip()

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

internal fun TripSessionState.toUiState(): LiveTripUiState =
    when (this) {
        TripSessionState.Idle -> LiveTripUiState.NotRecording
        is TripSessionState.Finishing -> LiveTripUiState.Finishing
        is TripSessionState.Recording ->
            LiveTripUiState.Recording(
                startedAtMs = startedAtMs,
                distanceM = distanceM,
                minorCount = minorCount,
                moderateCount = moderateCount,
                severeCount = severeCount,
                gpsStatus = gpsStatus,
                pauseReason = pauseReason,
            )
    }
