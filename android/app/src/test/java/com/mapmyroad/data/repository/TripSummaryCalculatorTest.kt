package com.mapmyroad.data.repository

import com.mapmyroad.detector.RoughnessWindow
import com.mapmyroad.detector.Severity
import com.mapmyroad.domain.model.RecordedHazard
import com.mapmyroad.domain.model.SegmentSample
import com.mapmyroad.domain.model.TripPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

class TripSummaryCalculatorTest {
    private val calculator = TripSummaryCalculator(smoothnessScorer = { null })

    @Test
    fun distance_isWithinFivePercent_forStraightNorthRoute() {
        // 0.09 degrees of latitude along one meridian: R * delta(phi) = 6_371_000 * 0.0015708 = 10_007.5 m.
        val points = straightRoute(startLat = 18.50, startLon = 73.85, dLatPerStep = 0.00009, dLonPerStep = 0.0, steps = 1000)
        val referenceM = EARTH_RADIUS_M * Math.toRadians(0.09)

        val distanceM = calculator.distanceM(points)

        assertWithinFivePercent(referenceM, distanceM)
    }

    @Test
    fun distance_isWithinFivePercent_forStraightDiagonalRoute() {
        // About 5 km to the north-east. Over this length a flat-earth reference is accurate to far better than 1%.
        val dLat = 0.00003
        val dLon = 0.00003
        val steps = 1500
        val points = straightRoute(startLat = 18.52, startLon = 73.85, dLatPerStep = dLat, dLonPerStep = dLon, steps = steps)
        val meanLatRad = Math.toRadians(18.52 + dLat * steps / 2)
        val northM = EARTH_RADIUS_M * Math.toRadians(dLat * steps)
        val eastM = EARTH_RADIUS_M * Math.toRadians(dLon * steps) * cos(meanLatRad)
        val referenceM = sqrt(northM * northM + eastM * eastM)

        assertWithinFivePercent(referenceM, calculator.distanceM(points))
    }

    @Test
    fun distance_skipsGpsJump_andKeepsDistanceToNextGoodFix() {
        val route = straightRoute(startLat = 18.50, startLon = 73.85, dLatPerStep = 0.00009, dLonPerStep = 0.0, steps = 100)
        val clean = calculator.distanceM(route)
        // An outlier about 10 km east, half a second after point 50: implies far more than 200 km/h.
        val outlier = route[50].copy(tMs = route[50].tMs + 500, lon = route[50].lon + 0.1)
        val withJump = route.toMutableList().apply { add(51, outlier) }

        val distanceM = calculator.distanceM(withJump)

        assertEquals(clean, distanceM, clean * 0.001)
    }

    @Test
    fun distance_recovers_whenFirstPointIsOutlier() {
        val route = straightRoute(startLat = 18.50, startLon = 73.85, dLatPerStep = 0.00009, dLonPerStep = 0.0, steps = 100)
        val outlierStart = route.first().copy(tMs = route.first().tMs - 1000, lon = route.first().lon + 0.1)

        val distanceM = calculator.distanceM(listOf(outlierStart) + route)

        // The anchor is replaced after three rejections, so only the first few steps are lost.
        val clean = calculator.distanceM(route)
        assertTrue("distance $distanceM vs clean $clean", distanceM > clean * 0.95 && distanceM <= clean)
    }

    @Test
    fun distance_isZero_forFewerThanTwoPoints() {
        assertEquals(0.0, calculator.distanceM(emptyList()), 0.0)
        assertEquals(0.0, calculator.distanceM(listOf(point(tMs = 0, lat = 18.5, lon = 73.85))), 0.0)
    }

    @Test
    fun distance_ignoresPointsWithSameTimestamp() {
        val a = point(tMs = 1_000, lat = 18.5, lon = 73.85)
        val duplicate = point(tMs = 1_000, lat = 18.6, lon = 73.85)
        val b = point(tMs = 2_000, lat = 18.50009, lon = 73.85)

        val distanceM = calculator.distanceM(listOf(a, duplicate, b))

        assertEquals(EARTH_RADIUS_M * Math.toRadians(0.00009), distanceM, 0.5)
    }

    @Test
    fun distance_sortsPointsByTime() {
        val route = straightRoute(startLat = 18.50, startLon = 73.85, dLatPerStep = 0.00009, dLonPerStep = 0.0, steps = 50)

        assertEquals(calculator.distanceM(route), calculator.distanceM(route.shuffled(Random(7))), 1e-6)
    }

    @Test
    fun duration_isWholeSecondsBetweenStartAndEnd() {
        assertEquals(125, calculator.durationS(startedAtMs = 1_000_000, endedAtMs = 1_125_900))
        assertEquals(0, calculator.durationS(startedAtMs = 2_000, endedAtMs = 1_000))
    }

    @Test
    fun calculate_countsHazardsBySeverity() {
        val hazards =
            listOf(Severity.MINOR, Severity.MINOR, Severity.MODERATE, Severity.SEVERE, Severity.SEVERE, Severity.SEVERE)
                .mapIndexed { index, severity -> hazard(id = "h$index", severity = severity) }

        val summary = calculator.calculate(0, 60_000, emptyList(), hazards, emptyList())

        assertEquals(2, summary.minorCount)
        assertEquals(1, summary.moderateCount)
        assertEquals(3, summary.severeCount)
        assertEquals(60, summary.durationS)
        assertEquals(0.0, summary.distanceM, 0.0)
        assertNull(summary.smoothnessScore)
    }

    @Test
    fun calculate_passesSamplesToScorer_asWindowsInTimeOrder() {
        var received: List<RoughnessWindow> = emptyList()
        val scoring =
            TripSummaryCalculator(
                smoothnessScorer = { windows ->
                    received = windows
                    73
                },
            )
        val late = sample(id = "s2", observedAtMs = 20_000, roughness = 2.0)
        val early = sample(id = "s1", observedAtMs = 10_000, roughness = 1.0)

        val summary = scoring.calculate(0, 30_000, emptyList(), emptyList(), listOf(late, early))

        assertEquals(73, summary.smoothnessScore)
        assertEquals(listOf(10_000L, 20_000L), received.map { it.wallTimeMs })
        assertEquals(listOf(1.0, 2.0), received.map { it.roughness })
        assertTrue(received.all { it.startElapsedNanos == 0L && it.endElapsedNanos == 0L })
        assertEquals(early.lengthM, received.first().lengthM, 0.0)
        assertEquals(early.speedKmh, received.first().speedKmh, 0.0)
        assertEquals(early.startLat, received.first().startLat, 0.0)
        assertEquals(early.endLon, received.first().endLon, 0.0)
    }

    private fun assertWithinFivePercent(
        referenceM: Double,
        actualM: Double,
    ) {
        val relativeError = abs(actualM - referenceM) / referenceM
        assertTrue("distance $actualM m vs reference $referenceM m (error $relativeError)", relativeError <= 0.05)
    }

    private fun straightRoute(
        startLat: Double,
        startLon: Double,
        dLatPerStep: Double,
        dLonPerStep: Double,
        steps: Int,
    ): List<TripPoint> =
        (0..steps).map { i ->
            point(tMs = START_MS + i * 1_000L, lat = startLat + i * dLatPerStep, lon = startLon + i * dLonPerStep)
        }

    private fun point(
        tMs: Long,
        lat: Double,
        lon: Double,
    ) = TripPoint(tripId = "trip", tMs = tMs, lat = lat, lon = lon, speedMps = 10.0, accuracyM = 5.0, roughness = null)

    private fun hazard(
        id: String,
        severity: Severity,
    ) =
        RecordedHazard(
            id = id,
            tripId = "trip",
            lat = 18.5,
            lon = 73.85,
            observedAtMs = START_MS,
            severity = severity,
            speedKmh = 30.0,
            peakScore = 2.0,
            headingDeg = null,
        )

    private fun sample(
        id: String,
        observedAtMs: Long,
        roughness: Double,
    ) =
        SegmentSample(
            id = id,
            tripId = "trip",
            startLat = 18.5,
            startLon = 73.85,
            endLat = 18.5009,
            endLon = 73.85,
            observedAtMs = observedAtMs,
            lengthM = 100.0,
            roughness = roughness,
            speedKmh = 36.0,
        )

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
        const val START_MS = 1_700_000_000_000L
    }
}
