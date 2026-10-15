package com.mapmyroad.domain.upload

import com.mapmyroad.detector.TripScoring
import com.mapmyroad.domain.model.RecordedHazard
import com.mapmyroad.domain.model.SegmentSample
import com.mapmyroad.domain.model.TripPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class TripTrimmerTest {
    @Test
    fun tripUnder1Km_uploadsNothing() {
        val track = northTrack(lengthM = 990.0)
        val hazards = listOf(hazardAt("middle", track[track.size / 2]))
        val samples = listOf(windowBetween("w", track[45], track[50], lengthM = 50.0))

        val result = TripTrimmer.trim(track, hazards, samples)

        assertTrue(result.isEmpty)
        assertEquals(990.0, result.trackLengthM, 0.5)
    }

    @Test
    fun noOrSinglePoint_uploadsNothing() {
        val single = listOf(point(0.0, 0.0, 0L))
        val hazard = hazardAt("h", single[0])

        assertTrue(TripTrimmer.trim(emptyList(), listOf(hazard), emptyList()).isEmpty)
        assertTrue(TripTrimmer.trim(single, listOf(hazard), emptyList()).isEmpty)
    }

    @Test
    fun boundaries_keepFrom500mAndDropJustInside() {
        // One fix per meter, so the boundary can be probed to 1 m. Total 2000 m.
        val track = northTrack(lengthM = 2_000.0, stepM = 1.0)
        val hazards =
            listOf(
                hazardAt("start-499", track[499]),
                hazardAt("start-501", track[501]),
                hazardAt("end-499", track[2_000 - 499]),
                hazardAt("end-501", track[2_000 - 501]),
                hazardAt("middle", track[1_000]),
            )

        val kept = TripTrimmer.trim(track, hazards, emptyList()).hazards.map { it.id }.toSet()

        assertEquals(setOf("start-501", "end-501", "middle"), kept)
    }

    @Test
    fun exactly1Km_keepsNothingOffTheMiddle() {
        val track = northTrack(lengthM = 1_000.0, stepM = 1.0)
        val hazards = listOf(hazardAt("498", track[498]), hazardAt("502", track[502]))

        val result = TripTrimmer.trim(track, hazards, emptyList())

        // Anything off the 500 m point is within 500 m of one end.
        assertTrue(result.hazards.isEmpty())
    }

    @Test
    fun window_needsBothEndpointsInsideTheKeptSection() {
        val track = northTrack(lengthM = 3_000.0)
        val samples =
            listOf(
                // Ends at 530 m but starts at 480 m: its start is inside the trimmed head.
                windowBetween("head", track[48], track[53], lengthM = 50.0),
                windowBetween("middle", track[150], track[155], lengthM = 50.0),
                // Ends at 2480 m (520 m before the end) but the 50 m margin reaches into the tail.
                windowBetween("tail", track[243], track[248], lengthM = 50.0),
                windowBetween("bad-length", track[150], track[155], lengthM = Double.NaN),
            )

        val kept = TripTrimmer.trim(track, emptyList(), samples).samples.map { it.id }

        assertEquals(listOf("middle"), kept)
    }

    @Test
    fun loopPassingHome_dropsItemsNearTheStartPoint() {
        // Home -> 800 m north -> back past home -> 800 m south. Total 2400 m.
        val points = mutableListOf<TripPoint>()
        var tMs = 0L
        for (y in 0..800 step 10) points += point(0.0, y.toDouble(), tMs).also { tMs += 1_000L }
        for (y in 790 downTo -800 step 10) points += point(0.0, y.toDouble(), tMs).also { tMs += 1_000L }
        // 1500 m along the path, 1000 m from the end, but only 100 m from home.
        val nearHome = points.first { it.tMs == 150_000L }
        // 700 m along the path, 700 m north of home.
        val farNorth = points.first { it.tMs == 70_000L }

        val kept =
            TripTrimmer
                .trim(points, listOf(hazardAt("near-home", nearHome), hazardAt("far-north", farNorth)), emptyList())
                .hazards
                .map { it.id }

        assertEquals(100.0, TripScoring.haversineM(points[0].lat, points[0].lon, nearHome.lat, nearHome.lon), 1.0)
        assertEquals(listOf("far-north"), kept)
    }

    @Test
    fun loopEndingAtHome_dropsItemsNearTheEndPoint() {
        // A thin loop: 1500 m north, 30 m east, 1500 m back south, ending 30 m from the start.
        val points = mutableListOf<TripPoint>()
        var tMs = 0L
        for (y in 0..1_500 step 10) points += point(0.0, y.toDouble(), tMs).also { tMs += 1_000L }
        for (y in 1_500 downTo 0 step 10) points += point(30.0, y.toDouble(), tMs).also { tMs += 1_000L }
        val onReturnAt450 = points.first { it.lat == latAt(450.0) && it.lon == lonAt(30.0) }
        val onReturnAt600 = points.first { it.lat == latAt(600.0) && it.lon == lonAt(30.0) }

        val kept =
            TripTrimmer
                .trim(points, listOf(hazardAt("450", onReturnAt450), hazardAt("600", onReturnAt600)), emptyList())
                .hazards
                .map { it.id }

        assertEquals(listOf("600"), kept)
    }

    @Test
    fun gpsGapAtTheStart_boundsItemsByTheFixBeforeTheGap() {
        // Fix at 0 m, then no fix for 60 s, then fixes from 700 m to 2500 m.
        val points = mutableListOf(point(0.0, 0.0, 0L))
        for (y in 700..2_500 step 10) points += point(0.0, y.toDouble(), 60_000L + (y - 700) * 100L)
        // Detected during the gap. Its own position is 600 m out, but the track cannot prove it was past 500 m.
        val inGap = hazardAt("in-gap", point(0.0, 600.0, 30_000L))
        val afterGap = hazardAt("after-gap", points.first { it.lat == latAt(1_000.0) })

        val kept = TripTrimmer.trim(points, listOf(inGap, afterGap), emptyList()).hazards.map { it.id }

        assertEquals(listOf("after-gap"), kept)
    }

    @Test
    fun gpsGapInTheMiddle_keepsItemsBetweenFarFixes() {
        val points = mutableListOf<TripPoint>()
        for (y in 0..800 step 10) points += point(0.0, y.toDouble(), y * 100L)
        // Two minutes without a fix, then the trip continues from 1400 m.
        for (y in 1_400..2_500 step 10) points += point(0.0, y.toDouble(), 200_000L + y * 100L)
        val inGap = hazardAt("in-gap", point(0.0, 1_100.0, 150_000L))

        val kept = TripTrimmer.trim(points, listOf(inGap), emptyList()).hazards.map { it.id }

        assertEquals(listOf("in-gap"), kept)
    }

    @Test
    fun duplicatedTimestamps_useTheLeastFavorableFix() {
        // Two fixes share t = 50 s, at 495 m and 505 m.
        val points = northTrack(lengthM = 2_000.0).toMutableList()
        points[50] = point(0.0, 495.0, 49_500L)
        points.add(51, point(0.0, 505.0, 49_500L))
        val atDuplicate = hazardAt("dup", point(0.0, 600.0, 49_500L))

        val kept = TripTrimmer.trim(points, listOf(atDuplicate), emptyList()).hazards

        assertTrue(kept.isEmpty())
    }

    @Test
    fun unsortedInput_givesTheSameResultAsSorted() {
        val track = northTrack(lengthM = 3_000.0)
        val hazards = track.filterIndexed { index, _ -> index % 7 == 0 }.map { hazardAt("h${it.tMs}", it) }
        val shuffled = track.shuffled(Random(42))

        assertEquals(TripTrimmer.trim(track, hazards, emptyList()), TripTrimmer.trim(shuffled, hazards, emptyList()))
    }

    @Test
    fun itemsOutsideTheTrackTime_areDropped() {
        val track = northTrack(lengthM = 3_000.0)
        val middle = track[150]
        val before = hazardAt("before", middle, tMs = -1L)
        val after = hazardAt("after", middle, tMs = track.last().tMs + 1L)

        assertTrue(TripTrimmer.trim(track, listOf(before, after), emptyList()).hazards.isEmpty())
    }

    @Test
    fun nonFinitePoints_areIgnored() {
        val track = northTrack(lengthM = 3_000.0).toMutableList()
        track[10] = track[10].copy(lat = Double.NaN)

        val result = TripTrimmer.trim(track, listOf(hazardAt("middle", track[150])), emptyList())

        assertEquals(listOf("middle"), result.hazards.map { it.id })
        assertEquals(3_000.0, result.trackLengthM, 1.0)
    }

    /**
     * PV-2 property test (TESTING_STRATEGY.md section 6): for random trips, nothing that is kept lies within 500 m
     * along the path of the start or end, or within 500 m in a straight line of the first or last fix. Also checks
     * that items clearly in the middle are kept, so the trimmer cannot pass by dropping everything.
     */
    @Test
    fun property_randomTrips_neverKeepAnythingNearTheEnds() {
        val random = Random(20_261_015)
        var keptTotal = 0
        repeat(TRIP_COUNT) { tripIndex ->
            val trip = randomTrip(random, isLoop = tripIndex % 3 == 0, hasDuplicates = tripIndex % 4 == 1)
            val input = if (tripIndex % 5 == 2 && !trip.hasDuplicates) trip.points.shuffled(random) else trip.points
            val result = TripTrimmer.trim(input, trip.hazards.map { it.hazard }, trip.windows.map { it.window })
            val kept = result.hazards.map { it.id }.toSet() + result.samples.map { it.id }.toSet()
            keptTotal += kept.size
            val first = trip.points.first()
            val last = trip.points.last()

            fun farFromEnds(
                lat: Double,
                lon: Double,
            ): Boolean =
                TripScoring.haversineM(first.lat, first.lon, lat, lon) >= TRIM_M &&
                    TripScoring.haversineM(last.lat, last.lon, lat, lon) >= TRIM_M

            if (trip.totalM < 1_000.0) {
                assertTrue("trip $tripIndex under 1 km kept items", kept.isEmpty())
            }
            for (item in trip.hazards) {
                val isKept = item.hazard.id in kept
                val isInside = item.alongM >= TRIM_M && item.alongM <= trip.totalM - TRIM_M
                val isFar = farFromEnds(item.hazard.lat, item.hazard.lon)
                if (isKept) {
                    assertTrue("trip $tripIndex hazard ${item.hazard.id} along ${item.alongM}", isInside)
                    assertTrue("trip $tripIndex hazard ${item.hazard.id} near an end", isFar)
                }
                val isClearlyInside = item.alongM >= TRIM_M + 1.0 && item.alongM <= trip.totalM - TRIM_M - 1.0
                if (item.isOnUniqueFix && isClearlyInside && isFar && trip.totalM >= 1_000.0) {
                    assertTrue("trip $tripIndex hazard ${item.hazard.id} should be kept", isKept)
                }
            }
            for (item in trip.windows) {
                if (item.window.id !in kept) continue
                assertTrue("trip $tripIndex window start ${item.startAlongM}", item.startAlongM >= TRIM_M)
                assertTrue("trip $tripIndex window end ${item.endAlongM}", item.endAlongM <= trip.totalM - TRIM_M)
                assertTrue(farFromEnds(item.window.startLat, item.window.startLon))
                assertTrue(farFromEnds(item.window.endLat, item.window.endLon))
            }
        }
        assertTrue("the trimmer kept almost nothing ($keptTotal)", keptTotal > TRIP_COUNT * 5)
    }

    private class GeneratedHazard(
        val hazard: RecordedHazard,
        val alongM: Double,
        val isOnUniqueFix: Boolean,
    )

    private class GeneratedWindow(
        val window: SegmentSample,
        val startAlongM: Double,
        val endAlongM: Double,
    )

    private class GeneratedTrip(
        val points: List<TripPoint>,
        val totalM: Double,
        val hazards: List<GeneratedHazard>,
        val windows: List<GeneratedWindow>,
        val hasDuplicates: Boolean,
    )

    /** Random walk with turns, speed changes, GPS gaps and optionally duplicated timestamps or a return home. */
    private fun randomTrip(
        random: Random,
        isLoop: Boolean,
        hasDuplicates: Boolean,
    ): GeneratedTrip {
        val stepCount = random.nextInt(60, 900)
        val points = mutableListOf<TripPoint>()
        var x = 0.0
        var y = 0.0
        var headingRad = random.nextDouble(0.0, 2 * PI)
        var tMs = 1_000_000L
        points += point(x, y, tMs)
        for (step in 1..stepCount) {
            headingRad +=
                if (isLoop && step > stepCount / 2) {
                    // Steer back towards the start for the second half.
                    val turnRad = atan2(-y, -x) - headingRad
                    atan2(sin(turnRad), cos(turnRad)) * 0.3
                } else {
                    random.nextDouble(-0.3, 0.3)
                }
            val stepM = random.nextDouble(2.0, 20.0)
            x += stepM * cos(headingRad)
            y += stepM * sin(headingRad)
            val isGap = random.nextInt(40) == 0
            val isDuplicate = hasDuplicates && random.nextInt(15) == 0
            tMs +=
                when {
                    isDuplicate -> 0L
                    isGap -> random.nextLong(5_000L, 120_000L)
                    else -> 1_000L
                }
            points += point(x, y, tMs)
        }

        val cumulative = DoubleArray(points.size)
        for (index in 1 until points.size) {
            val a = points[index - 1]
            val b = points[index]
            cumulative[index] = cumulative[index - 1] + TripScoring.haversineM(a.lat, a.lon, b.lat, b.lon)
        }
        val totalM = cumulative.last()

        val hazards =
            (0 until random.nextInt(5, 40)).map { n ->
                val index = random.nextInt(points.size)
                val fix = points[index]
                val isUnique = points.count { it.tMs == fix.tMs } == 1
                GeneratedHazard(hazardAt("h$n", fix), cumulative[index], isUnique)
            }
        val windows =
            (0 until random.nextInt(5, 40)).mapNotNull { n ->
                val startIndex = random.nextInt(points.size)
                var endIndex = startIndex
                while (endIndex < points.size - 1 && cumulative[endIndex] - cumulative[startIndex] < 50.0) endIndex++
                if (endIndex == startIndex) return@mapNotNull null
                val lengthM = cumulative[endIndex] - cumulative[startIndex]
                GeneratedWindow(
                    windowBetween("w$n", points[startIndex], points[endIndex], lengthM),
                    startAlongM = cumulative[startIndex],
                    endAlongM = cumulative[endIndex],
                )
            }
        return GeneratedTrip(points, totalM, hazards, windows, hasDuplicates)
    }

    private companion object {
        const val TRIM_M = TripTrimmer.TRIM_DISTANCE_M
        const val TRIP_COUNT = 400
    }
}
