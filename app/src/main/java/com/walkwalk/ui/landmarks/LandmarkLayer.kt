package com.walkwalk.ui.landmarks

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.walkwalk.data.HikeEntity
import com.walkwalk.data.Landmark

/** What a screen hands to TopoMap so pins, selection and the photo strip work the same on every screen. */
class LandmarkMapArgs(
    val landmarks: List<Landmark>,
    val selectedLandmarkId: Long?,
    val onLandmarkClick: (Long) -> Unit,
    val onMapClick: () -> Unit,
    val onLongPress: (lat: Double, lon: Double) -> Unit,
    val selectedOverlay: @Composable () -> Unit,
)

/**
 * Owns everything landmark-related around a map: the map arguments, camera, picker and viewer,
 * view-model messages and a snackbar for them. [content] draws the screen and passes [LandmarkMapArgs] to TopoMap.
 */
@Composable
fun LandmarkLayer(
    vm: LandmarksViewModel,
    hike: HikeEntity?,
    canCamera: Boolean,
    content: @Composable (LandmarkMapArgs) -> Unit,
) {
    val landmarks by vm.landmarks.collectAsState()
    val selectedId by vm.selectedId.collectAsState()
    val menuFor by vm.menuOpenFor.collectAsState()
    val thumbs by vm.thumbs.collectAsState()
    // the window is fixed when the picker opens, so a live hike's clock doesn't re-query MediaStore every second
    var picker by remember { mutableStateOf<Pair<Long, LongRange>?>(null) }
    var viewerStart by remember { mutableStateOf<Int?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // a photo may have been deleted or access changed while the app was away
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { vm.onCameraResult(it) }
    val allowAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }

    // nothing left to show (landmark deleted, photos removed): don't keep a stale viewer around
    LaunchedEffect(thumbs.isEmpty()) { if (thumbs.isEmpty()) viewerStart = null }

    val canPick = hike?.let { vm.pickerWindow(it, 0) } != null

    val args = LandmarkMapArgs(
        landmarks = landmarks,
        selectedLandmarkId = selectedId,
        onLandmarkClick = vm::select,
        onMapClick = { vm.select(null); vm.closeMenu() },
        onLongPress = vm::addAt,
        selectedOverlay = {
            val id = selectedId
            if (id != null) Box {
                PhotoStrip(thumbs, onThumb = { viewerStart = it }, onAdd = { vm.openMenu(id) })
                LandmarkMenu(
                    expanded = menuFor == id,
                    canCamera = canCamera,
                    canPick = canPick,
                    onCamera = {
                        vm.closeMenu()
                        // null: the view model already told the user why; never launch without a target
                        vm.startCamera(id)?.let { camera.launch(it) }
                    },
                    onPick = {
                        vm.closeMenu()
                        hike?.let { h -> vm.pickerWindow(h, System.currentTimeMillis()) }?.let { w -> picker = id to w }
                    },
                    onDelete = { vm.delete(id) },
                    onDismiss = vm::closeMenu,
                )
            }
        },
    )

    Box(Modifier.fillMaxSize()) {
        content(args)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 88.dp))
    }

    picker?.let { (id, window) ->
        HikePhotoPicker(window, vm, id, onDone = { picker = null })
    }
    viewerStart?.let { start ->
        PhotoViewer(
            thumbs = thumbs,
            start = start,
            onRemove = vm::removePhoto,
            onAllowAccess = {
                allowAccess.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
            },
            onClose = { viewerStart = null },
        )
    }
}
