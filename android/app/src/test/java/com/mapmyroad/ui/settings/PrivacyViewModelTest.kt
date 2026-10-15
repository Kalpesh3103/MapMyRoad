package com.mapmyroad.ui.settings

import com.mapmyroad.domain.model.ApiError
import com.mapmyroad.domain.model.ApiResult
import com.mapmyroad.domain.model.UserSettings
import com.mapmyroad.domain.session.TripSessionController
import com.mapmyroad.domain.session.TripSessionState
import com.mapmyroad.ui.settings.DeletionCallLog.Companion.CANCEL_UPLOADS
import com.mapmyroad.ui.settings.DeletionCallLog.Companion.CLEAR_SETTINGS
import com.mapmyroad.ui.settings.DeletionCallLog.Companion.DELETE_REMOTE
import com.mapmyroad.ui.settings.DeletionCallLog.Companion.DELETE_TRIPS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyViewModelTest {
    private val log = DeletionCallLog()
    private val deviceSession = DeletionTestDeviceSession(log)
    private val settingsRepository =
        DeletionTestSettingsRepository(log, UserSettings.DEFAULT.copy(onboardingCompleted = true, contributionEnabled = true))
    private val tripRepository = DeletionTestTripRepository(log)
    private val tripSessionController = DeletionTestTripSessionController()
    private lateinit var viewModel: PrivacyViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() {
        viewModel =
            PrivacyViewModel(
                tripRepository = tripRepository,
                settingsRepository = settingsRepository,
                tripSessionController = tripSessionController,
                deviceSession = deviceSession,
                uploadScheduler = DeletionTestUploadScheduler(log),
            )
    }

    @Test
    fun uiState_reflectsRegistrationAndContribution() =
        runTest {
            createViewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            assertTrue(viewModel.uiState.value.isRegistered)
            assertTrue(viewModel.uiState.value.isContributionEnabled)
            assertTrue(viewModel.uiState.value.canDelete)
        }

    @Test
    fun deleteMyData_deletesServerThenLocal() =
        runTest {
            createViewModel()

            viewModel.onDeleteMyData()

            assertEquals(listOf(CANCEL_UPLOADS, DELETE_REMOTE, DELETE_TRIPS, CLEAR_SETTINGS), log.calls)
            assertFalse(settingsRepository.current.onboardingCompleted)
        }

    @Test
    fun deleteMyData_isBlockedWhileATripRecordsOrSaves() =
        runTest {
            tripSessionController.state.value = TripSessionState.Finishing(tripId = "trip-1")
            createViewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            viewModel.onDeleteMyData()

            assertTrue(log.calls.isEmpty())
            assertFalse(viewModel.uiState.value.canDelete)
            assertEquals(3, tripRepository.tripCount)
        }

    @Test
    fun networkFailure_offersRetryAndLocalOnly_thenLocalOnlyKeepsCredentials() =
        runTest {
            deviceSession.deleteResult = ApiResult.Failure(ApiError.Network)
            createViewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            viewModel.onDeleteMyData()

            assertEquals(DeleteStatus.SERVER_UNREACHABLE, viewModel.uiState.value.deleteStatus)
            assertTrue(viewModel.uiState.value.canDeleteOnlyOnThisPhone)
            assertEquals(3, tripRepository.tripCount)

            log.calls.clear()
            viewModel.onDeleteOnlyOnThisPhone()

            assertEquals(listOf(CANCEL_UPLOADS, DELETE_TRIPS, CLEAR_SETTINGS), log.calls)
            assertTrue(deviceSession.isRegistered())
        }

    @Test
    fun retryAfterNetworkFailure_deletesServerThenLocal() =
        runTest {
            deviceSession.deleteResult = ApiResult.Failure(ApiError.Network)
            createViewModel()
            viewModel.onDeleteMyData()

            deviceSession.deleteResult = ApiResult.Success(Unit)
            log.calls.clear()
            viewModel.onDeleteMyData()

            assertEquals(listOf(CANCEL_UPLOADS, DELETE_REMOTE, DELETE_TRIPS, CLEAR_SETTINGS), log.calls)
        }

    @Test
    fun deleteOnlyOnThisPhone_isIgnored_unlessTheServerDeletionFailed() =
        runTest {
            createViewModel()

            viewModel.onDeleteOnlyOnThisPhone()

            assertTrue(log.calls.isEmpty())
        }
}

private class DeletionTestTripSessionController : TripSessionController {
    override val state = MutableStateFlow<TripSessionState>(TripSessionState.Idle)

    override val finishedTrips: Flow<String> = emptyFlow()

    override fun startTrip() = Unit

    override fun stopTrip() = Unit

    override fun pauseTrip() = Unit

    override fun resumeTrip() = Unit
}
