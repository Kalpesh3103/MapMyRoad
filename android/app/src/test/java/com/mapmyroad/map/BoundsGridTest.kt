package com.mapmyroad.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundsGridTest {
    private fun cells(
        points: List<Pair<Double, Double>>,
        maxCells: Int = 10,
    ): List<GeoBounds> =
        BoundsGrid.cellsAlong(points, { it.first }, { it.second }, cellDeg = 0.09, padDeg = 0.0005, maxCells = maxCells)

    @Test
    fun shortTrip_insideOneCell_needsOneBox() {
        val result = cells(listOf(18.52 to 73.85, 18.53 to 73.86))
        assertEquals(1, result.size)
        assertTrue(result[0].contains(18.52, 73.85))
        assertTrue(result[0].contains(18.53, 73.86))
    }

    @Test
    fun everyBox_isWithinTheApiAreaLimit() {
        val points = (0..200).map { 18.40 + it * 0.002 to 73.70 + it * 0.002 }
        val result = cells(points)
        for (box in result) {
            assertTrue((box.north - box.south) * (box.east - box.west) <= 0.01)
            assertTrue(box.north > box.south && box.east > box.west)
        }
        // Every point (with its margin) is covered by some box.
        for ((lat, lon) in points) {
            assertTrue(result.any { it.contains(lat, lon) })
        }
    }

    @Test
    fun pointNearACellEdge_alsoQueriesTheNeighbour() {
        // 18.0 is a cell boundary (200 * 0.09): a point 20 m south of it needs the cell to the north too.
        val result = cells(listOf(17.9998 to 73.85))
        assertEquals(2, result.size)
    }

    @Test
    fun diagonalTrip_skipsEmptyCorners() {
        // Four cells along the diagonal instead of the 16 of its bounding box.
        val points = (0..40).map { 18.005 + it * 0.009 to 73.805 + it * 0.009 }
        val result = cells(points, maxCells = 100)
        assertTrue(result.size < 16)
    }

    @Test
    fun cellCount_isCapped_inTrackOrder() {
        val points = (0..100).map { 18.02 + it * 0.05 to 73.82 }
        val result = cells(points, maxCells = 3)
        assertEquals(3, result.size)
        assertTrue(result[0].contains(18.02, 73.82))
    }

    @Test
    fun negativeCoordinates_areSupported() {
        val result = cells(listOf(-33.87 to -151.21))
        assertEquals(1, result.size)
        assertTrue(result[0].contains(-33.87, -151.21))
    }
}
