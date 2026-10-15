package com.mapmyroad.data.routing

import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.model.CoverageLevel
import com.mapmyroad.domain.model.RouteLabel
import com.mapmyroad.domain.model.RouteQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingMappersTest {
    // Same settings as the app's Json (data/remote/ApiClients.kt).
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    @Test
    fun geocode_specExample_mapsToPlaces() {
        val body =
            """
            {
              "results": [
                {
                  "name": "Shaniwar Wada",
                  "displayName": "Shaniwar Wada, Kasba Peth, Pune, Maharashtra",
                  "lat": 18.5195,
                  "lon": 73.8553,
                  "type": "attraction",
                  "futureField": 1
                },
                { "name": "Pune Station", "displayName": null, "lat": 18.5286, "lon": 73.8743, "type": null },
                { "name": null, "displayName": null, "lat": 18.5, "lon": 73.8 }
              ],
              "cached": true
            }
            """.trimIndent()
        val places = RoutingMappers.toPlaces(json.decodeFromString(GeocodeResponseDto.serializer(), body))

        assertEquals(2, places.size)
        assertEquals("Shaniwar Wada", places[0].name)
        assertEquals("Shaniwar Wada, Kasba Peth, Pune, Maharashtra", places[0].displayName)
        assertEquals(18.5195, places[0].lat, 0.0)
        assertEquals(73.8553, places[0].lon, 0.0)
        assertEquals("attraction", places[0].type)
        // A missing display name falls back to the name; a place without any name is dropped.
        assertEquals("Pune Station", places[1].displayName)
        assertNull(places[1].type)
    }

    @Test
    fun geocode_emptyResults() {
        val response = json.decodeFromString(GeocodeResponseDto.serializer(), """{"results": [], "cached": false}""")
        assertTrue(RoutingMappers.toPlaces(response).isEmpty())
    }

    @Test
    fun routes_specExample_mapsToDomain() {
        val body =
            """
            {
              "requestId": "e8a3c0de-7b4a-4f55-8b62-0b2e4c1d9a77",
              "candidates": [
                {
                  "id": "c1",
                  "labels": ["fastest"],
                  "durationSec": 1380,
                  "distanceM": 9200,
                  "comfortScore": 62,
                  "hazards": { "severe": 3, "moderate": 7, "minor": 12 },
                  "geometry": "_p~iF~ps|U",
                  "recommended": false,
                  "coverage": { "level": "good", "fractionWithData": 0.91 }
                },
                {
                  "id": "c2",
                  "labels": ["smoothest", "balanced", "scenic"],
                  "durationSec": 1620,
                  "distanceM": 10100,
                  "comfortScore": 81,
                  "hazards": { "severe": 0, "moderate": 4, "minor": 9 },
                  "geometry": "_ulLnnqC",
                  "recommended": true
                }
              ],
              "coverage": { "level": "partial", "fractionWithData": 0.64 }
            }
            """.trimIndent()
        val options = RoutingMappers.toRouteOptions(json.decodeFromString(RouteResponseDto.serializer(), body))

        assertEquals("e8a3c0de-7b4a-4f55-8b62-0b2e4c1d9a77", options.requestId)
        assertEquals(CoverageLevel.PARTIAL, options.coverage.level)
        assertEquals(0.64, options.coverage.fractionWithData, 0.0)
        assertEquals(2, options.candidates.size)

        val fastest = options.candidates[0]
        assertEquals("c1", fastest.id)
        assertEquals(setOf(RouteLabel.FASTEST), fastest.labels)
        assertEquals(1380, fastest.durationS)
        assertEquals(9200, fastest.distanceM)
        assertEquals(62, fastest.comfortScore)
        assertEquals(3, fastest.severeCount)
        assertEquals(7, fastest.moderateCount)
        assertEquals(12, fastest.minorCount)
        assertEquals("_p~iF~ps|U", fastest.encodedPolyline6)
        assertFalse(fastest.isRecommended)
        assertEquals(CoverageLevel.GOOD, fastest.coverage?.level)

        val smoothest = options.candidates[1]
        // Unknown labels are ignored; a route can carry several labels.
        assertEquals(setOf(RouteLabel.SMOOTHEST, RouteLabel.BALANCED), smoothest.labels)
        assertTrue(smoothest.isRecommended)
        // Per-candidate coverage is optional.
        assertNull(smoothest.coverage)
    }

    @Test
    fun coverage_unknownLevel_isPartial_andFractionIsClamped() {
        val coverage = RoutingMappers.toCoverage(CoverageDto(level = "excellent", fractionWithData = 1.7))
        assertEquals(CoverageLevel.PARTIAL, coverage.level)
        assertEquals(1.0, coverage.fractionWithData, 0.0)
        assertEquals(CoverageLevel.NONE, RoutingMappers.toCoverage(CoverageDto(level = "none")).level)
    }

    @Test
    fun routeRequest_matchesBackendFieldNames() {
        val query =
            RouteQuery(
                originLat = 18.5204,
                originLon = 73.8567,
                destinationLat = 18.559,
                destinationLon = 73.7868,
                vehicleType = VehicleType.AUTO_RICKSHAW,
                preference = 1.4,
            )
        val encoded = json.encodeToString(RouteRequestDto.serializer(), RoutingMappers.toRequest(query))
        val obj = json.parseToJsonElement(encoded).jsonObject

        assertEquals(setOf("origin", "destination", "vehicleType", "preference"), obj.keys)
        val origin = obj.getValue("origin").jsonObject
        val destination = obj.getValue("destination").jsonObject
        assertEquals(18.5204, origin.getValue("lat").jsonPrimitive.double, 0.0)
        assertEquals(73.7868, destination.getValue("lon").jsonPrimitive.double, 0.0)
        assertEquals("auto_rickshaw", obj.getValue("vehicleType").jsonPrimitive.content)
        // Preference is clamped to the API range 0 to 1.
        assertEquals(1.0, obj.getValue("preference").jsonPrimitive.double, 0.0)
    }

    @Test
    fun dtosWithCoordinates_redactToString() {
        val request = RoutingMappers.toRequest(RouteQuery(18.52, 73.85, 18.55, 73.78, VehicleType.CAR, 0.5))
        assertFalse(request.toString().contains("18.5"))
        assertFalse(request.origin.toString().contains("18.5"))
        assertFalse(GeocodePlaceDto(name = "Home", lat = 18.5, lon = 73.8).toString().contains("Home"))
    }

    @Test
    fun normalizeQuery_andBiasRounding() {
        assertEquals("FC Road Pune", RoutingMappers.normalizeQuery("  FC   Road\tPune "))
        assertEquals(18.52, RoutingMappers.roundBias(18.520430), 0.0)
        assertEquals(73.86, RoutingMappers.roundBias(73.856743), 0.0)
        assertEquals(-12.35, RoutingMappers.roundBias(-12.3456), 0.0)
    }
}
