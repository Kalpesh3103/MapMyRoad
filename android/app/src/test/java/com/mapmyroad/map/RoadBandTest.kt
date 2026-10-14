package com.mapmyroad.map

import org.junit.Assert.assertEquals
import org.junit.Test

class RoadBandTest {
    @Test
    fun fromScore_usesGuidelineThresholds() {
        assertEquals(RoadBand.GOOD, RoadBand.fromScore(100))
        assertEquals(RoadBand.GOOD, RoadBand.fromScore(70))
        assertEquals(RoadBand.FAIR, RoadBand.fromScore(69))
        assertEquals(RoadBand.FAIR, RoadBand.fromScore(40))
        assertEquals(RoadBand.POOR, RoadBand.fromScore(39))
        assertEquals(RoadBand.POOR, RoadBand.fromScore(0))
    }

    @Test
    fun fromScore_null_isNoData() {
        assertEquals(RoadBand.NO_DATA, RoadBand.fromScore(null))
    }

    @Test
    fun keys_areUnique() {
        val keys = RoadBand.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
