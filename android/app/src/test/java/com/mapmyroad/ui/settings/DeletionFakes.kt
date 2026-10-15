package com.mapmyroad.ui.settings

import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.device.DeviceSession
import com.mapmyroad.domain.model.ApiResult
import com.mapmyroad.domain.model.RecordedHazard
import com.mapmyroad.domain.model.SegmentSample
import com.mapmyroad.domain.model.StopReason
import com.mapmyroad.domain.model.Trip
import com.mapmyroad.domain.model.TripPoint
import com.mapmyroad.domain.model.TripTrack
import com.mapmyroad.domain.model.UserSettings
import com.mapmyroad.domain.repository.SettingsRepository
import com.mapmyroad.domain.repository.TripRepository
import com.mapmyroad.domain.upload.UploadScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Ordered log of the side effects of "Delete my data", shared by all fakes so the tests can check the order. */
class DeletionCallLog {
    val calls = mutableListOf<String>()

    fun record(call: String) {
        calls += call
    }

    companion object {
        const val CANCEL_UPLOADS = "cancelUploads"
        const val DELETE_REMOTE = "deleteRemoteData"
        const val CLEAR_CREDENTIALS = "clearCredentials"
        const val DELETE_TRIPS = "deleteAllTrips"
        const val CLEAR_SETTINGS = "clearAll"
    }
}

class DeletionTestDeviceSession(
    private val log: DeletionCallLog,
    var registered: Boolean = true,
    var deleteResult: ApiResult<Unit> = ApiResult.Success(Unit),
) : DeviceSession {
    var isRegisteredFailure: Exception? = null

    override val updateRequired: StateFlow<Boolean> = MutableStateFlow(false)

    override suspend fun isRegistered(): Boolean {
        isRegisteredFailure?.let { throw it }
        return registered
    }

    override suspend fun ensureRegistered(): ApiResult<Unit> = ApiResult.Success(Unit)

    /** Mirrors the contract: forgets the credentials only after the server confirmed the deletion. */
    override suspend fun deleteRemoteData(): ApiResult<Unit> {
        log.record(DeletionCallLog.DELETE_REMOTE)
        if (deleteResult is ApiResult.Success) registered = false
        return deleteResult
    }

    override suspend fun clearCredentials() {
        log.record(DeletionCallLog.CLEAR_CREDENTIALS)
        registered = false
    }
}

class DeletionTestUploadScheduler(
    private val log: DeletionCallLog,
) : UploadScheduler {
    override fun scheduleUploads() = Unit

    override fun cancelUploads() {
        log.record(DeletionCallLog.CANCEL_UPLOADS)
    }
}

class DeletionTestSettingsRepository(
    private val log: DeletionCallLog,
    initial: UserSettings = UserSettings.DEFAULT,
) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    val current: UserSettings get() = state.value

    override val settings: Flow<UserSettings> = state

    override suspend fun setVehicleType(vehicleType: VehicleType) {
        state.value = state.value.copy(vehicleType = vehicleType)
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        state.value = state.value.copy(onboardingCompleted = completed)
    }

    override suspend fun setAutoPauseEnabled(enabled: Boolean) {
        state.value = state.value.copy(autoPauseEnabled = enabled)
    }

    override suspend fun setContributionEnabled(enabled: Boolean) {
        state.value = state.value.copy(contributionEnabled = enabled)
    }

    override suspend fun setUploadOnMobileData(enabled: Boolean) {
        state.value = state.value.copy(uploadOnMobileData = enabled)
    }

    override suspend fun setContributionConsentShown(shown: Boolean) {
        state.value = state.value.copy(contributionConsentShown = shown)
    }

    override suspend fun clearAll() {
        log.record(DeletionCallLog.CLEAR_SETTINGS)
        state.value = UserSettings.DEFAULT
    }
}

class DeletionTestTripRepository(
    private val log: DeletionCallLog,
) : TripRepository {
    var deleteFailure: Exception? = null
    var tripCount = 3

    override fun observeFinishedTrips(): Flow<List<Trip>> = emptyFlow()

    override fun observeTrip(tripId: String): Flow<Trip?> = emptyFlow()

    override fun observeLatestFinishedTrip(): Flow<Trip?> = emptyFlow()

    override fun observeTripPoints(tripId: String): Flow<List<TripPoint>> = emptyFlow()

    override fun observeTripHazards(tripId: String): Flow<List<RecordedHazard>> = emptyFlow()

    override fun observeAllTracks(): Flow<List<TripTrack>> = emptyFlow()

    override fun observeAllHazards(): Flow<List<RecordedHazard>> = emptyFlow()

    override suspend fun startTrip(trip: Trip) = Unit

    override suspend fun appendRecording(
        tripId: String,
        points: List<TripPoint>,
        hazards: List<RecordedHazard>,
        samples: List<SegmentSample>,
    ) = Unit

    override suspend fun finishTrip(
        tripId: String,
        endedAtMs: Long,
        stopReason: StopReason,
    ): Trip? = null

    override suspend fun findUnfinishedTrips(): List<Trip> = emptyList()

    override suspend fun deleteTrip(tripId: String) = Unit

    override suspend fun deleteAllTrips() {
        log.record(DeletionCallLog.DELETE_TRIPS)
        deleteFailure?.let { throw it }
        tripCount = 0
    }
}
