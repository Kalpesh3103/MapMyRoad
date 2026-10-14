package com.mapmyroad.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TripFormatTest {
    @Test
    fun distanceKm_roundsToOneDecimal() {
        assertEquals("12.4", TripFormat.distanceKm(12_400.0, Locale.US))
        assertEquals("12.5", TripFormat.distanceKm(12_460.0, Locale.US))
        assertEquals("0.0", TripFormat.distanceKm(0.0, Locale.US))
        assertEquals("0.9", TripFormat.distanceKm(949.0, Locale.US))
    }

    @Test
    fun distanceKm_negativeOrNaN_showsZero() {
        assertEquals("0.0", TripFormat.distanceKm(-5.0, Locale.US))
        assertEquals("0.0", TripFormat.distanceKm(Double.NaN, Locale.US))
    }

    @Test
    fun distanceKm_usesLocaleDecimalSeparator() {
        assertEquals("12,4", TripFormat.distanceKm(12_400.0, Locale.GERMANY))
    }

    @Test
    fun duration_formatsAsHoursMinutesSeconds() {
        assertEquals("0:00:00", TripFormat.duration(0L, Locale.US))
        assertEquals("0:23:41", TripFormat.duration(1_421L, Locale.US))
        assertEquals("1:02:05", TripFormat.duration(3_725L, Locale.US))
        assertEquals("12:00:00", TripFormat.duration(43_200L, Locale.US))
    }

    @Test
    fun duration_negative_showsZero() {
        assertEquals("0:00:00", TripFormat.duration(-30L, Locale.US))
    }

    @Test
    fun elapsedS_truncatesToWholeSeconds_andNeverNegative() {
        assertEquals(0L, TripFormat.elapsedS(startedAtMs = 1_000L, nowMs = 1_999L))
        assertEquals(1L, TripFormat.elapsedS(startedAtMs = 1_000L, nowMs = 2_000L))
        assertEquals(0L, TripFormat.elapsedS(startedAtMs = 5_000L, nowMs = 1_000L))
    }

    @Test
    fun scoreBand_followsGuidelineThresholds() {
        assertEquals(ScoreBand.NO_DATA, ScoreBand.of(null))
        assertEquals(ScoreBand.GOOD, ScoreBand.of(100))
        assertEquals(ScoreBand.GOOD, ScoreBand.of(70))
        assertEquals(ScoreBand.FAIR, ScoreBand.of(69))
        assertEquals(ScoreBand.FAIR, ScoreBand.of(40))
        assertEquals(ScoreBand.POOR, ScoreBand.of(39))
        assertEquals(ScoreBand.POOR, ScoreBand.of(0))
    }

    @Test
    fun dayPart_coversAllHours() {
        assertEquals(DayPart.EVENING, DayPart.of(0))
        assertEquals(DayPart.EVENING, DayPart.of(4))
        assertEquals(DayPart.MORNING, DayPart.of(5))
        assertEquals(DayPart.MORNING, DayPart.of(11))
        assertEquals(DayPart.AFTERNOON, DayPart.of(12))
        assertEquals(DayPart.AFTERNOON, DayPart.of(16))
        assertEquals(DayPart.EVENING, DayPart.of(17))
        assertEquals(DayPart.EVENING, DayPart.of(23))
    }
}
