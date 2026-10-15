package com.mapmyroad.data.upload

import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.model.RecordedHazard
import com.mapmyroad.domain.model.SegmentSample
import com.mapmyroad.domain.upload.TrimmedTrip
import java.time.Instant

/**
 * Turns the output of [com.mapmyroad.domain.upload.TripTrimmer] into `POST /v1/events` requests (API_SPEC.md 5.3).
 * Takes only a [TrimmedTrip], so nothing untrimmed can reach a request.
 *
 * Items the server would reject for predictable reasons (validation table in API_SPEC.md 5.3) are dropped here, so
 * they are not sent at all. The supported-region check stays on the server. Requests respect the per-request item
 * limits and an estimated body size well under the 256 KB limit; all requests of a trip share its trip token.
 * Pure: the current time is a parameter.
 */
object UploadPayloadBuilder {
    const val MAX_EVENTS_PER_REQUEST = 500
    const val MAX_WINDOWS_PER_REQUEST = 1_000
    const val MIN_EVENT_SPEED_KMH = 10.0
    const val MAX_SPEED_KMH = 200.0
    const val MIN_WINDOW_LENGTH_M = 20.0
    const val MAX_WINDOW_LENGTH_M = 120.0
    const val MAX_FUTURE_MS = 5L * 60L * 1_000L
    const val MAX_AGE_MS = 14L * 24L * 60L * 60L * 1_000L

    /**
     * Upper-bound JSON sizes of one item with full-precision doubles (about 240 and 300 bytes measured by hand on
     * the example in API_SPEC.md 5.3, rounded up), and the body budget below the server's 256 KB (262 144 bytes).
     */
    const val EVENT_BYTES_ESTIMATE = 320
    const val WINDOW_BYTES_ESTIMATE = 400
    const val REQUEST_BUDGET_BYTES = 240_000

    private const val MAX_HEADING_DEG = 359

    fun build(
        tripToken: String,
        vehicleType: VehicleType,
        trimmed: TrimmedTrip,
        nowMs: Long,
    ): List<EventUploadRequest> {
        val events =
            trimmed
                .hazards
                .filter { isValidEvent(it, nowMs) }
                .sortedBy { it.observedAtMs }
                .map { it.toDto() }
        val windows =
            trimmed
                .samples
                .filter { isValidWindow(it, nowMs) }
                .sortedBy { it.observedAtMs }
                .map { it.toDto() }
        if (events.isEmpty() && windows.isEmpty()) return emptyList()

        val requests = mutableListOf<EventUploadRequest>()
        var eventIndex = 0
        var windowIndex = 0
        while (eventIndex < events.size || windowIndex < windows.size) {
            val eventCount =
                minOf(events.size - eventIndex, MAX_EVENTS_PER_REQUEST, REQUEST_BUDGET_BYTES / EVENT_BYTES_ESTIMATE)
            val budgetLeft = REQUEST_BUDGET_BYTES - eventCount * EVENT_BYTES_ESTIMATE
            val windowCount = minOf(windows.size - windowIndex, MAX_WINDOWS_PER_REQUEST, budgetLeft / WINDOW_BYTES_ESTIMATE)
            requests +=
                EventUploadRequest(
                    tripToken = tripToken,
                    vehicleType = vehicleType.code,
                    events = events.subList(eventIndex, eventIndex + eventCount).toList(),
                    windows = windows.subList(windowIndex, windowIndex + windowCount).toList(),
                )
            eventIndex += eventCount
            windowIndex += windowCount
        }
        return requests
    }

    fun isValidEvent(
        hazard: RecordedHazard,
        nowMs: Long,
    ): Boolean =
        isValidPosition(hazard.lat, hazard.lon) &&
            isValidTime(hazard.observedAtMs, nowMs) &&
            hazard.speedKmh.isFinite() &&
            hazard.speedKmh >= MIN_EVENT_SPEED_KMH &&
            hazard.speedKmh <= MAX_SPEED_KMH &&
            hazard.peakScore.isFinite()

    fun isValidWindow(
        sample: SegmentSample,
        nowMs: Long,
    ): Boolean =
        isValidPosition(sample.startLat, sample.startLon) &&
            isValidPosition(sample.endLat, sample.endLon) &&
            isValidTime(sample.observedAtMs, nowMs) &&
            sample.speedKmh.isFinite() &&
            sample.speedKmh >= 0.0 &&
            sample.speedKmh <= MAX_SPEED_KMH &&
            sample.lengthM >= MIN_WINDOW_LENGTH_M &&
            sample.lengthM <= MAX_WINDOW_LENGTH_M &&
            sample.roughness.isFinite() &&
            sample.roughness >= 0.0

    /** ISO-8601 in UTC, for example `2026-10-13T08:30:12Z` (milliseconds are added when non-zero). */
    fun isoUtc(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    private fun isValidPosition(
        lat: Double,
        lon: Double,
    ): Boolean = lat.isFinite() && lon.isFinite() && lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0

    private fun isValidTime(
        observedAtMs: Long,
        nowMs: Long,
    ): Boolean = observedAtMs <= nowMs + MAX_FUTURE_MS && observedAtMs >= nowMs - MAX_AGE_MS

    private fun RecordedHazard.toDto(): EventDto =
        EventDto(
            id = id,
            lat = lat,
            lon = lon,
            observedAt = isoUtc(observedAtMs),
            severity = severity.level,
            speedKmh = speedKmh,
            peakScore = peakScore,
            headingDeg = headingDeg?.takeIf { it in 0..MAX_HEADING_DEG },
        )

    private fun SegmentSample.toDto(): WindowDto =
        WindowDto(
            id = id,
            startLat = startLat,
            startLon = startLon,
            endLat = endLat,
            endLon = endLon,
            observedAt = isoUtc(observedAtMs),
            lengthM = lengthM,
            roughness = roughness,
            speedKmh = speedKmh,
        )
}
