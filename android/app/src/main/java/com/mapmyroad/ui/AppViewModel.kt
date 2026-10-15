package com.mapmyroad.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapmyroad.domain.device.DeviceSession
import com.mapmyroad.domain.repository.SettingsRepository
import com.mapmyroad.domain.session.TripSessionController
import com.mapmyroad.domain.session.TripSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Which top-level UI to show. [Loading] renders an empty surface so onboarding never flashes. */
sealed interface AppGateState {
    data object Loading : AppGateState

    data object Onboarding : AppGateState

    data object Main : AppGateState

    /** The server refuses this app version (API `426`, UI_UX_GUIDELINES.md section 8). */
    data object UpdateRequired : AppGateState
}

/**
 * The update gate blocks the app only while no trip is active. A trip that is recording or being saved keeps the
 * live trip UI: an interruption mid-trip would break the "no prompts during a trip" rule (UI_UX_GUIDELINES.md
 * section 6), and recording works without the server anyway. The gate appears as soon as the trip has ended.
 */
fun resolveAppGate(
    onboardingCompleted: Boolean,
    updateRequired: Boolean,
    isTripActive: Boolean,
): AppGateState =
    when {
        updateRequired && !isTripActive -> AppGateState.UpdateRequired
        onboardingCompleted -> AppGateState.Main
        else -> AppGateState.Onboarding
    }

/** Activity-scoped state for the root gate and app-wide navigation events. */
@HiltViewModel
class AppViewModel
    @Inject
    constructor(
        settingsRepository: SettingsRepository,
        tripSessionController: TripSessionController,
        deviceSession: DeviceSession,
    ) : ViewModel() {
        val gateState: StateFlow<AppGateState> =
            combine(
                settingsRepository.settings.map { settings -> settings.onboardingCompleted },
                deviceSession.updateRequired,
                tripSessionController.state.map { session -> session !is TripSessionState.Idle },
            ) { onboardingCompleted, updateRequired, isTripActive ->
                resolveAppGate(onboardingCompleted, updateRequired, isTripActive)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppGateState.Loading)

        /** Each finished trip ID, so the shell can open its summary (PRD flow 7.2 step 4). */
        val finishedTrips: Flow<String> = tripSessionController.finishedTrips

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
