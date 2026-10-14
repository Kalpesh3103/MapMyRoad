package com.mapmyroad.domain.repository

import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow

/** User preferences (DataStore). */
interface SettingsRepository {
    val settings: Flow<UserSettings>

    suspend fun setVehicleType(vehicleType: VehicleType)

    suspend fun setOnboardingCompleted(completed: Boolean)

    suspend fun setAutoPauseEnabled(enabled: Boolean)

    /** Resets everything to [UserSettings.DEFAULT] ("Delete all data", PRD PM-4, DATABASE_SCHEMA.md B3). */
    suspend fun clearAll()
}
