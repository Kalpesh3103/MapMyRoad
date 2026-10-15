package com.mapmyroad.ui.summary.review

import com.mapmyroad.domain.model.ApiError
import com.mapmyroad.domain.model.CommunityHazard
import com.mapmyroad.domain.model.HazardStatus
import com.mapmyroad.domain.model.TripPoint
import com.mapmyroad.map.BoundsGrid
import com.mapmyroad.map.GeoBounds
import com.mapmyroad.map.TrackMatch
import com.mapmyroad.map.TrackProximity

/**
 * A known hazard the trip passed, with where and when the trip passed it. [match] is the feedback location and
 * time: it lies on the recorded track within [HazardReviewPlanner.MAX_DISTANCE_M] of the hazard, so inside the
 * server's 50 m check (API_SPEC.md 5.6). [toString] is redacted, both parts hold coordinates.
 */
class ReviewCandidate(
    val hazard: CommunityHazard,
    val match: TrackMatch,
) {
    override fun toString(): String = "ReviewCandidate(id=${hazard.id})"
}

/**
 * Decides what the post-trip hazard review (S12, PRD CM-4, ADR-0013) asks about. Pure: the view model does the I/O.
 * Thresholds are initial values, not measured facts.
 */
object HazardReviewPlanner {
    /** A hazard counts as passed when the track comes this close. Well inside the server's 50 m feedback check. */
    const val MAX_DISTANCE_M = 25.0

    /** The server rejects feedback whose `observedAt` is older than 14 days (API_SPEC.md 5.6). */
    const val FEEDBACK_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    /** Stop offering the review a bit before the server limit, so a vote does not fail while the card is open. */
    const val AGE_MARGIN_MS = 60L * 60 * 1000

    /** Query box size; `GET /v1/hazards` accepts at most about 0.1 by 0.1 degrees. */
    const val CELL_DEG = 0.09

    /** Margin around the track so hazards just outside its bounding box are found. About 55 m of latitude. */
    const val PAD_DEG = 0.0005

    /** At most this many hazard requests per trip; the rest of a very long trip is not reviewed. */
    const val MAX_REQUESTS = 6

    /** The review stays short (UI_UX_GUIDELINES.md S12): at most this many hazards, the first ones passed. */
    const val MAX_ROWS = 10

    /** True when a finished trip is recent enough for feedback. */
    fun isReviewable(
        endedAtMs: Long?,
        nowMs: Long,
    ): Boolean = endedAtMs != null && nowMs - endedAtMs <= FEEDBACK_MAX_AGE_MS - AGE_MARGIN_MS

    /** Boxes to query for the hazards near [points], at most [MAX_REQUESTS]. */
    fun queryBoxes(points: List<TripPoint>): List<GeoBounds> =
        BoundsGrid.cellsAlong(
            points = points,
            lat = { it.lat },
            lon = { it.lon },
            cellDeg = CELL_DEG,
            padDeg = PAD_DEG,
            maxCells = MAX_REQUESTS,
        )

    /**
     * Active hazards within [MAX_DISTANCE_M] of the track that were passed recently enough for feedback, in the
     * order the trip passed them, at most [MAX_ROWS]. Duplicates (from neighbouring query boxes) are dropped.
     */
    fun candidates(
        points: List<TripPoint>,
        hazards: List<CommunityHazard>,
        nowMs: Long,
    ): List<ReviewCandidate> {
        if (points.isEmpty()) return emptyList()
        val oldestAllowedMs = nowMs - (FEEDBACK_MAX_AGE_MS - AGE_MARGIN_MS)
        return hazards
            .asSequence()
            .filter { it.status == HazardStatus.ACTIVE }
            .distinctBy { it.id }
            .mapNotNull { hazard ->
                val match = TrackProximity.nearest(points, hazard.lat, hazard.lon) ?: return@mapNotNull null
                if (match.distanceM > MAX_DISTANCE_M || match.tMs < oldestAllowedMs) null else ReviewCandidate(hazard, match)
            }.sortedBy { it.match.alongTrackM }
            .take(MAX_ROWS)
            .toList()
    }

    /**
     * True when sending the same answer again may work (no connection, server busy). False when the server will
     * keep refusing it (hazard gone or hidden, too far, too old, app too old).
     */
    fun isRetryable(error: ApiError): Boolean =
        when (error) {
            ApiError.Network, ApiError.Unavailable, ApiError.Unauthorized, is ApiError.RateLimited, is ApiError.Server -> true
            ApiError.NotFound, ApiError.UpdateRequired, ApiError.IntegrityFailed, is ApiError.Rejected -> false
        }
}
