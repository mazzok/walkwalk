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
        val takenExpr = "COALESCE(${MediaStore.Images.Media.DATE_TAKEN}, ${MediaStore.Images.Media.DATE_ADDED} * 1000)"
        val out = mutableListOf<GalleryPhoto>()
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED),
                "$takenExpr BETWEEN ? AND ?",
                arrayOf(from.toString(), to.toString()),
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

    /** A pending MediaStore row in Pictures/walkwalk for the camera app to write into (EXTRA_OUTPUT). */
    override fun newCameraTarget(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "walkwalk_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/walkwalk")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("could not create camera target")
    }

    override fun commitCameraTarget(uri: Uri) {
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }

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
        fun takenAt(dateTakenMs: Long?, dateAddedSec: Long): Long = dateTakenMs ?: (dateAddedSec * 1000)

        fun decideState(found: Boolean, access: PhotoAccess, isPrivateFile: Boolean): PhotoState = when {
            found -> PhotoState.AVAILABLE
            isPrivateFile || access == PhotoAccess.FULL -> PhotoState.MISSING
            else -> PhotoState.UNREADABLE
        }
    }
}
