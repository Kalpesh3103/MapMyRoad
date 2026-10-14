package com.mapmyroad.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Access to `trip` and its child tables (DATABASE_SCHEMA.md B1). Reads return [Flow] (Room re-queries when a
 * referenced table changes), writes are `suspend` and run on Room's query executor, so all calls are main-safe.
 */
@Dao
abstract class TripDao {
    // region Writes

    /** Returns the new row id, or -1 when a trip with the same id already exists (ignored). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTrip(trip: TripEntity): Long

    /** Writes the end time and the summary computed when the trip ends (M2-8). Returns the number of rows updated. */
    @Query(
        """
        UPDATE trip SET
            ended_at = :endedAt,
            distance_m = :distanceM,
            duration_s = :durationS,
            smoothness_score = :smoothnessScore,
            minor_count = :minorCount,
            moderate_count = :moderateCount,
            severe_count = :severeCount,
            stop_reason = :stopReason
        WHERE id = :tripId
        """,
    )
    abstract suspend fun updateSummary(
        tripId: String,
        endedAt: Long,
        distanceM: Double,
        durationS: Int,
        smoothnessScore: Int?,
        minorCount: Int,
        moderateCount: Int,
        severeCount: Int,
        stopReason: String,
    ): Int

    // Points have an autoincrement key, so a conflict cannot happen; retries are safe because a failed
    // appendRecording transaction commits nothing.
    @Insert
    abstract suspend fun insertPoints(points: List<TripPointEntity>)

    // UUID-keyed rows: IGNORE makes a retried batch idempotent. Note that IGNORE does not cover foreign key
    // violations: rows for a trip that does not exist make the whole transaction fail.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertHazards(hazards: List<HazardEventLocalEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSamples(samples: List<SegmentSampleLocalEntity>)

    /** One batched write from the tracking service (DATABASE_SCHEMA.md B3). All or nothing. */
    @Transaction
    open suspend fun appendRecording(
        points: List<TripPointEntity>,
        hazards: List<HazardEventLocalEntity>,
        samples: List<SegmentSampleLocalEntity>,
    ) {
        if (points.isNotEmpty()) insertPoints(points)
        if (hazards.isNotEmpty()) insertHazards(hazards)
        if (samples.isNotEmpty()) insertSamples(samples)
    }

    /** Deletes one trip. Points, hazards and samples go with it by `ON DELETE CASCADE`. */
    @Query("DELETE FROM trip WHERE id = :tripId")
    abstract suspend fun deleteTrip(tripId: String): Int

    @Query("DELETE FROM trip_point")
    abstract suspend fun deleteAllPoints()

    @Query("DELETE FROM hazard_event_local")
    abstract suspend fun deleteAllHazards()

    @Query("DELETE FROM segment_sample_local")
    abstract suspend fun deleteAllSamples()

    @Query("DELETE FROM trip")
    abstract suspend fun deleteAllTripRows()

    /**
     * "Delete all data" (PRD PM-4). Deleting from `trip` cascades, but child tables are also cleared explicitly so
     * that nothing survives if foreign keys were ever off.
     */
    @Transaction
    open suspend fun deleteAll() {
        deleteAllPoints()
        deleteAllHazards()
        deleteAllSamples()
        deleteAllTripRows()
    }

    // endregion

    // region Trips

    @Query("SELECT * FROM trip WHERE ended_at IS NOT NULL ORDER BY started_at DESC")
    abstract fun observeFinishedTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trip WHERE id = :tripId")
    abstract fun observeTrip(tripId: String): Flow<TripEntity?>

    @Query("SELECT * FROM trip WHERE id = :tripId")
    abstract suspend fun getTrip(tripId: String): TripEntity?

    @Query("SELECT * FROM trip WHERE ended_at IS NOT NULL ORDER BY ended_at DESC LIMIT 1")
    abstract fun observeLatestFinishedTrip(): Flow<TripEntity?>

    @Query("SELECT * FROM trip WHERE ended_at IS NULL ORDER BY started_at ASC")
    abstract suspend fun getUnfinishedTrips(): List<TripEntity>

    // endregion

    // region Points, hazards and samples

    @Query("SELECT * FROM trip_point WHERE trip_id = :tripId ORDER BY t ASC")
    abstract fun observePoints(tripId: String): Flow<List<TripPointEntity>>

    @Query("SELECT * FROM trip_point WHERE trip_id = :tripId ORDER BY t ASC")
    abstract suspend fun getPoints(tripId: String): List<TripPointEntity>

    /**
     * Every point of every finished trip, grouped by trip and in time order. This can be large (about one row per
     * second of driving); the repository decimates it per trip before it reaches the map.
     */
    @Query(
        """
        SELECT * FROM trip_point
        WHERE trip_id IN (SELECT id FROM trip WHERE ended_at IS NOT NULL)
        ORDER BY trip_id ASC, t ASC
        """,
    )
    abstract fun observeFinishedTripPoints(): Flow<List<TripPointEntity>>

    @Query("SELECT * FROM hazard_event_local WHERE trip_id = :tripId ORDER BY observed_at ASC")
    abstract fun observeHazards(tripId: String): Flow<List<HazardEventLocalEntity>>

    @Query("SELECT * FROM hazard_event_local WHERE trip_id = :tripId ORDER BY observed_at ASC")
    abstract suspend fun getHazards(tripId: String): List<HazardEventLocalEntity>

    /** Hazards of finished trips only (a trip being recorded is not on the personal map yet). */
    @Query(
        """
        SELECT * FROM hazard_event_local
        WHERE trip_id IN (SELECT id FROM trip WHERE ended_at IS NOT NULL)
        ORDER BY observed_at ASC
        """,
    )
    abstract fun observeFinishedTripHazards(): Flow<List<HazardEventLocalEntity>>

    @Query("SELECT * FROM segment_sample_local WHERE trip_id = :tripId ORDER BY observed_at ASC")
    abstract suspend fun getSamples(tripId: String): List<SegmentSampleLocalEntity>

    // endregion
}
