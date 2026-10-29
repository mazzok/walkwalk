package com.walkwalk.ui.landmarks

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.walkwalk.data.HikeRepository
import com.walkwalk.data.WalkwalkDb
import com.walkwalk.photos.GalleryPhoto
import com.walkwalk.photos.PhotoAccess
import com.walkwalk.photos.PhotoState
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class FakePhotos : Photos {
    val states = mutableMapOf<String, PhotoState>()
    val committed = mutableListOf<Uri>()
    val discarded = mutableListOf<Uri>()
    override fun access() = PhotoAccess.FULL
    override suspend fun takenBetween(from: Long, to: Long) = emptyList<GalleryPhoto>()
    override suspend fun state(uri: String) = states[uri] ?: PhotoState.AVAILABLE
    override fun newCameraTarget(): Uri = Uri.parse("content://media/external/images/media/99")
    override fun commitCameraTarget(uri: Uri) { committed += uri }
    override fun discardCameraTarget(uri: Uri) { discarded += uri }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LandmarksViewModelTest {
    private lateinit var db: WalkwalkDb
    private lateinit var repo: HikeRepository
    private val photos = FakePhotos()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalkwalkDb::class.java)
            .allowMainThreadQueries().build()
        repo = HikeRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    /**
     * Room runs on its own executors (direct ones break withTransaction), so results arrive on other threads
     * and the test dispatcher must be pumped while we wait in real time.
     */
    private suspend fun TestScope.settle(done: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        advanceUntilIdle()
        while (!done() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            advanceUntilIdle()
        }
        assertTrue("condition not reached in time", done())
    }

    private suspend fun TestScope.vm(hikeId: Long, state: SavedStateHandle = SavedStateHandle()): LandmarksViewModel {
        val vm = LandmarksViewModel(repo, photos, hikeId, state)
        settle { vm.landmarks.value.size == repo.landmarks(hikeId).size }
        return vm
    }

    @Test
    fun addAtOpensMenuForNewLandmark() = runTest {
        val hike = repo.startHike(0)
        val vm = vm(hike)
        vm.addAt(47.0, 8.0)
        settle { vm.menuOpenFor.value != null && vm.landmarks.value.size == 1 }
        val id = vm.landmarks.value.single().id
        assertEquals(id, vm.menuOpenFor.value)
        assertEquals(id, vm.selectedId.value)
    }

    @Test
    fun thumbsReflectPhotoState() = runTest {
        val hike = repo.startHike(0)
        val lm = repo.addLandmark(hike, 47.0, 8.0, null)
        repo.attachPhotos(lm, listOf("content://a" to 1L, "content://b" to 2L))
        photos.states["content://b"] = PhotoState.MISSING
        val vm = vm(hike)
        vm.select(lm)
        settle { vm.thumbs.value.size == 2 }
        assertEquals(listOf(PhotoState.AVAILABLE, PhotoState.MISSING), vm.thumbs.value.map { it.state })
    }

    @Test
    fun pickerWindowUsesEndOrNow() = runTest {
        val finished = repo.startHike(1_000).also { repo.finish(it, 5_000) }
        val recording = repo.startHike(2_000)
        val vm = vm(finished)
        assertEquals(1_000L..5_000L, vm.pickerWindow(repo.hike(finished)!!, 9_000))
        assertEquals(2_000L..9_000L, vm.pickerWindow(repo.hike(recording)!!, 9_000))
        assertNull(vm.pickerWindow(repo.hike(finished)!!.copy(hasTimes = false), 9_000))
    }

    @Test
    fun attachSkipsPhotosAlreadyOnLandmark() = runTest {
        val hike = repo.startHike(0)
        val lm = repo.addLandmark(hike, 47.0, 8.0, null)
        val vm = vm(hike)
        val p = GalleryPhoto("content://a", 1L)
        vm.attach(lm, listOf(p)); settle { repo.landmarks(hike).single().photos.size == 1 }
        vm.attach(lm, listOf(p)); advanceUntilIdle(); Thread.sleep(100); advanceUntilIdle()
        assertEquals(1, repo.landmarks(hike).single().photos.size)
    }

    @Test
    fun cancelledCameraDiscardsEntry() = runTest {
        val hike = repo.startHike(0)
        val lm = repo.addLandmark(hike, 47.0, 8.0, null)
        val vm = vm(hike)
        val uri = vm.startCamera(lm)
        vm.onCameraResult(false)
        advanceUntilIdle(); Thread.sleep(100); advanceUntilIdle()
        assertEquals(listOf(uri), photos.discarded)
        assertTrue(repo.landmarks(hike).single().photos.isEmpty())
    }

    @Test
    fun cameraResultAfterProcessDeathAttachesToSavedLandmark() = runTest {
        val hike = repo.startHike(0)
        repo.addLandmark(hike, 1.0, 1.0, null)
        val lm = repo.addLandmark(hike, 47.0, 8.0, null)
        val state = SavedStateHandle()
        val uri = vm(hike, state).startCamera(lm)
        val revived = vm(hike, state)
        revived.onCameraResult(true)
        settle { repo.landmarks(hike).first { it.id == lm }.photos.isNotEmpty() }
        assertEquals(listOf(uri), photos.committed)
        val photosOnLm = repo.landmarks(hike).first { it.id == lm }.photos
        assertEquals(listOf(uri.toString()), photosOnLm.map { it.uri })
        revived.onCameraResult(true) // cleared: second call is a no-op
        assertEquals(1, photos.committed.size)
    }

    @Test
    fun deleteRemovesLandmarkAndClearsSelection() = runTest {
        val hike = repo.startHike(0)
        val lm = repo.addLandmark(hike, 47.0, 8.0, null)
        val vm = vm(hike)
        vm.select(lm)
        vm.delete(lm)
        settle { repo.landmarks(hike).isEmpty() }
        assertTrue(repo.landmarks(hike).isEmpty())
        assertNull(vm.selectedId.value)
    }
}
