package com.mapmyroad.ui.settings

import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.model.ApiError
import com.mapmyroad.domain.model.ApiResult
import com.mapmyroad.domain.model.UserSettings
import com.mapmyroad.ui.settings.DeletionCallLog.Companion.CANCEL_UPLOADS
import com.mapmyroad.ui.settings.DeletionCallLog.Companion.CLEAR_CREDENTIALS
import com.mapmyroad.ui.settings.DeletionCallLog.Companion.CLEAR_SETTINGS
import com.mapmyroad.ui.settings.DeletionCallLog.Companion.DELETE_REMOTE
import com.mapmyroad.ui.settings.DeletionCallLog.Companion.DELETE_TRIPS
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** PRD PV-3: server data first, then local data; a server failure deletes nothing. */
class DataDeletionTest {
    private val log = DeletionCallLog()
    private val deviceSession = DeletionTestDeviceSession(log)
    private val settingsRepository =
        DeletionTestSettingsRepository(
            log,
            UserSettings.DEFAULT.copy(
                vehicleType = VehicleType.CAR,
                onboardingCompleted = true,
                contributionEnabled = true,
            ),
        )
    private val tripRepository = DeletionTestTripRepository(log)
    private val deletion =
        DataDeletion(tripRepository, settingsRepository, deviceSession, DeletionTestUploadScheduler(log))

    @Test
    fun registered_deletesServerData_beforeLocalData() =
        runTest {
            val outcome = deletion.deleteEverywhere()

            assertEquals(DeleteOutcome.DELETED, outcome)
            assertEquals(listOf(CANCEL_UPLOADS, DELETE_REMOTE, DELETE_TRIPS, CLEAR_SETTINGS), log.calls)
            assertEquals(0, tripRepository.tripCount)
            assertEquals(UserSettings.DEFAULT, settingsRepository.current)
        }

    @Test
    fun networkFailure_deletesNothingLocally_andKeepsCredentials() =
        runTest {
            deviceSession.deleteResult = ApiResult.Failure(ApiError.Network)

            val outcome = deletion.deleteEverywhere()

            assertEquals(DeleteOutcome.SERVER_UNREACHABLE, outcome)
            assertEquals(listOf(CANCEL_UPLOADS, DELETE_REMOTE), log.calls)
            assertEquals(3, tripRepository.tripCount)
            assertTrue(settingsRepository.current.onboardingCompleted)
            assertTrue(deviceSession.isRegistered())
        }

    @Test
    fun notRegistered_deletesLocalDataOnly() =
        runTest {
            deviceSession.registered = false

            val outcome = deletion.deleteEverywhere()

            assertEquals(DeleteOutcome.DELETED, outcome)
            assertEquals(listOf(CANCEL_UPLOADS, DELETE_TRIPS, CLEAR_SETTINGS), log.calls)
        }

    @Test
    fun deleteOnlyOnThisPhone_keepsCredentials_soServerDataCanBeDeletedLater() =
        runTest {
            deviceSession.deleteResult = ApiResult.Failure(ApiError.Network)
            deletion.deleteEverywhere()
            log.calls.clear()

            val outcome = deletion.deleteOnlyOnThisPhone()

            assertEquals(DeleteOutcome.DELETED, outcome)
            assertEquals(listOf(CANCEL_UPLOADS, DELETE_TRIPS, CLEAR_SETTINGS), log.calls)
            assertFalse(log.calls.contains(CLEAR_CREDENTIALS))
            assertTrue(deviceSession.isRegistered())
            assertFalse(settingsRepository.current.contributionEnabled)
        }

    @Test
    fun updateRequired_deletesNothing() =
        runTest {
            deviceSession.deleteResult = ApiResult.Failure(ApiError.UpdateRequired)

            assertEquals(DeleteOutcome.UPDATE_REQUIRED, deletion.deleteEverywhere())
            assertEquals(listOf(CANCEL_UPLOADS, DELETE_REMOTE), log.calls)
        }

    @Test
    fun rejectedByServer_deletesNothing() =
        runTest {
            deviceSession.deleteResult = ApiResult.Failure(ApiError.Unauthorized)

            assertEquals(DeleteOutcome.SERVER_FAILED, deletion.deleteEverywhere())
            assertEquals(3, tripRepository.tripCount)
        }

    @Test
    fun registrationReadError_deletesNothing() =
        runTest {
            deviceSession.isRegisteredFailure = IOException("datastore")

            assertEquals(DeleteOutcome.SERVER_UNREACHABLE, deletion.deleteEverywhere())
            assertEquals(listOf(CANCEL_UPLOADS), log.calls)
        }

    @Test
    fun localFailureAfterServerDeletion_retryDeletesLocalDataWithoutServerCall() =
        runTest {
            tripRepository.deleteFailure = IOException("disk")
            assertEquals(DeleteOutcome.LOCAL_FAILED, deletion.deleteEverywhere())
            assertTrue(settingsRepository.current.onboardingCompleted)

            tripRepository.deleteFailure = null
            log.calls.clear()

            assertEquals(DeleteOutcome.DELETED, deletion.deleteEverywhere())
            assertEquals(listOf(CANCEL_UPLOADS, DELETE_TRIPS, CLEAR_SETTINGS), log.calls)
        }

    @Test
    fun serverFailures_mapToOutcomes() {
        val unreachable =
            listOf(ApiError.Network, ApiError.Unavailable, ApiError.RateLimited(retryAfterSec = 30), ApiError.Server(status = 500))
        unreachable.forEach { error -> assertEquals(DeleteOutcome.SERVER_UNREACHABLE, serverFailureOutcome(error)) }
        val failed =
            listOf(ApiError.Unauthorized, ApiError.IntegrityFailed, ApiError.NotFound, ApiError.Rejected(status = 400, code = null))
        failed.forEach { error -> assertEquals(DeleteOutcome.SERVER_FAILED, serverFailureOutcome(error)) }
        assertEquals(DeleteOutcome.UPDATE_REQUIRED, serverFailureOutcome(ApiError.UpdateRequired))
    }
}
