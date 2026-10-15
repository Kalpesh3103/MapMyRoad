package com.mapmyroad.data.routing

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Geocoding and route candidates (API_SPEC.md 5.7 and 5.8). All endpoints need the device token, which the shared
 * OkHttp client adds. Retrofit leaves out null query parameters, so a search without a bias sends no lat or lon.
 */
interface RoutingApi {
    @GET("v1/geocode")
    suspend fun search(
        @Query("q") query: String,
        @Query("lat") lat: Double?,
        @Query("lon") lon: Double?,
        @Query("limit") limit: Int,
        @Query("lang") language: String,
    ): GeocodeResponseDto

    @GET("v1/geocode/reverse")
    suspend fun reverse(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("lang") language: String,
    ): GeocodeResponseDto

    @POST("v1/routes")
    suspend fun routes(
        @Body body: RouteRequestDto,
    ): RouteResponseDto
}
