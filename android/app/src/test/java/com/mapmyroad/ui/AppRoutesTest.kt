package com.mapmyroad.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppRoutesTest {
    @Test
    fun tripSummary_fillsTheTripIdArgument() {
        assertEquals("trip/{tripId}", AppRoutes.TRIP_SUMMARY)
        assertEquals("trip/abc-123", AppRoutes.tripSummary("abc-123"))
    }

    @Test
    fun extraRoutes_doNotCollideWithTabs() {
        val tabRoutes = TopLevelDestination.entries.map { it.route }.toSet()
        listOf(AppRoutes.LIVE_TRIP, AppRoutes.TRIP_SUMMARY, AppRoutes.PRIVACY, AppRoutes.BATTERY_GUIDANCE, AppRoutes.ABOUT)
            .forEach { route -> assertFalse(route in tabRoutes) }
    }
}
