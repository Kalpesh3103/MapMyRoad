package com.mapmyroad.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapmyroad.domain.model.Trip
import com.mapmyroad.domain.repository.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

sealed interface TripsUiState {
    data object Loading : TripsUiState

    /** Finished trips, newest first. */
    data class Loaded(
        val trips: List<Trip>,
    ) : TripsUiState
}

sealed interface TripsEvent {
    data object TripDeleted : TripsEvent

    data object DeleteFailed : TripsEvent
}

/**
 * Trips list (S5). Deletion is confirmed with a dialog before it happens, instead of the "undo for 5 seconds"
 * snackbar in UI_UX_GUIDELINES.md S5: the repository has no restore, and a delayed delete would be lost if the
 * ViewModel is cleared before the snackbar times out.
 */
@HiltViewModel
class TripsViewModel
    @Inject
    constructor(
        private val tripRepository: TripRepository,
    ) : ViewModel() {
        private val eventChannel = Channel<TripsEvent>(Channel.BUFFERED)
        val events: Flow<TripsEvent> = eventChannel.receiveAsFlow()

        val uiState: StateFlow<TripsUiState> =
            tripRepository
                .observeFinishedTrips()
                .map<List<Trip>, TripsUiState> { TripsUiState.Loaded(it) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TripsUiState.Loading)

        fun onDeleteTrip(tripId: String) {
            viewModelScope.launch {
                try {
                    tripRepository.deleteTrip(tripId)
                    eventChannel.send(TripsEvent.TripDeleted)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Deleting a trip failed")
                    eventChannel.send(TripsEvent.DeleteFailed)
                }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
