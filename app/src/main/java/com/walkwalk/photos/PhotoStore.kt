package com.walkwalk.photos

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.walkwalk.ui.landmarks.Photos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

enum class PhotoState { AVAILABLE, MISSING, UNREADABLE }

data class GalleryPhoto(val uri: String, val takenAt: Long)

enum class PhotoAccess { FULL, PARTIAL, NONE }

/**
 * Gallery photos are content:// MediaStore URIs; private copies are file:// URIs under filesDir/photos/<hikeId>/.
 */
open class PhotoStore(private val context: Context) : Photos {

    override fun access(): PhotoAccess {
        fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return when {
            granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.FULL
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.PARTIAL
            else -> PhotoAccess.NONE
        }
    }

    /** Gallery photos taken within [from, to] (epoch ms), newest first. */
    override suspend fun takenBetween(from: Long, to: Long): List<GalleryPhoto> = withContext(Dispatchers.IO) {
        val (selection, args) = takenBetweenQuery(from, to)
        val out = mutableListOf<GalleryPhoto>()
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED),
                selection,
                args,
                null, // ordered in Kotlin below: an arithmetic sortOrder can be rejected by MediaProvider's SQL checker
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val taken = takenAt(if (c.isNull(1)) null else c.getLong(1), c.getLong(2))
                    out += GalleryPhoto(Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()).toString(), taken)
                }
            }
        } catch (_: SecurityException) {
            // no gallery access: nothing to suggest
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            // the provider rejected the query: nothing to suggest rather than a crash
        }
        out.sortedByDescending { it.takenAt }
    }

    override suspend fun state(uri: String): PhotoState = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        val isPrivate = parsed.scheme == "file"
        val found = if (isPrivate) {
            parsed.path?.let { File(it).exists() } ?: false
        } else {
            try {
                context.contentResolver.query(parsed, arrayOf(MediaStore.Images.Media._ID), null, null, null)
                    ?.use { it.moveToFirst() } ?: false
            } catch (_: SecurityException) {
                false
            } catch (_: IllegalArgumentException) {
                false // not a MediaStore URI, or the provider rejected it
            }
        }
        decideState(found, access(), isPrivate)
    }

    /**
     * A MediaStore row in Pictures/walkwalk for the camera app to write into (EXTRA_OUTPUT).
     * Not IS_PENDING: only the owner may open a pending row, so the camera app could not write it.
     */
    override fun newCameraTarget(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "walkwalk_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/walkwalk")
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("could not create camera target")
    }

    // nothing to publish: the row was never pending, and MediaProvider rescans the file when the camera closes it
    override fun commitCameraTarget(uri: Uri) = Unit

    override fun discardCameraTarget(uri: Uri) {
        context.contentResolver.delete(uri, null, null)
    }

    open fun privateDir(hikeId: Long): File = File(context.filesDir, "photos/$hikeId")

    fun deletePrivate(hikeId: Long) {
        privateDir(hikeId).deleteRecursively()
    }

    /** Opens a content:// or file:// photo; null when it is gone or unreadable. */
    fun open(uri: String): InputStream? = try {
        context.contentResolver.openInputStream(Uri.parse(uri))
    } catch (_: Exception) {
        null
    }

    companion object {
        /**
         * Plain columns, not COALESCE(...): a computed expression has no integer affinity, so the string
         * args would compare greater than every number and nothing would match.
         */
        internal fun takenBetweenQuery(from: Long, to: Long): Pair<String, Array<String>> {
            val taken = MediaStore.Images.Media.DATE_TAKEN
            val added = MediaStore.Images.Media.DATE_ADDED
            return "($taken BETWEEN ? AND ?) OR ($taken IS NULL AND $added BETWEEN ? AND ?)" to
                arrayOf(from.toString(), to.toString(), (from / 1000).toString(), (to / 1000).toString())
        }

        fun takenAt(dateTakenMs: Long?, dateAddedSec: Long): Long = dateTakenMs ?: (dateAddedSec * 1000)

        fun decideState(found: Boolean, access: PhotoAccess, isPrivateFile: Boolean): PhotoState = when {
            found -> PhotoState.AVAILABLE
            isPrivateFile || access == PhotoAccess.FULL -> PhotoState.MISSING
            else -> PhotoState.UNREADABLE
        }
    }
}
