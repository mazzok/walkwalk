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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    val states = java.util.concurrent.ConcurrentHashMap<String, PhotoState>()
    val throwingStates = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val committed = java.util.concurrent.CopyOnWriteArrayList<Uri>() // camera I/O runs on Dispatchers.IO
    val discarded = java.util.concurrent.CopyOnWriteArrayList<Uri>()
    @Volatile var failInsert = false
    @Volatile var failCommit = false
    override fun access() = PhotoAccess.FULL
    override suspend fun takenBetween(from: Long, to: Long) = emptyList<GalleryPhoto>()
    override suspend fun state(uri: String): PhotoState {
        if (uri in throwingStates) error("provider exploded")
        return states[uri] ?: PhotoState.AVAILABLE
    }
    override fun newCameraTarget(): Uri {
        if (failInsert) error("could not create camera target")
        return Uri.parse("content://media/external/images/media/99")
    }
    override fun commitCameraTarget(uri: Uri) {
        if (failCommit) error("commit failed")
        committed += uri
    }
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
        // the duplicate is skipped, the new one lands: exactly 2 once settled
        vm.attach(lm, listOf(p, GalleryPhoto("content://b", 2L))); settle { repo.landmarks(hike).single().photos.size >= 2 }
        assertEquals(listOf("content://a", "content://b"), repo.landmarks(hike).single().photos.map { it.uri })
    }

    @Test
    fun cancelledCameraDiscardsEntry() = runTest {
        val hike = repo.startHike(0)
        val lm = repo.addLandmark(hike, 47.0, 8.0, null)
        val vm = vm(hike)
        val uri = vm.startCamera(lm)
        vm.onCameraResult(false)
        settle { photos.discarded.isNotEmpty() }
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

    @Test
    fun refreshReResolvesChangedState() = runTest {
        val hike = repo.startHike(0)
        val lm = repo.addLandmark(hike, 47.0, 8.0, null)
        repo.attachPhotos(lm, listOf("content://a" to 1L))
        photos.states["content://a"] = PhotoState.UNREADABLE
        val vm = vm(hike)
        vm.select(lm)
        settle { vm.thumbs.value.map { it.state } == listOf(PhotoState.UNREADABLE) }
        photos.states["content://a"] = PhotoState.AVAILABLE
        vm.refresh()
        settle { vm.thumbs.value.map { it.state } == listOf(PhotoState.AVAILABLE) }
    }

    @Test
    fun insertFailureReturnsNullAndEmitsMessage() = runTest {
        val hike = repo.startHike(0)
        val lm = repo.addLandmark(hike, 47.0, 8.0, null)
        val vm = vm(hike)
        photos.failInsert = true
        val got = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.messages.collect { got += it } }
        assertNull(vm.startCamera(lm))
        advanceUntilIdle()
        assertEquals(listOf("Couldn't open the camera"), got)
        vm.onCameraResult(true) // nothing saved: no-op, no crash
        job.cancel()
    }

    @Test
    fun failedCommitDiscardsAndEmitsMessage() = runTest {
        val hike = repo.startHike(0)
        val lm = repo.addLandmark(hike, 47.0, 8.0, null)
        val vm = vm(hike)
        val uri = vm.startCamera(lm)
        photos.failCommit = true
        val got = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.messages.collect { got += it } }
        vm.onCameraResult(true)
        settle { photos.discarded.isNotEmpty() && got.isNotEmpty() }
        assertEquals(listOf(uri), photos.discarded)
        assertTrue(repo.landmarks(hike).single().photos.isEmpty())
        job.cancel()
    }

    @Test
    fun lookupFailureMapsToUnreadable() = runTest {
        val hike = repo.startHike(0)
        val lm = repo.addLandmark(hike, 47.0, 8.0, null)
        repo.attachPhotos(lm, listOf("content://a" to 1L))
        photos.throwingStates += "content://a"
        val vm = vm(hike)
        vm.select(lm)
        settle { vm.thumbs.value.isNotEmpty() }
        assertEquals(listOf(PhotoState.UNREADABLE), vm.thumbs.value.map { it.state })
    }
}
