package com.mapmyroad.data.community

import com.mapmyroad.BuildConfig
import com.mapmyroad.data.remote.apiCall
import com.mapmyroad.domain.device.DeviceSession
import com.mapmyroad.domain.model.ApiError
import com.mapmyroad.domain.model.ApiResult
import com.mapmyroad.domain.model.CommunityHazard
import com.mapmyroad.domain.model.HazardFeedbackAction
import com.mapmyroad.domain.model.HazardFeedbackOutcome
import com.mapmyroad.domain.repository.CommunityRepository
import com.mapmyroad.map.CommunityTiles
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CommunityRepository] over the backend API. Registers the device first for authenticated calls
 * ([DeviceSession.ensureRegistered]). Nothing is cached on disk, and nothing here logs coordinates or bodies.
 */
@Singleton
class ApiCommunityRepository
    @Inject
    constructor(
        private val api: CommunityApi,
        private val deviceSession: DeviceSession,
    ) : CommunityRepository {
        override fun tileJsonUrl(): String = CommunityTiles.tileJsonUrl(BuildConfig.API_BASE_URL)

        override suspend fun hazardsIn(
            south: Double,
            west: Double,
            north: Double,
            east: Double,
        ): ApiResult<List<CommunityHazard>> =
            authorized {
                apiCall {
                    val bbox = HazardDtoMapper.bboxParam(south = south, west = west, north = north, east = east)
                    HazardDtoMapper.collection(api.hazards(bbox = bbox, limit = MAX_LIMIT))
                }
            }

        override suspend fun hazard(id: String): ApiResult<CommunityHazard> =
            authorized {
                when (val result = apiCall { api.hazard(id) }) {
                    is ApiResult.Success ->
                        HazardDtoMapper.feature(result.value)?.let { ApiResult.Success(it) } ?: unreadable()
                    is ApiResult.Failure -> result
                }
            }

        override suspend fun submitFeedback(
            hazardId: String,
            action: HazardFeedbackAction,
            lat: Double,
            lon: Double,
            observedAtMs: Long,
        ): ApiResult<HazardFeedbackOutcome> =
            authorized {
                val body = HazardDtoMapper.feedbackRequest(action, lat, lon, observedAtMs)
                when (val result = apiCall { api.feedback(hazardId, body) }) {
                    is ApiResult.Success -> HazardDtoMapper.outcome(result.value)?.let { ApiResult.Success(it) } ?: unreadable()
                    is ApiResult.Failure -> result
                }
            }

        private suspend fun <T> authorized(block: suspend () -> ApiResult<T>): ApiResult<T> =
            when (val registration = deviceSession.ensureRegistered()) {
                is ApiResult.Failure -> registration
                is ApiResult.Success -> block()
            }

        private fun unreadable(): ApiResult.Failure = ApiResult.Failure(ApiError.Server(status = null))

        private companion object {
            /** API_SPEC.md 5.5: maximum `limit`. A 0.1 degree box rarely holds more active hazards. */
            const val MAX_LIMIT = 500
        }
    }
