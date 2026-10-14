package com.mapmyroad.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Room row for `hazard_event_local` (DATABASE_SCHEMA.md B1). Maps to server `hazard_event` with the same id (B4). */
@Entity(
    tableName = "hazard_event_local",
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
data class HazardEventLocalEntity(
    /** UUID. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "trip_id")
    val tripId: String,
    @ColumnInfo(name = "lat")
    val lat: Double,
    @ColumnInfo(name = "lon")
    val lon: Double,
    /** Epoch millis. */
    @ColumnInfo(name = "observed_at")
    val observedAt: Long,
    /** [com.mapmyroad.detector.Severity.level]: 1 minor, 2 moderate, 3 severe. */
    @ColumnInfo(name = "severity")
    val severity: Int,
    @ColumnInfo(name = "speed_kmh")
    val speedKmh: Double,
    @ColumnInfo(name = "peak_score")
    val peakScore: Double,
    @ColumnInfo(name = "heading_deg")
    val headingDeg: Int?,
    /** Stored as INTEGER 0 or 1. Set by the v2 upload worker. */
    @ColumnInfo(name = "uploaded")
    val isUploaded: Boolean = false,
)
