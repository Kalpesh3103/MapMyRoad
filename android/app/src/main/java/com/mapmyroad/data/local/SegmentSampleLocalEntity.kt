package com.mapmyroad.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room row for `segment_sample_local` (DATABASE_SCHEMA.md B1): one roughness window. Maps to server
 * `segment_observation` with the same id (B4), uploaded from v2 only.
 */
@Entity(
    tableName = "segment_sample_local",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["trip_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["trip_id"])],
)
data class SegmentSampleLocalEntity(
    /** UUID. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "trip_id")
    val tripId: String,
    @ColumnInfo(name = "start_lat")
    val startLat: Double,
    @ColumnInfo(name = "start_lon")
    val startLon: Double,
    @ColumnInfo(name = "end_lat")
    val endLat: Double,
    @ColumnInfo(name = "end_lon")
    val endLon: Double,
    /** Epoch millis. */
    @ColumnInfo(name = "observed_at")
    val observedAt: Long,
    @ColumnInfo(name = "length_m")
    val lengthM: Double,
    @ColumnInfo(name = "roughness")
    val roughness: Double,
    @ColumnInfo(name = "speed_kmh")
    val speedKmh: Double,
    /** Stored as INTEGER 0 or 1. Set by the v2 upload worker. */
    @ColumnInfo(name = "uploaded")
    val isUploaded: Boolean = false,
)
