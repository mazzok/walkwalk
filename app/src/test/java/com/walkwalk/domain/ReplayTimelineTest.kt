package com.walkwalk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayTimelineTest {
    @Test
    fun timelineWithSinglePoint() {
        val t = ReplayTimeline(listOf(p(1000, 47.0, 11.0)), emptyList())
        assertEquals(0L, t.durationMs)
        assertEquals(1000L, t.frameAt(500).clockTime)
    }

    @Test
    fun interpolatesBetweenPoints() {
        val t = ReplayTimeline(listOf(p(0, 47.0, 11.0), p(10_000, 47.001, 11.0)), emptyList())
        val f = t.frameAt(5_000)
        assertEquals(47.0005, f.lat, 1e-6)
        assertEquals(5_000L, f.clockTime)
        assertEquals(55.6, f.distanceM, 0.5)
    }

    @Test
    fun stopIsCompressed() {
        val t = ReplayTimeline(
            walkStandWalk(walkMs = 600_000, standMs = 1_800_000),
            listOf(Stop(600_000, 2_400_000)),
        )
        assertEquals(1_200_000L + ReplayTimeline.STOP_REPLAY_MS, t.durationMs)
        assertTrue(t.frameAt(600_000 + 15_000).inStop)
        assertFalse(t.frameAt(300_000).inStop)
        assertEquals(2_400_000L + 10_000, t.frameAt(600_000 + 30_000 + 10_000).clockTime)
        assertEquals(listOf(600_000L..630_000L), t.stopMarkers)
    }

    @Test
    fun manualPauseGapIsSkipped() {
        val t = ReplayTimeline(twoSegments(), emptyList())
        assertEquals(1_200_000L, t.durationMs)
        assertEquals(1_800_000.0, t.frameAt(600_001).clockTime.toDouble(), 2_000.0)
    }

    @Test
    fun replayMsForDistanceInvertsFrameDistance() {
        val t = ReplayTimeline(twoSegments(), emptyList())
        val r = t.replayMsForDistance(900.0)
        assertEquals(900.0, t.frameAt(r).distanceM, 1.0)
    }

    @Test
    fun activeElapsedSkipsPausesBetweenSegments() {
        val points = straightLine(1.0, 5_000, 5, seg = 0) +
            straightLine(1.0, 5_000, 5, startTime = 620_000, seg = 1, startLat = 47.01)
        val f = ReplayTimeline(points, emptyList()).frameAt(20_000)
        assertEquals(620_000L, f.elapsedMs)
        assertEquals(20_000L, f.activeMs)
    }

    @Test
    fun activeElapsedEqualsElapsedWithoutPauses() {
        val f = ReplayTimeline(straightLine(1.0, 5_000, 20), emptyList()).frameAt(40_000)
        assertEquals(40_000L, f.elapsedMs)
        assertEquals(f.elapsedMs, f.activeMs)
    }

    @Test
    fun activeElapsedKeepsRunningThroughAStop() {
        val t = ReplayTimeline(walkStandWalk(walkMs = 600_000, standMs = 1_800_000), listOf(Stop(600_000, 2_400_000)))
        val f = t.frameAt(600_000 + 15_000) // half way through the compressed stop
        assertEquals(1_500_000L, f.elapsedMs)
        assertEquals(1_500_000L, f.activeMs)
    }

    @Test
    fun frameClampsOutOfRange() {
        val t = ReplayTimeline(twoSegments(), emptyList())
        assertEquals(0L, t.frameAt(-10).elapsedMs)
        assertEquals(2_400_000L, t.frameAt(Long.MAX_VALUE).clockTime)
    }
}
