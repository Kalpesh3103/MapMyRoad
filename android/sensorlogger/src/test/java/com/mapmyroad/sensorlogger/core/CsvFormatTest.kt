package com.mapmyroad.sensorlogger.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class CsvFormatTest {
    @Test
    fun timeMs_hasExactlyThreeDecimals() {
        assertEquals("0.000", CsvFormat.appendTimeMs(StringBuilder(), 0L).toString())
        assertEquals("0.007", CsvFormat.appendTimeMs(StringBuilder(), 7L).toString())
        assertEquals("0.070", CsvFormat.appendTimeMs(StringBuilder(), 70L).toString())
        assertEquals("1234.567", CsvFormat.appendTimeMs(StringBuilder(), 1_234_567L).toString())
        assertEquals("10.000", CsvFormat.appendTimeMs(StringBuilder(), 10_000L).toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun timeMs_rejectsNegative() {
        CsvFormat.appendTimeMs(StringBuilder(), -1L)
    }

    @Test
    fun sensorRow_leavesWEmpty_whenNaN() {
        val row = CsvFormat.appendSensorRow(StringBuilder(), 10_500L, "acc", 0.5f, -9.81f, 1.25f, Float.NaN).toString()
        assertEquals("10.500,acc,0.5,-9.81,1.25,\n", row)
    }

    @Test
    fun sensorRow_writesW_forRotationVector() {
        val row = CsvFormat.appendSensorRow(StringBuilder(), 1_000L, "rot", 0.1f, 0.2f, 0.3f, 0.9f).toString()
        assertEquals("1.000,rot,0.1,0.2,0.3,0.9\n", row)
    }

    @Test
    fun sensorRow_usesDotDecimal_evenWithCommaLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val row = CsvFormat.appendSensorRow(StringBuilder(), 0L, "gyro", 1.5f, 0f, -0.25f, Float.NaN).toString()
            assertEquals("0.000,gyro,1.5,0.0,-0.25,\n", row)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun locationRow_writesAllValues() {
        val row =
            CsvFormat
                .appendLocationRow(StringBuilder(), 2_000_000L, 1_760_000_000_000L, 18.5204, 73.8567, 8.5f, 4.0f, 187.5f, 560.25)
                .toString()
        assertEquals("2000.000,1760000000000,18.5204,73.8567,8.5,4.0,187.5,560.25\n", row)
    }

    @Test
    fun locationRow_leavesMissingValuesEmpty() {
        val row =
            CsvFormat
                .appendLocationRow(StringBuilder(), 1L, 5L, 1.0, 2.0, Float.NaN, Float.NaN, Float.NaN, Double.NaN)
                .toString()
        assertEquals("0.001,5,1.0,2.0,,,,\n", row)
    }

    @Test
    fun tagRow_writesSeverity_orEmpty() {
        assertEquals(
            "183400.000,99,pothole,3\n",
            CsvFormat.appendTagRow(StringBuilder(), 183_400_000L, 99L, TagType.POTHOLE.code, TagSeverity.SEVERE).toString(),
        )
        assertEquals(
            "204100.000,99,speed_breaker,\n",
            CsvFormat.appendTagRow(StringBuilder(), 204_100_000L, 99L, TagType.SPEED_BREAKER.code, TagSeverity.NONE).toString(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun tagRow_rejectsInvalidSeverity() {
        CsvFormat.appendTagRow(StringBuilder(), 0L, 0L, TagType.POTHOLE.code, 4)
    }

    @Test
    fun headers_matchConverterContract() {
        assertEquals("t_ms,sensor,x,y,z,w", CsvFormat.SENSORS_HEADER)
        assertEquals("t_ms,wall_ms,lat,lon,speed_mps,accuracy_m,bearing_deg,altitude_m", CsvFormat.LOCATION_HEADER)
        assertEquals("t_ms,wall_ms,type,severity", CsvFormat.TAGS_HEADER)
    }

    @Test
    fun codes_matchSpec() {
        assertEquals(listOf("motorcycle", "scooter", "car", "auto_rickshaw"), VehicleType.entries.map { it.code })
        assertEquals(listOf("mount", "pocket", "bag", "other"), PhonePlacement.entries.map { it.code })
        assertEquals(
            listOf("pothole", "speed_breaker", "rough_patch_start", "rough_patch_end", "other"),
            TagType.entries.map { it.code },
        )
        assertEquals(listOf("acc", "gyro", "grav", "rot"), SensorStream.entries.map { it.code })
    }
}
