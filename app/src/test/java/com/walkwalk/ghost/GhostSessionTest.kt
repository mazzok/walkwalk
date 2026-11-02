package com.walkwalk.ghost

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
import com.walkwalk.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GhostSessionTest {
    private lateinit var db: WalkwalkDb
    private lateinit var repo: HikeRepository
    private lateinit var settings: AppSettings
    private lateinit var session: GhostSession

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, WalkwalkDb::class.java).allowMainThreadQueries().build()
        repo = HikeRepository(db)
        val prefs = ctx.getSharedPreferences("ghost-session-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        settings = AppSettings(prefs)
        settings.setGhostMode(true)
        session = GhostSession(repo, settings, UnconfinedTestDispatcher(), clock = { clockMs })
    }

    @After
    fun tearDown() = db.close()

    private var clockMs = 0L

    /** One per-second update, then wait for a load it may have started (the load runs in the session's own scope). */
    private suspend fun GhostSession.tick(hikeId: Long, track: List<TrackPoint>, elapsedMs: Long, distanceM: Double) {
        update(hikeId, track, elapsedMs, distanceM)
        awaitLoad()
    }

    private suspend fun seed(points: List<TrackPoint>): Long {
        val id = db.hikeDao().insertHike(
            HikeEntity(name = "old", startTime = 0, endTime = null, status = HikeStatus.FINISHED, source = HikeSource.RECORDED),
        )
        db.hikeDao().insertPoints(points.map { TrackPointEntity.from(id, it) })
        return id
    }

    private val oldWalk get() = straightLine(1.0, 10_000, 100) // 990 s, 1 m/s from 47.0
    private val start = listOf(p(0))

    @Test
    fun ghostOffByDefault() = runTest {
        settings.setGhostMode(false)
        seed(oldWalk)
        session.tick(1, start, 0, 0.0)
        assertTrue(session.state.value.views.isEmpty())
    }

    @Test
    fun matchesNearStartOnly() = runTest {
        val near = seed(straightLine(1.0, 10_000, 100, startLat = 47.0 + 100 / M_PER_DEG_LAT))
        seed(straightLine(1.0, 10_000, 100, startLat = 47.0 + 400 / M_PER_DEG_LAT))
        session.tick(1, start, 0, 0.0)
        assertEquals(listOf(near), session.state.value.views.map { it.id })
    }

    @Test
    fun ghostPositionFollowsElapsed() = runTest {
        seed(oldWalk)
        session.tick(1, start, 150_000, 150.0)
        val g = session.state.value.views.single()
        assertEquals(47.0 + 150 / M_PER_DEG_LAT, g.lat, 1e-5)
        assertEquals(0.0, g.deltaMs!!.toDouble(), 1000.0)
        assertNull(g.finishedMs)
    }

    @Test
    fun restoreMidHike() = runTest {
        seed(oldWalk)
        session.tick(1, straightLine(1.0, 10_000, 61), 600_000, 600.0)
        assertEquals(47.0 + 600 / M_PER_DEG_LAT, session.state.value.views.single().lat, 1e-5)
    }

    @Test
    fun finishedLabel() = runTest {
        seed(straightLine(1.0, 10_000, 11)) // 100 s
        session.tick(1, start, 300_000, 300.0)
        val g = session.state.value.views.single()
        assertEquals(100_000L, g.finishedMs)
        assertNotNull(g.deltaMs)
    }

    @Test
    fun noNumberBeforeFiftyMeters() = runTest {
        seed(oldWalk)
        session.tick(1, start, 10_000, 10.0)
        val g = session.state.value.views.single()
        assertNull(g.deltaMs)
    }

    @Test
    fun dropsGhostLeavingRoute() = runTest {
        seed(oldWalk)
        fun far(t: Long) = p(t, 47.0, 11.004)
        session.tick(1, start, 0, 0.0)
        assertEquals(1, session.state.value.views.size)
        session.tick(1, start + far(100_000), 100_000, 100.0)
        assertEquals(1, session.state.value.views.size)
        session.tick(1, start + far(161_000), 161_000, 100.0)
        assertTrue(session.state.value.views.isEmpty())
        session.tick(1, start + p(170_000, 47.0 + 170 / M_PER_DEG_LAT), 170_000, 170.0)
        assertTrue(session.state.value.views.isEmpty())
    }

    @Test
    fun newHikeResetsSession() = runTest {
        seed(oldWalk)
        session.tick(1, start, 0, 0.0)
        assertEquals(1, session.state.value.views.size)
        session.tick(2, listOf(p(0, lat = 48.0)), 0, 0.0)
        assertTrue(session.state.value.views.isEmpty())
    }

    @Test
    fun degenerateOldHikeIgnored() = runTest {
        seed(listOf(p(0), p(10_000), p(20_000)))
        session.tick(1, start, 5_000, 80.0)
        session.state.value.views.forEach {
            assertTrue(it.lat.isFinite() && it.lon.isFinite())
            assertTrue(it.deltaMs == null || it.deltaMs!! > Long.MIN_VALUE)
        }
    }

    private fun breakPoints() = db.openHelper.writableDatabase.execSQL("ALTER TABLE TrackPointEntity RENAME TO TrackPointEntity_tmp")
    private fun fixPoints() = db.openHelper.writableDatabase.execSQL("ALTER TABLE TrackPointEntity_tmp RENAME TO TrackPointEntity")

    @Test
    fun failedFirstUpdateRetriesLoading() = runTest {
        seed(oldWalk)
        breakPoints()
        try {
            session.tick(1, start, 0, 0.0) // the load fails in the background; the update itself does not throw
        } finally {
            fixPoints()
        }
        assertTrue(session.state.value.views.isEmpty())
        clockMs = GhostSession.LOAD_RETRY_MS
        session.tick(1, start, 0, 0.0)
        assertEquals(1, session.state.value.views.size)
    }

    @Test
    fun noRetryDuringBackoffThenRetry() = runTest {
        seed(oldWalk)
        var loads = 0
        session.onLoadStart = { loads++ }
        breakPoints()
        try {
            session.tick(1, start, 0, 0.0)
        } finally {
            fixPoints()
        }
        assertEquals(1, loads)
        clockMs = GhostSession.LOAD_RETRY_MS - 1
        session.tick(1, start, 0, 0.0)
        assertEquals(1, loads)
        assertTrue(session.state.value.views.isEmpty())
        clockMs = GhostSession.LOAD_RETRY_MS
        session.tick(1, start, 0, 0.0)
        assertEquals(2, loads)
        assertEquals(1, session.state.value.views.size)
    }

    @Test
    fun loadSurvivesRepeatedAndCancelledUpdates() = runTest {
        seed(oldWalk)
        var loads = 0
        val gate = CompletableDeferred<Unit>()
        session.onLoadStart = { loads++; gate.await() }
        session.update(1, start, 0, 0.0)
        // the screen's per-second effect cancels its update every tick; that must not cancel the load
        val cancelled = launch(UnconfinedTestDispatcher(testScheduler)) { session.update(1, start, 1_000, 1.0) }
        cancelled.cancel()
        session.update(1, start, 2_000, 2.0)
        assertEquals(1, loads)
        assertTrue(session.state.value.views.isEmpty())
        gate.complete(Unit)
        session.awaitLoad()
        assertEquals(1, loads)
        assertEquals(1, session.state.value.views.size)
        // the load renders with the latest inputs, without waiting for the next tick
        assertEquals(47.0 + 2 / M_PER_DEG_LAT, session.state.value.views.single().lat, 1e-5)
    }

    @Test
    fun newHikeCancelsPendingLoadAndStartsOver() = runTest {
        seed(oldWalk)
        var loads = 0
        val never = CompletableDeferred<Unit>()
        session.onLoadStart = { loads++; if (loads == 1) never.await() }
        session.update(1, start, 0, 0.0)
        assertEquals(1, loads)
        session.tick(2, start, 0, 0.0)
        assertEquals(2, loads)
        assertEquals(1, session.state.value.views.size)
        never.complete(Unit) // the cancelled hike-1 load must not come back and overwrite hike 2
        session.awaitLoad()
        assertEquals(2, loads)
    }

    @Test
    fun stateIsTaggedWithItsHike() = runTest {
        seed(oldWalk)
        session.tick(1, start, 0, 0.0)
        // a screen opened for hike 2 must not show hike 1's ghosts before its first update lands
        assertEquals(1, session.state.value.viewsFor(1).size)
        assertTrue(session.state.value.viewsFor(2).isEmpty())
        session.tick(2, start, 0, 0.0)
        assertEquals(1, session.state.value.viewsFor(2).size)
        assertTrue(session.state.value.viewsFor(1).isEmpty())
    }

    @Test
    fun newHikeClearsBackoff() = runTest {
        seed(oldWalk)
        breakPoints()
        try {
            session.tick(1, start, 0, 0.0)
        } finally {
            fixPoints()
        }
        session.tick(2, start, 0, 0.0)
        assertEquals(1, session.state.value.views.size)
    }
}
