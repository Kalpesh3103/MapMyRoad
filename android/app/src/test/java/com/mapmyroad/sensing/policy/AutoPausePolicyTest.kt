package com.mapmyroad.sensing.policy

import com.mapmyroad.sensing.policy.AutoPausePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoPausePolicyTest {
    private fun policy() =
        AutoPausePolicy(
            stationarySpeedMps = 0.5f,
            stationaryRadiusM = 20.0,
            pauseAfterMs = 120_000L,
            stopAfterMs = 900_000L,
            resumeSpeedMps = 2.2f,
            resumeAccuracyM = 20f,
            resumeRadiusM = 50.0,
            fixFreshnessMs = 10_000L,
            distanceCalculator = TestDistance,
        ).apply { reset(0L) }

    /** Feeds one fix per second from [fromMs] (exclusive) to [toMs] (inclusive), returning the last decision. */
    private fun AutoPausePolicy.feed(
        fromMs: Long,
        toMs: Long,
        speedMps: Float?,
        accuracyM: Float? = 5f,
        isPaused: Boolean = false,
        latAt: (Long) -> Double = { BASE_LAT },
    ): Decision {
        var decision = Decision.NONE
        var t = fromMs + 1_000L
        while (t <= toMs) {
            decision = onFix(t, latAt(t), BASE_LON, speedMps, accuracyM, isPaused)
            t += 1_000L
        }
        return decision
    }

    @Test
    fun stationary_pausesAfterConfiguredTime() {
        val policy = policy()
        // The stationary clock starts at the first fix (t = 1 s).
        assertEquals(Decision.NONE, policy.feed(0L, 120_000L, speedMps = 0f))
        assertEquals(Decision.PAUSE, policy.onFix(121_000L, BASE_LAT, BASE_LON, 0f, 5f, isPaused = false))
    }

    @Test
    fun moving_neverPauses() {
        val policy = policy()
        assertEquals(Decision.NONE, policy.feed(0L, 600_000L, speedMps = 8f))
        assertEquals(Decision.NONE, policy.onTick(600_500L, isPaused = false))
    }

    @Test
    fun movementBySpeed_restartsStationaryClock() {
        val policy = policy()
        policy.feed(0L, 100_000L, speedMps = 0f)
        policy.onFix(101_000L, BASE_LAT, BASE_LON, 3f, 5f, isPaused = false)
        assertEquals(Decision.NONE, policy.feed(101_000L, 220_000L, speedMps = 0f))
        assertEquals(Decision.PAUSE, policy.onFix(221_000L, BASE_LAT, BASE_LON, 0f, 5f, isPaused = false))
    }

    @Test
    fun displacement_countsAsMovement_whenSpeedIsUnknown() {
        val policy = policy()
        // 5 m per second with no speed reported: the anchor is left every few seconds.
        val decision = policy.feed(0L, 300_000L, speedMps = null, latAt = { t -> BASE_LAT + (t / 1_000L) * 5.0 * DEG_PER_M })
        assertEquals(Decision.NONE, decision)
    }

    @Test
    fun jitterWithinRadius_stillCountsAsStationary() {
        val policy = policy()
        // Plus or minus 8 m around one spot: at most 16 m from the anchor, inside the 20 m radius.
        val jitterLat: (Long) -> Double = { t -> BASE_LAT + (if ((t / 1_000L) % 2L == 0L) 8.0 else -8.0) * DEG_PER_M }
        assertEquals(Decision.PAUSE, policy.feed(0L, 121_000L, speedMps = null, latAt = jitterLat))
    }

    @Test
    fun withoutFreshFixes_doesNotPause() {
        val policy = policy()
        policy.onFix(1_000L, BASE_LAT, BASE_LON, 0f, 5f, isPaused = false)
        var decision = Decision.NONE
        for (t in 2_000L..300_000L step 1_000L) decision = policy.onTick(t, isPaused = false)
        assertEquals(Decision.NONE, decision)
    }

    @Test
    fun gapInFixes_restartsStationaryClock() {
        val policy = policy()
        policy.feed(0L, 60_000L, speedMps = 0f)
        // 60 s tunnel without fixes, then stationary again: 120 s are needed from the first fix after the gap.
        assertEquals(Decision.NONE, policy.feed(120_000L, 239_000L, speedMps = 0f))
        assertEquals(Decision.PAUSE, policy.onFix(241_000L, BASE_LAT, BASE_LON, 0f, 5f, isPaused = false))
    }

    @Test
    fun paused_resumesOnlyOnAccurateFastFix() {
        val policy = policy()
        policy.feed(0L, 120_000L, speedMps = 0f)
        assertEquals(Decision.NONE, policy.onFix(121_000L, BASE_LAT, BASE_LON, 1.5f, 5f, isPaused = true))
        assertEquals(Decision.NONE, policy.onFix(122_000L, BASE_LAT, BASE_LON, 3f, 50f, isPaused = true))
        assertEquals(Decision.NONE, policy.onFix(123_000L, BASE_LAT, BASE_LON, 3f, null, isPaused = true))
        assertEquals(Decision.RESUME, policy.onFix(124_000L, BASE_LAT, BASE_LON, 3f, 10f, isPaused = true))
    }

    @Test
    fun paused_resumesOnLargeDisplacement() {
        val policy = policy()
        policy.feed(0L, 120_000L, speedMps = 0f)
        policy.onFix(121_000L, BASE_LAT, BASE_LON, null, 5f, isPaused = true)
        val farLat = BASE_LAT + 80.0 * DEG_PER_M
        assertEquals(Decision.RESUME, policy.onFix(122_000L, farLat, BASE_LON, null, 5f, isPaused = true))
    }

    @Test
    fun paused_stopsAfterLongStationaryPeriod_evenWithoutFixes() {
        val policy = policy()
        policy.feed(0L, 120_000L, speedMps = 0f)
        // Last movement was the first fix (t = 1 s); no fixes afterwards, as when parked in a basement.
        assertEquals(Decision.NONE, policy.onTick(900_000L, isPaused = true))
        assertEquals(Decision.STOP, policy.onTick(901_000L, isPaused = true))
    }

    @Test
    fun reset_givesFreshStationaryClock() {
        val policy = policy()
        policy.feed(0L, 120_000L, speedMps = 0f)
        policy.reset(500_000L)
        assertEquals(Decision.NONE, policy.feed(500_000L, 619_000L, speedMps = 0f))
        assertEquals(Decision.PAUSE, policy.onFix(621_000L, BASE_LAT, BASE_LON, 0f, 5f, isPaused = false))
    }
}
