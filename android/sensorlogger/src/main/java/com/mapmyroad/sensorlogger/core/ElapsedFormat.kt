package com.mapmyroad.sensorlogger.core

/** Formats durations for display. */
object ElapsedFormat {
    private const val MS_PER_S = 1_000L
    private const val S_PER_MIN = 60L
    private const val S_PER_HOUR = 3_600L

    /** `H:MM:SS`, e.g. 3_723_000 -> "1:02:03". Negative input is treated as 0. */
    fun hms(durationMs: Long): String {
        val totalS = durationMs.coerceAtLeast(0L) / MS_PER_S
        val hours = totalS / S_PER_HOUR
        val minutes = (totalS % S_PER_HOUR) / S_PER_MIN
        val seconds = totalS % S_PER_MIN
        val sb = StringBuilder()
        sb.append(hours).append(':')
        if (minutes < 10) sb.append('0')
        sb.append(minutes).append(':')
        if (seconds < 10) sb.append('0')
        sb.append(seconds)
        return sb.toString()
    }
}
