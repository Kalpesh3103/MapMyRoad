package com.mapmyroad.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapmyroad.data.upload.UploadStatusRepository
import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val isLoading: Boolean = true,
    val vehicleType: VehicleType? = null,
    val isAutoPauseEnabled: Boolean = true,
    /** PRD CM-1, off until the user accepts the consent screen. */
    val isContributionEnabled: Boolean = false,
    val isUploadOnMobileData: Boolean = false,
    /** Finished trips queued and not uploaded yet. */
    val waitingTripCount: Int = 0,
    /** The full-screen contribution consent (UI_UX section 13) is shown instead of the settings list. */
    val isConsentVisible: Boolean = false,
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        uploadStatusRepository: UploadStatusRepository,
    ) : ViewModel() {
        private val isConsentRequested = MutableStateFlow(false)

        val uiState: StateFlow<SettingsUiState> =
            combine(
                settingsRepository.settings,
                uploadStatusRepository.observeWaitingTripCount(),
                isConsentRequested,
            ) { settings, waitingTripCount, isConsentVisible ->
                SettingsUiState(
                    isLoading = false,
                    vehicleType = settings.vehicleType,
                    isAutoPauseEnabled = settings.autoPauseEnabled,
                    isContributionEnabled = settings.contributionEnabled,
                    isUploadOnMobileData = settings.uploadOnMobileData,
                    waitingTripCount = waitingTripCount,
                    isConsentVisible = isConsentVisible,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

        fun onVehicleSelected(vehicleType: VehicleType) {
            viewModelScope.launch { settingsRepository.setVehicleType(vehicleType) }
        }

        fun onAutoPauseChanged(isEnabled: Boolean) {
            viewModelScope.launch { settingsRepository.setAutoPauseEnabled(isEnabled) }
        }

        /** Turning contribution on the first time shows the consent screen instead (UI_UX section 13). */
        fun onContributionChanged(isEnabled: Boolean) {
            viewModelScope.launch {
                when {
                    !isEnabled -> settingsRepository.setContributionEnabled(false)
                    settingsRepository.settings.first().contributionConsentShown ->
                        settingsRepository.setContributionEnabled(true)
                    else -> isConsentRequested.value = true
                }
            }
        }

        fun onUploadOnMobileDataChanged(isEnabled: Boolean) {
            viewModelScope.launch { settingsRepository.setUploadOnMobileData(isEnabled) }
        }

        /** "What is shared": the same consent text, informational when already contributing. */
        fun onOpenSharingInfo() {
            isConsentRequested.value = true
        }

        /** "Share anonymously". Also records that the consent was shown (SettingsRepository contract). */
        fun onConsentAccepted() {
            isConsentRequested.value = false
            viewModelScope.launch { settingsRepository.setContributionEnabled(true) }
        }

        /** "Not now", Close or back. Contribution stays as it was; the consent appears again next time. */
        fun onConsentDismissed() {
            isConsentRequested.value = false
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
