package com.mapmyroad.sensorlogger.core

/**
 * Session time base.
 *
 * Every CSV row carries `t_ms`: milliseconds since session start on the elapsed-realtime clock,
 * written with exactly 3 decimals (microsecond resolution). Internally we keep it as a Long of
 * microseconds (`tUs`) so the hot path never formats doubles.
 *
 * Assumption (documented in README): SensorEvent.timestamp, Location.elapsedRealtimeNanos and
 * SystemClock.elapsedRealtimeNanos() share the same elapsed-realtime base. This holds on modern
 * devices; meta.json records the lag of the first event per sensor so a wrong base can be spotted.
 */
object TimeBase {
    private const val NANOS_PER_MICRO = 1_000L
    private const val NANOS_PER_MILLI = 1_000_000L

    /** Returns microseconds since [sessionStartNanos], or -1 if [elapsedNanos] is before the start. */
    fun toSessionUs(
        elapsedNanos: Long,
        sessionStartNanos: Long,
    ): Long = if (elapsedNanos < sessionStartNanos) -1L else (elapsedNanos - sessionStartNanos) / NANOS_PER_MICRO

    fun nanosToMs(nanos: Long): Long = nanos / NANOS_PER_MILLI
}
