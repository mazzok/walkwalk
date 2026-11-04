package com.walkwalk.race

import com.walkwalk.domain.straightLine
import com.walkwalk.ghost.GhostTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceRulesTest {
    private fun candidate(id: Long, points: List<com.walkwalk.domain.TrackPoint>) = RaceCandidate(id, "hike $id", GhostTrack(points))

    @Test
    fun pickRivalTakesHighestPaceOverStopsIncludedClock() {
        val steady = candidate(1, straightLine(0.9, 5_000, 61)) // 0.9 m/s throughout
        // walks 1 m/s but stands still 600 s: 300 m in 900 s overall
        val walk = straightLine(1.0, 5_000, 61)
        val stand = (1..120).map { i -> walk.last().copy(time = 300_000L + i * 5_000) }
        val breaks = candidate(2, walk + stand)
        assertSame(steady, pickRival(listOf(breaks, steady)))
    }

    @Test
    fun droppedCandidatesAreSkipped() {
        val slow = candidate(1, straightLine(0.8, 5_000, 61))
        val fast = candidate(2, straightLine(1.2, 5_000, 61))
        fast.dropped = true
        assertSame(slow, pickRival(listOf(slow, fast)))
        slow.dropped = true
        assertNull(pickRival(listOf(slow, fast)))
    }

    @Test
    fun observeDropsAfterHoldBeyond100m() {
        val route = straightLine(1.0, 5_000, 61)
        val c = candidate(1, route)
        val farLon = 11.0 + 0.002 // ~152 m east at 47° N
        for (t in 0L..59L) c.observe(t * 1_000, 47.0, farLon)
        assertFalse(c.dropped)
        c.observe(60_000, 47.0, farLon)
        assertTrue(c.dropped)

        val near = candidate(2, route)
        for (t in 0L..120L) near.observe(t * 1_000, 47.0, 11.0 + 0.0012) // ~91 m
        assertFalse(near.dropped)
    }
}
