package com.walkwalk.share

import android.net.Uri
import com.walkwalk.data.HikeRepository
import com.walkwalk.data.Landmark
import com.walkwalk.data.LandmarkPhotoEntity
import com.walkwalk.domain.GpxException
import com.walkwalk.domain.GpxParser
import com.walkwalk.domain.GpxWaypoint
import com.walkwalk.domain.GpxWriter
import com.walkwalk.domain.ParsedGpx
import com.walkwalk.domain.TrackPoint
import com.walkwalk.photos.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ReadBundle(val bundleId: String, val gpx: ParsedGpx, val photoUris: Map<String, String>)

/** A `.walkwalk` file: a zip of `hike.gpx` (photos as waypoint links), `walkwalk.json` and `photos/N.jpg`. */
object HikeBundle {
    const val MAX_EDGE_PX = 1600
    const val JPEG_QUALITY = 80
    const val WARN_BYTES = 25L * 1024 * 1024

    private const val GPX = "hike.gpx"
    private const val META = "walkwalk.json"
    private val PHOTO_ENTRY = Regex("photos/[^/]+\\.jpg")

    /** [photo] returns the shrunk JPEG for a photo, or null when it is missing; those get no file and no link. */
    fun write(
        out: OutputStream, bundleId: String, name: String, points: List<TrackPoint>, landmarks: List<Landmark>,
        photo: (LandmarkPhotoEntity) -> ByteArray?,
    ) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(META))
            zip.write(JSONObject().put("bundleId", bundleId).put("format", 1).toString().toByteArray())
            zip.closeEntry()
            // each photo is streamed into the zip as soon as it is shrunk, so no more than one is held in memory;
            // hike.gpx comes last because its links depend on which photos succeeded (the reader ignores entry order)
            var count = 0
            val waypoints = landmarks.map { lm ->
                val links = lm.photos.mapNotNull { p ->
                    photo(p)?.let { bytes ->
                        "photos/${++count}.jpg".also { entry ->
                            zip.putNextEntry(ZipEntry(entry))
                            zip.write(bytes)
                            zip.closeEntry()
                        }
                    }
                }
                GpxWaypoint(lm.lat, lm.lon, lm.time, links)
            }
            zip.putNextEntry(ZipEntry(GPX))
            GpxWriter.write(name, points, zip, waypoints) // flushes, does not close
            zip.closeEntry()
        }
    }

    /** Only `photos/<name>.jpg` entries reach [savePhoto]; everything else (zip-slip names included) is ignored. */
    fun read(input: InputStream, savePhoto: (entryName: String, InputStream) -> String): ReadBundle {
        var gpxBytes: ByteArray? = null
        var bundleId: String? = null
        val photoUris = mutableMapOf<String, String>()
        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: break
            when {
                entry.name == GPX -> gpxBytes = zip.readBytes()
                entry.name == META -> bundleId = parseBundleId(zip.readBytes())
                PHOTO_ENTRY.matches(entry.name) -> photoUris[entry.name] = savePhoto(entry.name, NonClosingIn(zip))
            }
        }
        val gpx = gpxBytes ?: throw GpxException("Not a walkwalk file")
        return ReadBundle(bundleId ?: throw GpxException("Not a walkwalk file"), GpxParser.parse(ByteArrayInputStream(gpx)), photoUris)
    }

    /** Reads just `walkwalk.json`, so a known bundle can be recognised before anything is stored. */
    fun peekBundleId(input: InputStream): String {
        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.name == META) return parseBundleId(zip.readBytes())
        }
        throw GpxException("Not a walkwalk file")
    }

    fun fileName(hikeName: String): String =
        hikeName.replace(Regex("[^A-Za-z0-9 _.-]"), "_").ifBlank { "hike" } + ".walkwalk"

    // a higher "format" is still read: unknown fields are ignored
    private fun parseBundleId(bytes: ByteArray): String = try {
        JSONObject(String(bytes, Charsets.UTF_8)).getString("bundleId").also { if (it.isBlank()) throw GpxException("Not a walkwalk file") }
    } catch (e: JSONException) {
        throw GpxException("Not a walkwalk file")
    }

    /** Keeps a callee's close() from ending the surrounding zip stream. */
    private class NonClosingIn(private val s: InputStream) : InputStream() {
        override fun read() = s.read()
        override fun read(b: ByteArray, off: Int, len: Int) = s.read(b, off, len)
        override fun close() {}
    }
}

/** [existing] is true when the bundle was already here and nothing was imported. */
data class ImportResult(val hikeId: Long, val existing: Boolean)

class BundleImporter(private val repo: HikeRepository, private val photos: PhotoStore) {

    /** Returns the existing hike (existing = true) when this bundle was imported (or exported) here before. */
    suspend fun import(input: () -> InputStream, fallbackName: String): ImportResult = withContext(Dispatchers.IO) {
        val bundleId = input().use { HikeBundle.peekBundleId(it) }
        repo.hikeByBundleId(bundleId)?.let { return@withContext ImportResult(it.id, true) }

        // the new hike id is unknown while streaming, so photos land in a staging dir and move once it exists
        val staging = File(photos.privateDir(0).parentFile, ".import-${UUID.randomUUID()}")
        var hikeId: Long? = null
        try {
            val bundle = input().use { stream ->
                HikeBundle.read(stream) { entry, photoIn ->
                    val file = File(staging, entry.substringAfterLast('/'))
                    file.parentFile?.mkdirs()
                    file.outputStream().use { photoIn.copyTo(it) }
                    Uri.fromFile(file).toString()
                }
            }
            val id = repo.importBundle(bundle, fallbackName) { id ->
                hikeId = id
                if (bundle.photoUris.isEmpty()) emptyMap() else {
                    val target = photos.privateDir(id)
                    target.deleteRecursively()
                    target.parentFile?.mkdirs()
                    if (!staging.renameTo(target)) throw java.io.IOException("could not store photos")
                    bundle.photoUris.mapValues { (_, uri) -> Uri.fromFile(File(target, File(Uri.parse(uri).path!!).name)).toString() }
                }
            }
            ImportResult(id, false)
        } catch (e: Throwable) {
            hikeId?.let { photos.deletePrivate(it) }
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }
}
