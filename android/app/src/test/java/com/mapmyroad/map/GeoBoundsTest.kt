package com.mapmyroad.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoBoundsTest {
    @Test
    fun of_coversAllPoints_andIsNullWhenEmpty() {
        val points = listOf(testPoint(0, lat = 18.5, lon = 73.9), testPoint(1, lat = 18.6, lon = 73.8))
        val bounds = GeoBounds.of(points, { it.lat }, { it.lon })
        assertEquals(GeoBounds(south = 18.5, west = 73.8, north = 18.6, east = 73.9), bounds)
        assertNull(GeoBounds.of(emptyList<Int>(), { 0.0 }, { 0.0 }))
    }

    @Test
    fun singlePoint_isPoint() {
        val bounds = GeoBounds.of(listOf(testPoint(0)), { it.lat }, { it.lon })
        assertTrue(bounds?.isPoint == true)
    }

    @Test
    fun containsAndIntersects() {
        val box = GeoBounds(south = 18.0, west = 73.0, north = 19.0, east = 74.0)
        assertTrue(box.contains(18.5, 73.5))
        assertFalse(box.contains(19.5, 73.5))
        assertTrue(box.intersects(GeoBounds(south = 18.9, west = 73.9, north = 20.0, east = 75.0)))
        assertFalse(box.intersects(GeoBounds(south = 19.1, west = 73.0, north = 20.0, east = 74.0)))
    }

    @Test
    fun union_ignoresNulls() {
        val union =
            GeoBounds.union(
                listOf(
                    GeoBounds(south = 18.0, west = 73.0, north = 18.5, east = 73.5),
                    null,
                    GeoBounds(south = 18.2, west = 73.2, north = 19.0, east = 74.0),
                ),
            )
        assertEquals(GeoBounds(south = 18.0, west = 73.0, north = 19.0, east = 74.0), union)
        assertNull(GeoBounds.union(listOf(null)))
    }

    @Test
    fun toString_isRedacted() {
        assertEquals("GeoBounds(***)", GeoBounds(south = 18.0, west = 73.0, north = 19.0, east = 74.0).toString())
    }
}
