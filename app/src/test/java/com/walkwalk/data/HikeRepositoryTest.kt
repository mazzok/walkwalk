package com.walkwalk.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.walkwalk.domain.ParsedGpx
import com.walkwalk.domain.p
import com.walkwalk.domain.straightLine
import com.walkwalk.domain.walkStandWalk
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
import java.time.ZoneOffset
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
class HikeRepositoryTest {
    private lateinit var db: WalkwalkDb
    private lateinit var repo: HikeRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalkwalkDb::class.java)
            .allowMainThreadQueries().build()
        repo = HikeRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun defaultName() = assertEquals(
        "Mon 12 Oct 2026, 09:00",
        HikeRepository.defaultName(ZonedDateTime.of(2026, 10, 12, 9, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli(), ZoneOffset.UTC),
    )

    @Test
    fun recordAndFinishComputesStats() = runTest {
        val id = repo.startHike(0)
        straightLine(1.25, 2000, 100).forEach { repo.appendPoint(id, it) }
        repo.finish(id, 200_000)
        val h = repo.hike(id)!!
        assertEquals(HikeStatus.FINISHED, h.status)
        assertEquals(247.5, h.distanceM, 2.0)
        assertEquals(200_000L, h.endTime)
        assertNull(repo.activeHike())
    }

    @Test
    fun finishStoresDetectedStops() = runTest {
        val id = repo.startHike(0)
        walkStandWalk().forEach { repo.appendPoint(id, it) }
        repo.finish(id, 1_200_000)
        assertEquals(1, repo.stops(id).size)
        assertTrue(repo.hike(id)!!.movingMs < repo.hike(id)!!.totalMs)
    }

    @Test
    fun activeHikeFoundAfterStart() = runTest {
        val id = repo.startHike(0)
        assertEquals(id, repo.activeHike()!!.id)
        repo.setStatus(id, HikeStatus.PAUSED)
        assertEquals(id, repo.activeHike()!!.id)
    }

    @Test
    fun lastSegmentTracksAppends() = runTest {
        val id = repo.startHike(0)
        assertNull(repo.lastSegment(id))
        repo.appendPoint(id, p(0, seg = 0))
        repo.appendPoint(id, p(1000, seg = 2))
        assertEquals(2, repo.lastSegment(id))
    }

    @Test
    fun deleteRemovesPoints() = runTest {
        val id = repo.startHike(0)
        repo.appendPoint(id, p(0, 47.0, 11.0))
        repo.delete(id)
        assertTrue(repo.points(id).isEmpty())
        assertNull(repo.hike(id))
    }

    @Test
    fun renameChangesName() = runTest {
        val id = repo.startHike(0)
        repo.rename(id, "Zugspitze")
        assertEquals("Zugspitze", repo.hike(id)!!.name)
    }

    @Test
    fun importWithoutTimesMarksHike() = runTest {
        val id = repo.importGpx(ParsedGpx(null, listOf(p(0), p(1000, 47.001)), false), "route.gpx")
        val h = repo.hike(id)!!
        assertFalse(h.hasTimes)
        assertEquals("route.gpx", h.name)
        assertEquals(HikeSource.IMPORTED, h.source)
        assertEquals(HikeStatus.FINISHED, h.status)
        assertEquals(2, repo.points(id).size)
    }

    @Test
    fun finishedHikesNewestFirst() = runTest {
        val a = repo.startHike(1000); repo.finish(a, 2000)
        val b = repo.startHike(5000); repo.finish(b, 6000)
        val c = repo.startHike(9000) // still recording, not listed
        val list = repo.finishedHikes()
        assertEquals(listOf(b, a), list.map { it.id })
        assertFalse(list.any { it.id == c })
    }
}
