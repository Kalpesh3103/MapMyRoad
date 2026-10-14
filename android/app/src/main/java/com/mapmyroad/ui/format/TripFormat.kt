package com.mapmyroad.ui.format

import java.util.Locale

/**
 * Pure formatting helpers for trip numbers (UI_UX_GUIDELINES.md section 9). No Android imports, so they run in
 * JVM tests. Units ("km") are added by string resources so translators control their position.
 */
object TripFormat {
    private const val METERS_PER_KM = 1000.0
    private const val MS_PER_S = 1000L
    private const val S_PER_MIN = 60L
    private const val S_PER_H = 3600L

    /** Kilometers with one decimal, for example "12.4" for 12 400 m. Negative or invalid input shows as "0.0". */
    fun distanceKm(
        distanceM: Double,
        locale: Locale,
    ): String {
        val safeM = if (distanceM.isNaN() || distanceM < 0.0) 0.0 else distanceM
        return String.format(locale, "%.1f", safeM / METERS_PER_KM)
    }

    /** Duration as h:mm:ss, for example "0:23:41". Negative input shows as "0:00:00". */
    fun duration(
        durationS: Long,
        locale: Locale,
    ): String {
        val totalS = durationS.coerceAtLeast(0L)
        val hours = totalS / S_PER_H
        val minutes = (totalS % S_PER_H) / S_PER_MIN
        val seconds = totalS % S_PER_MIN
        return String.format(locale, "%d:%02d:%02d", hours, minutes, seconds)
    }

    /** Whole seconds between [startedAtMs] and [nowMs], never negative (the wall clock can move backwards). */
    fun elapsedS(
        startedAtMs: Long,
        nowMs: Long,
    ): Long = ((nowMs - startedAtMs) / MS_PER_S).coerceAtLeast(0L)
}

/**
 * Road quality band of a 0 to 100 smoothness score (UI_UX_GUIDELINES.md section 4.2). Thresholds are initial
 * values from the guidelines, not measured facts.
 */
enum class ScoreBand {
    GOOD,
    FAIR,
    POOR,
    NO_DATA,
    ;

    companion object {
        const val GOOD_MIN_SCORE = 70
        const val FAIR_MIN_SCORE = 40

        fun of(score: Int?): ScoreBand =
            when {
                score == null -> NO_DATA
                score >= GOOD_MIN_SCORE -> GOOD
                score >= FAIR_MIN_SCORE -> FAIR
                else -> POOR
            }
    }
}

/** Part of the day for the Home greeting. */
enum class DayPart {
    MORNING,
    AFTERNOON,
    EVENING,
    ;

    companion object {
        private const val MORNING_START_HOUR = 5
        private const val AFTERNOON_START_HOUR = 12
        private const val EVENING_START_HOUR = 17

        fun of(hourOfDay: Int): DayPart =
            when (hourOfDay) {
                in MORNING_START_HOUR until AFTERNOON_START_HOUR -> MORNING
                in AFTERNOON_START_HOUR until EVENING_START_HOUR -> AFTERNOON
                else -> EVENING
            }
    }
}
