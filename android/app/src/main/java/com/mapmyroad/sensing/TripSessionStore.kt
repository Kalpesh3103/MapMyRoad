package com.mapmyroad.sensing

import com.mapmyroad.domain.session.TripSessionState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide live trip state. Written by [TripTrackingService] and its [TripRecorder], read through
 * [DefaultTripSessionController].
 *
 * Threading: every function is safe to call from any thread (MutableStateFlow.update is atomic, tryEmit is
 * thread-safe). The recorder writes from its sensor thread, the service from the main thread.
 */
@Singleton
class TripSessionStore
    @Inject
    constructor() {
        private val mutableState = MutableStateFlow<TripSessionState>(TripSessionState.Idle)
        val state: StateFlow<TripSessionState> = mutableState.asStateFlow()

        // replay 0: a finished trip is a one-off event for whoever is watching (the UI opens the summary).
        private val mutableFinishedTrips = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = FINISHED_BUFFER)
        val finishedTrips: SharedFlow<String> = mutableFinishedTrips.asSharedFlow()

        fun setState(newState: TripSessionState) {
            mutableState.value = newState
        }

        /** Applies [transform] only while [tripId] is the trip being recorded, so late updates cannot revive it. */
        fun updateRecording(
            tripId: String,
            transform: (TripSessionState.Recording) -> TripSessionState.Recording,
        ) {
            mutableState.update { current ->
                if (current is TripSessionState.Recording && current.tripId == tripId) transform(current) else current
            }
        }

        fun emitFinished(tripId: String) {
            mutableFinishedTrips.tryEmit(tripId)
        }

        private companion object {
            const val FINISHED_BUFFER = 8
        }
    }
