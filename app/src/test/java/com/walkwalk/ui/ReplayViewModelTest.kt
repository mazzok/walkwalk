package com.walkwalk.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.walkwalk.data.HikeRepository
import com.walkwalk.data.WalkwalkDb
import com.walkwalk.domain.ParsedGpx
import com.walkwalk.domain.p
import com.walkwalk.domain.straightLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReplayViewModelTest {
    private lateinit var db: WalkwalkDb
    private lateinit var repo: HikeRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        val direct = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalkwalkDb::class.java)
            .setQueryExecutor(direct).setTransactionExecutor(direct).allowMainThreadQueries().build()
        repo = HikeRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    /** 20 minutes of steady walking. */
    private suspend fun TestScope.walkVm(): ReplayViewModel {
        val id = repo.startHike(0)
        straightLine(1.25, 2000, 600).forEach { repo.appendPoint(id, it) }
        repo.finish(id, 1_200_000)
        return ReplayViewModel(repo, id).also { advanceUntilIdle() }
    }

    @Test
    fun loadsHike() = runTest {
        val vm = walkVm()
        assertEquals(1_198_000L, vm.durationMs)
        assertTrue(vm.canPlay.value)
        assertEquals(0L, vm.frame.value!!.elapsedMs)
    }

    @Test
    fun playAdvancesBySpeedFactor() = runTest {
        val vm = walkVm()
        vm.setSpeed(60)
        vm.togglePlay()
        advanceTimeBy(1_000)
        assertEquals(60_000.0, vm.replayMs.value.toDouble(), 3_000.0)
        assertEquals(vm.replayMs.value, vm.frame.value!!.elapsedMs)
        vm.togglePlay()
    }

    @Test
    fun playStopsAtEnd() = runTest {
        val vm = walkVm()
        vm.seek(vm.durationMs - 100)
        vm.togglePlay()
        advanceTimeBy(5_000)
        assertFalse(vm.playing.value)
        assertEquals(vm.durationMs, vm.replayMs.value)
    }

    @Test
    fun playAtEndRestartsFromBeginning() = runTest {
        val vm = walkVm()
        vm.seek(vm.durationMs)
        vm.togglePlay()
        advanceTimeBy(100)
        assertTrue(vm.replayMs.value < 60_000)
        vm.togglePlay()
    }

    @Test
    fun seekClamps() = runTest {
        val vm = walkVm()
        vm.seek(-5)
        assertEquals(0L, vm.replayMs.value)
        vm.seek(Long.MAX_VALUE)
        assertEquals(vm.durationMs, vm.replayMs.value)
    }

    @Test
    fun seekToDistanceMovesMarker() = runTest {
        val vm = walkVm()
        vm.seekToDistance(750.0)
        assertEquals(750.0, vm.frame.value!!.distanceM, 3.0)
    }

    @Test
    fun timelessHikeCannotPlay() = runTest {
        val id = repo.importGpx(ParsedGpx(null, listOf(p(0), p(1000, 47.001)), false), "r.gpx")
        val vm = ReplayViewModel(repo, id).also { advanceUntilIdle() }
        assertFalse(vm.canPlay.value)
        vm.togglePlay()
        assertFalse(vm.playing.value)
    }

    @Test
    fun speedsCycle() = runTest {
        val vm = walkVm()
        assertEquals(60, vm.speedFactor.value)
        vm.cycleSpeed(); assertEquals(300, vm.speedFactor.value)
        vm.cycleSpeed(); assertEquals(10, vm.speedFactor.value)
    }
}
