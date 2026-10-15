package com.mapmyroad.data.routing

import com.mapmyroad.data.remote.apiCall
import com.mapmyroad.domain.device.DeviceSession
import com.mapmyroad.domain.model.ApiResult
import com.mapmyroad.domain.model.Place
import com.mapmyroad.domain.model.RouteOptions
import com.mapmyroad.domain.model.RouteQuery
import com.mapmyroad.domain.model.map
import com.mapmyroad.domain.repository.RouteRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RouteRepository] backed by the MapMyRoad API. Registers the install lazily before the first call (ADR-0007).
 * Never logs queries, coordinates or bodies (AGENTS.md section 6).
 */
@Singleton
class NetworkRouteRepository
    @Inject
    constructor(
        private val api: RoutingApi,
        private val deviceSession: DeviceSession,
    ) : RouteRepository {
        override suspend fun search(
            query: String,
            biasLat: Double?,
            biasLon: Double?,
            language: String,
        ): ApiResult<List<Place>> {
            val text = RoutingMappers.normalizeQuery(query)
            if (text.length < RoutingMappers.MIN_QUERY_LENGTH) return ApiResult.Success(emptyList())
            // The server needs both or neither (API_SPEC.md 5.7).
            val hasBias = biasLat != null && biasLon != null
            return registered {
                apiCall {
                    api.search(
                        query = text,
                        lat = if (hasBias) biasLat?.let(RoutingMappers::roundBias) else null,
                        lon = if (hasBias) biasLon?.let(RoutingMappers::roundBias) else null,
                        limit = RoutingMappers.SEARCH_LIMIT,
                        language = language,
                    )
                }.map(RoutingMappers::toPlaces)
            }
        }

        override suspend fun reverse(
            lat: Double,
            lon: Double,
            language: String,
        ): ApiResult<Place?> =
            registered {
                apiCall { api.reverse(lat = lat, lon = lon, language = language) }
                    .map { response -> RoutingMappers.toPlaces(response).firstOrNull() }
            }

        override suspend fun routes(query: RouteQuery): ApiResult<RouteOptions> =
            registered {
                apiCall { api.routes(RoutingMappers.toRequest(query)) }.map(RoutingMappers::toRouteOptions)
            }

        private suspend inline fun <T> registered(block: () -> ApiResult<T>): ApiResult<T> =
            when (val registration = deviceSession.ensureRegistered()) {
                is ApiResult.Failure -> registration
                is ApiResult.Success -> block()
            }
    }
