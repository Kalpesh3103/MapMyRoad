package com.mapmyroad.data.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.model.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserSettingsMappingTest {
    @Test
    fun emptyPreferences_mapToDefaults() {
        assertEquals(UserSettings.DEFAULT, emptyPreferences().toUserSettings())
    }

    @Test
    fun storedValues_areMapped() {
        val preferences =
            mutablePreferencesOf(
                DataStoreSettingsRepository.Keys.VEHICLE_TYPE to VehicleType.CAR.dbCode,
                DataStoreSettingsRepository.Keys.ONBOARDING_COMPLETED to true,
                DataStoreSettingsRepository.Keys.AUTO_PAUSE_ENABLED to false,
                DataStoreSettingsRepository.Keys.LOW_BATTERY_STOP_PERCENT to 20,
            )

        val settings = preferences.toUserSettings()

        assertEquals(VehicleType.CAR, settings.vehicleType)
        assertEquals(true, settings.onboardingCompleted)
        assertEquals(false, settings.autoPauseEnabled)
        assertEquals(20, settings.lowBatteryStopPercent)
    }

    @Test
    fun contributionKeys_areMapped() {
        val preferences =
            mutablePreferencesOf(
                DataStoreSettingsRepository.Keys.CONTRIBUTION_ENABLED to true,
                DataStoreSettingsRepository.Keys.CONTRIBUTION_ENABLED_AT to 1_791_880_212_000L,
            )

        val settings = preferences.toUserSettings()

        assertEquals(true, settings.contributionEnabled)
        assertEquals(1_791_880_212_000L, settings.contributionEnabledAtMs)
        assertNull(emptyPreferences().toUserSettings().contributionEnabledAtMs)
    }

    @Test
    fun unknownVehicleCode_mapsToNull() {
        val preferences = mutablePreferencesOf(DataStoreSettingsRepository.Keys.VEHICLE_TYPE to 99)

        assertNull(preferences.toUserSettings().vehicleType)
    }
}
