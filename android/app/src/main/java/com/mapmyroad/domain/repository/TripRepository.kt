package com.mapmyroad.domain.repository

import com.mapmyroad.domain.model.RecordedHazard
import com.mapmyroad.domain.model.SegmentSample
import com.mapmyroad.domain.model.StopReason
import com.mapmyroad.domain.model.Trip
import com.mapmyroad.domain.model.TripPoint
import com.mapmyroad.domain.model.TripTrack
import kotlinx.coroutines.flow.Flow

/** Local trip storage (Room). All functions are main-safe. */
interface TripRepository {
    /** Finished trips, newest first. */
    fun observeFinishedTrips(): Flow<List<Trip>>

    fun observeTrip(tripId: String): Flow<Trip?>

    fun observeLatestFinishedTrip(): Flow<Trip?>

    fun observeTripPoints(tripId: String): Flow<List<TripPoint>>

    fun observeTripHazards(tripId: String): Flow<List<RecordedHazard>>

    /** Tracks of all finished trips, for the personal map (PRD PM-1). */
    fun observeAllTracks(): Flow<List<TripTrack>>

    /** Hazards from all finished trips, for the personal map (PRD PM-2). */
    fun observeAllHazards(): Flow<List<RecordedHazard>>

    /** Inserts a trip row with [Trip.endedAtMs] null. */
    suspend fun startTrip(trip: Trip)

    /** Appends recorded data in one transaction (DATABASE_SCHEMA.md B3: batched writes). */
    suspend fun appendRecording(
        tripId: String,
        points: List<TripPoint>,
        hazards: List<RecordedHazard>,
        samples: List<SegmentSample>,
    )

    /**
     * Closes a trip and computes its summary (distance, duration, smoothness, counts; M2-8).
     * Returns the finished trip, or null if it does not exist.
     */
    suspend fun finishTrip(
        tripId: String,
        endedAtMs: Long,
        stopReason: StopReason,
    ): Trip?

    /** Trips with no end time, left by a killed service. Used for recovery at startup. */
    suspend fun findUnfinishedTrips(): List<Trip>

    /** Deletes one trip and, by cascade, its points, hazards and samples (PRD PM-4). */
    suspend fun deleteTrip(tripId: String)

    /** Deletes every trip and all recorded data. Settings are cleared separately. */
    suspend fun deleteAllTrips()
}
