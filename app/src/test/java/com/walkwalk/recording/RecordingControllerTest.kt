package com.walkwalk.recording

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.walkwalk.data.HikeRepository
import com.walkwalk.data.HikeStatus
import com.walkwalk.data.WalkwalkDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
class RecordingControllerTest {
    private lateinit var db: WalkwalkDb
    private lateinit var repo: HikeRepository

    private class FakeGps : LocationSource {
        val flow = MutableSharedFlow<Fix>(extraBufferCapacity = 64)
        val collecting get() = flow.subscriptionCount.value > 0
        override fun fixes(): Flow<Fix> = flow
        fun emit(f: Fix) = check(flow.tryEmit(f))
    }

    private class FakeBaro : PressureSource {
        var value: Double? = null
        override fun latestHpa() = value
    }

    private val fakeGps = FakeGps()
    private val fakeBaro = FakeBaro()

    @Before
    fun setUp() {
        val direct = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalkwalkDb::class.java)
            .setQueryExecutor(direct).setTransactionExecutor(direct).allowMainThreadQueries().build()
        repo = HikeRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun TestScope.newController() = RecordingController(repo, fakeGps, fakeBaro, backgroundScope) { testScheduler.currentTime }

    /** ~1.1 m north per second of [t]. */
    private fun fix(t: Long) = Fix(t, 47.0 + t / 1000.0 * 1e-5, 11.0, 1000.0, 5f)

    @Test
    fun fixesArePersistedImmediately() = runTest {
        val ctl = newController()
        val id = ctl.start(); runCurrent()
        fakeGps.emit(fix(0)); fakeGps.emit(fix(2000)); runCurrent()
        assertEquals(2, repo.points(id).size)
        assertEquals(2.2, ctl.state.value!!.distanceM, 0.2)
    }

    @Test
    fun badFixesAreDropped() = runTest {
        val ctl = newController()
        val id = ctl.start(); runCurrent()
        fakeGps.emit(fix(0)); fakeGps.emit(Fix(2000, 47.0, 11.0, 1000.0, 120f)); runCurrent()
        assertEquals(1, repo.points(id).size)
    }

    @Test
    fun pauseStopsGpsAndResumeStartsNewSegment() = runTest {
        val ctl = newController()
        val id = ctl.start(); runCurrent()
        fakeGps.emit(fix(0)); runCurrent()
        ctl.pause(); runCurrent()
        assertFalse(fakeGps.collecting)
        assertEquals(HikeStatus.PAUSED, repo.hike(id)!!.status)
        ctl.resume(); runCurrent()
        fakeGps.emit(fix(60_000)); runCurrent()
        assertEquals(listOf(0, 1), repo.points(id).map { it.segment })
    }

    @Test
    fun liveStateNamesTheNewSegmentBeforeItsFirstFix() = runTest {
        val ctl = newController()
        ctl.start(); runCurrent()
        fakeGps.emit(fix(0)); runCurrent()
        assertEquals(0, ctl.state.value!!.segment)
        ctl.pause(); runCurrent()
        ctl.resume(); runCurrent()
        assertEquals(1, ctl.state.value!!.segment)
        assertEquals(0, ctl.state.value!!.track.last().segment)
    }

    @Test
    fun restartContinuesActiveHikeInNewSegment() = runTest {
        val ctl = newController()
        val id = ctl.start(); runCurrent()
        fakeGps.emit(fix(0)); fakeGps.emit(fix(2000)); runCurrent()
        ctl.detach(); runCurrent() // simulates the process dying

        val ctl2 = newController()
        assertTrue(ctl2.restoreActive()); runCurrent()
        fakeGps.emit(fix(300_000)); runCurrent()
        assertEquals(id, ctl2.state.value!!.hikeId)
        assertEquals(listOf(0, 0, 1), repo.points(id).map { it.segment })
        assertEquals(2.2, ctl2.state.value!!.distanceM, 0.2) // no distance across the gap
    }

    @Test
    fun restorePausedHikeStaysPausedWithoutGps() = runTest {
        val ctl = newController()
        val id = ctl.start(); runCurrent()
        ctl.pause(); ctl.detach(); runCurrent()
        val ctl2 = newController()
        assertTrue(ctl2.restoreActive()); runCurrent()
        assertEquals(HikeStatus.PAUSED, ctl2.state.value!!.status)
        assertFalse(fakeGps.collecting)
        assertEquals(id, ctl2.state.value!!.hikeId)
    }

    @Test
    fun restoreWithNothingActiveReturnsFalse() = runTest {
        assertFalse(newController().restoreActive())
    }

    @Test
    fun stopFinishesHike() = runTest {
        val ctl = newController()
        val id = ctl.start(); runCurrent()
        fakeGps.emit(fix(0)); runCurrent()
        val done = ctl.stop(); runCurrent()
        assertEquals(id, done)
        assertEquals(HikeStatus.FINISHED, repo.hike(id)!!.status)
        assertFalse(fakeGps.collecting)
        assertNull(ctl.state.value)
    }

    @Test
    fun startWithUnfinishedHikeContinuesItInsteadOfOrphaningIt() = runTest {
        val ctl = newController()
        val id = ctl.start(); runCurrent()
        ctl.detach(); runCurrent()
        val ctl2 = newController()
        assertEquals(id, ctl2.start()); runCurrent()
        assertEquals(1, repo.finishedHikes().size + 1) // still exactly one hike: none finished, one active
        assertEquals(id, repo.activeHike()!!.id)
    }

    @Test
    fun publishedTrackIsASnapshot() = runTest {
        val ctl = newController()
        ctl.start(); runCurrent()
        fakeGps.emit(fix(0)); runCurrent()
        val first = ctl.state.value!!
        fakeGps.emit(fix(2000)); runCurrent()
        assertEquals(1, first.track.size)
        assertEquals(2, ctl.state.value!!.track.size)
    }

    @Test
    fun pressureStoredWithFix() = runTest {
        fakeBaro.value = 900.0
        val ctl = newController()
        val id = ctl.start(); runCurrent()
        fakeGps.emit(fix(0)); runCurrent()
        assertEquals(900.0, repo.points(id)[0].pressureHpa!!, 0.0)
    }
}
