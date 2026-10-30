package com.walkwalk.share

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import com.walkwalk.WalkwalkApp
import com.walkwalk.data.HikeEntity
import com.walkwalk.photos.PhotoState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/** Builds the .walkwalk bundle for a hike in the cache's send/ folder. */
class HikeSender(private val context: Context, private val app: WalkwalkApp) {
    suspend fun build(hike: HikeEntity): File = withContext(Dispatchers.IO) {
        val bundleId = app.repository.ensureBundleId(hike.id)
        val dir = File(context.cacheDir, "send").apply { mkdirs() }
        val file = File(dir, HikeBundle.fileName(hike.name))
        val points = app.repository.points(hike.id)
        val landmarks = app.repository.landmarks(hike.id)
        file.outputStream().use { out ->
            HikeBundle.write(out, bundleId, hike.name, points, landmarks) { photo ->
                try {
                    // missing or unreadable photos are simply left out of the bundle
                    if (runBlocking { app.photos.state(photo.uri) } != PhotoState.AVAILABLE) return@write null
                    PhotoShrinker.shrink(source(Uri.parse(photo.uri)))
                } catch (e: Exception) {
                    null
                }
            }
        }
        file
    }

    private fun source(uri: Uri): ImageDecoder.Source =
        if (uri.scheme == "file") ImageDecoder.createSource(File(uri.path!!))
        else ImageDecoder.createSource(context.contentResolver, uri)
}
