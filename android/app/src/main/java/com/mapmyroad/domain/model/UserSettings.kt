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
    /** PRD CM-1: share anonymous hazard data. Off until the user opts in on the consent screen (UI_UX S11, 13). */
    val contributionEnabled: Boolean = false,
    /** Upload over mobile data as well; Wi-Fi only by default (API_SPEC.md section 6). */
    val uploadOnMobileData: Boolean = false,
    /** The one-time contribution consent screen was shown (UI_UX_GUIDELINES.md section 13). */
    val contributionConsentShown: Boolean = false,
    /**
     * Epoch millis when contribution was last switched on; null while it is off. Only trips that started at or after
     * this time are ever uploaded, so trips recorded before opting in (or while sharing was off) stay on the phone.
     */
    val contributionEnabledAtMs: Long? = null,
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
