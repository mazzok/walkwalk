package com.walkwalk.ghost

import com.walkwalk.data.HikeEntity
import com.walkwalk.data.HikeSource
import com.walkwalk.data.HikeStatus
import com.walkwalk.domain.M_PER_DEG_LAT
import com.walkwalk.domain.TrackPoint
import com.walkwalk.domain.p
import com.walkwalk.domain.straightLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostRulesTest {

    private fun hike(id: Long) = HikeEntity(
        id = id, name = "h$id", startTime = 0, endTime = 1,
        status = HikeStatus.FINISHED, source = HikeSource.RECORDED,
    )

    private fun cand(id: Long, first: TrackPoint) = GhostCandidate(hike(id), first)

    private fun northOf(start: TrackPoint, m: Double) = p(0, lat = start.lat + m / M_PER_DEG_LAT, lon = start.lon)

    @Test fun matchesWithinRadiusOnly() {
        val start = p(0)
        val near = cand(1, northOf(start, 100.0))
        val far = cand(2, northOf(start, 200.0))
        val same = cand(3, start)
        assertEquals(listOf(near, same), matchGhosts(start, listOf(near, far, same)))
    }

    @Test fun emptyCandidatesGiveEmpty() {
        assertTrue(matchGhosts(p(0), emptyList()).isEmpty())
    }

    private val ghost = GhostTrack(straightLine(1.0, 5_000, 61))

    @Test fun aheadWhenSlower() {
        assertEquals(30_000L, aheadBehindMs(ghost, 150.0, 180_000))
    }

    @Test fun behindWhenFaster() {
        assertEquals(-30_000L, aheadBehindMs(ghost, 150.0, 120_000))
    }

    @Test fun nullBelowMinDistance() {
        assertNull(aheadBehindMs(ghost, 49.0, 100_000))
    }

    @Test fun beyondEndUsesTotalTime() {
        assertEquals(200_000L, aheadBehindMs(ghost, 400.0, 500_000))
    }

    @Test fun dropsAfterHold() {
        val t = OffRouteTracker(200)
        assertFalse(t.update(0, 250.0))
        assertFalse(t.update(59_000, 250.0))
        assertTrue(t.update(60_000, 250.0))
        assertTrue(t.update(61_000, 0.0))
    }

    @Test fun singleOutlierDoesNotDrop() {
        val t = OffRouteTracker(200)
        assertFalse(t.update(0, 10.0))
        assertFalse(t.update(1_000, 900.0))
        assertFalse(t.update(2_000, 10.0))
        assertFalse(t.update(70_000, 10.0))
    }

    @Test fun returningResetsTimer() {
        val t = OffRouteTracker(200)
        assertFalse(t.update(0, 300.0))
        assertFalse(t.update(50_000, 300.0))
        assertFalse(t.update(55_000, 10.0))
        assertFalse(t.update(56_000, 300.0))
        assertFalse(t.update(100_000, 300.0))
        assertTrue(t.update(116_000, 300.0))
    }

    @Test fun offNeverDrops() {
        val t = OffRouteTracker(0)
        assertFalse(t.update(0, 10_000.0))
        assertFalse(t.update(600_000, 10_000.0))
    }
}
