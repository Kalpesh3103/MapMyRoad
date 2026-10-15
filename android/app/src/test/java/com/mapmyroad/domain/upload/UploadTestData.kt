package com.mapmyroad.domain.upload

import com.mapmyroad.detector.Severity
import com.mapmyroad.domain.model.RecordedHazard
import com.mapmyroad.domain.model.SegmentSample
import com.mapmyroad.domain.model.TripPoint
import kotlin.math.cos

// Test geometry on a local flat grid around Pune (synthetic, no real trips). x is east, y is north, in meters.

internal const val ORIGIN_LAT = 18.52
internal const val ORIGIN_LON = 73.85

/** Meters per degree of latitude on the sphere used by the haversine in the detector module. */
private val METERS_PER_DEG_LAT = Math.toRadians(1.0) * 6_371_008.8

internal fun latAt(yM: Double): Double = ORIGIN_LAT + yM / METERS_PER_DEG_LAT

internal fun lonAt(xM: Double): Double = ORIGIN_LON + xM / (METERS_PER_DEG_LAT * cos(Math.toRadians(ORIGIN_LAT)))

internal fun point(
    xM: Double,
    yM: Double,
    tMs: Long,
): TripPoint =
    TripPoint(
        tripId = "trip",
        tMs = tMs,
        lat = latAt(yM),
        lon = lonAt(xM),
        speedMps = 10.0,
        accuracyM = 5.0,
        roughness = null,
    )

/** A straight trip due north with one fix per [stepM] meters, one second apart. */
internal fun northTrack(
    lengthM: Double,
    stepM: Double = 10.0,
): List<TripPoint> {
    val count = (lengthM / stepM).toInt()
    return (0..count).map { index -> point(xM = 0.0, yM = index * stepM, tMs = index * 1_000L) }
}

internal fun hazardAt(
    id: String,
    point: TripPoint,
    tMs: Long = point.tMs,
    speedKmh: Double = 30.0,
): RecordedHazard =
    RecordedHazard(
        id = id,
        tripId = "trip",
        lat = point.lat,
        lon = point.lon,
        observedAtMs = tMs,
        severity = Severity.MODERATE,
        speedKmh = speedKmh,
        peakScore = 2.5,
        headingDeg = 0,
    )

internal fun windowBetween(
    id: String,
    start: TripPoint,
    end: TripPoint,
    lengthM: Double,
    tMs: Long = end.tMs,
): SegmentSample =
    SegmentSample(
        id = id,
        tripId = "trip",
        startLat = start.lat,
        startLon = start.lon,
        endLat = end.lat,
        endLon = end.lon,
        observedAtMs = tMs,
        lengthM = lengthM,
        roughness = 0.4,
        speedKmh = 30.0,
    )
