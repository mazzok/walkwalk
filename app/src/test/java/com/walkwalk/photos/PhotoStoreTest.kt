package com.walkwalk.photos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PhotoStoreTest {
    @Test fun takenAtUsesDateTaken() = assertEquals(1_000L, PhotoStore.takenAt(1_000L, 5))

    @Test fun takenAtFallsBackToDateAdded() = assertEquals(5_000L, PhotoStore.takenAt(null, 5))

    @Test fun decideStateTable() {
        val d = PhotoStore::decideState
        assertEquals(PhotoState.AVAILABLE, d(true, PhotoAccess.FULL, true))
        assertEquals(PhotoState.MISSING, d(false, PhotoAccess.NONE, true))
        assertEquals(PhotoState.AVAILABLE, d(true, PhotoAccess.NONE, false))
        assertEquals(PhotoState.MISSING, d(false, PhotoAccess.FULL, false))
        assertEquals(PhotoState.UNREADABLE, d(false, PhotoAccess.PARTIAL, false))
        assertEquals(PhotoState.UNREADABLE, d(false, PhotoAccess.NONE, false))
    }

    @Test fun privateDirIsPerHike() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PhotoStore(context)
        assertEquals(File(context.filesDir, "photos/7"), store.privateDir(7))
        val f = File(store.privateDir(7).also { it.mkdirs() }, "a.jpg").apply { writeText("x") }
        assertTrue(f.exists())
        store.deletePrivate(7)
        assertFalse(f.exists())
    }
}
