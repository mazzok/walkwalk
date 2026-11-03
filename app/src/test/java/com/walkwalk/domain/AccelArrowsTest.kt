package com.walkwalk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccelArrowsTest {
    /** Points heading north every [everyMs] with the speed in m/s given by [speedAt] at each fix's time. */
    private fun track(durMs: Long, everyMs: Long = 5_000, speedAt: (Long) -> Double): List<TrackPoint> {
        var lat = 47.0
        return (0..durMs / everyMs).map { i ->
            val t = i * everyMs
            if (i > 0) lat += speedAt(t) * everyMs / 1000.0 / M_PER_DEG_LAT
            p(t, lat, 11.0)
        }
    }

    /** 2 minutes at 1.25 m/s, 1 minute of speeding up to 10 m/s, 5 minutes at 10 m/s. */
    private val speedingUp = track(480_000) { t ->
        when {
            t < 120_000 -> 1.25
            t < 180_000 -> 1.25 + (t - 120_000) / 60_000.0 * 8.75
            else -> 10.0
        }
    }

    /** 2 minutes at 10 m/s, 1 minute of slowing to 1.25 m/s, 5 minutes at 1.25 m/s. */
    private val slowingDown = track(480_000) { t ->
        when {
            t < 120_000 -> 10.0
            t < 180_000 -> 10.0 - (t - 120_000) / 60_000.0 * 8.75
            else -> 1.25
        }
    }

    private fun gapsM(arrows: List<AccelArrow>) = arrows.zipWithNext { a, b -> Geo.distance(a.lat, a.lon, b.lat, b.lon) }

    @Test
    fun steadyWalkGetsSmallArrowsFarApart() {
        val arrows = AccelArrows.of(track(2_000_000) { 1.25 }, emptyList()) // 2.5 km
        assertTrue(arrows.size in 8..12)
        assertTrue(arrows.all { Math.abs(it.size - AccelArrows.MIN_SIZE) < 1e-9 })
    }

    @Test
    fun speedingUpGetsBigArrowsCloseTogether() {
        val arrows = AccelArrows.of(speedingUp, emptyList())
        val biggest = arrows.indices.maxByOrNull { arrows[it].size }!!
        assertTrue(arrows[biggest].size > 0.95)
        val gaps = gapsM(arrows)
        assertTrue(gaps[biggest - 1] < 50 && gaps[biggest] < 50)
        // on the steady stretch afterwards they are small and far apart again
        assertEquals(AccelArrows.MIN_SIZE, arrows.last().size, 1e-9)
        assertTrue(gaps.last() > 200)
    }

    @Test
    fun slowingDownGetsBigArrowsCloseTogetherToo() {
        val arrows = AccelArrows.of(slowingDown, emptyList())
        val biggest = arrows.indices.maxByOrNull { arrows[it].size }!!
        assertTrue(arrows[biggest].size > 0.95)
        val gaps = gapsM(arrows)
        assertTrue(gaps[biggest - 1] < 50 && gaps[biggest] < 50)
        assertEquals(AccelArrows.MIN_SIZE, arrows.last().size, 1e-9)
    }

    @Test
    fun aMilderChangeGetsSmallerArrowsThanAStrongOne() {
        // hard speeding up at 120-180 s, a mild slowing down (10 to 6 m/s) at 360-420 s
        val pts = track(600_000) { t ->
            when {
                t < 120_000 -> 1.25
                t < 180_000 -> 1.25 + (t - 120_000) / 60_000.0 * 8.75
                t < 360_000 -> 10.0
                t < 420_000 -> 10.0 - (t - 360_000) / 60_000.0 * 4.0
                else -> 6.0
            }
        }
        val arrows = AccelArrows.of(pts, emptyList())
        val slowFrom = pts.first { it.time >= 360_000 }.lat
        val slowTo = pts.first { it.time >= 420_000 }.lat
        val strong = arrows.filter { it.lat < slowFrom }.maxOf { it.size }
        val mild = arrows.filter { it.lat in slowFrom..slowTo }.maxOf { it.size }
        assertTrue(strong > 0.95)
        assertTrue(mild in 0.35..0.8)
        assertTrue(mild < strong - 0.2)
    }

    @Test
    fun arrowsPointAlongTheWalkingDirection() {
        val north = AccelArrows.of(track(600_000) { 1.25 }, emptyList())
        assertTrue(north.all { Math.abs(it.bearingDeg) < 1.0 || Math.abs(it.bearingDeg - 360) < 1.0 })
        val east = (0..120).map { p(it * 5_000L, 47.0, 11.0 + 6.25 * it / 75_000.0) } // about 1.25 m/s due east
        val arrows = AccelArrows.of(east, emptyList())
        assertTrue(arrows.isNotEmpty())
        assertTrue(arrows.all { Math.abs(it.bearingDeg - 90.0) < 1.0 })
    }

    @Test
    fun restingStretchesGetNoArrows() {
        val pts = walkStandWalk()
        val standLat = pts.first { it.time == 300_000L }.lat
        val arrows = AccelArrows.of(pts, listOf(Stop(300_000, 905_000)))
        assertTrue(arrows.isNotEmpty())
        assertTrue(arrows.none { Math.abs(it.lat - standLat) < 1e-5 })
    }

    @Test
    fun tooFewFixesGiveNoArrows() {
        assertTrue(AccelArrows.of(emptyList(), emptyList()).isEmpty())
        assertTrue(AccelArrows.of(listOf(p(0)), emptyList()).isEmpty())
    }
}
