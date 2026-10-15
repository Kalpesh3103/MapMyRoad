package com.mapmyroad.ui.summary.review

import com.mapmyroad.detector.Severity
import com.mapmyroad.domain.model.ApiError
import com.mapmyroad.domain.model.CommunityHazard
import com.mapmyroad.domain.model.HazardStatus
import com.mapmyroad.domain.model.TripPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class HazardReviewPlannerTest {
    private val day = 24L * 60 * 60 * 1000
    private val tripStartMs = 1_790_000_000_000L

    /** Straight track north along lon 73.8, one point per 10 m and per second. */
    private val track =
        (0..100).map { i ->
            TripPoint(
                tripId = "t",
                tMs = tripStartMs + i * 1_000L,
                lat = 18.5 + i * DEG_PER_10_M,
                lon = 73.8,
                speedMps = 10.0,
                accuracyM = 5.0,
                roughness = null,
            )
        }

    private fun eastDeg(meters: Double): Double = meters / (111_195.0 * cos(Math.toRadians(18.5)))

    private fun hazard(
        id: String,
        alongM: Double,
        offsetM: Double,
        status: HazardStatus = HazardStatus.ACTIVE,
    ): CommunityHazard =
        CommunityHazard(
            id = id,
            lat = 18.5 + alongM / 10.0 * DEG_PER_10_M,
            lon = 73.8 + eastDeg(offsetM),
            severity = Severity.MODERATE,
            confidence = 0.6,
            status = status,
            firstSeenMs = 0L,
            lastSeenMs = 0L,
            confirmCount = 0,
            dismissCount = 0,
        )

    @Test
    fun keepsHazardsWithin25m_inTripOrder() {
        val hazards =
            listOf(
                hazard("late", alongM = 800.0, offsetM = 10.0),
                hazard("early", alongM = 120.0, offsetM = 24.0),
                hazard("far", alongM = 500.0, offsetM = 26.0),
                hazard("way-off", alongM = 500.0, offsetM = 400.0),
            )
        val result = HazardReviewPlanner.candidates(track, hazards, nowMs = tripStartMs + day)
        assertEquals(listOf("early", "late"), result.map { it.hazard.id })
        val early = result.first().match
        assertTrue(early.distanceM <= HazardReviewPlanner.MAX_DISTANCE_M)
        assertEquals(tripStartMs + 12_000L, early.tMs)
        assertEquals(73.8, early.lon, 1e-9)
    }

    @Test
    fun skipsInactiveAndDuplicateHazards() {
        val hazards =
            listOf(
                hazard("a", alongM = 100.0, offsetM = 5.0),
                hazard("a", alongM = 100.0, offsetM = 5.0),
                hazard("fixed", alongM = 200.0, offsetM = 5.0, status = HazardStatus.FIXED),
                hazard("stale", alongM = 300.0, offsetM = 5.0, status = HazardStatus.STALE),
            )
        val result = HazardReviewPlanner.candidates(track, hazards, nowMs = tripStartMs + day)
        assertEquals(listOf("a"), result.map { it.hazard.id })
    }

    @Test
    fun passesOlderThanTheFeedbackLimit_areDropped() {
        val hazards = listOf(hazard("start", alongM = 0.0, offsetM = 5.0), hazard("end", alongM = 1000.0, offsetM = 5.0))
        // The start of the trip is just past the limit (minus the margin), its end is not.
        val nowMs = tripStartMs + HazardReviewPlanner.FEEDBACK_MAX_AGE_MS - HazardReviewPlanner.AGE_MARGIN_MS + 50_000L
        val result = HazardReviewPlanner.candidates(track, hazards, nowMs)
        assertEquals(listOf("end"), result.map { it.hazard.id })
    }

    @Test
    fun isReviewable_onlyForFinishedTripsWithin14Days() {
        val nowMs = tripStartMs + 20 * day
        assertFalse(HazardReviewPlanner.isReviewable(endedAtMs = null, nowMs = nowMs))
        assertTrue(HazardReviewPlanner.isReviewable(endedAtMs = nowMs - day, nowMs = nowMs))
        assertTrue(HazardReviewPlanner.isReviewable(endedAtMs = nowMs - 13 * day, nowMs = nowMs))
        assertFalse(HazardReviewPlanner.isReviewable(endedAtMs = nowMs - 14 * day, nowMs = nowMs))
        assertFalse(HazardReviewPlanner.isReviewable(endedAtMs = nowMs - 30 * day, nowMs = nowMs))
    }

    @Test
    fun rowCount_isCapped() {
        val hazards = (0 until 20).map { hazard("h$it", alongM = 40.0 * it, offsetM = 3.0) }
        val result = HazardReviewPlanner.candidates(track, hazards, nowMs = tripStartMs + day)
        assertEquals(HazardReviewPlanner.MAX_ROWS, result.size)
        assertEquals("h0", result.first().hazard.id)
    }

    @Test
    fun emptyTrack_hasNoCandidatesAndNoBoxes() {
        assertEquals(0, HazardReviewPlanner.candidates(emptyList(), listOf(hazard("a", 0.0, 0.0)), tripStartMs).size)
        assertEquals(0, HazardReviewPlanner.queryBoxes(emptyList()).size)
    }

    @Test
    fun queryBoxes_coverTheTrack_withinTheApiLimits() {
        val boxes = HazardReviewPlanner.queryBoxes(track)
        assertTrue(boxes.size in 1..HazardReviewPlanner.MAX_REQUESTS)
        for (point in track) {
            assertTrue(boxes.any { it.contains(point.lat, point.lon) })
        }
        for (box in boxes) {
            assertTrue((box.north - box.south) * (box.east - box.west) <= 0.01)
        }
    }

    @Test
    fun retryableErrors() {
        assertTrue(HazardReviewPlanner.isRetryable(ApiError.Network))
        assertTrue(HazardReviewPlanner.isRetryable(ApiError.Unavailable))
        assertTrue(HazardReviewPlanner.isRetryable(ApiError.RateLimited(retryAfterSec = 30)))
        assertTrue(HazardReviewPlanner.isRetryable(ApiError.Server(status = 500)))
        assertFalse(HazardReviewPlanner.isRetryable(ApiError.NotFound))
        assertFalse(HazardReviewPlanner.isRetryable(ApiError.Rejected(status = 422, code = "TOO_FAR")))
        assertFalse(HazardReviewPlanner.isRetryable(ApiError.Rejected(status = 400, code = "INVALID_REQUEST")))
    }

    @Test
    fun candidate_toString_hasNoCoordinates() {
        val candidate = HazardReviewPlanner.candidates(track, listOf(hazard("a", 50.0, 2.0)), tripStartMs + day).single()
        assertEquals("ReviewCandidate(id=a)", candidate.toString())
    }

    private companion object {
        const val DEG_PER_10_M = 0.00009
    }
}
