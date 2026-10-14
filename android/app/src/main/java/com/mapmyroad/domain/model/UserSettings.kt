package com.mapmyroad.domain.model

import com.mapmyroad.detector.VehicleType

/** User preferences stored in DataStore (DATABASE_SCHEMA.md B3). */
data class UserSettings(
    /** Null until chosen in onboarding. */
    val vehicleType: VehicleType?,
    val onboardingCompleted: Boolean,
    /** PRD TS-7: pause recording while stationary. */
    val autoPauseEnabled: Boolean,
    /** PRD TS-7: stop recording at or below this battery level. */
    val lowBatteryStopPercent: Int,
) {
    companion object {
        const val DEFAULT_LOW_BATTERY_STOP_PERCENT = 15

        val DEFAULT =
            UserSettings(
                vehicleType = null,
                onboardingCompleted = false,
                autoPauseEnabled = true,
                lowBatteryStopPercent = DEFAULT_LOW_BATTERY_STOP_PERCENT,
            )
    }
}
