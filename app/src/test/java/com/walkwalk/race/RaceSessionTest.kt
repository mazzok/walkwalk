package com.walkwalk.race

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.walkwalk.data.HikeEntity
import com.walkwalk.data.HikeRepository
import com.walkwalk.data.HikeSource
import com.walkwalk.data.HikeStatus
import com.walkwalk.data.TrackPointEntity
import com.walkwalk.data.WalkwalkDb
import com.walkwalk.domain.M_PER_DEG_LAT
import com.walkwalk.domain.TrackPoint
import com.walkwalk.domain.p
import com.walkwalk.domain.straightLine
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos

@RunWith(RobolectricTestRunner::class)
class RaceSessionTest {
    private lateinit var db: WalkwalkDb
    private lateinit var repo: HikeRepository
    private lateinit var session: RaceSession

    private val me = 1_000L // the hike being raced; never a candidate
    private val mPerDegLon = M_PER_DEG_LAT * cos(Math.toRadians(47.0))

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, WalkwalkDb::class.java).allowMainThreadQueries().build()
        repo = HikeRepository(db)
        session = RaceSession(repo, UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(points: List<TrackPoint>): Long {
        val id = db.hikeDao().insertHike(
            HikeEntity(name = "old", startTime = 0, endTime = null, status = HikeStatus.FINISHED, source = HikeSource.RECORDED),
        )
        db.hikeDao().insertPoints(points.map { TrackPointEntity.from(id, it) })
        return id
    }

    private suspend fun tick(track: List<TrackPoint>, elapsedMs: Long, distanceM: Double, paused: Boolean = false) {
        session.update(me, track, elapsedMs, distanceM, paused)
        session.awaitLoad()
    }

    /** User walking north at 1 m/s, a point every 10 s, up to [untilS]. */
    private fun northWalk(untilS: Int) = straightLine(1.0, 10_000, untilS / 10 + 1)

    /** Goes 100 m north at [speed], then turns east. */
    private fun northThenEast(speed: Double) = (0 until 100).map { i ->
        val t = i * 10_000L
        val d = speed * t / 1000
        if (d <= 100) p(t, 47.0 + d / M_PER_DEG_LAT, 11.0) else p(t, 47.0 + 100 / M_PER_DEG_LAT, 11.0 + (d - 100) / mPerDegLon)
    }

    @Test
    fun picksFastestMatchingStart() = runTest {
        seed(straightLine(1.0, 10_000, 100))
        val fast = seed(straightLine(1.2, 10_000, 100))
        seed(straightLine(2.0, 10_000, 100, startLat = 47.05)) // starts 5 km away
        tick(listOf(p(0)), 0, 0.0)
        assertEquals(fast, session.state.value.rivalId)
        assertNull(session.state.value.event)
    }

    @Test
    fun gapIsAheadBehindOfRival() = runTest {
        seed(straightLine(1.0, 10_000, 100))
        tick(listOf(p(0)), 0, 0.0)
        tick(listOf(p(0), p(130_000, 47.0 + 100 / M_PER_DEG_LAT)), 130_000, 100.0)
        assertEquals(30_000.0, session.state.value.gapMs!!.toDouble(), 1_000.0)
    }

    @Test
    fun noRivalWhenNothingMatches() = runTest {
        tick(listOf(p(0)), 0, 0.0)
        assertEquals(RaceEvent.NoRival, session.state.value.event)
        assertNull(session.state.value.rivalId)
        val seq = session.state.value.eventSeq
        tick(listOf(p(0), p(10_000)), 10_000, 0.0)
        assertEquals(seq, session.state.value.eventSeq)
    }

    @Test
    fun switchesWhenRivalRouteLeft() = runTest {
        val slow = seed(straightLine(1.0, 10_000, 100))
        val fast = seed(northThenEast(1.2))
        val walk = northWalk(400)
        tick(walk.take(1), 0, 0.0)
        assertEquals(fast, session.state.value.rivalId)
        val seq0 = session.state.value.eventSeq
        for (n in 2..walk.size) tick(walk.take(n), walk[n - 1].time, (n - 1) * 10.0)
        assertEquals(slow, session.state.value.rivalId)
        assertEquals(RaceEvent.Switched(slow), session.state.value.event)
        assertEquals(seq0 + 1, session.state.value.eventSeq)
    }

    @Test
    fun allDroppedGivesNoRivalOnce() = runTest {
        seed(straightLine(1.0, 10_000, 100))
        seed(straightLine(1.2, 10_000, 100))
        val west = (0..30).map { i -> p(i * 10_000L, 47.0, 11.0 - i * 10.0 / mPerDegLon) }
        tick(west.take(1), 0, 0.0)
        for (n in 2..west.size) tick(west.take(n), west[n - 1].time, (n - 1) * 10.0)
        assertEquals(RaceEvent.NoRival, session.state.value.event)
        assertNull(session.state.value.rivalId)
        val seq = session.state.value.eventSeq
        for (n in 1..5) tick(west, 300_000L + n * 10_000, 300.0 + n * 10)
        assertEquals(seq, session.state.value.eventSeq)
    }

    @Test
    fun restoreReplaysTrackHistory() = runTest {
        val slow = seed(straightLine(1.0, 10_000, 100))
        seed(northThenEast(1.2))
        // the first update after a restart already carries 400 s that left the fast route long ago
        val walk = northWalk(400)
        tick(walk, 400_000, 400.0)
        assertEquals(slow, session.state.value.rivalId)
        assertNull(session.state.value.event)
    }

    @Test
    fun rivalFinishedGapKeepsGrowing() = runTest {
        seed(straightLine(1.0, 10_000, 21)) // 200 m in 200 s
        val end = 47.0 + 200 / M_PER_DEG_LAT
        tick(listOf(p(0)), 0, 0.0)
        tick(listOf(p(0), p(300_000, end)), 300_000, 250.0)
        assertEquals(100_000L, session.state.value.gapMs)
        tick(listOf(p(0), p(320_000, end)), 320_000, 260.0)
        assertTrue(session.state.value.gapMs!! > 100_000L)
    }

    @Test
    fun pausedFlagPassedThrough() = runTest {
        seed(straightLine(1.0, 10_000, 100))
        tick(listOf(p(0)), 0, 0.0, paused = true)
        assertTrue(session.state.value.paused)
    }

    @Test
    fun resetForgetsTheRace() = runTest {
        seed(straightLine(1.0, 10_000, 100))
        tick(listOf(p(0)), 0, 0.0)
        session.reset()
        assertNull(session.state.value.rivalId)
        assertNull(session.state.value.hikeId)
    }
}
