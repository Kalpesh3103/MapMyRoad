package com.mapmyroad.data.upload

import com.mapmyroad.detector.Severity
import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.model.RecordedHazard
import com.mapmyroad.domain.model.SegmentSample
import com.mapmyroad.domain.upload.TrimmedTrip
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadPayloadBuilderTest {
    @Test
    fun emptyTrim_buildsNoRequest() {
        assertTrue(build(hazards = emptyList(), samples = emptyList()).isEmpty())
    }

    @Test
    fun largeTrip_isSplitWithinLimits_andKeepsEveryItemOnce() {
        // UUID-length ids and full-precision doubles, so the body size check below is realistic.
        val hazards = (0 until 1_234).map { hazard(uuid(it), observedAtMs = NOW_MS - 3_600_000L + it * 1_000L) }
        val samples = (0 until 2_345).map { sample(uuid(10_000 + it), observedAtMs = NOW_MS - 3_600_000L + it * 500L) }

        val requests = build(hazards, samples, vehicleType = VehicleType.AUTO_RICKSHAW)

        assertTrue(requests.size > 1)
        requests.forEach { request ->
            assertTrue(request.events.size <= UploadPayloadBuilder.MAX_EVENTS_PER_REQUEST)
            assertTrue(request.windows.size <= UploadPayloadBuilder.MAX_WINDOWS_PER_REQUEST)
            assertFalse(request.events.isEmpty() && request.windows.isEmpty())
            assertEquals(TOKEN, request.tripToken)
            assertEquals("auto_rickshaw", request.vehicleType)
            // The real body stays under the server's 256 KB even with full-precision doubles.
            assertTrue(json.encodeToString(request).toByteArray().size < SERVER_BODY_LIMIT_BYTES)
        }
        assertEquals(hazards.map { it.id }, requests.flatMap { it.events }.map { it.id })
        assertEquals(samples.map { it.id }, requests.flatMap { it.windows }.map { it.id })
    }

    @Test
    fun items_areSortedByTime() {
        val requests = build(hazards = listOf(hazard("late", NOW_MS - 1_000L), hazard("early", NOW_MS - 9_000L)))

        assertEquals(listOf("early", "late"), requests.single().events.map { it.id })
    }

    @Test
    fun timestamps_areIsoUtc() {
        // 2026-10-13T08:30:12Z, the example of API_SPEC.md 5.3.
        assertEquals("2026-10-13T08:30:12Z", UploadPayloadBuilder.isoUtc(1_791_880_212_000L))
        assertEquals("2026-10-13T08:30:12.345Z", UploadPayloadBuilder.isoUtc(1_791_880_212_345L))
    }

    @Test
    fun vehicleCodes_matchTheApiEnumeration() {
        val codes = VehicleType.entries.map { build(hazards = listOf(hazard("h")), vehicleType = it).single().vehicleType }

        assertEquals(listOf("motorcycle", "scooter", "car", "auto_rickshaw"), codes)
    }

    @Test
    fun events_belowMinimumSpeedOrImplausible_areDroppedLocally() {
        val hazards =
            listOf(
                hazard("slow", speedKmh = 9.9),
                hazard("min", speedKmh = 10.0),
                hazard("fast", speedKmh = 200.1),
                hazard("nan", speedKmh = Double.NaN),
                hazard("old", observedAtMs = NOW_MS - UploadPayloadBuilder.MAX_AGE_MS - 1L),
                hazard("future", observedAtMs = NOW_MS + UploadPayloadBuilder.MAX_FUTURE_MS + 1L),
                hazard("lat", lat = 91.0),
            )

        assertEquals(listOf("min"), build(hazards = hazards).single().events.map { it.id })
    }

    @Test
    fun windows_outside20To120m_areDroppedLocally() {
        val samples =
            listOf(
                sample("short", lengthM = 19.9),
                sample("min", lengthM = 20.0),
                sample("max", lengthM = 120.0),
                sample("long", lengthM = 120.1),
                sample("negative-roughness", roughness = -0.1),
                sample("fast", speedKmh = 250.0),
            )

        assertEquals(listOf("min", "max"), build(samples = samples).single().windows.map { it.id })
    }

    @Test
    fun invalidHeading_isOmitted() {
        val event = build(hazards = listOf(hazard("h", headingDeg = 400))).single().events.single()

        assertNull(event.headingDeg)
    }

    @Test
    fun json_matchesTheApiFieldNames() {
        val request = build(hazards = listOf(hazard("h", headingDeg = null)), samples = listOf(sample("w"))).single()

        val body = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertEquals(setOf("tripToken", "vehicleType", "events", "windows"), body.keys)
        val events = body.getValue("events").jsonArray
        val windows = body.getValue("windows").jsonArray
        assertEquals(
            setOf("id", "lat", "lon", "observedAt", "severity", "speedKmh", "peakScore"),
            events[0].jsonObject.keys,
        )
        assertEquals(
            setOf("id", "startLat", "startLon", "endLat", "endLon", "observedAt", "lengthM", "roughness", "speedKmh"),
            windows[0].jsonObject.keys,
        )
    }

    @Test
    fun response_parsesTheSpecExample() {
        val response =
            json.decodeFromString<EventUploadResponse>(
                """
                {
                  "accepted": { "events": 12, "windows": 340 },
                  "duplicates": { "events": 0, "windows": 0 },
                  "rejected": [ { "id": "d1f2", "kind": "event", "reason": "IMPLAUSIBLE_SPEED" } ],
                  "extra": true
                }
                """.trimIndent(),
            )

        assertEquals(ItemCounts(events = 12, windows = 340), response.accepted)
        assertEquals("IMPLAUSIBLE_SPEED", response.rejected.single().reason)
    }

    @Test
    fun toString_neverPrintsCoordinatesOrToken() {
        val request = build(hazards = listOf(hazard("h")), samples = listOf(sample("w"))).single()

        assertEquals("EventUploadRequest(events=1, windows=1)", request.toString())
        val eventText = request.events.single().toString()
        val windowText = request.windows.single().toString()
        assertFalse(eventText.contains("18."))
        assertFalse(windowText.contains("18."))
    }

    private fun uuid(n: Int): String = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    private fun build(
        hazards: List<RecordedHazard> = emptyList(),
        samples: List<SegmentSample> = emptyList(),
        vehicleType: VehicleType = VehicleType.SCOOTER,
    ): List<EventUploadRequest> =
        UploadPayloadBuilder.build(TOKEN, vehicleType, TrimmedTrip(hazards, samples, trackLengthM = 10_000.0), NOW_MS)

    private fun hazard(
        id: String,
        observedAtMs: Long = NOW_MS - 60_000L,
        speedKmh: Double = 32.123456789012344,
        lat: Double = 18.520412345678901,
        headingDeg: Int? = 187,
    ): RecordedHazard =
        RecordedHazard(
            id = id,
            tripId = "trip",
            lat = lat,
            lon = 73.856712345678901,
            observedAtMs = observedAtMs,
            severity = Severity.SEVERE,
            speedKmh = speedKmh,
            peakScore = 2.812345678901234,
            headingDeg = headingDeg,
        )

    private fun sample(
        id: String,
        observedAtMs: Long = NOW_MS - 60_000L,
        lengthM: Double = 52.123456789012344,
        roughness: Double = 0.4212345678901234,
        speedKmh: Double = 28.123456789012344,
    ): SegmentSample =
        SegmentSample(
            id = id,
            tripId = "trip",
            startLat = -18.520112345678901,
            startLon = -173.856512345678901,
            endLat = -18.520612345678901,
            endLon = -173.856812345678901,
            observedAtMs = observedAtMs,
            lengthM = lengthM,
            roughness = roughness,
            speedKmh = speedKmh,
        )

    private companion object {
        const val TOKEN = "0b7a4c35-3a2e-4b8f-a6f3-8d4f1f1c7e11"
        const val NOW_MS = 1_791_880_212_000L
        const val SERVER_BODY_LIMIT_BYTES = 256 * 1_024
        val json = Json { ignoreUnknownKeys = true }
    }
}
