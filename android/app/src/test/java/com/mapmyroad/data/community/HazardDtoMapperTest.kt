package com.mapmyroad.data.community

import com.mapmyroad.detector.Severity
import com.mapmyroad.domain.model.HazardFeedbackAction
import com.mapmyroad.domain.model.HazardStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class HazardDtoMapperTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun collection(text: String): HazardFeatureCollectionDto = json.decodeFromString(HazardFeatureCollectionDto.serializer(), text)

    private fun element(text: String): JsonElement = json.parseToJsonElement(text)

    // The example of API_SPEC.md 5.5 with the dates truncated as the server sends them.
    private val validFeature =
        """
        {
          "type": "Feature",
          "geometry": { "type": "Point", "coordinates": [73.8567, 18.5204] },
          "properties": {
            "id": "4b1e9c5a-2d7f-4a39-9f55-0c3d7a2e8b14",
            "severity": 3,
            "confidence": 0.78,
            "status": "active",
            "firstSeen": "2026-09-02T00:00:00Z",
            "lastSeen": "2026-10-12T00:00:00Z",
            "confirmCount": 4,
            "dismissCount": 1,
            "futureField": true
          }
        }
        """.trimIndent()

    @Test
    fun feature_mapsEveryField() {
        val hazard = HazardDtoMapper.feature(element(validFeature))
        requireNotNull(hazard)
        assertEquals("4b1e9c5a-2d7f-4a39-9f55-0c3d7a2e8b14", hazard.id)
        assertEquals(18.5204, hazard.lat, 0.0)
        assertEquals(73.8567, hazard.lon, 0.0)
        assertEquals(Severity.SEVERE, hazard.severity)
        assertEquals(0.78, hazard.confidence, 0.0)
        assertEquals(HazardStatus.ACTIVE, hazard.status)
        assertEquals(1_788_307_200_000L, hazard.firstSeenMs)
        assertEquals(1_791_763_200_000L, hazard.lastSeenMs)
        assertEquals(4, hazard.confirmCount)
        assertEquals(1, hazard.dismissCount)
    }

    @Test
    fun collection_skipsMalformedFeatures() {
        val text =
            """
            {
              "type": "FeatureCollection",
              "features": [
                $validFeature,
                { "type": "Feature", "geometry": { "type": "Point", "coordinates": [73.8] }, "properties": { "id": "a" } },
                { "type": "Feature", "geometry": { "type": "Point", "coordinates": [73.8, 18.5] },
                  "properties": { "id": "b", "severity": "high", "confidence": 0.5, "status": "active",
                    "firstSeen": "2026-09-02T00:00:00Z", "lastSeen": "2026-09-02T00:00:00Z" } },
                { "type": "Feature", "geometry": { "type": "Point", "coordinates": [73.8, 18.5] },
                  "properties": { "id": "c", "severity": 9, "confidence": 0.5, "status": "active",
                    "firstSeen": "2026-09-02T00:00:00Z", "lastSeen": "2026-09-02T00:00:00Z" } },
                { "type": "Feature", "geometry": { "type": "Point", "coordinates": [73.8, 18.5] },
                  "properties": { "id": "d", "severity": 1, "confidence": 0.5, "status": "unknown",
                    "firstSeen": "2026-09-02T00:00:00Z", "lastSeen": "2026-09-02T00:00:00Z" } },
                { "type": "Feature", "geometry": { "type": "Point", "coordinates": [73.8, 18.5] },
                  "properties": { "id": "e", "severity": 1, "confidence": 0.5, "status": "active",
                    "firstSeen": "2026-09-02T00:00:00Z", "lastSeen": "yesterday" } },
                { "type": "Feature", "geometry": { "type": "Point", "coordinates": [200.0, 18.5] },
                  "properties": { "id": "f", "severity": 1, "confidence": 0.5, "status": "active",
                    "firstSeen": "2026-09-02T00:00:00Z", "lastSeen": "2026-09-02T00:00:00Z" } },
                "not an object"
              ]
            }
            """.trimIndent()
        val hazards = HazardDtoMapper.collection(collection(text))
        assertEquals(listOf("4b1e9c5a-2d7f-4a39-9f55-0c3d7a2e8b14"), hazards.map { it.id })
    }

    @Test
    fun emptyCollection_isEmptyList() {
        assertEquals(emptyList<Any>(), HazardDtoMapper.collection(collection("""{"type":"FeatureCollection","features":[]}""")))
    }

    @Test
    fun missingCountsAndFirstSeen_useDefaults() {
        val text =
            """
            { "type": "Feature", "geometry": { "type": "Point", "coordinates": [73.8, 18.5] },
              "properties": { "id": "x", "severity": 2, "confidence": 0.4, "status": "fixed", "lastSeen": "2026-09-02" } }
            """.trimIndent()
        val hazard = HazardDtoMapper.feature(element(text))
        requireNotNull(hazard)
        assertEquals(HazardStatus.FIXED, hazard.status)
        assertEquals(1_788_307_200_000L, hazard.lastSeenMs)
        assertEquals(hazard.lastSeenMs, hazard.firstSeenMs)
        assertEquals(0, hazard.confirmCount)
        assertEquals(0, hazard.dismissCount)
    }

    @Test
    fun parseDate_acceptsInstantsAndPlainDates() {
        assertEquals(1_788_307_200_000L, HazardDtoMapper.parseDateMs("2026-09-02T00:00:00Z"))
        assertEquals(1_788_307_200_000L, HazardDtoMapper.parseDateMs("2026-09-02"))
        assertNull(HazardDtoMapper.parseDateMs("02/09/2026"))
    }

    @Test
    fun outcome_mapsResponse_andRejectsIncompleteOnes() {
        val outcome = HazardDtoMapper.outcome(FeedbackResponseDto(id = "h", confidence = 0.81, status = "active"))
        assertNotNull(outcome)
        assertEquals("h", outcome?.hazardId)
        assertEquals(HazardStatus.ACTIVE, outcome?.status)
        assertNull(HazardDtoMapper.outcome(FeedbackResponseDto(id = "h", confidence = null, status = "active")))
        assertNull(HazardDtoMapper.outcome(FeedbackResponseDto(id = "h", confidence = 0.5, status = "gone")))
    }

    @Test
    fun feedbackRequest_matchesTheWireFormat_andRoundsTheLocation() {
        val body = HazardDtoMapper.feedbackRequest(HazardFeedbackAction.DISMISS, 18.5204567, 73.8567891, 1_791_882_120_456L)
        assertEquals("dismiss", body.action)
        assertEquals(18.52046, body.lat, 0.0)
        assertEquals(73.85679, body.lon, 0.0)
        assertEquals("2026-10-13T09:02:00Z", body.observedAt)
        val encoded = json.encodeToString(FeedbackRequestDto.serializer(), body)
        assertEquals("""{"action":"dismiss","lat":18.52046,"lon":73.85679,"observedAt":"2026-10-13T09:02:00Z"}""", encoded)
    }

    @Test
    fun toString_neverContainsCoordinates() {
        val body = HazardDtoMapper.feedbackRequest(HazardFeedbackAction.CONFIRM, 18.52045, 73.85672, 0L)
        assertFalse(body.toString().contains("18.52"))
        assertFalse(PointDto(coordinates = listOf(73.85672, 18.52045)).toString().contains("18.52"))
    }

    @Test
    fun bboxParam_isLonLatOrder_withDotDecimals() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals(
                "73.800000,18.500000,73.890000,18.590000",
                HazardDtoMapper.bboxParam(south = 18.5, west = 73.8, north = 18.59, east = 73.89),
            )
        } finally {
            Locale.setDefault(previous)
        }
    }
}
