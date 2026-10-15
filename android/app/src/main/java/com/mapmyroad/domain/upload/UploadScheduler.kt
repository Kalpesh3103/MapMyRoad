package com.mapmyroad.domain.upload

/**
 * Schedules background uploads of finished trips (PRD CM-1, API_SPEC.md 5.3). Uploads run only when the user opted
 * in to contribute, on unmetered networks unless they allowed mobile data, with backoff and retry.
 */
interface UploadScheduler {
    /** Queues all finished, not yet uploaded trips and schedules the worker. Safe to call repeatedly. */
    fun scheduleUploads()

    /** Cancels pending uploads (contribution turned off, or "delete my data"). */
    fun cancelUploads()
}
