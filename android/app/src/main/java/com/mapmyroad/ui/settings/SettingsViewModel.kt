package com.mapmyroad.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val isLoading: Boolean = true,
    val vehicleType: VehicleType? = null,
    val isAutoPauseEnabled: Boolean = true,
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        val uiState: StateFlow<SettingsUiState> =
            settingsRepository.settings
                .map { settings ->
                    SettingsUiState(
                        isLoading = false,
                        vehicleType = settings.vehicleType,
                        isAutoPauseEnabled = settings.autoPauseEnabled,
                    )
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

        fun onVehicleSelected(vehicleType: VehicleType) {
            viewModelScope.launch { settingsRepository.setVehicleType(vehicleType) }
        }

        fun onAutoPauseChanged(isEnabled: Boolean) {
            viewModelScope.launch { settingsRepository.setAutoPauseEnabled(isEnabled) }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
