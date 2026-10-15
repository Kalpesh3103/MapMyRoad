package com.mapmyroad.ui.routes

import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.model.CoverageLevel
import com.mapmyroad.domain.model.RouteCandidate
import com.mapmyroad.domain.model.RouteCoverage
import com.mapmyroad.domain.model.RouteLabel
import com.mapmyroad.ui.AppRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.Locale

class RouteHelpersTest {
    private fun candidate(
        id: String,
        durationS: Int,
        severe: Int,
        moderate: Int = 0,
        labels: Set<RouteLabel> = emptySet(),
        coverage: RouteCoverage? = null,
    ): RouteCandidate =
        RouteCandidate(
            id = id,
            labels = labels,
            durationS = durationS,
            distanceM = 10_000,
            comfortScore = 70,
            severeCount = severe,
            moderateCount = moderate,
            minorCount = 0,
            encodedPolyline6 = "",
            isRecommended = false,
            coverage = coverage,
        )

    private val fastest = candidate("c1", durationS = 1380, severe = 3, moderate = 7, labels = setOf(RouteLabel.FASTEST))

    @Test
    fun tradeOff_specExample_fourMinutesSlower_farFewerSevere() {
        val smoothest = candidate("c2", durationS = 1620, severe = 0, moderate = 4)
        assertEquals(TradeOff(4, HazardComparison.FAR_FEWER_SEVERE), RouteTradeOffs.tradeOff(smoothest, fastest))
    }

    @Test
    fun tradeOff_fastestItself_hasNoSentence() {
        assertNull(RouteTradeOffs.tradeOff(fastest, fastest))
    }

    @Test
    fun tradeOff_minutesRoundToNearest_andCanBeFaster() {
        // 89 s is 1.48 min, rounds to 1.
        assertEquals(1, RouteTradeOffs.tradeOff(candidate("a", 1469, severe = 2), fastest)?.minutesDifference)
        // 29 s rounds to 0: about the same time.
        assertEquals(0, RouteTradeOffs.tradeOff(candidate("b", 1409, severe = 2), fastest)?.minutesDifference)
        // A candidate can be faster than the "fastest" label when the label is stale relative to the durations.
        assertEquals(-2, RouteTradeOffs.tradeOff(candidate("c", 1260, severe = 2), fastest)?.minutesDifference)
    }

    @Test
    fun hazards_comparison() {
        assertEquals(HazardComparison.FAR_FEWER_SEVERE, RouteTradeOffs.compareHazards(candidate("a", 0, severe = 1), fastest))
        assertEquals(HazardComparison.FEWER_SEVERE, RouteTradeOffs.compareHazards(candidate("a", 0, severe = 2), fastest))
        assertEquals(HazardComparison.MORE_SEVERE, RouteTradeOffs.compareHazards(candidate("a", 0, severe = 4), fastest))
        assertEquals(HazardComparison.FEWER_ROUGH, RouteTradeOffs.compareHazards(candidate("a", 0, severe = 3, moderate = 2), fastest))
        assertEquals(HazardComparison.MORE_ROUGH, RouteTradeOffs.compareHazards(candidate("a", 0, severe = 3, moderate = 9), fastest))
        assertEquals(HazardComparison.SIMILAR, RouteTradeOffs.compareHazards(candidate("a", 0, severe = 3, moderate = 7), fastest))
        // From one severe to none is "far fewer".
        val oneSevere = candidate("f", 0, severe = 1)
        assertEquals(HazardComparison.FAR_FEWER_SEVERE, RouteTradeOffs.compareHazards(candidate("a", 0, severe = 0), oneSevere))
    }

    @Test
    fun tradeOff_withoutData_saysUnknown() {
        val noData = RouteCoverage(CoverageLevel.NONE, 0.0)
        val other = candidate("c2", durationS = 1620, severe = 0, coverage = noData)
        assertEquals(HazardComparison.UNKNOWN, RouteTradeOffs.tradeOff(other, fastest)?.hazards)
        // Falls back to the top-level coverage when candidates carry none.
        val plain = candidate("c3", durationS = 1620, severe = 0)
        assertEquals(HazardComparison.UNKNOWN, RouteTradeOffs.tradeOff(plain, fastest, fallbackCoverage = noData)?.hazards)
    }

    @Test
    fun fastestOf_prefersLabel_elseShortestDuration() {
        val slowLabeled = candidate("x", durationS = 2000, severe = 0, labels = setOf(RouteLabel.FASTEST))
        val quick = candidate("y", durationS = 1000, severe = 0)
        assertEquals("x", RouteTradeOffs.fastestOf(listOf(quick, slowLabeled))?.id)
        assertEquals("y", RouteTradeOffs.fastestOf(listOf(quick, candidate("z", 1500, 0)))?.id)
        assertNull(RouteTradeOffs.fastestOf(emptyList()))
    }

    @Test
    fun limitedData_forPartialOrNone() {
        val partial = RouteCoverage(CoverageLevel.PARTIAL, 0.5)
        val good = RouteCoverage(CoverageLevel.GOOD, 0.9)
        assertTrue(RouteTradeOffs.isLimitedData(candidate("a", 0, 0, coverage = partial), good))
        assertFalse(RouteTradeOffs.isLimitedData(candidate("a", 0, 0, coverage = good), partial))
        assertTrue(RouteTradeOffs.isLimitedData(candidate("a", 0, 0), RouteCoverage(CoverageLevel.NONE, 0.0)))
        assertFalse(RouteTradeOffs.isLimitedData(candidate("a", 0, 0), null))
    }

    @Test
    fun labels_shownOnceInFixedOrder() {
        assertEquals(
            listOf(RouteLabel.SMOOTHEST, RouteLabel.BALANCED),
            RouteTradeOffs.orderedLabels(setOf(RouteLabel.BALANCED, RouteLabel.SMOOTHEST)),
        )
    }

    @Test
    fun eta_roundsToMinutes_andSplitsHours() {
        assertEquals(EtaParts(0, 23), EtaParts.of(1380))
        assertEquals(EtaParts(0, 1), EtaParts.of(10))
        assertEquals(EtaParts(1, 5), EtaParts.of(3900))
        assertEquals(EtaParts(2, 0), EtaParts.of(7190))
    }

    @Test
    fun preference_sliderLeftIsComfort() {
        assertEquals(1.0, RoutePreference.fromSliderPosition(0f), 0.0)
        assertEquals(0.0, RoutePreference.fromSliderPosition(1f), 0.0)
        assertEquals(0.5, RoutePreference.fromSliderPosition(RoutePreference.toSliderPosition(0.5)), 1e-6)
        assertEquals(0.0, RoutePreference.fromSliderPosition(1.5f), 0.0)
    }

    @Test
    fun googleMaps_travelModes() {
        assertEquals("two-wheeler", GoogleMapsHandoff.travelMode(VehicleType.MOTORCYCLE))
        assertEquals("two-wheeler", GoogleMapsHandoff.travelMode(VehicleType.SCOOTER))
        assertEquals("driving", GoogleMapsHandoff.travelMode(VehicleType.CAR))
        assertEquals("driving", GoogleMapsHandoff.travelMode(VehicleType.AUTO_RICKSHAW))
        assertEquals("driving", GoogleMapsHandoff.travelMode(null))
    }

    @Test
    fun googleMaps_url() {
        val url = GoogleMapsHandoff.directionsUrl(18.5204, 73.8567, 18.559, 73.7868, VehicleType.SCOOTER)
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&origin=18.520400,73.856700&destination=18.559000,73.786800&travelmode=two-wheeler",
            url,
        )
    }

    @Test
    fun googleMaps_url_withoutOrigin_startsFromCurrentLocation() {
        val url = GoogleMapsHandoff.directionsUrl(null, null, 18.559, 73.7868, VehicleType.CAR)
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=18.559000,73.786800&travelmode=driving", url)
    }

    @Test
    fun googleMaps_coordinates_neverUseCommaDecimals() {
        val previous = Locale.getDefault()
        try {
            // German and French use a comma as the decimal separator.
            Locale.setDefault(Locale.GERMANY)
            assertEquals("18.520400,-73.856700", GoogleMapsHandoff.coordinates(18.5204, -73.8567))
            Locale.setDefault(Locale.FRANCE)
            assertTrue(GoogleMapsHandoff.directionsUrl(1.5, 2.5, 3.5, 4.5, null).contains("origin=1.500000,2.500000"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun routeCandidatesRoute_encodesNameAndCoordinates() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val route = AppRoutes.routeCandidates(18.5195, 73.8553, "Shaniwar Wada & Co/1+1 ?#")
            assertEquals("routes/18.519500/73.855300?destName=Shaniwar%20Wada%20%26%20Co%2F1%2B1%20%3F%23", route)
            val encodedName = route.substringAfter("destName=")
            assertEquals("Shaniwar Wada & Co/1+1 ?#", URLDecoder.decode(encodedName, "UTF-8"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun routeCandidatesRoute_patternHasAllArguments() {
        assertEquals("routes/{destLat}/{destLon}?destName={destName}", AppRoutes.ROUTE_CANDIDATES)
        assertEquals("search", AppRoutes.SEARCH)
        assertEquals("%E0%A4%AA%E0%A5%81%E0%A4%A3%E0%A5%87", AppRoutes.encodeArgument("पुणे"))
    }
}
