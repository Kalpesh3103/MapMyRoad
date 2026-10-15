package com.mapmyroad.data.upload

import com.mapmyroad.data.local.UploadQueueState
import com.mapmyroad.detector.TripScoring
import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.device.DeviceSession
import com.mapmyroad.domain.model.ApiError
import com.mapmyroad.domain.model.ApiResult
import com.mapmyroad.domain.model.RecordedHazard
import com.mapmyroad.domain.model.SegmentSample
import com.mapmyroad.domain.model.TripPoint
import com.mapmyroad.domain.model.UserSettings
import com.mapmyroad.domain.repository.SettingsRepository
import com.mapmyroad.domain.upload.TripTrimmer
import com.mapmyroad.domain.upload.UploadDecision
import com.mapmyroad.domain.upload.hazardAt
import com.mapmyroad.domain.upload.northTrack
import com.mapmyroad.domain.upload.windowBetween
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadRunnerTest {
    private val settings = FakeSettingsRepository(contributionEnabled = true, enabledAtMs = OPT_IN_MS)
    private val device = FakeDeviceSession()
    private val store = FakeUploadStore()
    private val uploader = FakeUploader()
    private val runner = UploadRunner(settings, device, store, uploader, clock = { NOW_MS })

    @Test
    fun contributionOff_doesNothing() =
        runBlocking {
            settings.state.value = settings.state.value.copy(contributionEnabled = false)
            store.addTrip(longTrip("a"))

            assertEquals(UploadRunOutcome.SUCCESS, runner.run())
            assertEquals(0, device.registerCalls)
            assertTrue(uploader.requests.isEmpty())
        }

    @Test
    fun nothingQueued_doesNotRegister() =
        runBlocking {
            assertEquals(UploadRunOutcome.SUCCESS, runner.run())
            assertEquals(0, device.registerCalls)
        }

    @Test
    fun longTrip_isUploadedTrimmed_andMarkedDone() =
        runBlocking {
            val trip = longTrip("a")
            store.addTrip(trip)

            assertEquals(UploadRunOutcome.SUCCESS, runner.run())

            assertEquals(UploadQueueState.DONE, store.rows.getValue("a").state)
            val sent = uploader.requests.flatMap { it.events }
            assertTrue(sent.isNotEmpty())
            // Nothing sent lies within 500 m of the start or end (PV-2).
            val first = trip.points.first()
            val last = trip.points.last()
            sent.forEach { event ->
                assertTrue(TripScoring.haversineM(first.lat, first.lon, event.lat, event.lon) >= TripTrimmer.TRIM_DISTANCE_M)
                assertTrue(TripScoring.haversineM(last.lat, last.lon, event.lat, event.lon) >= TripTrimmer.TRIM_DISTANCE_M)
            }
            assertEquals(sent.map { it.id }.toSet(), store.uploadedIds.filter { it.startsWith("a-h") }.toSet())
            assertTrue(uploader.requests.all { it.tripToken == "token-a" })
        }

    @Test
    fun shortTrip_isSkippedWithoutUpload() =
        runBlocking {
            val track = northTrack(lengthM = 900.0)
            store.addTrip(trip("short", track, hazards = track.map { hazardAt("short-h${it.tMs}", it) }))

            assertEquals(UploadRunOutcome.SUCCESS, runner.run())

            assertEquals(UploadQueueState.SKIPPED, store.rows.getValue("short").state)
            assertTrue(uploader.requests.isEmpty())
        }

    @Test
    fun registrationNetworkError_retries_andIntegrityFailure_stops() =
        runBlocking {
            store.addTrip(longTrip("a"))
            device.registerResults += ApiResult.Failure(ApiError.Network)
            assertEquals(UploadRunOutcome.RETRY, runner.run())

            device.registerResults += ApiResult.Failure(ApiError.IntegrityFailed)
            assertEquals(UploadRunOutcome.FAILURE, runner.run())
            assertTrue(uploader.requests.isEmpty())
            assertEquals(UploadQueueState.PENDING, store.rows.getValue("a").state)
        }

    @Test
    fun rejectedTrip_failsAlone_andTheNextTripStillUploads() =
        runBlocking {
            store.addTrip(longTrip("a"))
            store.addTrip(longTrip("b"))
            uploader.results += ApiResult.Failure(ApiError.Rejected(400, "INVALID_REQUEST"))

            assertEquals(UploadRunOutcome.SUCCESS, runner.run())

            assertEquals(UploadQueueState.FAILED, store.rows.getValue("a").state)
            assertEquals("rejected:INVALID_REQUEST", store.rows.getValue("a").lastError)
            assertEquals(UploadQueueState.DONE, store.rows.getValue("b").state)
        }

    @Test
    fun networkError_keepsTheTripPending_andRetries() =
        runBlocking {
            store.addTrip(longTrip("a"))
            uploader.results += ApiResult.Failure(ApiError.Network)

            assertEquals(UploadRunOutcome.RETRY, runner.run())

            val row = store.rows.getValue("a")
            assertEquals(UploadQueueState.PENDING, row.state)
            assertEquals(1, row.attempts)
            assertEquals("network", row.lastError)
        }

    @Test
    fun rateLimited_holdsTheTripUntilRetryAfter() =
        runBlocking {
            store.addTrip(longTrip("a"))
            uploader.results += ApiResult.Failure(ApiError.RateLimited(retryAfterSec = 60L))

            assertEquals(UploadRunOutcome.RETRY, runner.run())

            assertEquals(NOW_MS + 60_000L, store.rows.getValue("a").nextAttemptAtMs)
        }

    @Test
    fun retriesExhausted_marksTheTripFailed() =
        runBlocking {
            store.addTrip(longTrip("a"), attempts = UploadDecision.MAX_ATTEMPTS - 1)
            uploader.results += ApiResult.Failure(ApiError.Server(500))

            runner.run()

            assertEquals(UploadQueueState.FAILED, store.rows.getValue("a").state)
            assertEquals(UploadDecision.CATEGORY_RETRIES_EXHAUSTED, store.rows.getValue("a").lastError)
        }

    @Test
    fun unauthorized_registersAgainOnce_andRepeatsTheRequest() =
        runBlocking {
            store.addTrip(longTrip("a"))
            uploader.results += ApiResult.Failure(ApiError.Unauthorized)

            assertEquals(UploadRunOutcome.SUCCESS, runner.run())

            assertEquals(1, device.clearCalls)
            assertEquals(2, device.registerCalls)
            assertEquals(UploadQueueState.DONE, store.rows.getValue("a").state)
        }

    @Test
    fun unauthorizedTwice_retriesLater() =
        runBlocking {
            store.addTrip(longTrip("a"))
            uploader.results += ApiResult.Failure(ApiError.Unauthorized)
            uploader.results += ApiResult.Failure(ApiError.Unauthorized)

            assertEquals(UploadRunOutcome.RETRY, runner.run())

            assertEquals(1, device.clearCalls)
            assertEquals("unauthorized", store.rows.getValue("a").lastError)
        }

    @Test
    fun updateRequired_stopsTheRun_andKeepsTheTrip() =
        runBlocking {
            store.addTrip(longTrip("a"))
            store.addTrip(longTrip("b"))
            uploader.results += ApiResult.Failure(ApiError.UpdateRequired)

            assertEquals(UploadRunOutcome.FAILURE, runner.run())

            assertEquals(UploadQueueState.PENDING, store.rows.getValue("a").state)
            assertEquals(1, uploader.requests.size)
        }

    @Test
    fun partialUpload_sendsOnlyTheRestOnRetry() =
        runBlocking {
            // Enough windows for several requests.
            val track = northTrack(lengthM = 60_000.0)
            val windows = (60 until track.size - 60).map { windowBetween("a-w$it", track[it - 5], track[it], lengthM = 50.0) }
            store.addTrip(trip("a", track, samples = windows))
            uploader.results += ApiResult.Success(EventUploadResponse())
            uploader.results += ApiResult.Failure(ApiError.Network)

            assertEquals(UploadRunOutcome.RETRY, runner.run())
            val firstBatch = uploader.requests[0].windows.map { it.id }.toSet()
            assertEquals(firstBatch, store.uploadedIds)

            uploader.requests.clear()
            assertEquals(UploadRunOutcome.SUCCESS, runner.run())
            val resent = uploader.requests.flatMap { it.windows }.map { it.id }.toSet()
            assertTrue(resent.intersect(firstBatch).isEmpty())
            assertEquals(windows.size, firstBatch.size + resent.size)
            assertEquals(UploadQueueState.DONE, store.rows.getValue("a").state)
        }

    @Test
    fun tripStartedBeforeOptIn_isNeverQueuedOrSent() =
        runBlocking {
            store.addTrip(longTrip("before", startMs = OPT_IN_MS - 1L), queued = false)
            store.addTrip(longTrip("after", startMs = OPT_IN_MS), queued = false)

            assertEquals(UploadRunOutcome.SUCCESS, runner.run())

            assertTrue(uploader.requests.isNotEmpty())
            assertTrue(uploader.requests.all { it.tripToken == "token-after" })
            assertEquals(null, store.rows["before"])
            assertEquals(UploadQueueState.DONE, store.rows.getValue("after").state)
        }

    @Test
    fun queuedRowOfATripBeforeOptIn_isSkippedByTheRunnerItself() =
        runBlocking {
            // Simulates a store that failed to apply the cutoff: the runner must still refuse.
            store.appliesCutoff = false
            store.addTrip(longTrip("before", startMs = OPT_IN_MS - 60_000L))

            assertEquals(UploadRunOutcome.SUCCESS, runner.run())

            assertTrue(uploader.requests.isEmpty())
            assertEquals(UploadQueueState.SKIPPED, store.rows.getValue("before").state)
            assertEquals(UploadQueueState.CATEGORY_BEFORE_OPT_IN, store.rows.getValue("before").lastError)
        }

    @Test
    fun missingOptInTime_uploadsNothing() =
        runBlocking {
            settings.state.value = settings.state.value.copy(contributionEnabledAtMs = null)
            store.addTrip(longTrip("a"))

            assertEquals(UploadRunOutcome.SUCCESS, runner.run())

            assertTrue(uploader.requests.isEmpty())
            assertEquals(0, device.registerCalls)
        }

    @Test
    fun reEnabling_resetsTheCutoff() =
        runBlocking {
            // Recorded while sharing was on, but not uploaded before the user switched sharing off.
            store.addTrip(longTrip("first-period", startMs = OPT_IN_MS + 1_000L))
            settings.clockMs = OPT_IN_MS + 10_000L
            settings.setContributionEnabled(false)
            assertEquals(null, settings.state.value.contributionEnabledAtMs)
            settings.clockMs = OPT_IN_MS + 20_000L
            settings.setContributionEnabled(true)
            assertEquals(OPT_IN_MS + 20_000L, settings.state.value.contributionEnabledAtMs)
            store.addTrip(longTrip("second-period", startMs = OPT_IN_MS + 30_000L), queued = false)

            assertEquals(UploadRunOutcome.SUCCESS, runner.run())

            assertTrue(uploader.requests.all { it.tripToken == "token-second-period" })
            assertEquals(UploadQueueState.SKIPPED, store.rows.getValue("first-period").state)
            assertEquals(UploadQueueState.DONE, store.rows.getValue("second-period").state)
        }

    private fun longTrip(
        id: String,
        startMs: Long = START_MS,
    ): FakeTrip {
        val track = northTrack(lengthM = 5_000.0)
        val hazards = track.filterIndexed { index, _ -> index % 10 == 0 }.map { hazardAt("$id-h${it.tMs}", it) }
        return trip(id, track, hazards, startMs = startMs)
    }

    private fun trip(
        id: String,
        points: List<TripPoint>,
        hazards: List<RecordedHazard> = emptyList(),
        samples: List<SegmentSample> = emptyList(),
        startMs: Long = START_MS,
    ): FakeTrip =
        FakeTrip(
            id = id,
            points = points.map { it.copy(tripId = id, tMs = startMs + it.tMs) },
            hazards = hazards.map { it.copy(tripId = id, observedAtMs = startMs + it.observedAtMs) },
            samples = samples.map { it.copy(tripId = id, observedAtMs = startMs + it.observedAtMs) },
        )

    private companion object {
        const val NOW_MS = 1_791_880_212_000L

        /** Trips start ten hours before "now", so even a 60 km test trip lies in the past. */
        const val START_MS = NOW_MS - 36_000_000L

        /** Contribution was switched on just before [START_MS]. */
        const val OPT_IN_MS = START_MS - 1_000L
    }
}

private class FakeTrip(
    val id: String,
    val points: List<TripPoint>,
    val hazards: List<RecordedHazard>,
    val samples: List<SegmentSample>,
) {
    val startedAtMs: Long get() = points.first().tMs
}

private data class FakeRow(
    val state: String,
    val attempts: Int,
    val nextAttemptAtMs: Long,
    val lastError: String?,
)

private class FakeUploadStore : UploadStore {
    val trips = linkedMapOf<String, FakeTrip>()
    val rows = linkedMapOf<String, FakeRow>()
    val uploadedIds = mutableSetOf<String>()

    /** Mirrors UploadDao.refreshQueue (tested on a device in MigrationTest). Off to test the runner's own check. */
    var appliesCutoff = true

    /** [queued] false: the trip exists but has no queue row yet, as right after it finished. */
    fun addTrip(
        trip: FakeTrip,
        attempts: Int = 0,
        queued: Boolean = true,
    ) {
        trips[trip.id] = trip
        if (queued) rows[trip.id] = FakeRow(UploadQueueState.PENDING, attempts, nextAttemptAtMs = 0L, lastError = null)
    }

    override suspend fun refreshQueue(
        nowMs: Long,
        contributionEnabledAtMs: Long,
    ) {
        for ((tripId, trip) in trips) {
            val row = rows[tripId]
            val isBefore = trip.startedAtMs < contributionEnabledAtMs
            when {
                !appliesCutoff && row == null -> rows[tripId] = FakeRow(UploadQueueState.PENDING, 0, nowMs, null)
                !appliesCutoff -> Unit
                row == null && !isBefore -> rows[tripId] = FakeRow(UploadQueueState.PENDING, 0, nowMs, null)
                row != null && isBefore && row.state in WAITING_STATES ->
                    rows[tripId] = row.copy(state = UploadQueueState.SKIPPED, lastError = UploadQueueState.CATEGORY_BEFORE_OPT_IN)
            }
        }
    }

    override suspend fun resetInProgress() {
        rows.replaceAll { _, row -> if (row.state == UploadQueueState.IN_PROGRESS) row.copy(state = UploadQueueState.PENDING) else row }
    }

    override suspend fun dueTrips(
        nowMs: Long,
        maxRows: Int,
    ): List<QueuedTrip> =
        rows
            .filter { (_, row) -> row.state == UploadQueueState.PENDING && row.nextAttemptAtMs <= nowMs }
            .map { (tripId, row) -> QueuedTrip(tripId, row.attempts) }
            .take(maxRows)

    override suspend fun countWaiting(): Int =
        rows.values.count { it.state == UploadQueueState.PENDING || it.state == UploadQueueState.IN_PROGRESS }

    override suspend fun load(tripId: String): TripUploadSource? {
        val trip = trips[tripId] ?: return null
        return TripUploadSource(
            tripId = trip.id,
            tripToken = "token-${trip.id}",
            startedAtMs = trip.startedAtMs,
            vehicleType = VehicleType.CAR,
            points = trip.points,
            hazards = trip.hazards.filter { it.id !in uploadedIds },
            samples = trip.samples.filter { it.id !in uploadedIds },
            alreadyUploadedCount = (trip.hazards.map { it.id } + trip.samples.map { it.id }).count { it in uploadedIds },
        )
    }

    override suspend fun markInProgress(tripId: String) {
        rows[tripId] = rows.getValue(tripId).copy(state = UploadQueueState.IN_PROGRESS)
    }

    override suspend fun markUploaded(
        hazardIds: List<String>,
        sampleIds: List<String>,
    ) {
        uploadedIds += hazardIds
        uploadedIds += sampleIds
    }

    override suspend fun updateQueue(
        tripId: String,
        state: String,
        attempts: Int,
        nextAttemptAtMs: Long,
        lastError: String?,
    ) {
        rows[tripId] = FakeRow(state, attempts, nextAttemptAtMs, lastError)
    }

    private companion object {
        val WAITING_STATES = setOf(UploadQueueState.PENDING, UploadQueueState.IN_PROGRESS)
    }
}

/** Returns queued results first, then success. */
private class FakeUploader : EventsUploader {
    val results = ArrayDeque<ApiResult<EventUploadResponse>>()
    val requests = mutableListOf<EventUploadRequest>()

    override suspend fun upload(request: EventUploadRequest): ApiResult<EventUploadResponse> {
        requests += request
        return results.removeFirstOrNull() ?: ApiResult.Success(EventUploadResponse())
    }
}

private class FakeDeviceSession : DeviceSession {
    val registerResults = ArrayDeque<ApiResult<Unit>>()
    var registerCalls = 0
    var clearCalls = 0

    override val updateRequired: StateFlow<Boolean> = MutableStateFlow(false)

    override suspend fun isRegistered(): Boolean = true

    override suspend fun ensureRegistered(): ApiResult<Unit> {
        registerCalls++
        return registerResults.removeFirstOrNull() ?: ApiResult.Success(Unit)
    }

    override suspend fun deleteRemoteData(): ApiResult<Unit> = ApiResult.Success(Unit)

    override suspend fun clearCredentials() {
        clearCalls++
    }
}

/** Records the opt-in time like DataStoreSettingsRepository, from [clockMs]. */
private class FakeSettingsRepository(
    contributionEnabled: Boolean,
    enabledAtMs: Long?,
) : SettingsRepository {
    val state =
        MutableStateFlow(
            UserSettings.DEFAULT.copy(contributionEnabled = contributionEnabled, contributionEnabledAtMs = enabledAtMs),
        )
    var clockMs = 0L

    override val settings: Flow<UserSettings> = state

    override suspend fun setVehicleType(vehicleType: VehicleType) = Unit

    override suspend fun setOnboardingCompleted(completed: Boolean) = Unit

    override suspend fun setAutoPauseEnabled(enabled: Boolean) = Unit

    override suspend fun setContributionEnabled(enabled: Boolean) {
        state.value =
            state.value.copy(
                contributionEnabled = enabled,
                contributionEnabledAtMs = if (enabled) clockMs else null,
            )
    }

    override suspend fun setUploadOnMobileData(enabled: Boolean) = Unit

    override suspend fun setContributionConsentShown(shown: Boolean) = Unit

    override suspend fun clearAll() = Unit
}
