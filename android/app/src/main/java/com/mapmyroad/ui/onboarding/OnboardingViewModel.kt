package com.mapmyroad.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Onboarding steps (UI_UX_GUIDELINES.md S1). Auto-start is not part of v1 onboarding (M2-14). */
enum class OnboardingStep {
    WELCOME,
    VEHICLE,

    /** Rationale for location while using the app, shown just before the system prompt (PRD PV-4). */
    LOCATION,

    /** Rationale for notifications, Android 13 and later only. */
    NOTIFICATIONS,
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val selectedVehicle: VehicleType? = null,
    /** The permission of the current step was declined; explain what stops working. */
    val isPermissionDenied: Boolean = false,
    val isSaving: Boolean = false,
    /** Settings are saved; the root gate switches to the main shell. */
    val isCompleted: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(OnboardingUiState())
        val uiState: StateFlow<OnboardingUiState> = mutableUiState.asStateFlow()

        /**
         * Called whenever onboarding is shown. This ViewModel is activity scoped, so after "Delete all data"
         * returns the user to onboarding, the finished state from the first run must be reset.
         */
        fun onShown() {
            if (mutableUiState.value.isCompleted) {
                mutableUiState.value = OnboardingUiState()
            }
        }

        fun onGetStarted() = moveTo(OnboardingStep.VEHICLE)

        fun onVehicleSelected(vehicleType: VehicleType) {
            mutableUiState.update { it.copy(selectedVehicle = vehicleType) }
        }

        fun onVehicleConfirmed() {
            if (mutableUiState.value.selectedVehicle != null) {
                moveTo(OnboardingStep.LOCATION)
            }
        }

        /** Result of the system prompt, or "already granted" when the step is entered. */
        fun onPermissionResult(
            isGranted: Boolean,
            areNotificationsRuntime: Boolean,
        ) {
            if (isGranted) {
                advanceFromPermissionStep(areNotificationsRuntime)
            } else {
                mutableUiState.update { it.copy(isPermissionDenied = true) }
            }
        }

        /** "Not now" on a rationale screen. Declining never blocks onboarding (S1). */
        fun onPermissionDeclined() {
            mutableUiState.update { it.copy(isPermissionDenied = true) }
        }

        fun onContinueAfterDenial(areNotificationsRuntime: Boolean) = advanceFromPermissionStep(areNotificationsRuntime)

        fun onBack() {
            when (mutableUiState.value.step) {
                OnboardingStep.WELCOME -> Unit
                OnboardingStep.VEHICLE -> moveTo(OnboardingStep.WELCOME)
                // Permission steps go back to the vehicle step, because a granted permission step is skipped.
                OnboardingStep.LOCATION, OnboardingStep.NOTIFICATIONS -> moveTo(OnboardingStep.VEHICLE)
            }
        }

        private fun advanceFromPermissionStep(areNotificationsRuntime: Boolean) {
            when (mutableUiState.value.step) {
                OnboardingStep.LOCATION ->
                    if (areNotificationsRuntime) moveTo(OnboardingStep.NOTIFICATIONS) else finish()
                OnboardingStep.NOTIFICATIONS -> finish()
                OnboardingStep.WELCOME, OnboardingStep.VEHICLE -> Unit
            }
        }

        private fun moveTo(step: OnboardingStep) {
            mutableUiState.update { it.copy(step = step, isPermissionDenied = false) }
        }

        private fun finish() {
            val current = mutableUiState.value
            if (current.isSaving || current.isCompleted) return
            val vehicle = current.selectedVehicle
            if (vehicle == null) {
                moveTo(OnboardingStep.VEHICLE)
                return
            }
            mutableUiState.update { it.copy(isSaving = true) }
            viewModelScope.launch {
                settingsRepository.setVehicleType(vehicle)
                settingsRepository.setOnboardingCompleted(true)
                mutableUiState.update { it.copy(isSaving = false, isCompleted = true) }
            }
        }
    }
