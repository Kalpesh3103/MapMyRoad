package com.mapmyroad.map

import com.mapmyroad.detector.Severity
import com.mapmyroad.domain.model.HazardStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityMapHelpersTest {
    @Test
    fun confidenceLevel_usesSheetThresholds() {
        assertEquals(ConfidenceLevel.LOW, ConfidenceLevel.of(0.0))
        assertEquals(ConfidenceLevel.LOW, ConfidenceLevel.of(0.49))
        assertEquals(ConfidenceLevel.MEDIUM, ConfidenceLevel.of(0.5))
        assertEquals(ConfidenceLevel.MEDIUM, ConfidenceLevel.of(0.79))
        assertEquals(ConfidenceLevel.HIGH, ConfidenceLevel.of(0.8))
        assertEquals(ConfidenceLevel.HIGH, ConfidenceLevel.of(1.0))
        assertEquals(ConfidenceLevel.LOW, ConfidenceLevel.of(Double.NaN))
    }

    @Test
    fun confidenceOpacity_increasesWithLevel() {
        assertTrue(ConfidenceLevel.LOW_OPACITY < ConfidenceLevel.MEDIUM_OPACITY)
        assertTrue(ConfidenceLevel.MEDIUM_OPACITY < ConfidenceLevel.HIGH_OPACITY)
    }

    @Test
    fun scoreRanges_matchRoadBandForEveryScore() {
        for (score in 0..100) {
            val matching = CommunityTiles.tileBands.filter { band -> CommunityTiles.scoreRange(band)?.contains(score) == true }
            assertEquals("score $score", listOf(RoadBand.fromScore(score)), matching)
        }
        assertNull(CommunityTiles.scoreRange(RoadBand.NO_DATA))
    }

    @Test
    fun scoreRanges_useGuidelineThresholds() {
        assertEquals(ScoreRange(minInclusive = 70, maxExclusive = null), CommunityTiles.scoreRange(RoadBand.GOOD))
        assertEquals(ScoreRange(minInclusive = 40, maxExclusive = 70), CommunityTiles.scoreRange(RoadBand.FAIR))
        assertEquals(ScoreRange(minInclusive = null, maxExclusive = 40), CommunityTiles.scoreRange(RoadBand.POOR))
    }

    @Test
    fun tileUrls_areBuiltFromTheApiBaseUrl() {
        assertEquals("http://localhost:8080/v1/tiles/{z}/{x}/{y}.mvt", CommunityTiles.tileUrlTemplate("http://localhost:8080"))
        assertEquals("https://api.example.com/v1/tiles/{z}/{x}/{y}.mvt", CommunityTiles.tileUrlTemplate("https://api.example.com/"))
        assertEquals("http://localhost:8080/v1/tiles/tilejson.json", CommunityTiles.tileJsonUrl("http://localhost:8080/"))
    }

    @Test
    fun communityLayerIds_coverEveryTileBand() {
        val ids = CommunityTiles.tileBands.map { MapLayerIds.communityRoadLayerId(it) }
        assertEquals(MapLayerIds.communityRoadLayerIds, ids)
        assertNull(MapLayerIds.communityRoadLayerId(RoadBand.NO_DATA))
        val all = MapLayerIds.personalLayerIds + MapLayerIds.communityLayerIds
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun tileHazard_readsTileProperties() {
        val hazard = TileHazard.fromProperties(id = "h1", severity = 3, confidence = 0.62, status = "active", seenDays = 4)
        assertEquals(TileHazard("h1", Severity.SEVERE, 0.62, HazardStatus.ACTIVE, 4), hazard)
    }

    @Test
    fun tileHazard_withoutIdOrSeverity_isNull() {
        assertNull(TileHazard.fromProperties(id = null, severity = 2, confidence = 0.5, status = "active", seenDays = 1))
        assertNull(TileHazard.fromProperties(id = " ", severity = 2, confidence = 0.5, status = "active", seenDays = 1))
        assertNull(TileHazard.fromProperties(id = "h1", severity = null, confidence = 0.5, status = "active", seenDays = 1))
        assertNull(TileHazard.fromProperties(id = "h1", severity = 7, confidence = 0.5, status = "active", seenDays = 1))
    }

    @Test
    fun tileHazard_dropsInvalidOptionalValues() {
        val hazard = TileHazard.fromProperties(id = "h1", severity = 1.0, confidence = 1.5, status = "weird", seenDays = -1)
        assertEquals(TileHazard("h1", Severity.MINOR, null, null, null), hazard)
    }

    @Test
    fun coverage_followsZoomAndRenderedData() {
        assertEquals(CommunityCoverage.UNKNOWN, CommunityCoverage.of(null))
        assertEquals(CommunityCoverage.ZOOM_IN, CommunityCoverage.of(viewport(zoom = 11.9, roads = 5)))
        assertEquals(CommunityCoverage.LIMITED, CommunityCoverage.of(viewport(zoom = 12.0)))
        assertEquals(CommunityCoverage.AVAILABLE, CommunityCoverage.of(viewport(zoom = 14.0, roads = 1)))
        assertEquals(CommunityCoverage.AVAILABLE, CommunityCoverage.of(viewport(zoom = 14.0, severe = 1)))
    }

    @Test
    fun hazardAge_wholeDaysNeverNegative() {
        val day = HazardAge.DAY_MS
        assertEquals(0, HazardAge.daysSince(epochMs = 10 * day, nowMs = 10 * day + day - 1))
        assertEquals(3, HazardAge.daysSince(epochMs = 10 * day, nowMs = 13 * day + 5))
        assertEquals(0, HazardAge.daysSince(epochMs = 10 * day, nowMs = 9 * day))
    }

    private fun viewport(
        zoom: Double,
        roads: Int = 0,
        severe: Int = 0,
    ): CommunityViewport =
        CommunityViewport(zoom = zoom, roadFeatureCount = roads, minorCount = 0, moderateCount = 0, severeCount = severe)
}
