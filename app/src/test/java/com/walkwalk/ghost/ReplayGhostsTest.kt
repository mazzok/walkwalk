package com.walkwalk.ghost

import com.walkwalk.domain.SpeedHeatmap
import com.walkwalk.domain.TrackPoint
import com.walkwalk.domain.straightLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGhostsTest {
    private val route = straightLine(1.0, 5_000, 61) // 300 m north in 300 s
    private val ghostTrack = GhostTrack(route)

    /** On the route until 100 s, then 1.5 km east of it. */
    private val divergingHike = route.map { if (it.time >= 105_000) it.copy(lon = it.lon + 0.02) else it }

    private fun ghost(id: Long, vanishAtMs: Long?, points: List<TrackPoint> = route) =
        ReplayGhost(id, "hike $id", GhostTrack(points), SpeedHeatmap.of(points, emptyList()), vanishAtMs)

    @Test
    fun vanishMomentIsHoldAfterFirstFarFix() {
        assertEquals(165_000L, vanishMomentMs(divergingHike, ghostTrack, 200))
    }

    @Test
    fun neverVanishesWhenDropIsOffOrHikeStaysOnRoute() {
        assertNull(vanishMomentMs(divergingHike, ghostTrack, 0))
        assertNull(vanishMomentMs(route, ghostTrack, 200))
    }

    @Test
    fun pauseBetweenSegmentsDoesNotShiftTheVanishClock() {
        val on = route.filter { it.time <= 100_000 }
        val lat = on.last().lat
        val off = straightLine(1.0, 5_000, 15, startTime = 700_000, seg = 1, startLat = lat).map { it.copy(lon = it.lon + 0.02) }
        // segment 1 starts at active time 100 s, so the hold ends at 160 s whatever the length of the pause
        assertEquals(160_000L, vanishMomentMs(on + off, ghostTrack, 200))
    }

    @Test
    fun visibleOnlyBeforeTheVanishMoment() {
        val g = ghost(1, 165_000)
        assertTrue(g.visibleAt(164_999))
        assertFalse(g.visibleAt(165_000))
        assertTrue(g.visibleAt(10_000)) // dragging back brings it back
        assertTrue(ghost(2, null).visibleAt(10_000_000))
    }

    @Test
    fun viewsFollowTheGhostTrackAtTheGivenMoment() {
        val v = replayGhostViews(listOf(ghost(1, null)), 10_000, 0xFFCC3333.toInt()).single()
        val (lat, lon) = ghostTrack.positionAt(10_000)
        assertEquals(lat, v.lat, 0.0)
        assertEquals(lon, v.lon, 0.0)
        assertNull(v.deltaMs)
        assertNull(v.finishedMs)
    }

    @Test
    fun deltaComparesTimeAtTheSameDistanceLikeLiveMode() {
        // 100 m covered in 130 s; the ghost (1 m/s) took 100 s for that distance -> 30 s behind
        val v = replayGhostViews(listOf(ghost(1, null)), 130_000, 0xFFCC3333.toInt(), distanceM = 100.0).single()
        assertEquals(aheadBehindMs(ghostTrack, 100.0, 130_000), v.deltaMs)
        assertEquals(30_000.0, v.deltaMs!!.toDouble(), 1_000.0)
    }

    @Test
    fun noDeltaBeforeTheMinimumDistance() {
        val v = replayGhostViews(listOf(ghost(1, null)), 20_000, 0xFFCC3333.toInt(), distanceM = 20.0).single()
        assertNull(v.deltaMs)
    }

    @Test
    fun vanishedGhostsAreLeftOut() {
        val views = replayGhostViews(listOf(ghost(1, 50_000), ghost(2, null)), 60_000, 0xFFCC3333.toInt())
        assertEquals(listOf(2L), views.map { it.id })
    }

    @Test
    fun colourStaysWithTheGhostWhenAnotherVanishes() {
        val ghosts = listOf(ghost(1, 50_000), ghost(2, null))
        val before = replayGhostViews(ghosts, 10_000, 0xFFCC3333.toInt()).first { it.id == 2L }.colorArgb
        val after = replayGhostViews(ghosts, 60_000, 0xFFCC3333.toInt()).first { it.id == 2L }.colorArgb
        assertEquals(before, after)
    }

    @Test
    fun finishedGhostStaysAtItsEnd() {
        val short = straightLine(1.0, 5_000, 5)
        val v = replayGhostViews(listOf(ghost(1, null, short)), 30_000, 0xFFCC3333.toInt()).single()
        assertEquals(20_000L, v.finishedMs)
        assertEquals(short.last().lat, v.lat, 1e-9)
    }
}
