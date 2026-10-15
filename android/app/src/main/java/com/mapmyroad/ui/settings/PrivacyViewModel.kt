package com.mapmyroad.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapmyroad.domain.device.DeviceSession
import com.mapmyroad.domain.repository.SettingsRepository
import com.mapmyroad.domain.repository.TripRepository
import com.mapmyroad.domain.session.TripSessionController
import com.mapmyroad.domain.session.TripSessionState
import com.mapmyroad.domain.upload.UploadScheduler
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

/** Where "Delete my data" stands. */
enum class DeleteStatus {
    IDLE,
    DELETING,

    /** Server not reachable: offer Retry and "Delete only on this phone". Nothing was deleted. */
    SERVER_UNREACHABLE,

    /** Server refused: offer Retry and "Delete only on this phone". Nothing was deleted. */
    SERVER_FAILED,

    /** Local deletion failed (server data, if any, is already gone). Offer Retry. */
    LOCAL_FAILED,
}

data class PrivacyUiState(
    /** Deleting is blocked while a trip is recording or being saved. */
    val isTripActive: Boolean = false,
    /** This install has backend credentials, so there is shared data on the server to delete. */
    val isRegistered: Boolean = false,
    val isContributionEnabled: Boolean = false,
    val deleteStatus: DeleteStatus = DeleteStatus.IDLE,
) {
    val isDeleting: Boolean get() = deleteStatus == DeleteStatus.DELETING

    val canDelete: Boolean get() = !isTripActive && !isDeleting

    /** "Delete only on this phone" is offered only after the server deletion failed. */
    val canDeleteOnlyOnThisPhone: Boolean
        get() = canDelete && (deleteStatus == DeleteStatus.SERVER_UNREACHABLE || deleteStatus == DeleteStatus.SERVER_FAILED)
}

/** Privacy and data (S11). */
@HiltViewModel
class PrivacyViewModel
    @Inject
    constructor(
        tripRepository: TripRepository,
        settingsRepository: SettingsRepository,
        private val tripSessionController: TripSessionController,
        private val deviceSession: DeviceSession,
        uploadScheduler: UploadScheduler,
    ) : ViewModel() {
        private val dataDeletion = DataDeletion(tripRepository, settingsRepository, deviceSession, uploadScheduler)
        private val deleteStatus = MutableStateFlow(DeleteStatus.IDLE)
        private val isRegistered = MutableStateFlow(false)

        val uiState: StateFlow<PrivacyUiState> =
            combine(
                tripSessionController.state,
                settingsRepository.settings,
                isRegistered,
                deleteStatus,
            ) { session, settings, registered, status ->
                PrivacyUiState(
                    isTripActive = session !is TripSessionState.Idle,
                    isRegistered = registered,
                    isContributionEnabled = settings.contributionEnabled,
                    deleteStatus = status,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PrivacyUiState())

        init {
            refreshRegistration()
        }

        /** Server data first, then local data (see [DataDeletion]). Also used by Retry. */
        fun onDeleteMyData() {
            if (!tryStartDeleting()) return
            viewModelScope.launch { finish(dataDeletion.deleteEverywhere()) }
        }

        /** Fallback after a failed server deletion. Keeps the credentials so server data can be deleted later. */
        fun onDeleteOnlyOnThisPhone() {
            if (!isServerFailure(deleteStatus.value)) return
            if (!tryStartDeleting()) return
            viewModelScope.launch { finish(dataDeletion.deleteOnlyOnThisPhone()) }
        }

        private fun tryStartDeleting(): Boolean {
            if (deleteStatus.value == DeleteStatus.DELETING) return false
            if (tripSessionController.state.value !is TripSessionState.Idle) return false
            deleteStatus.value = DeleteStatus.DELETING
            return true
        }

        private fun finish(outcome: DeleteOutcome) {
            deleteStatus.value =
                when (outcome) {
                    // On success the root gate shows onboarding; on 426 the update screen takes over.
                    DeleteOutcome.DELETED, DeleteOutcome.UPDATE_REQUIRED -> DeleteStatus.IDLE
                    DeleteOutcome.SERVER_UNREACHABLE -> DeleteStatus.SERVER_UNREACHABLE
                    DeleteOutcome.SERVER_FAILED -> DeleteStatus.SERVER_FAILED
                    DeleteOutcome.LOCAL_FAILED -> DeleteStatus.LOCAL_FAILED
                }
            refreshRegistration()
        }

        private fun refreshRegistration() {
            viewModelScope.launch {
                isRegistered.value =
                    try {
                        deviceSession.isRegistered()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Could not read the registration state")
                        // Assume shared data may exist, so the confirmation never understates what is deleted.
                        true
                    }
            }
        }

        private fun isServerFailure(status: DeleteStatus): Boolean =
            status == DeleteStatus.SERVER_UNREACHABLE || status == DeleteStatus.SERVER_FAILED

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
