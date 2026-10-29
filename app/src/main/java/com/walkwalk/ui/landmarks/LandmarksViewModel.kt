package com.walkwalk.ui.landmarks

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walkwalk.data.HikeEntity
import com.walkwalk.data.HikeRepository
import com.walkwalk.data.Landmark
import com.walkwalk.photos.GalleryPhoto
import com.walkwalk.photos.PhotoAccess
import com.walkwalk.photos.PhotoState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The subset of PhotoStore the view model uses, so tests can fake it. */
interface Photos {
    fun access(): PhotoAccess
    suspend fun takenBetween(from: Long, to: Long): List<GalleryPhoto>
    suspend fun state(uri: String): PhotoState
    fun newCameraTarget(): Uri
    fun commitCameraTarget(uri: Uri)
    fun discardCameraTarget(uri: Uri)
}

data class Thumb(val photoId: Long, val uri: String, val state: PhotoState)

@OptIn(ExperimentalCoroutinesApi::class)
class LandmarksViewModel(
    private val repo: HikeRepository,
    private val photos: Photos,
    private val hikeId: Long,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    val landmarks: StateFlow<List<Landmark>> = repo.observeLandmarks(hikeId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectedId = MutableStateFlow<Long?>(null)
    val selectedId: StateFlow<Long?> = _selectedId

    private val _menuOpenFor = MutableStateFlow<Long?>(null)
    val menuOpenFor: StateFlow<Long?> = _menuOpenFor

    /** Selected landmark's photos with their live state; re-resolved whenever selection or its photos change. */
    val thumbs: StateFlow<List<Thumb>> = combine(landmarks, _selectedId) { lms, id ->
        lms.firstOrNull { it.id == id }?.photos.orEmpty()
    }.distinctUntilChanged().mapLatest { list ->
        list.map { Thumb(it.id, it.uri, safeState(it.uri)) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private suspend fun safeState(uri: String): PhotoState = try {
        photos.state(uri)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        PhotoState.MISSING // a lookup must never crash the UI
    }

    fun select(id: Long?) {
        _selectedId.value = id
        if (_menuOpenFor.value != id) _menuOpenFor.value = null
    }

    fun openMenu(id: Long) { _selectedId.value = id; _menuOpenFor.value = id }
    fun closeMenu() { _menuOpenFor.value = null }

    fun addHere(lat: Double, lon: Double, time: Long) {
        viewModelScope.launch { openMenu(repo.addLandmark(hikeId, lat, lon, time)) }
    }

    fun addAt(lat: Double, lon: Double) {
        viewModelScope.launch { openMenu(repo.addLandmarkAt(hikeId, lat, lon)) }
    }

    fun delete(id: Long) {
        if (_selectedId.value == id) select(null)
        if (_menuOpenFor.value == id) closeMenu()
        viewModelScope.launch { repo.deleteLandmark(id) }
    }

    fun removePhoto(photoId: Long) {
        viewModelScope.launch { repo.removePhoto(photoId) }
    }

    fun pickerWindow(hike: HikeEntity, now: Long): LongRange? =
        if (hike.hasTimes) hike.startTime..(hike.endTime ?: now) else null

    suspend fun pickerPhotos(window: LongRange): List<GalleryPhoto> = photos.takenBetween(window.first, window.last)

    fun photoAccess(): PhotoAccess = photos.access()

    fun attach(landmarkId: Long, picked: List<GalleryPhoto>) {
        if (picked.isEmpty()) return
        viewModelScope.launch { repo.attachPhotos(landmarkId, picked.map { it.uri to it.takenAt }) }
    }

    /** Saved so the photo still lands on the right landmark if the process dies while the camera is open. */
    fun startCamera(landmarkId: Long): Uri {
        val target = photos.newCameraTarget()
        savedState[KEY_LANDMARK] = landmarkId
        savedState[KEY_URI] = target.toString()
        return target
    }

    fun onCameraResult(success: Boolean) {
        val landmarkId = savedState.get<Long>(KEY_LANDMARK)
        val uri = savedState.get<String>(KEY_URI)?.let(Uri::parse)
        savedState.remove<Long>(KEY_LANDMARK)
        savedState.remove<String>(KEY_URI)
        if (landmarkId == null || uri == null) return
        if (success) {
            photos.commitCameraTarget(uri)
            attach(landmarkId, listOf(GalleryPhoto(uri.toString(), System.currentTimeMillis())))
        } else {
            photos.discardCameraTarget(uri)
        }
    }

    private companion object {
        const val KEY_LANDMARK = "cameraLandmark"
        const val KEY_URI = "cameraUri"
    }
}
