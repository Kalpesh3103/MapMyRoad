package com.mapmyroad.data.upload

import com.mapmyroad.data.remote.apiCall
import com.mapmyroad.domain.model.ApiResult
import retrofit2.http.Body
import retrofit2.http.POST
import javax.inject.Inject

/** `POST /v1/events` (API_SPEC.md 5.3). Created from the shared Retrofit instance in UploadModule. */
interface EventsApi {
    @POST("v1/events")
    suspend fun uploadEvents(
        @Body request: EventUploadRequest,
    ): EventUploadResponse
}

/** [EventsUploader] over Retrofit; errors are mapped in one place by [apiCall]. */
class RetrofitEventsUploader
    @Inject
    constructor(
        private val eventsApi: EventsApi,
    ) : EventsUploader {
        override suspend fun upload(request: EventUploadRequest): ApiResult<EventUploadResponse> =
            apiCall { eventsApi.uploadEvents(request) }
    }
