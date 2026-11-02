package com.walkwalk.ghost

import com.walkwalk.domain.M_PER_DEG_LAT
import com.walkwalk.domain.straightLine
import com.walkwalk.domain.walkStandWalk
import com.walkwalk.domain.p
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class GhostTrackTest {

    @Test
    fun positionIsRealTime() {
        val g = GhostTrack(straightLine(1.0, 5_000, 61))
        assertEquals(300_000L, g.totalMs)
        assertEquals(47.0 + 150 / M_PER_DEG_LAT, g.positionAt(150_000).first, 1e-6)
    }

    @Test
    fun pauseGapIsExcluded() {
        val seg0 = straightLine(1.0, 5_000, 21)
        val startLat = 47.0 + 500 / M_PER_DEG_LAT
        val seg1 = straightLine(1.0, 5_000, 21, startTime = 1_000_000, seg = 1, startLat = startLat)
        val g = GhostTrack(seg0 + seg1)
        assertEquals(200_000L, g.totalMs)
        assertEquals(startLat + 50 / M_PER_DEG_LAT, g.positionAt(150_000).first, 1e-6)
    }

    @Test
    fun standStillIsKept() {
        val pts = walkStandWalk()
        val g = GhostTrack(pts)
        assertEquals(pts.last().time - pts.first().time, g.totalMs)
    }

    @Test
    fun elapsedAtDistanceMatchesPosition() {
        val g = GhostTrack(straightLine(1.0, 5_000, 61))
        assertEquals(150_000.0, g.elapsedAtDistance(150.0).toDouble(), 1_000.0)
        assertEquals(300_000L, g.elapsedAtDistance(10_000.0))
        assertEquals(0L, g.elapsedAtDistance(0.0))
    }

    @Test
    fun distanceToTrack() {
        val pts = straightLine(1.0, 5_000, 61)
        val g = GhostTrack(pts)
        val midLat = 47.0 + 150 / M_PER_DEG_LAT
        val east = 11.0 + 100 / (M_PER_DEG_LAT * cos(Math.toRadians(47.0)))
        assertEquals(100.0, g.distanceToTrackM(midLat, east), 2.0)
        val last = pts.last()
        assertEquals(50.0, g.distanceToTrackM(last.lat + 50 / M_PER_DEG_LAT, last.lon), 1.0)
    }

    @Test
    fun degenerateTracksDoNotBlowUp() {
        val single = GhostTrack(listOf(p(1_000, 47.0, 11.0)))
        assertEquals(0L, single.totalMs)
        assertEquals(47.0 to 11.0, single.positionAt(5_000))
        val d = single.distanceToTrackM(47.0 + 50 / M_PER_DEG_LAT, 11.0)
        assertEquals(50.0, d, 1.0)
        assertFalse(d.isNaN())

        val twin = GhostTrack(listOf(p(1_000, 47.0, 11.0), p(1_000, 47.0, 11.0)))
        assertEquals(0L, twin.elapsedAtDistance(10.0))
        assertTrue(twin.distanceToTrackM(47.001, 11.001).isFinite())
    }
}
