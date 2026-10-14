package com.mapmyroad.sensing.policy

import com.mapmyroad.domain.session.GpsStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsStatusEvaluatorTest {
    private val evaluator = GpsStatusEvaluator(goodAccuracyM = 20f, maxFixAgeMs = 5_000L)

    @Test
    fun noFix_isWaiting() {
        assertEquals(GpsStatus.WAITING, evaluator.evaluate(nowMs = 10_000L))
    }

    @Test
    fun accurateRecentFix_isGood() {
        evaluator.onFix(fixAtMs = 10_000L, accuracyM = 20f)
        assertEquals(GpsStatus.GOOD, evaluator.evaluate(nowMs = 11_000L))
    }

    @Test
    fun inaccurateOrUnknownAccuracy_isWeak() {
        evaluator.onFix(fixAtMs = 10_000L, accuracyM = 35f)
        assertEquals(GpsStatus.WEAK, evaluator.evaluate(nowMs = 10_500L))
        evaluator.onFix(fixAtMs = 11_000L, accuracyM = null)
        assertEquals(GpsStatus.WEAK, evaluator.evaluate(nowMs = 11_500L))
    }

    @Test
    fun staleFix_isWaiting() {
        evaluator.onFix(fixAtMs = 10_000L, accuracyM = 5f)
        assertEquals(GpsStatus.GOOD, evaluator.evaluate(nowMs = 15_000L))
        assertEquals(GpsStatus.WAITING, evaluator.evaluate(nowMs = 15_001L))
    }

    @Test
    fun reset_forgetsFix() {
        evaluator.onFix(fixAtMs = 10_000L, accuracyM = 5f)
        evaluator.reset()
        assertEquals(GpsStatus.WAITING, evaluator.evaluate(nowMs = 10_100L))
    }
}
