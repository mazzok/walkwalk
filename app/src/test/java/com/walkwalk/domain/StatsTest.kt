package com.walkwalk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsTest {
    @Test
    fun statsOfEmptyAndSinglePointTrack() {
        val e = Stats.compute(emptyList(), emptyList())
        assertEquals(0.0, e.distanceM, 0.0)
        assertEquals(0L, e.totalMs)
        assertNull(e.maxAltM)
        val one = Stats.compute(listOf(p(0, 47.0, 11.0, alt = 900.0)), emptyList())
        assertEquals(0L, one.totalMs)
        assertEquals(900.0, one.maxAltM!!, 0.0)
    }

    @Test
    fun manualPauseExcludedStopsExcludedFromMoving() {
        val s = Stats.compute(twoSegments(), listOf(Stop(100_000, 220_000)))
        assertEquals(1_200_000L, s.totalMs)
        assertEquals(1_080_000L, s.movingMs)
    }

    @Test
    fun steadyWalkSpeed() {
        val speeds = Stats.speedsKmh(straightLine(metersPerSec = 1.25, everyMs = 2000, count = 100))
        assertEquals(4.5, speeds[50], 0.1)
    }

    @Test
    fun sparseFixesStillGetASpeed() {
        // fixes every 20 s are further apart than half the 30 s window
        val speeds = Stats.speedsKmh(straightLine(metersPerSec = 1.25, everyMs = 20_000, count = 10))
        speeds.forEach { assertEquals(4.5, it, 0.1) }
    }

    @Test
    fun aLoneFixInItsSegmentHasNoSpeed() {
        val pts = straightLine(1.25, 20_000, 4) + p(500_000, 47.1, 11.0, seg = 1)
        assertEquals(0.0, Stats.speedsKmh(pts).last(), 0.0)
    }

    @Test
    fun averageSpeedUsesMovingTime() {
        // 1200 m in 1200 s total, 120 s stopped -> 1200 m / 1080 s = 4.0 km/h
        val s = Stats.compute(twoSegments(), listOf(Stop(100_000, 220_000)))
        assertEquals(1200.0, s.distanceM, 2.0)
        assertEquals(4.0, s.avgSpeedKmh, 0.05)
    }
}
