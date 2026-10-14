package com.mapmyroad.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Room row for `trip_point` (DATABASE_SCHEMA.md B1): downsampled track for display. Never uploaded. */
@Entity(
    tableName = "trip_point",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["trip_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["trip_id", "t"])],
)
data class TripPointEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "trip_id")
    val tripId: String,
    /** Epoch millis. */
    @ColumnInfo(name = "t")
    val t: Long,
    @ColumnInfo(name = "lat")
    val lat: Double,
    @ColumnInfo(name = "lon")
    val lon: Double,
    @ColumnInfo(name = "speed_mps")
    val speedMps: Double,
    @ColumnInfo(name = "accuracy_m")
    val accuracyM: Double,
    @ColumnInfo(name = "roughness")
    val roughness: Double?,
)
