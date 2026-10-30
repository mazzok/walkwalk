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

    @Test fun takenBetweenSelectionMatchesWithStringArgs() {
        val db = android.database.sqlite.SQLiteDatabase.create(null)
        db.execSQL("CREATE TABLE images (_id INTEGER, datetaken INTEGER, date_added INTEGER)")
        db.execSQL("INSERT INTO images VALUES (1, 5000, 5)")       // taken inside
        db.execSQL("INSERT INTO images VALUES (2, NULL, 6)")       // added inside (6000 ms)
        db.execSQL("INSERT INTO images VALUES (3, 20000, 20)")     // taken outside
        db.execSQL("INSERT INTO images VALUES (4, NULL, 30)")      // added outside
        db.execSQL("INSERT INTO images VALUES (5, 1000, 7)")       // taken outside, added inside: DATE_TAKEN wins
        val (sel, args) = PhotoStore.takenBetweenQuery(4_000, 8_000)
        val ids = db.rawQuery("SELECT _id FROM images WHERE $sel ORDER BY _id", args).use { c ->
            generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList()
        }
        assertEquals(listOf(1L, 2L), ids)
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
