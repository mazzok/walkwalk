package com.walkwalk.ui

import com.walkwalk.domain.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveElapsedTest {
    private fun fix(time: Long, segment: Int) = TrackPoint(time, 47.0, 11.0, null, null, null, segment)

    @Test
    fun ticksBetweenFixesInCurrentSegment() {
        assertEquals(10_000 + 5_000L, liveElapsedMs(10_000, recording = true, last = fix(100_000, 0), segment = 0, now = 105_000))
    }

    @Test
    fun tickCappedAtSixtySeconds() {
        assertEquals(10_000 + 60_000L, liveElapsedMs(10_000, recording = true, last = fix(100_000, 0), segment = 0, now = 400_000))
    }

    @Test
    fun clockSkewNeverSubtracts() {
        assertEquals(10_000L, liveElapsedMs(10_000, recording = true, last = fix(100_000, 0), segment = 0, now = 90_000))
    }

    @Test
    fun resumedWithoutNewFixDoesNotJump() {
        // paused 5 min after the last fix of segment 0, then resumed into segment 1 with no fix yet
        assertEquals(10_000L, liveElapsedMs(10_000, recording = true, last = fix(100_000, 0), segment = 1, now = 100_000 + 300_000 + 2_000))
    }

    @Test
    fun firstFixOfResumedSegmentTicksAgain() {
        assertEquals(10_000 + 3_000L, liveElapsedMs(10_000, recording = true, last = fix(500_000, 1), segment = 1, now = 503_000))
    }

    @Test
    fun pausedAddsNothing() {
        assertEquals(10_000L, liveElapsedMs(10_000, recording = false, last = fix(100_000, 0), segment = 0, now = 130_000))
    }

    @Test
    fun noFixAddsNothing() {
        assertEquals(0L, liveElapsedMs(0, recording = true, last = null, segment = 0, now = 130_000))
    }
}
