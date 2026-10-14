package com.mapmyroad.sensorlogger.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class TimeAndNamingTest {
    @Test
    fun toSessionUs_convertsNanosToMicros() {
        assertEquals(0L, TimeBase.toSessionUs(1_000_000_000L, 1_000_000_000L))
        assertEquals(1_500L, TimeBase.toSessionUs(1_001_500_999L, 1_000_000_000L))
    }

    @Test
    fun toSessionUs_returnsMinusOne_beforeStart() {
        assertEquals(-1L, TimeBase.toSessionUs(999L, 1_000L))
    }

    @Test
    fun directoryName_usesUtcPatternAndCodes() {
        // 2026-10-14T08:30:12Z
        val name =
            SessionNaming.directoryName(
                startWallMs = 1_791_966_612_000L,
                vehicle = VehicleType.AUTO_RICKSHAW,
                placement = PhonePlacement.POCKET,
                timeZone = TimeZone.getTimeZone("UTC"),
            )
        assertEquals("20261014_083012_auto_rickshaw_pocket", name)
    }

    @Test
    fun hms_formatsDurations() {
        assertEquals("0:00:00", ElapsedFormat.hms(0L))
        assertEquals("0:00:00", ElapsedFormat.hms(-5L))
        assertEquals("0:01:05", ElapsedFormat.hms(65_999L))
        assertEquals("1:02:03", ElapsedFormat.hms(3_723_000L))
    }
}
