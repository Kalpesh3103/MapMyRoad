package com.mapmyroad.sensorlogger.core

/**
 * Row formatting for the session CSV files. Pure Kotlin, no Android dependencies.
 *
 * The formats are a contract with tools/traces/convert_session.py. Do not change columns.
 * - Numbers use StringBuilder.append(Float/Double/Long), which is locale independent (always '.'
 *   as decimal separator). Floats may appear in scientific notation (for example `1.0E-4`).
 * - Missing values are written as empty cells. Callers pass NaN (or [TagSeverity.NONE]) for "missing".
 * - Rows end with '\n'.
 *
 * Threading: stateless; callers own the StringBuilder they pass in (one per stream, reused).
 */
object CsvFormat {
    const val SENSORS_HEADER = "t_ms,sensor,x,y,z,w"
    const val LOCATION_HEADER = "t_ms,wall_ms,lat,lon,speed_mps,accuracy_m,bearing_deg,altitude_m"
    const val TAGS_HEADER = "t_ms,wall_ms,type,severity"

    private const val MICROS_PER_MILLI = 1_000L

    /** Appends [tUs] (microseconds, >= 0) as milliseconds with exactly 3 decimals, e.g. 1234567 -> "1234.567". */
    fun appendTimeMs(
        sb: StringBuilder,
        tUs: Long,
    ): StringBuilder {
        require(tUs >= 0L) { "tUs must be >= 0" }
        val wholeMs = tUs / MICROS_PER_MILLI
        val fracUs = (tUs % MICROS_PER_MILLI).toInt()
        sb.append(wholeMs).append('.')
        if (fracUs < 100) sb.append('0')
        if (fracUs < 10) sb.append('0')
        sb.append(fracUs)
        return sb
    }

    /** sensors.csv row. [w] is NaN for all streams except the rotation vector. */
    fun appendSensorRow(
        sb: StringBuilder,
        tUs: Long,
        sensorCode: String,
        x: Float,
        y: Float,
        z: Float,
        w: Float,
    ): StringBuilder {
        appendTimeMs(sb, tUs).append(',').append(sensorCode).append(',')
        appendFloat(sb, x).append(',')
        appendFloat(sb, y).append(',')
        appendFloat(sb, z).append(',')
        appendFloat(sb, w).append('\n')
        return sb
    }

    /** location.csv row. Pass NaN for values the Location does not have. */
    fun appendLocationRow(
        sb: StringBuilder,
        tUs: Long,
        wallMs: Long,
        lat: Double,
        lon: Double,
        speedMps: Float,
        accuracyM: Float,
        bearingDeg: Float,
        altitudeM: Double,
    ): StringBuilder {
        appendTimeMs(sb, tUs).append(',').append(wallMs).append(',')
        appendDouble(sb, lat).append(',')
        appendDouble(sb, lon).append(',')
        appendFloat(sb, speedMps).append(',')
        appendFloat(sb, accuracyM).append(',')
        appendFloat(sb, bearingDeg).append(',')
        appendDouble(sb, altitudeM).append('\n')
        return sb
    }

    /** tags.csv row. [severity] is 1..3, or [TagSeverity.NONE] for an empty cell. */
    fun appendTagRow(
        sb: StringBuilder,
        tUs: Long,
        wallMs: Long,
        typeCode: String,
        severity: Int,
    ): StringBuilder {
        require(TagSeverity.isValid(severity)) { "severity must be 0..3" }
        appendTimeMs(sb, tUs).append(',').append(wallMs).append(',')
        sb.append(typeCode).append(',')
        if (severity != TagSeverity.NONE) sb.append(severity)
        sb.append('\n')
        return sb
    }

    private fun appendFloat(
        sb: StringBuilder,
        value: Float,
    ): StringBuilder {
        if (!value.isNaN() && !value.isInfinite()) sb.append(value)
        return sb
    }

    private fun appendDouble(
        sb: StringBuilder,
        value: Double,
    ): StringBuilder {
        if (!value.isNaN() && !value.isInfinite()) sb.append(value)
        return sb
    }
}
