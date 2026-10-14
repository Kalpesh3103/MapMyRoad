package com.mapmyroad.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.model.StopReason
import com.mapmyroad.domain.model.Trip
import com.mapmyroad.domain.repository.SettingsRepository
import com.mapmyroad.domain.repository.TripRepository
import com.mapmyroad.domain.session.TripSessionController
import com.mapmyroad.domain.session.TripSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

enum class HomeSessionStatus {
    IDLE,
    RECORDING,
    FINISHING,
}

data class HomeUiState(
    val isLoading: Boolean = true,
    val vehicleType: VehicleType? = null,
    val latestTrip: Trip? = null,
    val sessionStatus: HomeSessionStatus = HomeSessionStatus.IDLE,
    val isStarting: Boolean = false,
    val hasStartFailed: Boolean = false,
    /** The latest trip was closed after the service was killed (UI_UX_GUIDELINES.md section 8). */
    val isServiceKilledCardVisible: Boolean = false,
    /** Dismissed for this session only; shown again after the app process restarts. */
    val isBatteryCardDismissed: Boolean = false,
)

sealed interface HomeEvent {
    data object OpenLiveTrip : HomeEvent
}

private data class HomeLocalState(
    val isStarting: Boolean = false,
    val hasStartFailed: Boolean = false,
    val isBatteryCardDismissed: Boolean = false,
    val dismissedServiceKilledTripId: String? = null,
)

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        tripRepository: TripRepository,
        private val tripSessionController: TripSessionController,
    ) : ViewModel() {
        private val localState = MutableStateFlow(HomeLocalState())
        private val eventChannel = Channel<HomeEvent>(Channel.BUFFERED)

        /** One-off navigation events, consumed once by the screen. */
        val events: Flow<HomeEvent> = eventChannel.receiveAsFlow()

        val uiState: StateFlow<HomeUiState> =
            combine(
                settingsRepository.settings,
                tripRepository.observeLatestFinishedTrip(),
                tripSessionController.state,
                localState,
            ) { settings, latestTrip, session, local ->
                HomeUiState(
                    isLoading = false,
                    vehicleType = settings.vehicleType,
                    latestTrip = latestTrip,
                    sessionStatus = session.toStatus(),
                    isStarting = local.isStarting,
                    hasStartFailed = local.hasStartFailed,
                    isServiceKilledCardVisible =
                        latestTrip != null &&
                            latestTrip.stopReason == StopReason.SERVICE_KILLED &&
                            latestTrip.id != local.dismissedServiceKilledTripId,
                    isBatteryCardDismissed = local.isBatteryCardDismissed,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

        fun onVehicleSelected(vehicleType: VehicleType) {
            viewModelScope.launch { settingsRepository.setVehicleType(vehicleType) }
        }

        /**
         * Starts a trip. The screen calls this only after the location permission is granted. Navigates to the
         * live trip once the session reports Recording.
         */
        fun onStartTrip() {
            if (localState.value.isStarting) return
            if (tripSessionController.state.value is TripSessionState.Recording) {
                eventChannel.trySend(HomeEvent.OpenLiveTrip)
                return
            }
            localState.update { it.copy(isStarting = true, hasStartFailed = false) }
            viewModelScope.launch {
                tripSessionController.startTrip()
                val recording =
                    withTimeoutOrNull(START_TIMEOUT_MS) {
                        tripSessionController.state.first { it is TripSessionState.Recording }
                    }
                localState.update { it.copy(isStarting = false, hasStartFailed = recording == null) }
                if (recording != null) {
                    eventChannel.send(HomeEvent.OpenLiveTrip)
                }
            }
        }

        fun onDismissBatteryCard() {
            localState.update { it.copy(isBatteryCardDismissed = true) }
        }

        fun onDismissServiceKilledCard() {
            val tripId = uiState.value.latestTrip?.id ?: return
            localState.update { it.copy(dismissedServiceKilledTripId = tripId) }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L

            /** How long to wait for the service to report Recording before showing an error. Initial value. */
            const val START_TIMEOUT_MS = 10_000L
        }
    }

private fun TripSessionState.toStatus(): HomeSessionStatus =
    when (this) {
        TripSessionState.Idle -> HomeSessionStatus.IDLE
        is TripSessionState.Recording -> HomeSessionStatus.RECORDING
        is TripSessionState.Finishing -> HomeSessionStatus.FINISHING
    }
