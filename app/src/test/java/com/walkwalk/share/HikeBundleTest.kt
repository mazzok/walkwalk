package com.walkwalk.share

import com.walkwalk.data.Landmark
import com.walkwalk.data.LandmarkPhotoEntity
import com.walkwalk.domain.GpxException
import com.walkwalk.domain.straightLine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class HikeBundleTest {
    private fun photo(id: Long, lm: Long) = LandmarkPhotoEntity(id = id, landmarkId = lm, uri = "content://p/$id", takenAt = null)

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bos.toByteArray()
    }

    private val gpx = """<gpx version="1.1"><wpt lat="1" lon="2"><link href="photos/1.jpg"/><link href="photos/2.jpg"/></wpt>
        <trk><trkseg><trkpt lat="1" lon="2"><time>2026-01-01T00:00:00Z</time></trkpt></trkseg></trk></gpx>""".toByteArray()
    private val json = """{"bundleId":"b1","format":1}""".toByteArray()

    @Test fun roundTrip() {
        val lms = listOf(
            Landmark(1, 47.0, 11.0, 1000L, listOf(photo(1, 1), photo(2, 1))),
            Landmark(2, 47.1, 11.1, null, emptyList()),
        )
        val bos = ByteArrayOutputStream()
        HikeBundle.write(bos, "b1", "Hike", straightLine(1.25, 2000, 3), lms) { byteArrayOf(1, 2, 3) }
        val saved = mutableMapOf<String, ByteArray>()
        val r = HikeBundle.read(ByteArrayInputStream(bos.toByteArray())) { name, input -> saved[name] = input.readBytes(); "file://$name" }
        assertEquals("b1", r.bundleId)
        assertEquals(3, r.gpx.points.size)
        assertEquals(2, r.gpx.waypoints.size)
        val links = r.gpx.waypoints[0].links
        assertEquals(2, links.size)
        links.forEach { assertArrayEquals(byteArrayOf(1, 2, 3), saved.getValue(it)); assertEquals("file://$it", r.photoUris[it]) }
        assertEquals(0, r.gpx.waypoints[1].links.size)
    }

    @Test fun missingPhotoLeftOut() {
        val lms = listOf(Landmark(1, 47.0, 11.0, null, listOf(photo(1, 1), photo(2, 1))))
        val bos = ByteArrayOutputStream()
        HikeBundle.write(bos, "b1", "Hike", straightLine(1.25, 2000, 3), lms) { if (it.id == 1L) byteArrayOf(9) else null }
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bos.toByteArray())).use { z -> generateSequence { z.nextEntry }.forEach { names += it.name } }
        assertEquals(1, names.count { it.startsWith("photos/") })
        val r = HikeBundle.read(ByteArrayInputStream(bos.toByteArray())) { n, _ -> n }
        assertEquals(1, r.gpx.waypoints.single().links.size)
    }

    @Test fun ignoresZipSlipAndMissingPhotos() {
        val bytes = zip("hike.gpx" to gpx, "walkwalk.json" to json, "photos/1.jpg" to byteArrayOf(1), "../evil.jpg" to byteArrayOf(2))
        val calls = mutableListOf<String>()
        val r = HikeBundle.read(ByteArrayInputStream(bytes)) { n, _ -> calls += n; "u:$n" }
        assertEquals(listOf("photos/1.jpg"), calls)
        assertEquals(setOf("photos/1.jpg"), r.photoUris.keys)
    }

    @Test fun rejectsZipWithoutGpx() {
        assertThrows(GpxException::class.java) {
            HikeBundle.read(ByteArrayInputStream(zip("walkwalk.json" to json))) { n, _ -> n }
        }
    }

    @Test fun fileNameIsSafe() = assertEquals("Thu 29 Oct 2026_ 22_07.walkwalk", HikeBundle.fileName("Thu 29 Oct 2026, 22:07"))
}
