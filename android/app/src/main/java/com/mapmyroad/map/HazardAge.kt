package com.mapmyroad.map

/** Age of a community hazard date for display ("Seen 3 days ago"). Dates from the server are whole UTC days. */
object HazardAge {
    const val DAY_MS = 86_400_000L

    /** Whole days from [epochMs] to [nowMs], never negative (the clock can be behind the server). */
    fun daysSince(
        epochMs: Long,
        nowMs: Long,
    ): Int = ((nowMs - epochMs).coerceAtLeast(0L) / DAY_MS).toInt()
}
