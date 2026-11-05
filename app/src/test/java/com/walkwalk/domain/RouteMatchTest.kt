package com.walkwalk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMatchTest {
    // 1e-5 deg longitude at 47° N ≈ 0.76 m
    private val mPerDegLon = M_PER_DEG_LAT * Math.cos(Math.toRadians(47.0))

    /** 1 km north, shifted [eastM] metres east, one fix every [everyMs]. */
    private fun north(eastM: Double = 0.0, everyMs: Long = 5_000): List<TrackPoint> =
        straightLine(2.0, everyMs, (500_000 / everyMs).toInt() + 1).map { it.copy(lon = it.lon + eastM / mPerDegLon) }

    private fun shape(pts: List<TrackPoint>) = RouteMatch.resample(pts)

    @Test
    fun resampleSpacesPointsEvenlyAndKeepsEnds() {
        val pts = north()
        val r = RouteMatch.resample(pts, stepM = 10.0)
        assertEquals(101, r.size)
        assertEquals(pts.first().lat, r.first().lat, 1e-9)
        assertEquals(pts.last().lat, r.last().lat, 1e-9)
        assertEquals(10.0, Geo.distance(r[0].lat, r[0].lon, r[1].lat, r[1].lon), 0.01)
    }

    @Test
    fun resampleCapsPointCount() {
        val r = RouteMatch.resample(north(), stepM = 1.0, maxPoints = 50)
        assertTrue(r.size <= 51)
    }

    @Test
    fun recordingRateDoesNotMatter() {
        val d = RouteMatch.frechetM(shape(north(everyMs = 1_000)), shape(north(everyMs = 20_000)), 50.0)
        assertNotNull(d)
        assertTrue("was $d", d!! < 1.0)
    }

    @Test
    fun parallelTrackIsAsFarAsItsOffset() {
        val d = RouteMatch.frechetM(shape(north()), shape(north(eastM = 30.0)), 50.0)
        assertEquals(30.0, d!!, 0.5)
    }

    @Test
    fun beyondLimitIsNull() = assertNull(RouteMatch.frechetM(shape(north()), shape(north(eastM = 30.0)), 20.0))

    @Test
    fun oppositeDirectionNeverMatches() =
        assertNull(RouteMatch.frechetM(shape(north()), shape(north().reversed()), 200.0))

    @Test
    fun detourInTheMiddleCounts() {
        // same start and end, but bulges 100 m east halfway
        val detour = north().mapIndexed { i, p ->
            val bulge = 100.0 * (1 - Math.abs(i - 50) / 50.0).coerceAtLeast(0.0)
            p.copy(lon = p.lon + bulge / mPerDegLon)
        }
        assertNull(RouteMatch.frechetM(shape(north()), shape(detour), 50.0))
    }

    @Test
    fun groupsByLeaderAndKeepsDirectionsApart() {
        val shapes = listOf(
            RouteShape(1, shape(north())),
            RouteShape(2, shape(north().reversed())),
            RouteShape(3, shape(north(eastM = 10.0))),
            RouteShape(4, shape(north(eastM = 5.0).reversed())),
            RouteShape(5, shape(north(eastM = 500.0))),
        )
        assertEquals(listOf(listOf(1L, 3L), listOf(2L, 4L), listOf(5L)), RouteMatch.group(shapes, 50.0))
    }

    @Test
    fun hikeJoinsTheNearestLeader() {
        val shapes = listOf(
            RouteShape(1, shape(north())),
            RouteShape(2, shape(north(eastM = 60.0))),
            RouteShape(3, shape(north(eastM = 40.0))),
        )
        assertEquals(listOf(listOf(1L), listOf(2L, 3L)), RouteMatch.group(shapes, 50.0))
    }

    @Test
    fun emptyAndSinglePointShapesStayAlone() {
        val shapes = listOf(RouteShape(1, emptyList()), RouteShape(2, emptyList()), RouteShape(3, shape(north())))
        assertEquals(listOf(listOf(1L), listOf(2L), listOf(3L)), RouteMatch.group(shapes, 50.0))
    }
}
