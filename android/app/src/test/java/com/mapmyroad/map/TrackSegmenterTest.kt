package com.mapmyroad.map

import com.mapmyroad.domain.model.TripPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSegmenterTest {
    /** Test mapping: roughness is used directly as the score. */
    private val bandOf: (TripPoint) -> RoadBand = { RoadBand.fromScore(it.roughness?.toInt()) }

    @Test
    fun empty_givesNoRuns() {
        assertTrue(TrackSegmenter.segment(emptyList(), bandOf).isEmpty())
    }

    @Test
    fun singlePoint_givesOneSinglePointRun() {
        val point = testPoint(0, roughness = 80.0)
        val runs = TrackSegmenter.segment(listOf(point), bandOf)
        assertEquals(1, runs.size)
        assertEquals(RoadBand.GOOD, runs[0].band)
        assertEquals(listOf(point), runs[0].points)
    }

    @Test
    fun sameBand_givesOneRun() {
        val points = (0 until 5).map { testPoint(it, roughness = 50.0) }
        val runs = TrackSegmenter.segment(points, bandOf)
        assertEquals(1, runs.size)
        assertEquals(RoadBand.FAIR, runs[0].band)
        assertEquals(points, runs[0].points)
    }

    @Test
    fun bandChanges_giveContiguousRuns_sharingBoundaryPoints() {
        val points =
            listOf(
                testPoint(0, roughness = 90.0),
                testPoint(1, roughness = 90.0),
                testPoint(2, roughness = 10.0),
                testPoint(3, roughness = 10.0),
                testPoint(4, roughness = 50.0),
                testPoint(5, roughness = 50.0),
            )
        val runs = TrackSegmenter.segment(points, bandOf)

        assertEquals(listOf(RoadBand.GOOD, RoadBand.POOR, RoadBand.FAIR), runs.map { it.band })
        assertEquals(points.subList(0, 3), runs[0].points)
        assertEquals(points.subList(2, 5), runs[1].points)
        assertEquals(points.subList(4, 6), runs[2].points)
        // Each run starts where the previous one ends, so the drawn lines connect.
        for (index in 1 until runs.size) {
            assertSame(runs[index - 1].points.last(), runs[index].points.first())
        }
    }

    @Test
    fun nullRoughness_isNoData() {
        val points =
            listOf(
                testPoint(0, roughness = null),
                testPoint(1, roughness = null),
                testPoint(2, roughness = 80.0),
                testPoint(3, roughness = 80.0),
            )
        val runs = TrackSegmenter.segment(points, bandOf)
        assertEquals(listOf(RoadBand.NO_DATA, RoadBand.GOOD), runs.map { it.band })
        assertEquals(points.subList(0, 3), runs[0].points)
        assertEquals(points.subList(2, 4), runs[1].points)
    }

    @Test
    fun singlePointBandInMiddle_givesTwoPointRun_endingAtNextPoint() {
        val points =
            listOf(
                testPoint(0, roughness = 80.0),
                testPoint(1, roughness = 10.0),
                testPoint(2, roughness = 80.0),
            )
        val runs = TrackSegmenter.segment(points, bandOf)
        // The line between two points takes the band of the first one, and the last point only ends a run.
        assertEquals(listOf(RoadBand.GOOD, RoadBand.POOR), runs.map { it.band })
        assertEquals(points.subList(0, 2), runs[0].points)
        assertEquals(points.subList(1, 3), runs[1].points)
    }

    @Test
    fun lastPointChangingBand_addsNoExtraRun() {
        val points =
            listOf(
                testPoint(0, roughness = 80.0),
                testPoint(1, roughness = 80.0),
                testPoint(2, roughness = 10.0),
            )
        val runs = TrackSegmenter.segment(points, bandOf)
        assertEquals(1, runs.size)
        assertEquals(RoadBand.GOOD, runs[0].band)
        assertEquals(points, runs[0].points)
    }
}
