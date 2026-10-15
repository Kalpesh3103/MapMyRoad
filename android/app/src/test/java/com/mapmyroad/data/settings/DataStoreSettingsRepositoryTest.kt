package com.mapmyroad.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.model.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreSettingsRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // DataStore does real file I/O, so it gets a real dispatcher; the scope is cancelled after each test.
    private val dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var clockMs = 0L

    private val repository by lazy {
        DataStoreSettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { File(temporaryFolder.root, "settings.preferences_pb") },
            ),
            nowMs = { clockMs },
        )
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    @Test
    fun freshStore_emitsDefaults() =
        runTest {
            assertEquals(UserSettings.DEFAULT, repository.settings.first())
        }

    @Test
    fun setters_updateSettings_andClearAll_restoresDefaults() =
        runTest {
            repository.setVehicleType(VehicleType.MOTORCYCLE)
            repository.setOnboardingCompleted(true)
            repository.setAutoPauseEnabled(false)

            assertEquals(
                UserSettings.DEFAULT.copy(
                    vehicleType = VehicleType.MOTORCYCLE,
                    onboardingCompleted = true,
                    autoPauseEnabled = false,
                ),
                repository.settings.first(),
            )

            repository.clearAll()

            assertEquals(UserSettings.DEFAULT, repository.settings.first())
        }

    @Test
    fun contributionSwitch_recordsTheOptInTime_andEveryReEnableResetsIt() =
        runTest {
            clockMs = 1_000L
            repository.setContributionEnabled(true)
            val first = repository.settings.first()
            assertEquals(true, first.contributionEnabled)
            assertEquals(true, first.contributionConsentShown)
            assertEquals(1_000L, first.contributionEnabledAtMs)

            clockMs = 2_000L
            repository.setContributionEnabled(false)
            assertNull(repository.settings.first().contributionEnabledAtMs)

            clockMs = 3_000L
            repository.setContributionEnabled(true)
            assertEquals(3_000L, repository.settings.first().contributionEnabledAtMs)
        }
}
