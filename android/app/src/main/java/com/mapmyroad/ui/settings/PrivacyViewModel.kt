package com.mapmyroad.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapmyroad.domain.repository.SettingsRepository
import com.mapmyroad.domain.repository.TripRepository
import com.mapmyroad.domain.session.TripSessionController
import com.mapmyroad.domain.session.TripSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class PrivacyUiState(
    /** Deleting is blocked while a trip is recording or being saved. */
    val isTripActive: Boolean = false,
    val isDeleting: Boolean = false,
    val hasDeleteFailed: Boolean = false,
)

private data class DeleteAllState(
    val isDeleting: Boolean = false,
    val hasFailed: Boolean = false,
)

/** Privacy and data (S11). */
@HiltViewModel
class PrivacyViewModel
    @Inject
    constructor(
        private val tripRepository: TripRepository,
        private val settingsRepository: SettingsRepository,
        private val tripSessionController: TripSessionController,
    ) : ViewModel() {
        private val deleteState = MutableStateFlow(DeleteAllState())

        val uiState: StateFlow<PrivacyUiState> =
            combine(tripSessionController.state, deleteState) { session, delete ->
                PrivacyUiState(
                    isTripActive = session !is TripSessionState.Idle,
                    isDeleting = delete.isDeleting,
                    hasDeleteFailed = delete.hasFailed,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PrivacyUiState())

        /**
         * Deletes every trip, hazard and setting on this phone (v1 stores nothing elsewhere). Settings are cleared
         * last: that resets "onboarding completed", and the root gate then shows onboarding again.
         */
        fun onDeleteAllData() {
            if (deleteState.value.isDeleting) return
            if (tripSessionController.state.value !is TripSessionState.Idle) return
            deleteState.value = DeleteAllState(isDeleting = true)
            viewModelScope.launch {
                try {
                    tripRepository.deleteAllTrips()
                    settingsRepository.clearAll()
                    deleteState.value = DeleteAllState()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Deleting all data failed")
                    deleteState.value = DeleteAllState(hasFailed = true)
                }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
