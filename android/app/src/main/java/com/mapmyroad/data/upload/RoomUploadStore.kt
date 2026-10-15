package com.mapmyroad.data.upload

import com.mapmyroad.data.local.TripDao
import com.mapmyroad.data.local.UploadDao
import com.mapmyroad.data.local.UploadQueueState
import com.mapmyroad.data.local.toDomain
import com.mapmyroad.data.local.toDomainOrNull
import com.mapmyroad.detector.VehicleType
import javax.inject.Inject

/** [UploadStore] backed by Room (DATABASE_SCHEMA.md B1). All calls are main-safe (Room's query executor). */
class RoomUploadStore
    @Inject
    constructor(
        private val tripDao: TripDao,
        private val uploadDao: UploadDao,
    ) : UploadStore {
        override suspend fun refreshQueue(
            nowMs: Long,
            contributionEnabledAtMs: Long,
        ) {
            uploadDao.refreshQueue(nowMs, contributionEnabledAtMs)
        }

        override suspend fun resetInProgress() {
            uploadDao.resetInProgress()
        }

        override suspend fun dueTrips(
            nowMs: Long,
            maxRows: Int,
        ): List<QueuedTrip> = uploadDao.getDue(nowMs, maxRows).map { QueuedTrip(tripId = it.tripId, attempts = it.attempts) }

        override suspend fun countWaiting(): Int = uploadDao.countWaiting()

        override suspend fun load(tripId: String): TripUploadSource? {
            val trip = tripDao.getTrip(tripId) ?: return null
            return TripUploadSource(
                tripId = trip.id,
                tripToken = trip.tripToken,
                startedAtMs = trip.startedAt,
                vehicleType = VehicleType.fromDbCode(trip.vehicleType),
                points = tripDao.getPoints(tripId).map { it.toDomain() },
                // Rows with an unknown severity code (written by a newer app version) are not uploaded.
                hazards = uploadDao.getHazardsNotUploaded(tripId).mapNotNull { it.toDomainOrNull() },
                samples = uploadDao.getSamplesNotUploaded(tripId).map { it.toDomain() },
                alreadyUploadedCount = uploadDao.countUploaded(tripId),
            )
        }

        override suspend fun markInProgress(tripId: String) {
            uploadDao.setState(tripId, UploadQueueState.IN_PROGRESS)
        }

        override suspend fun markUploaded(
            hazardIds: List<String>,
            sampleIds: List<String>,
        ) {
            uploadDao.markUploaded(hazardIds, sampleIds)
        }

        override suspend fun updateQueue(
            tripId: String,
            state: String,
            attempts: Int,
            nextAttemptAtMs: Long,
            lastError: String?,
        ) {
            uploadDao.update(tripId, state, attempts, nextAttemptAtMs, lastError)
        }
    }
