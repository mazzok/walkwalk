package com.walkwalk.ui.landmarks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.walkwalk.photos.PhotoState

/** Full-screen pager over a landmark's photos with pinch zoom; gone or unreadable photos explain themselves. */
@Composable
fun PhotoViewer(
    thumbs: List<Thumb>,
    start: Int,
    onRemove: (Long) -> Unit,
    onAllowAccess: () -> Unit,
    onClose: () -> Unit,
) {
    if (thumbs.isEmpty()) return
    val pager = rememberPagerState(initialPage = start.coerceIn(0, thumbs.lastIndex)) { thumbs.size }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                val t = thumbs[page]
                when (t.state) {
                    PhotoState.AVAILABLE -> ZoomableImage(t.uri)
                    PhotoState.MISSING -> Notice("This photo is no longer on the phone", "Remove") { onRemove(t.photoId) }
                    PhotoState.UNREADABLE -> Notice("walkwalk can't read this photo", "Allow access", onAllowAccess)
                }
            }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd)) { Text("Close", color = Color.White) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class) // transformable(canPan) so one-finger swipes reach the pager at scale 1
@Composable
private fun ZoomableImage(uri: String) {
    // twice the screen's long edge keeps zoomed photos sharp; capped so a bitmap stays around 50 MB
    val screen = LocalContext.current.resources.displayMetrics
    val bitmap = rememberBitmap(uri, (maxOf(screen.widthPixels, screen.heightPixels) * 2).coerceIn(1600, 4096))
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(Modifier.fillMaxSize().transformable(state, canPan = { scale > 1f }), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(
                b.asImageBitmap(), null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(
                    scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y,
                ),
            )
        } else {
            Text("⊘", color = Color.White)
        }
    }
}

@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Color.White)
        Button(onClick = onAction) { Text(action) }
    }
}
