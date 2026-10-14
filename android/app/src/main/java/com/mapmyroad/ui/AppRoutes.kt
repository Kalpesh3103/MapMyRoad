package com.mapmyroad.ui

/** Routes outside the four bottom navigation tabs ([TopLevelDestination]). These screens hide the bottom bar. */
object AppRoutes {
    /** Live trip (S3), full screen. */
    const val LIVE_TRIP = "live"

    const val TRIP_ID_ARG = "tripId"

    /** Trip summary (S4). Use [tripSummary] to build a concrete route. */
    const val TRIP_SUMMARY = "trip/{$TRIP_ID_ARG}"

    /** Privacy and data (S11). */
    const val PRIVACY = "settings/privacy"

    /** Battery guidance (PRD PV-5). */
    const val BATTERY_GUIDANCE = "settings/battery"

    const val ABOUT = "settings/about"

    /** Trip IDs are generated UUIDs, so they are safe inside a route without encoding. */
    fun tripSummary(tripId: String): String = "trip/$tripId"
}
