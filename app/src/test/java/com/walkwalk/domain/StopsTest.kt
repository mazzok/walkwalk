package com.walkwalk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StopsTest {
    @Test
    fun detectsLunchStop() {
        val stops = Stops.detect(walkStandWalk())
        assertEquals(1, stops.size)
        assertEquals(600_000.0, (stops[0].end - stops[0].start).toDouble(), 15_000.0)
    }

    @Test
    fun shortPauseIsNotAStop() = assertTrue(Stops.detect(walkStandWalk(standMs = 60_000)).isEmpty())

    @Test
    fun steadyWalkHasNoStops() = assertTrue(Stops.detect(straightLine(1.0, 5_000, 200)).isEmpty())
}
