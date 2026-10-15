package com.mapmyroad.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
    internal constructor(
        private val dataStore: DataStore<Preferences>,
        /** Wall clock for [UserSettings.contributionEnabledAtMs]; replaced in tests. */
        private val nowMs: () -> Long,
    ) : SettingsRepository {
        @Inject
        constructor(
            @SettingsDataStore dataStore: DataStore<Preferences>,
        ) : this(dataStore, System::currentTimeMillis)

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

        override suspend fun setContributionEnabled(enabled: Boolean) {
            dataStore.edit {
                it[Keys.CONTRIBUTION_ENABLED] = enabled
                if (enabled) {
                    it[Keys.CONTRIBUTION_CONSENT_SHOWN] = true
                    // Every switch-on starts a new cutoff: trips recorded while sharing was off stay private.
                    it[Keys.CONTRIBUTION_ENABLED_AT] = nowMs()
                } else {
                    it.remove(Keys.CONTRIBUTION_ENABLED_AT)
                }
            }
        }

        override suspend fun setUploadOnMobileData(enabled: Boolean) {
            dataStore.edit { it[Keys.UPLOAD_ON_MOBILE_DATA] = enabled }
        }

        override suspend fun setContributionConsentShown(shown: Boolean) {
            dataStore.edit { it[Keys.CONTRIBUTION_CONSENT_SHOWN] = shown }
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
            val CONTRIBUTION_ENABLED = booleanPreferencesKey("contribution_enabled")
            val UPLOAD_ON_MOBILE_DATA = booleanPreferencesKey("upload_on_mobile_data")
            val CONTRIBUTION_CONSENT_SHOWN = booleanPreferencesKey("contribution_consent_shown")

            /** Epoch millis of the last switch-on, absent while contribution is off. */
            val CONTRIBUTION_ENABLED_AT = longPreferencesKey("contribution_enabled_at")
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
        contributionEnabled =
            this[DataStoreSettingsRepository.Keys.CONTRIBUTION_ENABLED] ?: defaults.contributionEnabled,
        uploadOnMobileData =
            this[DataStoreSettingsRepository.Keys.UPLOAD_ON_MOBILE_DATA] ?: defaults.uploadOnMobileData,
        contributionConsentShown =
            this[DataStoreSettingsRepository.Keys.CONTRIBUTION_CONSENT_SHOWN] ?: defaults.contributionConsentShown,
        contributionEnabledAtMs =
            this[DataStoreSettingsRepository.Keys.CONTRIBUTION_ENABLED_AT] ?: defaults.contributionEnabledAtMs,
    )
}
