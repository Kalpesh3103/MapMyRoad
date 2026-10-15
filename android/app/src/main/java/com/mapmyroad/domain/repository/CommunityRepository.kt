package com.mapmyroad.domain.repository

import com.mapmyroad.domain.model.ApiResult
import com.mapmyroad.domain.model.CommunityHazard
import com.mapmyroad.domain.model.HazardFeedbackAction
import com.mapmyroad.domain.model.HazardFeedbackOutcome

/** The shared road-condition map (PRD CM-2, CM-3, CM-4). Backed by the API; nothing is cached on disk. */
interface CommunityRepository {
    /** TileJSON URL for the MapLibre vector source (API_SPEC.md 5.4). Tiles need no authentication. */
    fun tileJsonUrl(): String

    /** Active hazards in a box of at most about 0.1 by 0.1 degrees (API_SPEC.md 5.5). */
    suspend fun hazardsIn(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ): ApiResult<List<CommunityHazard>>

    suspend fun hazard(id: String): ApiResult<CommunityHazard>

    /**
     * Sends "still there" or "fixed". [lat]/[lon] is where the user passed the hazard (from the trip track),
     * used by the server only for its 50 m check and never stored (API_SPEC.md 5.6).
     */
    suspend fun submitFeedback(
        hazardId: String,
        action: HazardFeedbackAction,
        lat: Double,
        lon: Double,
        observedAtMs: Long,
    ): ApiResult<HazardFeedbackOutcome>
}
