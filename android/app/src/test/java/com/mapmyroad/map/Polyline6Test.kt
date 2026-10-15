package com.mapmyroad.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Polyline6Test {
    @Test
    fun decode_knownVector() {
        // Google's documented example string; read at precision 6 instead of 5, every value is 10 times smaller.
        val points = Polyline6.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)
        assertPoint(3.85, -12.02, points[0])
        assertPoint(4.07, -12.095, points[1])
        assertPoint(4.3252, -12.6453, points[2])
    }

    @Test
    fun encode_knownVector() {
        val encoded = Polyline6.encode(listOf(GeoPoint(3.85, -12.02), GeoPoint(4.07, -12.095), GeoPoint(4.3252, -12.6453)))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", encoded)
    }

    @Test
    fun roundTrip_keepsSixDecimals() {
        val route =
            listOf(
                GeoPoint(18.520430, 73.856743),
                GeoPoint(18.521001, 73.857002),
                GeoPoint(18.559000, 73.786800),
                GeoPoint(-33.868820, 151.209296),
                GeoPoint(0.0, 0.0),
            )
        val decoded = Polyline6.decode(Polyline6.encode(route))
        assertEquals(route.size, decoded.size)
        route.zip(decoded).forEach { (expected, actual) -> assertPoint(expected.lat, expected.lon, actual) }
    }

    @Test
    fun decode_emptyAndMalformed_neverThrows() {
        assertTrue(Polyline6.decode("").isEmpty())
        // A truncated value (continuation bit set on the last character) stops decoding.
        val valid = Polyline6.encode(listOf(GeoPoint(18.5, 73.8), GeoPoint(18.6, 73.9)))
        assertEquals(2, Polyline6.decode(valid + "_").size)
        // Characters below '?' are not part of the format.
        assertTrue(Polyline6.decode("  ").isEmpty())
        // Only a latitude, no longitude: no point.
        assertTrue(Polyline6.decode("_p~iF").isEmpty())
    }

    @Test
    fun geoPoint_toStringIsRedacted() {
        assertEquals("GeoPoint(***)", GeoPoint(18.5, 73.8).toString())
    }

    private fun assertPoint(
        lat: Double,
        lon: Double,
        actual: GeoPoint,
    ) {
        assertEquals(lat, actual.lat, EPSILON)
        assertEquals(lon, actual.lon, EPSILON)
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}
