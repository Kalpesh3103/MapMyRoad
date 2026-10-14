package com.mapmyroad.sensing.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryStopPolicyTest {
    private val policy = BatteryStopPolicy(thresholdPercent = 15)

    @Test
    fun stopsAtOrBelowThreshold_whenNotCharging() {
        assertFalse(policy.shouldStop(levelPercent = 16, isCharging = false))
        assertTrue(policy.shouldStop(levelPercent = 15, isCharging = false))
        assertTrue(policy.shouldStop(levelPercent = 3, isCharging = false))
    }

    @Test
    fun neverStopsWhileCharging() {
        assertFalse(policy.shouldStop(levelPercent = 5, isCharging = true))
    }

    @Test
    fun unknownLevel_neverStops() {
        assertFalse(policy.shouldStop(levelPercent = Int.MIN_VALUE, isCharging = false))
        assertFalse(policy.shouldStop(levelPercent = 0, isCharging = false))
        assertFalse(policy.shouldStop(levelPercent = 150, isCharging = false))
    }

    @Test
    fun zeroThreshold_disablesTheCheck() {
        assertFalse(BatteryStopPolicy(thresholdPercent = 0).shouldStop(levelPercent = 1, isCharging = false))
    }
}
