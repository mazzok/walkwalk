package com.walkwalk.ui.landmarks

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.walkwalk.photos.GalleryPhoto
import com.walkwalk.photos.PhotoAccess

/** Full-screen grid of gallery photos taken during the hike, newest first. Photos already on the landmark start ticked. */
@Composable
fun HikePhotoPicker(window: LongRange, vm: LandmarksViewModel, landmarkId: Long, onDone: () -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    var access by remember { mutableStateOf(vm.photoAccess()) }
    val already = remember(landmarkId) {
        vm.landmarks.value.firstOrNull { it.id == landmarkId }?.photos?.map { it.uri }?.toSet().orEmpty()
    }
    val ticked: SnapshotStateList<String> = remember(landmarkId) { already.toList().toMutableStateList() }

    val allowMore = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = vm.photoAccess()
        reload++ // the selection screen may have granted more photos
    }
    val photos by produceState<List<GalleryPhoto>?>(null, window, reload) { value = vm.pickerPhotos(window) }

    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Photos from this hike", Modifier.weight(1f).padding(start = 8.dp))
                    Button(onClick = {
                        val fresh = photos.orEmpty().filter { it.uri in ticked && it.uri !in already }
                        vm.attach(landmarkId, fresh)
                        onDone()
                    }) { Text("Done") }
                }
                if (access != PhotoAccess.FULL) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (access == PhotoAccess.PARTIAL) "Only photos you allowed are shown"
                            else "walkwalk needs access to your photos to list the ones from this hike",
                            Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            allowMore.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
                        }) { Text("Allow more") }
                    }
                }
                val list = photos
                when {
                    list == null -> Unit
                    list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No photos taken during this hike")
                    }
                    else -> LazyVerticalGrid(
                        GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(list, key = { it.uri }) { p ->
                            val on = p.uri in ticked
                            Box(
                                Modifier.aspectRatio(1f).background(Color.LightGray)
                                    .clickable { if (on) ticked.remove(p.uri) else ticked.add(p.uri) },
                            ) {
                                val bmp = rememberBitmap(p.uri, 320)
                                if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                if (on) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.TopEnd) {
                                    Icon(Icons.Filled.Check, "Selected", tint = Color.White, modifier = Modifier.padding(6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
