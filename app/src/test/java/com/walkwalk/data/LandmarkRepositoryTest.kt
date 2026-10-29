package com.walkwalk.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.walkwalk.domain.ParsedGpx
import com.walkwalk.domain.straightLine
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LandmarkRepositoryTest {
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
    fun addLandmarkAtTakesNearestPointTime() = runTest {
        val pts = straightLine(1.25, 2000, 10)
        val id = repo.importGpx(ParsedGpx("t", pts, hasTimes = true), "fallback")
        val lmId = repo.addLandmarkAt(id, pts[2].lat, pts[2].lon)
        assertEquals(pts[2].time, repo.landmarks(id).single { it.id == lmId }.time)
    }

    @Test
    fun addLandmarkAtOnRouteWithoutTimesHasNoTime() = runTest {
        val pts = straightLine(1.25, 2000, 10)
        val id = repo.importGpx(ParsedGpx("t", pts, hasTimes = false), "fallback")
        repo.addLandmarkAt(id, pts[2].lat, pts[2].lon)
        assertNull(repo.landmarks(id).single().time)
    }

    @Test
    fun attachPhotosSkipsDuplicates() = runTest {
        val id = repo.startHike(0)
        val lm = repo.addLandmark(id, 47.0, 11.0, 5L)
        repo.attachPhotos(lm, listOf("content://a" to 1L))
        repo.attachPhotos(lm, listOf("content://a" to 1L))
        assertEquals(1, repo.landmarks(id).single().photos.size)
    }

    @Test
    fun deleteHikeDeletesLandmarksAndPhotos() = runTest {
        val id = repo.startHike(0)
        val lm = repo.addLandmark(id, 47.0, 11.0, null)
        repo.attachPhotos(lm, listOf("content://a" to null))
        repo.delete(id)
        assertTrue(repo.landmarks(id).isEmpty())
        assertTrue(db.landmarkDao().photosOf(lm).isEmpty())
    }

    @Test
    fun ensureBundleIdIsStable() = runTest {
        val id = repo.startHike(0)
        val a = repo.ensureBundleId(id)
        assertTrue(a.isNotBlank())
        assertEquals(a, repo.ensureBundleId(id))
        assertEquals(id, repo.hikeByBundleId(a)?.id)
    }
}
