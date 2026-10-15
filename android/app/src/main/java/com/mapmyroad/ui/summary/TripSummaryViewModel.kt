package com.mapmyroad.ui.summary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapmyroad.data.upload.TripUploadState
import com.mapmyroad.data.upload.UploadStatusRepository
import com.mapmyroad.domain.model.Trip
import com.mapmyroad.domain.repository.SettingsRepository
import com.mapmyroad.domain.repository.TripRepository
import com.mapmyroad.ui.AppRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

sealed interface TripSummaryUiState {
    data object Loading : TripSummaryUiState

    /** The trip was deleted or never existed. */
    data object NotFound : TripSummaryUiState

    data class Loaded(
        val trip: Trip,
        val hasDeleteFailed: Boolean,
        /** v2 sharing line (UI_UX_GUIDELINES.md S4). */
        val uploadState: TripUploadState = TripUploadState.NOT_SHARED,
        /** True when uploads wait for Wi-Fi ("Waiting for Wi-Fi to share"). */
        val uploadsNeedWifi: Boolean = true,
    ) : TripSummaryUiState
}

sealed interface TripSummaryEvent {
    data object TripDeleted : TripSummaryEvent
}

private data class DeleteState(
    val isDeleting: Boolean = false,
    val hasFailed: Boolean = false,
)

@HiltViewModel
class TripSummaryViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val tripRepository: TripRepository,
        uploadStatusRepository: UploadStatusRepository,
        settingsRepository: SettingsRepository,
    ) : ViewModel() {
        val tripId: String =
            requireNotNull(savedStateHandle.get<String>(AppRoutes.TRIP_ID_ARG)) { "Trip summary opened without a trip ID" }

        private val deleteState = MutableStateFlow(DeleteState())
        private val eventChannel = Channel<TripSummaryEvent>(Channel.BUFFERED)
        val events: Flow<TripSummaryEvent> = eventChannel.receiveAsFlow()

        val uiState: StateFlow<TripSummaryUiState> =
            combine(
                tripRepository.observeTrip(tripId),
                deleteState,
                uploadStatusRepository.observeUploadState(tripId),
                settingsRepository.settings.map { it.uploadOnMobileData },
            ) { trip, delete, uploadState, uploadOnMobileData ->
                when {
                    // While deleting, the trip disappears; show loading instead of "not found" until we navigate back.
                    delete.isDeleting -> TripSummaryUiState.Loading
                    trip == null -> TripSummaryUiState.NotFound
                    else ->
                        TripSummaryUiState.Loaded(
                            trip = trip,
                            hasDeleteFailed = delete.hasFailed,
                            uploadState = uploadState,
                            uploadsNeedWifi = !uploadOnMobileData,
                        )
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TripSummaryUiState.Loading)

        fun onDeleteTrip() {
            if (deleteState.value.isDeleting) return
            deleteState.value = DeleteState(isDeleting = true)
            viewModelScope.launch {
                try {
                    tripRepository.deleteTrip(tripId)
                    eventChannel.send(TripSummaryEvent.TripDeleted)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Deleting a trip failed")
                    deleteState.value = DeleteState(hasFailed = true)
                }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
