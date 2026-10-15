package com.mapmyroad.ui.onboarding

import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.model.UserSettings
import com.mapmyroad.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val settingsRepository = FakeSettingsRepository()
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = OnboardingViewModel(settingsRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun vehicleStep_cannotContinue_withoutSelection() {
        viewModel.onGetStarted()
        viewModel.onVehicleConfirmed()
        assertEquals(OnboardingStep.VEHICLE, viewModel.uiState.value.step)
    }

    @Test
    fun grantedPermissions_finishOnboarding_andSaveSettings() {
        goToLocationStep()

        viewModel.onPermissionResult(isGranted = true, areNotificationsRuntime = true)
        assertEquals(OnboardingStep.NOTIFICATIONS, viewModel.uiState.value.step)

        viewModel.onPermissionResult(isGranted = true, areNotificationsRuntime = true)
        assertTrue(viewModel.uiState.value.isCompleted)
        assertEquals(VehicleType.MOTORCYCLE, settingsRepository.current.vehicleType)
        assertTrue(settingsRepository.current.onboardingCompleted)
    }

    @Test
    fun notificationStep_isSkipped_whenNotARuntimePermission() {
        goToLocationStep()
        viewModel.onPermissionResult(isGranted = true, areNotificationsRuntime = false)
        assertTrue(viewModel.uiState.value.isCompleted)
    }

    @Test
    fun deniedLocation_explainsAndStillFinishes() {
        goToLocationStep()

        viewModel.onPermissionResult(isGranted = false, areNotificationsRuntime = true)
        assertTrue(viewModel.uiState.value.isPermissionDenied)
        assertEquals(OnboardingStep.LOCATION, viewModel.uiState.value.step)

        viewModel.onContinueAfterDenial(areNotificationsRuntime = true)
        assertEquals(OnboardingStep.NOTIFICATIONS, viewModel.uiState.value.step)
        assertFalse(viewModel.uiState.value.isPermissionDenied)

        viewModel.onPermissionDeclined()
        viewModel.onContinueAfterDenial(areNotificationsRuntime = true)
        assertTrue(viewModel.uiState.value.isCompleted)
        assertTrue(settingsRepository.current.onboardingCompleted)
    }

    @Test
    fun back_fromPermissionStep_returnsToVehicle() {
        goToLocationStep()
        viewModel.onBack()
        assertEquals(OnboardingStep.VEHICLE, viewModel.uiState.value.step)
        viewModel.onBack()
        assertEquals(OnboardingStep.WELCOME, viewModel.uiState.value.step)
    }

    @Test
    fun onShown_afterCompletion_restartsFromWelcome() {
        goToLocationStep()
        viewModel.onPermissionResult(isGranted = true, areNotificationsRuntime = false)
        assertTrue(viewModel.uiState.value.isCompleted)

        viewModel.onShown()
        assertEquals(OnboardingUiState(), viewModel.uiState.value)
    }

    private fun goToLocationStep() {
        viewModel.onGetStarted()
        viewModel.onVehicleSelected(VehicleType.MOTORCYCLE)
        viewModel.onVehicleConfirmed()
        assertEquals(OnboardingStep.LOCATION, viewModel.uiState.value.step)
    }
}

private class FakeSettingsRepository : SettingsRepository {
    private val state = MutableStateFlow(UserSettings.DEFAULT)
    val current: UserSettings get() = state.value

    override val settings: Flow<UserSettings> = state

    override suspend fun setVehicleType(vehicleType: VehicleType) {
        state.update { it.copy(vehicleType = vehicleType) }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        state.update { it.copy(onboardingCompleted = completed) }
    }

    override suspend fun setAutoPauseEnabled(enabled: Boolean) {
        state.update { it.copy(autoPauseEnabled = enabled) }
    }

    override suspend fun setContributionEnabled(enabled: Boolean) {
        state.update { it.copy(contributionEnabled = enabled, contributionConsentShown = it.contributionConsentShown || enabled) }
    }

    override suspend fun setUploadOnMobileData(enabled: Boolean) {
        state.update { it.copy(uploadOnMobileData = enabled) }
    }

    override suspend fun setContributionConsentShown(shown: Boolean) {
        state.update { it.copy(contributionConsentShown = shown) }
    }

    override suspend fun clearAll() {
        state.value = UserSettings.DEFAULT
    }
}
