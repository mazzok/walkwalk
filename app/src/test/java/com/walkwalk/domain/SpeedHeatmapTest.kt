package com.walkwalk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedHeatmapTest {
    private val blue = 0xFF0000FF.toInt()
    private val red = 0xFFFF0000.toInt()

    @Test
    fun slowestIsBlueAndFastestIsRed() {
        assertEquals(blue, SpeedHeatmap.colorFor(2.0, 2.0, 6.0))
        assertEquals(red, SpeedHeatmap.colorFor(6.0, 2.0, 6.0))
    }

    @Test
    fun colorsBlendLinearlyBetweenTheExtremes() {
        assertEquals(0xFF800080.toInt(), SpeedHeatmap.colorFor(4.0, 2.0, 6.0))
        assertEquals(0xFF4000BF.toInt(), SpeedHeatmap.colorFor(3.0, 2.0, 6.0))
    }

    @Test
    fun speedsOutsideTheRangeAreClamped() {
        assertEquals(blue, SpeedHeatmap.colorFor(1.0, 2.0, 6.0))
        assertEquals(red, SpeedHeatmap.colorFor(9.0, 2.0, 6.0))
    }

    @Test
    fun flatRangeIsBlue() {
        assertEquals(blue, SpeedHeatmap.colorFor(4.0, 4.0, 4.0))
    }

    @Test
    fun oneEdgePerConsecutivePointPair() {
        val heat = SpeedHeatmap.of(straightLine(1.0, 5_000, 10), emptyList())
        assertEquals(9, heat.edges.size)
    }

    @Test
    fun noEdgeCrossesASegmentBreak() {
        val pts = straightLine(1.0, 5_000, 4, seg = 0) + straightLine(1.0, 5_000, 4, startTime = 100_000, seg = 1, startLat = 47.1)
        val heat = SpeedHeatmap.of(pts, emptyList())
        assertEquals(6, heat.edges.size)
    }

    @Test
    fun constantSpeedGivesAFlatRange() {
        val heat = SpeedHeatmap.of(straightLine(1.0, 5_000, 20), emptyList())
        assertEquals(3.6, heat.minKmh, 0.1)
        assertEquals(3.6, heat.maxKmh, 0.1)
    }

    @Test
    fun edgesInsideAStopAreRestingAndOutOfTheRange() {
        val pts = walkStandWalk()
        val heat = SpeedHeatmap.of(pts, listOf(Stop(300_000, 900_000)))
        val resting = heat.edges.filter { it.speedKmh == null }
        assertTrue(resting.isNotEmpty())
        assertTrue(resting.all { it.to.time >= 300_000 && it.from.time <= 900_000 })
        // nothing inside the stop is coloured, so it cannot set either end of the range
        val moving = heat.edges.filter { it.speedKmh != null }
        assertTrue(moving.all { it.to.time <= 300_000 || it.from.time >= 900_000 })
    }

    @Test
    fun zeroSpeedEdgesAreResting() {
        val still = (0 until 6).map { p(it * 5_000L) }
        val heat = SpeedHeatmap.of(still, emptyList())
        assertTrue(heat.edges.all { it.speedKmh == null })
    }

    @Test
    fun movingEdgesCarryTheirSpeed() {
        val heat = SpeedHeatmap.of(straightLine(2.0, 5_000, 20), emptyList())
        assertNotNull(heat.edges.first().speedKmh)
        assertEquals(7.2, heat.edges[10].speedKmh!!, 0.2)
    }

    @Test
    fun emptyOrSinglePointTrackHasNoEdges() {
        assertTrue(SpeedHeatmap.of(emptyList(), emptyList()).edges.isEmpty())
        assertNull(SpeedHeatmap.of(listOf(p(0)), emptyList()).edges.firstOrNull())
    }
}
