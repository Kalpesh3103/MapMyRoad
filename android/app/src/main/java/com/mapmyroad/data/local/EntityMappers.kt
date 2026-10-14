package com.mapmyroad.data.local

import com.mapmyroad.detector.Severity
import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.model.RecordedHazard
import com.mapmyroad.domain.model.SegmentSample
import com.mapmyroad.domain.model.StartMode
import com.mapmyroad.domain.model.StopReason
import com.mapmyroad.domain.model.Trip
import com.mapmyroad.domain.model.TripPoint

// Entity <-> domain mappers. Pure functions with no Android or logging dependencies so they run in JVM tests.
// Rows with codes this app version does not know (for example written by a newer version before a downgrade)
// map to null instead of crashing; callers drop and log them by id.

fun Trip.toEntity(tripToken: String): TripEntity =
    TripEntity(
        id = id,
        tripToken = tripToken,
        vehicleType = vehicleType.dbCode,
        startedAt = startedAtMs,
        endedAt = endedAtMs,
        distanceM = distanceM,
        durationS = durationS,
        smoothnessScore = smoothnessScore,
        minorCount = minorCount,
        moderateCount = moderateCount,
        severeCount = severeCount,
        startMode = startMode.code,
        stopReason = stopReason?.code,
    )

/** Null when [TripEntity.vehicleType] is unknown. */
fun TripEntity.toDomainOrNull(): Trip? {
    val vehicle = VehicleType.fromDbCode(vehicleType) ?: return null
    return Trip(
        id = id,
        vehicleType = vehicle,
        startMode = startModeFromCode(startMode),
        startedAtMs = startedAt,
        endedAtMs = endedAt,
        distanceM = distanceM,
        durationS = durationS,
        smoothnessScore = smoothnessScore,
        minorCount = minorCount,
        moderateCount = moderateCount,
        severeCount = severeCount,
        stopReason = stopReason?.let(::stopReasonFromCode),
    )
}

/** Unknown codes fall back to [StartMode.MANUAL]: the start mode is informational and must not hide a trip. */
fun startModeFromCode(code: String): StartMode = StartMode.entries.firstOrNull { it.code == code } ?: StartMode.MANUAL

/** Null for unknown codes. */
fun stopReasonFromCode(code: String): StopReason? = StopReason.entries.firstOrNull { it.code == code }

fun TripPoint.toEntity(): TripPointEntity =
    TripPointEntity(
        tripId = tripId,
        t = tMs,
        lat = lat,
        lon = lon,
        speedMps = speedMps,
        accuracyM = accuracyM,
        roughness = roughness,
    )

fun TripPointEntity.toDomain(): TripPoint =
    TripPoint(
        tripId = tripId,
        tMs = t,
        lat = lat,
        lon = lon,
        speedMps = speedMps,
        accuracyM = accuracyM,
        roughness = roughness,
    )

fun RecordedHazard.toEntity(): HazardEventLocalEntity =
    HazardEventLocalEntity(
        id = id,
        tripId = tripId,
        lat = lat,
        lon = lon,
        observedAt = observedAtMs,
        severity = severity.level,
        speedKmh = speedKmh,
        peakScore = peakScore,
        headingDeg = headingDeg,
        isUploaded = false,
    )

/** Null when [HazardEventLocalEntity.severity] is unknown. */
fun HazardEventLocalEntity.toDomainOrNull(): RecordedHazard? {
    val level = Severity.fromLevel(severity) ?: return null
    return RecordedHazard(
        id = id,
        tripId = tripId,
        lat = lat,
        lon = lon,
        observedAtMs = observedAt,
        severity = level,
        speedKmh = speedKmh,
        peakScore = peakScore,
        headingDeg = headingDeg,
    )
}

fun SegmentSample.toEntity(): SegmentSampleLocalEntity =
    SegmentSampleLocalEntity(
        id = id,
        tripId = tripId,
        startLat = startLat,
        startLon = startLon,
        endLat = endLat,
        endLon = endLon,
        observedAt = observedAtMs,
        lengthM = lengthM,
        roughness = roughness,
        speedKmh = speedKmh,
        isUploaded = false,
    )

fun SegmentSampleLocalEntity.toDomain(): SegmentSample =
    SegmentSample(
        id = id,
        tripId = tripId,
        startLat = startLat,
        startLon = startLon,
        endLat = endLat,
        endLon = endLon,
        observedAtMs = observedAt,
        lengthM = lengthM,
        roughness = roughness,
        speedKmh = speedKmh,
    )
