package com.mapmyroad.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.mapmyroad.detector.VehicleType
import com.mapmyroad.di.SettingsDataStore
import com.mapmyroad.domain.model.UserSettings
import com.mapmyroad.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** [SettingsRepository] backed by Preferences DataStore (DATABASE_SCHEMA.md B3). */
@Singleton
class DataStoreSettingsRepository
    @Inject
    constructor(
        @SettingsDataStore private val dataStore: DataStore<Preferences>,
    ) : SettingsRepository {
        override val settings: Flow<UserSettings> =
            dataStore
                .data
                .catch { error ->
                    // A read failure must not break the app: fall back to defaults. Anything else is a bug.
                    if (error is IOException) {
                        Timber.w(error, "Reading settings failed, using defaults")
                        emit(emptyPreferences())
                    } else {
                        throw error
                    }
                }.map { preferences -> preferences.toUserSettings() }

        override suspend fun setVehicleType(vehicleType: VehicleType) {
            dataStore.edit { it[Keys.VEHICLE_TYPE] = vehicleType.dbCode }
        }

        override suspend fun setOnboardingCompleted(completed: Boolean) {
            dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
        }

        override suspend fun setAutoPauseEnabled(enabled: Boolean) {
            dataStore.edit { it[Keys.AUTO_PAUSE_ENABLED] = enabled }
        }

        override suspend fun clearAll() {
            dataStore.edit { it.clear() }
            Timber.i("Settings cleared")
        }

        internal object Keys {
            /** [VehicleType.dbCode]. */
            val VEHICLE_TYPE = intPreferencesKey("vehicle_type")
            val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
            val AUTO_PAUSE_ENABLED = booleanPreferencesKey("auto_pause_enabled")
            val LOW_BATTERY_STOP_PERCENT = intPreferencesKey("low_battery_stop_percent")
        }
    }

/** Maps stored preferences to [UserSettings]; missing keys and unknown vehicle codes use [UserSettings.DEFAULT]. */
internal fun Preferences.toUserSettings(): UserSettings {
    val defaults = UserSettings.DEFAULT
    return UserSettings(
        vehicleType = this[DataStoreSettingsRepository.Keys.VEHICLE_TYPE]?.let(VehicleType::fromDbCode) ?: defaults.vehicleType,
        onboardingCompleted = this[DataStoreSettingsRepository.Keys.ONBOARDING_COMPLETED] ?: defaults.onboardingCompleted,
        autoPauseEnabled = this[DataStoreSettingsRepository.Keys.AUTO_PAUSE_ENABLED] ?: defaults.autoPauseEnabled,
        lowBatteryStopPercent =
            this[DataStoreSettingsRepository.Keys.LOW_BATTERY_STOP_PERCENT] ?: defaults.lowBatteryStopPercent,
    )
}
