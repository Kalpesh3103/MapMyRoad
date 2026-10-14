package com.mapmyroad.sensing.policy

/**
 * Stops recording at a low battery level (PRD TS-7) so the phone keeps enough charge for the rider.
 *
 * Pure. [thresholdPercent] comes from UserSettings.lowBatteryStopPercent; 0 or less disables the check.
 */
class BatteryStopPolicy(
    private val thresholdPercent: Int,
) {
    /**
     * [levelPercent] is BatteryManager.BATTERY_PROPERTY_CAPACITY. Values outside 1 to 100 mean "unknown" (the
     * property returns Integer.MIN_VALUE or 0 on devices that do not support it) and never stop the trip.
     */
    fun shouldStop(
        levelPercent: Int,
        isCharging: Boolean,
    ): Boolean {
        if (thresholdPercent <= 0 || isCharging) return false
        if (levelPercent !in MIN_VALID_PERCENT..MAX_VALID_PERCENT) return false
        return levelPercent <= thresholdPercent
    }

    private companion object {
        const val MIN_VALID_PERCENT = 1
        const val MAX_VALID_PERCENT = 100
    }
}
