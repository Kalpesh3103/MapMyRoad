package com.mapmyroad.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Room row for `trip` (DATABASE_SCHEMA.md B1). Never uploaded. */
@Entity(tableName = "trip")
data class TripEntity(
    /** UUID. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    /** Random UUID per trip, used only to group uploads in v2 (B4). Never shown to the user. */
    @ColumnInfo(name = "trip_token")
    val tripToken: String,
    /** [com.mapmyroad.detector.VehicleType.dbCode]. */
    @ColumnInfo(name = "vehicle_type")
    val vehicleType: Int,
    /** Epoch millis. */
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    /** Epoch millis, null while recording. */
    @ColumnInfo(name = "ended_at")
    val endedAt: Long?,
    @ColumnInfo(name = "distance_m")
    val distanceM: Double,
    @ColumnInfo(name = "duration_s")
    val durationS: Int,
    /** 0 to 100, null when there was too little data. */
    @ColumnInfo(name = "smoothness_score")
    val smoothnessScore: Int?,
    @ColumnInfo(name = "minor_count")
    val minorCount: Int,
    @ColumnInfo(name = "moderate_count")
    val moderateCount: Int,
    @ColumnInfo(name = "severe_count")
    val severeCount: Int,
    /** [com.mapmyroad.domain.model.StartMode.code]. */
    @ColumnInfo(name = "start_mode")
    val startMode: String,
    /** [com.mapmyroad.domain.model.StopReason.code], null while recording. */
    @ColumnInfo(name = "stop_reason")
    val stopReason: String?,
)
