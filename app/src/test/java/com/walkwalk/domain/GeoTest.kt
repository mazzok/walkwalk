package com.walkwalk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    @Test
    fun distanceOneDegreeLatitude() =
        assertEquals(111_195.0, Geo.distance(47.0, 11.0, 48.0, 11.0), 50.0)

    @Test
    fun cumulativeIgnoresSegmentGaps() {
        val pts = listOf(p(0, 47.0, 11.0, seg = 0), p(1000, 47.001, 11.0, seg = 0), p(2000, 48.0, 11.0, seg = 1))
        val d = Geo.cumulativeDistances(pts)
        assertEquals(111.2, d[1], 0.5)
        assertEquals(d[1], d[2], 0.001)
    }

    @Test
    fun filtersInaccurateAndTeleportingFixes() {
        val a = p(0, 47.0, 11.0, acc = 5f)
        assertFalse(Geo.acceptFix(a, p(2000, 47.0001, 11.0, acc = 80f)))
        assertFalse(Geo.acceptFix(a, p(2000, 47.01, 11.0, acc = 5f)))
        assertTrue(Geo.acceptFix(a, p(2000, 47.00002, 11.0, acc = 5f)))
        assertTrue(Geo.acceptFix(null, p(0, 47.0, 11.0, acc = 20f)))
    }
}
