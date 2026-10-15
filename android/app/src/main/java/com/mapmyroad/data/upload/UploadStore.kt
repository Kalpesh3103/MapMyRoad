package com.mapmyroad.data.upload

import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.model.RecordedHazard
import com.mapmyroad.domain.model.SegmentSample
import com.mapmyroad.domain.model.TripPoint

/** One queued trip that is due for upload. */
data class QueuedTrip(
    val tripId: String,
    /** Transient failures so far. */
    val attempts: Int,
)

/**
 * What one trip can contribute, loaded for the upload worker. Holds precise coordinates and the trip token, so its
 * [toString] prints the trip id only.
 */
class TripUploadSource(
    val tripId: String,
    val tripToken: String,
    /** Epoch millis; compared with the opt-in time before anything is sent. */
    val startedAtMs: Long,
    /** Null when the stored vehicle code is unknown to this app version. */
    val vehicleType: VehicleType?,
    /** The full track, used only to compute the trim (it never leaves the phone). */
    val points: List<TripPoint>,
    /** Hazards not uploaded yet. */
    val hazards: List<RecordedHazard>,
    /** Roughness windows not uploaded yet. */
    val samples: List<SegmentSample>,
    /** Hazards and windows of this trip already uploaded by an earlier run. */
    val alreadyUploadedCount: Int,
) {
    override fun toString(): String = "TripUploadSource(tripId=$tripId)"
}

/** Storage used by [UploadRunner]. Implemented with Room by [RoomUploadStore]; an interface so the runner is testable. */
interface UploadStore {
    /**
     * Skips waiting rows of trips that started before [contributionEnabledAtMs] and adds a pending row for every
     * finished trip that started at or after it and has none.
     */
    suspend fun refreshQueue(
        nowMs: Long,
        contributionEnabledAtMs: Long,
    )

    /** Puts rows left `in_progress` by an interrupted run back to `pending`. */
    suspend fun resetInProgress()

    /** Pending rows whose next attempt time has come, oldest first. */
    suspend fun dueTrips(
        nowMs: Long,
        maxRows: Int,
    ): List<QueuedTrip>

    /** Rows still to upload (pending or in progress), due or not. */
    suspend fun countWaiting(): Int

    /** Null when the trip no longer exists. */
    suspend fun load(tripId: String): TripUploadSource?

    suspend fun markInProgress(tripId: String)

    /** Marks the items of one accepted request as uploaded so a retry does not send them again. */
    suspend fun markUploaded(
        hazardIds: List<String>,
        sampleIds: List<String>,
    )

    /** Writes the queue row. [state] is one of [com.mapmyroad.data.local.UploadQueueState]. */
    suspend fun updateQueue(
        tripId: String,
        state: String,
        attempts: Int,
        nextAttemptAtMs: Long,
        lastError: String?,
    )
}

/** Wall clock, injectable for tests. */
fun interface UploadClock {
    fun nowMs(): Long
}
