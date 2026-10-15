package com.mapmyroad.domain.repository

import com.mapmyroad.domain.model.ApiResult
import com.mapmyroad.domain.model.Place
import com.mapmyroad.domain.model.RouteOptions
import com.mapmyroad.domain.model.RouteQuery

/** Destination search and route candidates (PRD RT-1, RT-2, RT-3). */
interface RouteRepository {
    /**
     * Place search (API_SPEC.md 5.7). [biasLat]/[biasLon] is optional; the server rounds it to about 1 km before it
     * leaves the server. [query] must have at least 3 characters after trimming.
     */
    suspend fun search(
        query: String,
        biasLat: Double?,
        biasLon: Double?,
        language: String,
    ): ApiResult<List<Place>>

    suspend fun reverse(
        lat: Double,
        lon: Double,
        language: String,
    ): ApiResult<Place?>

    suspend fun routes(query: RouteQuery): ApiResult<RouteOptions>
}
