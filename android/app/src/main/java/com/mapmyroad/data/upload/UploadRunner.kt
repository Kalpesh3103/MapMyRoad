package com.mapmyroad.data.upload

import com.mapmyroad.data.local.UploadQueueState
import com.mapmyroad.domain.device.DeviceSession
import com.mapmyroad.domain.model.ApiError
import com.mapmyroad.domain.model.ApiResult
import com.mapmyroad.domain.repository.SettingsRepository
import com.mapmyroad.domain.upload.TripTrimmer
import com.mapmyroad.domain.upload.UploadAction
import com.mapmyroad.domain.upload.UploadDecision
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

/** Result of one [UploadRunner.run], mapped to a WorkManager result by [UploadWorker]. */
enum class UploadRunOutcome {
    /** Nothing left to do now. */
    SUCCESS,

    /** Something is still waiting: WorkManager retries with exponential backoff. */
    RETRY,

    /** Stop without retrying (426 update required, or registration impossible). The next schedule starts over. */
    FAILURE,
}

/**
 * One upload run (PRD CM-1, API_SPEC.md 5.3 and 6): trims each queued trip with [TripTrimmer], builds requests with
 * [UploadPayloadBuilder], sends them and records progress in `upload_queue`.
 *
 * - Nothing happens unless the user opted in to contribute, and only trips that started at or after the last opt-in
 *   ([com.mapmyroad.domain.model.UserSettings.contributionEnabledAtMs]) are ever sent.
 * - Items of an accepted request are marked uploaded at once, so a retry after a partial upload sends only the rest.
 *   Item IDs are stable, so the server ignores anything sent twice anyway.
 * - Transient errors keep the trip pending and end the run with [UploadRunOutcome.RETRY]; a 4xx rejection marks only
 *   that trip failed; 426 stops the run. A 401 leads to one fresh registration per run.
 *
 * Logs contain local trip IDs and counts only, never coordinates, tokens or device identifiers.
 */
class UploadRunner
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val deviceSession: DeviceSession,
        private val store: UploadStore,
        private val uploader: EventsUploader,
        private val clock: UploadClock,
    ) {
        suspend fun run(): UploadRunOutcome {
            val settings = settingsRepository.settings.first()
            if (!settings.contributionEnabled) return UploadRunOutcome.SUCCESS
            // Without a recorded opt-in time nothing can be proven to be recorded after opt-in: upload nothing.
            val enabledAtMs = settings.contributionEnabledAtMs ?: return UploadRunOutcome.SUCCESS

            store.resetInProgress()
            store.refreshQueue(clock.nowMs(), enabledAtMs)
            if (store.countWaiting() == 0) return UploadRunOutcome.SUCCESS

            val registration = deviceSession.ensureRegistered()
            if (registration is ApiResult.Failure) {
                Timber.i("Upload run: registration failed (%s)", UploadDecision.category(registration.error))
                return outcomeOfRegistrationFailure(registration.error)
            }

            val runState = RunState()
            while (true) {
                val due = store.dueTrips(clock.nowMs(), TRIPS_PER_QUERY)
                if (due.isEmpty()) break
                for (queued in due) {
                    when (uploadTrip(queued, enabledAtMs, runState)) {
                        TripStep.CONTINUE -> Unit
                        TripStep.RETRY -> return UploadRunOutcome.RETRY
                        TripStep.STOP -> return UploadRunOutcome.FAILURE
                    }
                }
            }
            // Rows held back by Retry-After are still pending: come back after the backoff.
            return if (store.countWaiting() > 0) UploadRunOutcome.RETRY else UploadRunOutcome.SUCCESS
        }

        private suspend fun uploadTrip(
            queued: QueuedTrip,
            enabledAtMs: Long,
            runState: RunState,
        ): TripStep {
            val tripId = queued.tripId
            store.markInProgress(tripId)
            val source = store.load(tripId)
            if (source == null) {
                // The trip was deleted meanwhile; its row normally goes with it by cascade.
                finish(queued, UploadQueueState.FAILED, CATEGORY_MISSING_TRIP)
                return TripStep.CONTINUE
            }
            if (source.startedAtMs < enabledAtMs) {
                // Defence in depth: the queue query already excludes trips from before the opt-in.
                finish(queued, UploadQueueState.SKIPPED, UploadQueueState.CATEGORY_BEFORE_OPT_IN)
                return TripStep.CONTINUE
            }
            val vehicleType = source.vehicleType
            if (vehicleType == null) {
                finish(queued, UploadQueueState.FAILED, UploadDecision.CATEGORY_UNKNOWN_VEHICLE)
                return TripStep.CONTINUE
            }

            val trimmed = TripTrimmer.trim(source.points, source.hazards, source.samples)
            val requests = UploadPayloadBuilder.build(source.tripToken, vehicleType, trimmed, clock.nowMs())
            if (requests.isEmpty()) {
                val state = if (source.alreadyUploadedCount > 0) UploadQueueState.DONE else UploadQueueState.SKIPPED
                Timber.i("Upload trip %s: nothing to send (%s)", tripId, state)
                finish(queued, state, lastError = null)
                return TripStep.CONTINUE
            }

            for (request in requests) {
                var result = uploader.upload(request)
                if (result.isUnauthorized() && !runState.hasReregistered) {
                    runState.hasReregistered = true
                    // The token refresh already failed (API_SPEC.md section 6): register again, once per run.
                    deviceSession.clearCredentials()
                    val registration = deviceSession.ensureRegistered()
                    if (registration is ApiResult.Failure) {
                        return onFailure(queued, UploadDecision.forRegistrationError(registration.error))
                    }
                    result = uploader.upload(request)
                }
                when (result) {
                    is ApiResult.Success -> {
                        store.markUploaded(request.events.map { it.id }, request.windows.map { it.id })
                        val response = result.value
                        Timber.i(
                            "Upload trip %s: accepted %d/%d, duplicates %d/%d, rejected %d",
                            tripId,
                            response.accepted.events,
                            response.accepted.windows,
                            response.duplicates.events,
                            response.duplicates.windows,
                            response.rejected.size,
                        )
                    }
                    is ApiResult.Failure -> {
                        val action = UploadDecision.forUploadError(result.error)
                        // A second 401 in the same run: try again later instead of looping.
                        val effective =
                            if (action is UploadAction.Reregister) {
                                UploadAction.RetryLater(UploadDecision.category(result.error))
                            } else {
                                action
                            }
                        return onFailure(queued, effective)
                    }
                }
            }
            finish(queued, UploadQueueState.DONE, lastError = null)
            return TripStep.CONTINUE
        }

        private suspend fun onFailure(
            queued: QueuedTrip,
            action: UploadAction,
        ): TripStep {
            Timber.i("Upload trip %s: %s", queued.tripId, action)
            return when (action) {
                is UploadAction.RetryLater -> {
                    val attempts = queued.attempts + 1
                    if (attempts >= UploadDecision.MAX_ATTEMPTS) {
                        finish(queued, UploadQueueState.FAILED, UploadDecision.CATEGORY_RETRIES_EXHAUSTED, attempts)
                    } else {
                        store.updateQueue(
                            tripId = queued.tripId,
                            state = UploadQueueState.PENDING,
                            attempts = attempts,
                            nextAttemptAtMs = clock.nowMs() + action.notBeforeDelayMs,
                            lastError = action.category,
                        )
                    }
                    TripStep.RETRY
                }
                is UploadAction.FailTrip -> {
                    finish(queued, UploadQueueState.FAILED, action.category)
                    TripStep.CONTINUE
                }
                is UploadAction.Stop -> {
                    // Kept pending: after an app update (426) or a later registration the trip can still go.
                    store.updateQueue(queued.tripId, UploadQueueState.PENDING, queued.attempts, clock.nowMs(), action.category)
                    TripStep.STOP
                }
                UploadAction.Reregister -> {
                    store.updateQueue(queued.tripId, UploadQueueState.PENDING, queued.attempts + 1, clock.nowMs(), "unauthorized")
                    TripStep.RETRY
                }
            }
        }

        private suspend fun finish(
            queued: QueuedTrip,
            state: String,
            lastError: String?,
            attempts: Int = queued.attempts,
        ) {
            store.updateQueue(queued.tripId, state, attempts, clock.nowMs(), lastError)
        }

        private fun outcomeOfRegistrationFailure(error: ApiError): UploadRunOutcome =
            when (UploadDecision.forRegistrationError(error)) {
                is UploadAction.RetryLater -> UploadRunOutcome.RETRY
                else -> UploadRunOutcome.FAILURE
            }

        private fun ApiResult<*>.isUnauthorized(): Boolean = this is ApiResult.Failure && error == ApiError.Unauthorized

        private class RunState {
            var hasReregistered: Boolean = false
        }

        private enum class TripStep {
            CONTINUE,
            RETRY,
            STOP,
        }

        companion object {
            const val CATEGORY_MISSING_TRIP = "missing_trip"
            private const val TRIPS_PER_QUERY = 20
        }
    }
