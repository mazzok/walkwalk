package com.walkwalk.share

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import com.walkwalk.WalkwalkApp
import com.walkwalk.data.HikeEntity
import com.walkwalk.photos.PhotoState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/** [skipped] counts photos left out of the bundle (missing, unreadable or failed to shrink). */
data class SentBundle(val file: File, val skipped: Int)

/** Builds the .walkwalk bundle for a hike in the cache's send/ folder. */
class HikeSender(private val context: Context, private val app: WalkwalkApp) {
    suspend fun build(hike: HikeEntity): SentBundle = withContext(Dispatchers.IO) {
        val bundleId = app.repository.ensureBundleId(hike.id)
        val dir = File(context.cacheDir, "send")
        // stale bundles can hold photos; keep only the one being built
        dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        val file = File(dir, HikeBundle.fileName(hike.name))
        val points = app.repository.points(hike.id)
        val landmarks = app.repository.landmarks(hike.id)
        var skipped = 0
        try {
            file.outputStream().use { out ->
                HikeBundle.write(out, bundleId, hike.name, points, landmarks) { photo ->
                    val bytes = try {
                        // missing or unreadable photos are simply left out of the bundle
                        if (runBlocking { app.photos.state(photo.uri) } != PhotoState.AVAILABLE) null
                        else PhotoShrinker.shrink(source(Uri.parse(photo.uri)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (bytes == null) skipped++
                    bytes
                }
            }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        SentBundle(file, skipped)
    }

    private fun source(uri: Uri): ImageDecoder.Source =
        if (uri.scheme == "file") ImageDecoder.createSource(File(uri.path!!))
        else ImageDecoder.createSource(context.contentResolver, uri)
}
