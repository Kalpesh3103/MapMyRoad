package com.mapmyroad.ui

import java.net.URLEncoder
import java.util.Locale

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

    /** Destination search (S8, M5-7). */
    const val SEARCH = "search"

    const val DESTINATION_LAT_ARG = "destLat"
    const val DESTINATION_LON_ARG = "destLon"
    const val DESTINATION_NAME_ARG = "destName"

    /**
     * Route candidates (S8, M5-8). Coordinates are string arguments (a Float argument would lose precision) and
     * the name is an optional query argument. Use [routeCandidates] to build a concrete route.
     */
    const val ROUTE_CANDIDATES = "routes/{$DESTINATION_LAT_ARG}/{$DESTINATION_LON_ARG}?$DESTINATION_NAME_ARG={$DESTINATION_NAME_ARG}"

    fun routeCandidates(
        destinationLat: Double,
        destinationLon: Double,
        destinationName: String,
    ): String {
        val lat = String.format(Locale.ROOT, "%.6f", destinationLat)
        val lon = String.format(Locale.ROOT, "%.6f", destinationLon)
        return "routes/$lat/$lon?$DESTINATION_NAME_ARG=${encodeArgument(destinationName)}"
    }

    /**
     * Percent-encodes a free-text argument (like Android's Uri.encode, but pure JVM so it runs in unit tests).
     * URLEncoder writes spaces as "+", which navigation would not turn back into spaces, hence the replace; a
     * literal "+" is already encoded as "%2B" at that point.
     */
    fun encodeArgument(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
