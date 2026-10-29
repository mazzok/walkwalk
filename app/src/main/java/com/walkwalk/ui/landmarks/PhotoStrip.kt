package com.walkwalk.ui.landmarks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.walkwalk.photos.PhotoState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val Grey = Color(0xFFB0B0B0)

/** Horizontally scrolling thumbnails under the selected pin; the trailing ＋ opens the landmark menu. */
@Composable
fun PhotoStrip(thumbs: List<Thumb>, onThumb: (Int) -> Unit, onAdd: () -> Unit) {
    Surface(
        color = Color.White.copy(alpha = 0.45f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color.White),
    ) {
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(thumbs, key = { _, t -> t.photoId }) { i, t ->
                PhotoCircle(t.uri, t.state, 44, Modifier.clickable { onThumb(i) })
            }
            item {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.8f))
                        .border(2.dp, Color.White, CircleShape).clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, contentDescription = "Add photo") }
            }
        }
    }
}

/** A round thumbnail; MISSING and UNREADABLE photos get a grey placeholder instead of an image. */
@Composable
internal fun PhotoCircle(uri: String, state: PhotoState, sizeDp: Int, modifier: Modifier = Modifier) {
    val base = modifier.size(sizeDp.dp).clip(CircleShape).border(2.dp, Color.White, CircleShape)
    when (state) {
        PhotoState.AVAILABLE -> {
            val bitmap = rememberBitmap(uri, 132)
            val b = bitmap
            if (b != null) {
                Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = base)
            } else {
                Box(base.background(Grey), contentAlignment = Alignment.Center) { Text("⊘") } // loading or failed: missing look
            }
        }
        PhotoState.MISSING -> Box(base.background(Grey), contentAlignment = Alignment.Center) { Text("⊘") }
        PhotoState.UNREADABLE -> Box(base.background(Grey), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Lock, contentDescription = "Photo not readable")
        }
    }
}

/** Decodes off the main thread; null while loading and on any failure. */
@Composable
internal fun rememberBitmap(uri: String, px: Int): Bitmap? {
    val context = LocalContext.current
    val result by produceState<Bitmap?>(null, uri, px) {
        value = withContext(Dispatchers.IO) { loadBitmap(context, uri, px) }
    }
    return result
}

private fun loadBitmap(context: Context, uri: String, px: Int): Bitmap? = try {
    val parsed = Uri.parse(uri)
    if (parsed.scheme == "file") {
        val source = ImageDecoder.createSource(File(parsed.path ?: return null))
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val scale = px.toFloat() / maxOf(info.size.width, info.size.height)
            if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        context.contentResolver.loadThumbnail(parsed, Size(px, px), null)
    }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

@Composable
fun LandmarkMenu(
    expanded: Boolean,
    canCamera: Boolean,
    canPick: Boolean,
    onCamera: () -> Unit,
    onPick: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("📷 Take photo") }, enabled = canCamera, onClick = onCamera)
        DropdownMenuItem(text = { Text("🖼 Photos from this hike") }, enabled = canPick, onClick = onPick)
        DropdownMenuItem(text = { Text("🗑 Delete landmark") }, onClick = { confirmDelete = true })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text("Delete this landmark? Its photos stay in your gallery.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
