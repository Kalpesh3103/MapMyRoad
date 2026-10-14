package com.mapmyroad.sensing

import com.mapmyroad.di.ApplicationScope
import com.mapmyroad.domain.model.StopReason
import com.mapmyroad.domain.model.Trip
import com.mapmyroad.domain.repository.TripRepository
import com.mapmyroad.domain.session.TripSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Closes trips left open by a killed service (UI_UX_GUIDELINES.md section 8, "Service killed"; PRD: no data loss
 * for completed trips).
 *
 * [TripTrackingService] returns START_NOT_STICKY, so after process death nothing restarts recording. Instead,
 * on the next app start every unfinished trip is closed with the data persisted so far (points, hazards and samples
 * are written every few seconds), ending at its last track point. The stop reason is PERMISSION_REVOKED when fine
 * location is no longer granted (Android kills the process when a permission is revoked), otherwise SERVICE_KILLED.
 * The UI can then show "Recording stopped unexpectedly" for trips with those reasons.
 *
 * A trip that the live session owns (Recording or Finishing with the same ID) is never touched: the service
 * publishes the Recording state before it inserts the trip row, so a trip started during recovery is skipped.
 */
@Singleton
class TripRecovery
    @Inject
    constructor(
        private val tripRepository: TripRepository,
        private val store: TripSessionStore,
        private val locationPermissionChecker: LocationPermissionChecker,
        @ApplicationScope private val applicationScope: CoroutineScope,
    ) {
        private val hasLaunched = AtomicBoolean(false)

        /** Runs [recoverUnfinishedTrips] once per process, in the background. Called from Application.onCreate. */
        fun launchRecovery() {
            if (!hasLaunched.compareAndSet(false, true)) return
            applicationScope.launch {
                val recoveredCount = recoverUnfinishedTrips()
                if (recoveredCount > 0) Timber.i("Recovered %d unfinished trip(s)", recoveredCount)
            }
        }

        /** Returns the number of trips closed. */
        suspend fun recoverUnfinishedTrips(): Int {
            val unfinished =
                try {
                    tripRepository.findUnfinishedTrips()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Recovery: cannot read unfinished trips")
                    return 0
                }
            if (unfinished.isEmpty()) return 0
            val stopReason =
                if (locationPermissionChecker.hasFineLocation()) StopReason.SERVICE_KILLED else StopReason.PERMISSION_REVOKED
            var recoveredCount = 0
            for (trip in unfinished) {
                if (isOwnedByLiveSession(trip.id)) continue
                if (closeTrip(trip, stopReason)) recoveredCount++
            }
            return recoveredCount
        }

        private fun isOwnedByLiveSession(tripId: String): Boolean =
            when (val current = store.state.value) {
                is TripSessionState.Recording -> current.tripId == tripId
                is TripSessionState.Finishing -> current.tripId == tripId
                TripSessionState.Idle -> false
            }

        private suspend fun closeTrip(
            trip: Trip,
            stopReason: StopReason,
        ): Boolean =
            try {
                val lastPointAtMs = tripRepository.observeTripPoints(trip.id).first().maxOfOrNull { it.tMs }
                val endedAtMs = maxOf(lastPointAtMs ?: trip.startedAtMs, trip.startedAtMs)
                // Re-check right before writing: a trip started in the meantime must not be closed.
                if (isOwnedByLiveSession(trip.id)) {
                    false
                } else {
                    val finished = tripRepository.finishTrip(trip.id, endedAtMs, stopReason)
                    Timber.i("Recovery: trip %s closed (%s)", trip.id, stopReason.code)
                    finished != null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Recovery: cannot close trip %s", trip.id)
                false
            }
    }
