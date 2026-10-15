package com.mapmyroad.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Access to `upload_queue` and the `uploaded` flags of `hazard_event_local` and `segment_sample_local`
 * (DATABASE_SCHEMA.md B1). Used only by the upload worker, the scheduler and the upload status UI.
 */
@Dao
abstract class UploadDao {
    // region Queue

    /**
     * Adds a pending row for every finished trip that started at or after [enabledAtMs] (the last time contribution
     * was switched on) and has no row yet. Trips that started earlier are never queued. Existing rows are unchanged.
     */
    @Query(
        """
        INSERT OR IGNORE INTO upload_queue (trip_id, state, attempts, next_attempt_at, last_error)
        SELECT t.id, 'pending', 0, :nowMs, NULL FROM trip AS t
        WHERE t.ended_at IS NOT NULL
            AND t.started_at >= :enabledAtMs
            AND t.id NOT IN (SELECT trip_id FROM upload_queue)
        """,
    )
    abstract suspend fun enqueueFinishedTrips(
        nowMs: Long,
        enabledAtMs: Long,
    )

    /**
     * Rows still waiting for trips that started before [enabledAtMs] (queued in an earlier opt-in period) become
     * `skipped`: re-enabling contribution never uploads trips from before the switch-on.
     */
    @Query(
        """
        UPDATE upload_queue SET state = 'skipped', last_error = 'before_opt_in'
        WHERE state IN ('pending', 'in_progress')
            AND trip_id IN (SELECT id FROM trip WHERE started_at < :enabledAtMs)
        """,
    )
    abstract suspend fun skipTripsStartedBefore(enabledAtMs: Long): Int

    /** Applies the opt-in cutoff to existing rows, then queues new finished trips, in one transaction. */
    @Transaction
    open suspend fun refreshQueue(
        nowMs: Long,
        enabledAtMs: Long,
    ) {
        skipTripsStartedBefore(enabledAtMs)
        enqueueFinishedTrips(nowMs, enabledAtMs)
    }

    /** Rows left `in_progress` by a run that was stopped or killed go back to `pending`. */
    @Query("UPDATE upload_queue SET state = 'pending' WHERE state = 'in_progress'")
    abstract suspend fun resetInProgress(): Int

    @Query(
        """
        SELECT * FROM upload_queue
        WHERE state = 'pending' AND next_attempt_at <= :nowMs
        ORDER BY id ASC
        LIMIT :maxRows
        """,
    )
    abstract suspend fun getDue(
        nowMs: Long,
        maxRows: Int,
    ): List<UploadQueueEntity>

    /** Rows still to upload, due or not. */
    @Query("SELECT COUNT(*) FROM upload_queue WHERE state IN ('pending', 'in_progress')")
    abstract suspend fun countWaiting(): Int

    @Query("SELECT COUNT(*) FROM upload_queue WHERE state IN ('pending', 'in_progress')")
    abstract fun observeWaitingCount(): Flow<Int>

    @Query("SELECT * FROM upload_queue WHERE trip_id = :tripId")
    abstract fun observeForTrip(tripId: String): Flow<UploadQueueEntity?>

    @Query("SELECT * FROM upload_queue WHERE trip_id = :tripId")
    abstract suspend fun getForTrip(tripId: String): UploadQueueEntity?

    @Query(
        """
        UPDATE upload_queue SET
            state = :state,
            attempts = :attempts,
            next_attempt_at = :nextAttemptAt,
            last_error = :lastError
        WHERE trip_id = :tripId
        """,
    )
    abstract suspend fun update(
        tripId: String,
        state: String,
        attempts: Int,
        nextAttemptAt: Long,
        lastError: String?,
    ): Int

    @Query("UPDATE upload_queue SET state = :state WHERE trip_id = :tripId")
    abstract suspend fun setState(
        tripId: String,
        state: String,
    ): Int

    @Query("DELETE FROM upload_queue")
    abstract suspend fun deleteAll()

    // endregion

    // region Items not uploaded yet

    @Query("SELECT * FROM hazard_event_local WHERE trip_id = :tripId AND uploaded = 0 ORDER BY observed_at ASC")
    abstract suspend fun getHazardsNotUploaded(tripId: String): List<HazardEventLocalEntity>

    @Query("SELECT * FROM segment_sample_local WHERE trip_id = :tripId AND uploaded = 0 ORDER BY observed_at ASC")
    abstract suspend fun getSamplesNotUploaded(tripId: String): List<SegmentSampleLocalEntity>

    /** Items of [tripId] already marked uploaded by an earlier run. */
    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM hazard_event_local WHERE trip_id = :tripId AND uploaded = 1) +
            (SELECT COUNT(*) FROM segment_sample_local WHERE trip_id = :tripId AND uploaded = 1)
        """,
    )
    abstract suspend fun countUploaded(tripId: String): Int

    @Query("UPDATE hazard_event_local SET uploaded = 1 WHERE id IN (:ids)")
    abstract suspend fun markHazardsUploaded(ids: List<String>): Int

    @Query("UPDATE segment_sample_local SET uploaded = 1 WHERE id IN (:ids)")
    abstract suspend fun markSamplesUploaded(ids: List<String>): Int

    /**
     * Marks the items of one accepted request as uploaded, all or nothing. IDs are chunked to stay under SQLite's
     * bound-variable limit (999 on older Android versions).
     */
    @Transaction
    open suspend fun markUploaded(
        hazardIds: List<String>,
        sampleIds: List<String>,
    ) {
        hazardIds.chunked(MAX_IDS_PER_STATEMENT).forEach { markHazardsUploaded(it) }
        sampleIds.chunked(MAX_IDS_PER_STATEMENT).forEach { markSamplesUploaded(it) }
    }

    // endregion

    private companion object {
        const val MAX_IDS_PER_STATEMENT = 500
    }
}
