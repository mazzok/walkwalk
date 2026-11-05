package com.walkwalk.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.walkwalk.data.HikeRepository
import com.walkwalk.data.WalkwalkDb
import com.walkwalk.domain.TrackPoint
import com.walkwalk.domain.straightLine
import com.walkwalk.ghost.GhostTrack
import com.walkwalk.ghost.ReplayGhost
import com.walkwalk.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReplayViewModelGhostsTest {
    private lateinit var db: WalkwalkDb
    private lateinit var repo: HikeRepository
    private lateinit var settings: AppSettings

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        val direct = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalkwalkDb::class.java)
            .setQueryExecutor(direct).setTransactionExecutor(direct).allowMainThreadQueries().build()
        repo = HikeRepository(db)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        settings = AppSettings(ctx.getSharedPreferences("replay-ghosts-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        settings.setGhostMode(true)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun record(points: List<TrackPoint>, start: Long = 0): Long {
        val id = repo.startHike(start)
        points.forEach { repo.appendPoint(id, it) }
        repo.finish(id, start + points.last().time)
        return id
    }

    private fun TestScope.replay(id: Long): ReplayViewModel =
        ReplayViewModel(repo, id, settings, ghostDispatcher = Dispatchers.Main).also { advanceUntilIdle() }

    /** 1.25 m/s north for 1200 s. */
    private val walk = straightLine(1.25, 2_000, 600)

    /** The same walk until 200 s, then 1.25 m/s due east: more than 200 m from the route from 362 s on. */
    private val divergingWalk = run {
        val turn = walk.first { it.time >= 200_000 }
        val mPerDegLon = 111_195.0 * Math.cos(Math.toRadians(turn.lat))
        walk.map { if (it.time <= 200_000) it else it.copy(lat = turn.lat, lon = turn.lon + (it.time - 200_000) / 1000.0 * 1.25 / mPerDegLon) }
    }

    /** 4 m/s north for 1200 s. */
    private val fastRoute = straightLine(4.0, 2_000, 600)

    @Test
    fun ghostsFollowRealElapsedTime() = runTest {
        val ghostId = record(straightLine(2.0, 2_000, 300), start = 1)
        val vm = replay(record(walk))
        vm.seek(60_000)
        val v = vm.ghostViews.value.single()
        val (lat, lon) = GhostTrack(straightLine(2.0, 2_000, 300)).positionAt(vm.frame.value!!.activeMs)
        assertEquals(ghostId, v.id)
        assertEquals(lat, v.lat, 1e-9)
        assertEquals(lon, v.lon, 1e-9)
    }

    @Test
    fun newerNearbyHikesAreGhostsToo() = runTest {
        val vm = replay(record(walk, start = 0).also { record(straightLine(2.0, 2_000, 300), start = 9_000_000) })
        assertEquals(1, vm.ghostViews.value.size)
    }

    @Test
    fun replayedHikeIsNotItsOwnGhost() = runTest {
        val vm = replay(record(walk))
        assertTrue(vm.ghostViews.value.isEmpty())
    }

    @Test
    fun farAwayHikesAreNotGhosts() = runTest {
        record(straightLine(2.0, 2_000, 300, startLat = 47.0 + 200.0 / 111_195.0), start = 1)
        val vm = replay(record(walk))
        assertTrue(vm.ghostViews.value.isEmpty())
    }

    @Test
    fun ghostModeOffMeansNoGhosts() = runTest {
        record(straightLine(2.0, 2_000, 300), start = 1)
        settings.setGhostMode(false)
        val vm = replay(record(walk))
        assertTrue(vm.ghostViews.value.isEmpty())
    }

    @Test
    fun dropOffKeepsEveryGhost() = runTest {
        record(fastRoute, start = 1)
        settings.setGhostDropM(0)
        val vm = replay(record(divergingWalk))
        vm.seek(900_000)
        assertEquals(1, vm.ghostViews.value.size)
    }

    @Test
    fun ghostVanishesAndReturnsWhenScrubbing() = runTest {
        record(fastRoute, start = 1)
        val vm = replay(record(divergingWalk))
        vm.seek(250_000)
        assertEquals(1, vm.ghostViews.value.size)
        vm.seek(430_000) // more than 200 m off the route since 362 s, so gone from 422 s
        assertTrue(vm.ghostViews.value.isEmpty())
        vm.seek(100_000)
        assertEquals(1, vm.ghostViews.value.size)
    }

    @Test
    fun openTrailReturnsWithItsGhostAndTheRangeFollows() = runTest {
        val ghostId = record(fastRoute, start = 1)
        val vm = replay(record(divergingWalk))
        val own = vm.heatmap.value!!
        vm.seek(250_000)
        vm.toggleTrail(ghostId)
        assertEquals(listOf(ghostId), vm.shownTrails.value.map { it.id })
        assertEquals(1, vm.ghostHeatmaps.value.size)
        assertTrue(vm.heatRange.value!!.endInclusive > own.maxKmh + 5.0) // the ghost is far faster

        vm.seek(430_000)
        assertTrue(vm.shownTrails.value.isEmpty())
        assertEquals(own.minKmh..own.maxKmh, vm.heatRange.value)

        vm.seek(100_000)
        assertEquals(listOf(ghostId), vm.shownTrails.value.map { it.id })
        assertTrue(vm.heatRange.value!!.endInclusive > own.maxKmh + 5.0)
    }

    @Test
    fun rangeIsStableWhilePlayingWhenTheShownSetIsUnchanged() = runTest {
        val ghostId = record(fastRoute, start = 1)
        val vm = replay(record(divergingWalk))
        vm.seek(100_000)
        vm.toggleTrail(ghostId)
        val range = vm.heatRange.value
        val heats = vm.ghostHeatmaps.value
        vm.seek(110_000)
        vm.seek(150_000)
        assertSame(range, vm.heatRange.value)
        assertSame(heats, vm.ghostHeatmaps.value)
    }

    @Test
    fun rangeIsTheHikesOwnWithoutOpenTrails() = runTest {
        record(fastRoute, start = 1)
        val vm = replay(record(walk))
        val own = vm.heatmap.value!!
        assertEquals(own.minKmh..own.maxKmh, vm.heatRange.value)
    }

    @Test
    fun ghostLoadFailureLeavesReplayWorking() = runTest {
        val vm = ReplayViewModel(
            repo, record(walk), settings,
            ghostLoader = { _, _, _, _ -> error("boom") },
            ghostDispatcher = Dispatchers.Main,
        ).also { advanceUntilIdle() }
        assertTrue(vm.hike.value != null)
        assertTrue(vm.frame.value != null)
        assertTrue(vm.ghostViews.value.isEmpty())
    }

    @Test
    fun switchingToAGhostCarriesEveryColourAndTheDistance() = runTest {
        val a = record(fastRoute, start = 1)
        record(straightLine(2.0, 2_000, 600), start = 2)
        val me = record(walk)
        val vm = replay(me)
        vm.seek(300_000)
        val colors = vm.ghostViews.value.associate { it.id to it.colorArgb }
        val s = vm.switchTo(a)
        assertEquals(a, s.hikeId)
        assertEquals(vm.frame.value!!.distanceM, s.atM, 1e-9)
        assertEquals(colors + (me to settings.trackColor.value), s.kept)
    }

    @Test
    fun aSwitchedReplayKeepsTheColoursAndStartsAtTheDistance() = runTest {
        val a = record(fastRoute, start = 1)
        val b = record(straightLine(2.0, 2_000, 600), start = 2)
        val me = record(walk)
        val kept = mapOf(me to settings.trackColor.value, a to 0xFF00897B.toInt(), b to 0xFFF9A825.toInt())
        val vm = ReplayViewModel(repo, a, settings, ghostDispatcher = Dispatchers.Main, kept = kept, startAtM = 900.0)
            .also { advanceUntilIdle() }
        assertEquals(0xFF00897B.toInt(), vm.trackColor)
        assertEquals(900.0, vm.frame.value!!.distanceM, 10.0)
        assertEquals(mapOf(me to settings.trackColor.value, b to 0xFFF9A825.toInt()), vm.ghostViews.value.associate { it.id to it.colorArgb })
    }

    @Test
    fun anOpenedReplayUsesTheTrackColour() = runTest {
        val vm = replay(record(walk))
        assertEquals(settings.trackColor.value, vm.trackColor)
    }

    @Test
    fun aGhostWithoutMovingEdgesDoesNotPullTheRangeToZero() = runTest {
        // a stationary old hike of the same place: all fixes in one spot
        record((0 until 10).map { com.walkwalk.domain.p(it * 20_000L) }, start = 1)
        val vm = replay(record(walk))
        val ghostId = vm.ghostViews.value.single().id
        vm.toggleTrail(ghostId)
        assertEquals(vm.heatmap.value!!.minKmh, vm.heatRange.value!!.start, 1e-9)
    }
}
