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
        session = GhostSession(repo, settings, UnconfinedTestDispatcher())
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

    private val oldWalk get() = straightLine(1.0, 10_000, 100) // 990 s, 1 m/s from 47.0
    private val start = listOf(p(0))

    @Test
    fun ghostOffByDefault() = runTest {
        settings.setGhostMode(false)
        seed(oldWalk)
        session.update(1, start, 0, 0.0)
        assertTrue(session.ghosts.value.isEmpty())
    }

    @Test
    fun matchesNearStartOnly() = runTest {
        val near = seed(straightLine(1.0, 10_000, 100, startLat = 47.0 + 100 / M_PER_DEG_LAT))
        seed(straightLine(1.0, 10_000, 100, startLat = 47.0 + 400 / M_PER_DEG_LAT))
        session.update(1, start, 0, 0.0)
        assertEquals(listOf(near), session.ghosts.value.map { it.id })
    }

    @Test
    fun ghostPositionFollowsElapsed() = runTest {
        seed(oldWalk)
        session.update(1, start, 150_000, 150.0)
        val g = session.ghosts.value.single()
        assertEquals(47.0 + 150 / M_PER_DEG_LAT, g.lat, 1e-5)
        assertEquals(0.0, g.deltaMs!!.toDouble(), 1000.0)
        assertNull(g.finishedMs)
    }

    @Test
    fun restoreMidHike() = runTest {
        seed(oldWalk)
        session.update(1, straightLine(1.0, 10_000, 61), 600_000, 600.0)
        assertEquals(47.0 + 600 / M_PER_DEG_LAT, session.ghosts.value.single().lat, 1e-5)
    }

    @Test
    fun finishedLabel() = runTest {
        seed(straightLine(1.0, 10_000, 11)) // 100 s
        session.update(1, start, 300_000, 300.0)
        val g = session.ghosts.value.single()
        assertEquals(100_000L, g.finishedMs)
        assertNotNull(g.deltaMs)
    }

    @Test
    fun noNumberBeforeFiftyMeters() = runTest {
        seed(oldWalk)
        session.update(1, start, 10_000, 10.0)
        val g = session.ghosts.value.single()
        assertNull(g.deltaMs)
    }

    @Test
    fun dropsGhostLeavingRoute() = runTest {
        seed(oldWalk)
        fun far(t: Long) = p(t, 47.0, 11.004)
        session.update(1, start, 0, 0.0)
        assertEquals(1, session.ghosts.value.size)
        session.update(1, start + far(100_000), 100_000, 100.0)
        assertEquals(1, session.ghosts.value.size)
        session.update(1, start + far(161_000), 161_000, 100.0)
        assertTrue(session.ghosts.value.isEmpty())
        session.update(1, start + p(170_000, 47.0 + 170 / M_PER_DEG_LAT), 170_000, 170.0)
        assertTrue(session.ghosts.value.isEmpty())
    }

    @Test
    fun newHikeResetsSession() = runTest {
        seed(oldWalk)
        session.update(1, start, 0, 0.0)
        assertEquals(1, session.ghosts.value.size)
        session.update(2, listOf(p(0, lat = 48.0)), 0, 0.0)
        assertTrue(session.ghosts.value.isEmpty())
    }

    @Test
    fun degenerateOldHikeIgnored() = runTest {
        seed(listOf(p(0), p(10_000), p(20_000)))
        session.update(1, start, 5_000, 80.0)
        session.ghosts.value.forEach {
            assertTrue(it.lat.isFinite() && it.lon.isFinite())
            assertTrue(it.deltaMs == null || it.deltaMs!! > Long.MIN_VALUE)
        }
    }

    @Test
    fun failedFirstUpdateRetriesLoading() = runTest {
        seed(oldWalk)
        val sql = db.openHelper.writableDatabase
        sql.execSQL("ALTER TABLE TrackPointEntity RENAME TO TrackPointEntity_tmp")
        try {
            session.update(1, start, 0, 0.0)
            org.junit.Assert.fail("expected the load to fail")
        } catch (_: Exception) {
        } finally {
            sql.execSQL("ALTER TABLE TrackPointEntity_tmp RENAME TO TrackPointEntity")
        }
        session.update(1, start, 0, 0.0)
        assertEquals(1, session.ghosts.value.size)
    }
}
