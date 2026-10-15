package com.mapmyroad.data.upload

import com.mapmyroad.domain.model.ApiResult

/** Sends one upload request. An interface so [UploadRunner] can be tested without HTTP. */
fun interface EventsUploader {
    suspend fun upload(request: EventUploadRequest): ApiResult<EventUploadResponse>
}
