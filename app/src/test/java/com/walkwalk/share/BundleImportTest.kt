package com.walkwalk.share

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.walkwalk.data.HikeRepository
import com.walkwalk.data.Landmark
import com.walkwalk.data.LandmarkPhotoEntity
import com.walkwalk.data.WalkwalkDb
import com.walkwalk.domain.straightLine
import com.walkwalk.photos.PhotoStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI

@RunWith(RobolectricTestRunner::class)
class BundleImportTest {
    private lateinit var db: WalkwalkDb
    private lateinit var repo: HikeRepository
    private lateinit var photos: PhotoStore
    private lateinit var importer: BundleImporter

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, WalkwalkDb::class.java).allowMainThreadQueries().build()
        repo = HikeRepository(db)
        photos = PhotoStore(context)
        importer = BundleImporter(repo, photos)
    }

    @After
    fun tearDown() = db.close()

    private fun bundle(): ByteArray {
        val p = LandmarkPhotoEntity(id = 1, landmarkId = 1, uri = "content://p/1", takenAt = null)
        val bos = ByteArrayOutputStream()
        HikeBundle.write(bos, "bundle-1", "Hike", straightLine(1.25, 2000, 3), listOf(Landmark(1, 47.0, 11.0, null, listOf(p)))) { byteArrayOf(1, 2, 3) }
        return bos.toByteArray()
    }

    @Test fun sameBundleTwiceOpensExisting() = runTest {
        val bytes = bundle()
        val a = importer.import({ ByteArrayInputStream(bytes) }, "fb")
        val b = importer.import({ ByteArrayInputStream(bytes) }, "fb")
        assertEquals(a, b)
        assertEquals(1, repo.finishedHikes().size)
    }

    @Test fun deleteThenReimport() = runTest {
        val bytes = bundle()
        val a = importer.import({ ByteArrayInputStream(bytes) }, "fb")
        val uri = repo.landmarks(a).single().photos.single().uri
        assertTrue(uri.startsWith("file:"))
        assertTrue(File(URI(uri)).exists())
        assertEquals(photos.privateDir(a), File(URI(uri)).parentFile)
        repo.delete(a); photos.deletePrivate(a)
        assertFalse(photos.privateDir(a).exists())
        val b = importer.import({ ByteArrayInputStream(bytes) }, "fb")
        assertNotEquals(a, b)
        assertTrue(File(URI(repo.landmarks(b).single().photos.single().uri)).exists())
    }

    @Test fun failedImportLeavesNothingAndCanBeRetried() = runTest {
        val bytes = bundle()
        val failing = BundleImporter(repo, object : PhotoStore(ApplicationProvider.getApplicationContext<Context>()) {
            override fun privateDir(hikeId: Long): File =
                // a regular file as parent makes creating the folder fail
                if (hikeId > 0) File(File(super.privateDir(0).parentFile, "blocked").apply { writeText("x") }, "$hikeId") else super.privateDir(hikeId)
        })
        try {
            failing.import({ ByteArrayInputStream(bytes) }, "fb")
            org.junit.Assert.fail("expected failure")
        } catch (_: java.io.IOException) {}
        assertEquals(0, repo.finishedHikes().size)
        val root = photos.privateDir(0).parentFile
        assertEquals(emptyList<String>(), root.list().orEmpty().filter { it.startsWith(".import-") })
        assertEquals(null, repo.hikeByBundleId("bundle-1"))
        File(root, "blocked").delete()

        val id = importer.import({ ByteArrayInputStream(bytes) }, "fb")
        assertEquals(1, repo.finishedHikes().size)
        assertTrue(File(URI(repo.landmarks(id).single().photos.single().uri)).exists())
    }
}
