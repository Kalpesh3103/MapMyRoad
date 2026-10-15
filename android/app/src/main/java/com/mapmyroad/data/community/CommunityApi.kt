package com.mapmyroad.data.community

import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Hazards endpoints (API_SPEC.md 5.5 and 5.6). All need a device token, which the shared client adds. Responses
 * are decoded leniently: the list keeps each feature as raw JSON so one malformed feature is skipped instead of
 * failing the whole response ([HazardDtoMapper]).
 */
interface CommunityApi {
    /** [bbox] is `minLon,minLat,maxLon,maxLat`, at most about 0.1 by 0.1 degrees. */
    @GET("v1/hazards")
    suspend fun hazards(
        @Query("bbox") bbox: String,
        @Query("minSeverity") minSeverity: Int? = null,
        @Query("status") status: String? = null,
        @Query("limit") limit: Int? = null,
    ): HazardFeatureCollectionDto

    @GET("v1/hazards/{id}")
    suspend fun hazard(
        @Path("id") id: String,
    ): JsonElement

    @POST("v1/hazards/{id}/feedback")
    suspend fun feedback(
        @Path("id") id: String,
        @Body body: FeedbackRequestDto,
    ): FeedbackResponseDto
}
