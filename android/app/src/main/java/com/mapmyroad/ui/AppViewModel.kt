package com.mapmyroad.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapmyroad.domain.repository.SettingsRepository
import com.mapmyroad.domain.session.TripSessionController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Which top-level UI to show. [Loading] renders an empty surface so onboarding never flashes. */
sealed interface AppGateState {
    data object Loading : AppGateState

    data object Onboarding : AppGateState

    data object Main : AppGateState
}

/** Activity-scoped state for the root gate and app-wide navigation events. */
@HiltViewModel
class AppViewModel
    @Inject
    constructor(
        settingsRepository: SettingsRepository,
        tripSessionController: TripSessionController,
    ) : ViewModel() {
        val gateState: StateFlow<AppGateState> =
            settingsRepository.settings
                .map { settings -> if (settings.onboardingCompleted) AppGateState.Main else AppGateState.Onboarding }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppGateState.Loading)

        /** Each finished trip ID, so the shell can open its summary (PRD flow 7.2 step 4). */
        val finishedTrips: Flow<String> = tripSessionController.finishedTrips

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
