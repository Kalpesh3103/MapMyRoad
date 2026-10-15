package com.mapmyroad.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room row for `upload_queue` (DATABASE_SCHEMA.md B1, schema version 2): upload progress of one finished trip.
 * At most one row per trip. Deleted with the trip by `ON DELETE CASCADE`.
 */
@Entity(
    tableName = "upload_queue",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["trip_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["state", "next_attempt_at"]),
        Index(value = ["trip_id"], unique = true),
    ],
)
data class UploadQueueEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "trip_id")
    val tripId: String,
    /** One of [UploadQueueState]. */
    @ColumnInfo(name = "state")
    val state: String,
    /** Transient failures so far. */
    @ColumnInfo(name = "attempts")
    val attempts: Int,
    /** Epoch millis; the worker skips the row before this time (honors `Retry-After`). */
    @ColumnInfo(name = "next_attempt_at")
    val nextAttemptAt: Long,
    /** Error category only (for example `network`, `rejected:INVALID_REQUEST`). Never bodies or coordinates. */
    @ColumnInfo(name = "last_error")
    val lastError: String?,
)
