package com.walkwalk.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    // Room's generated v1 schema (commit 21f87f7), so the migration is tested against what is on real phones.
    private val v1Schema = listOf(
        "CREATE TABLE IF NOT EXISTS `HikeEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER, `status` TEXT NOT NULL, `source` TEXT NOT NULL, `hasTimes` INTEGER NOT NULL, `demCorrected` INTEGER NOT NULL, `distanceM` REAL NOT NULL, `totalMs` INTEGER NOT NULL, `movingMs` INTEGER NOT NULL, `ascentM` REAL NOT NULL, `descentM` REAL NOT NULL, `minAltM` REAL, `maxAltM` REAL, `avgSpeedKmh` REAL NOT NULL, `maxSpeedKmh` REAL NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `TrackPointEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hikeId` INTEGER NOT NULL, `segment` INTEGER NOT NULL, `time` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `gpsAltitude` REAL, `pressureHpa` REAL, `accuracy` REAL, `altitude` REAL)",
        "CREATE INDEX IF NOT EXISTS `index_TrackPointEntity_hikeId` ON `TrackPointEntity` (`hikeId`)",
        "CREATE TABLE IF NOT EXISTS `StopEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hikeId` INTEGER NOT NULL, `start` INTEGER NOT NULL, `end` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_StopEntity_hikeId` ON `StopEntity` (`hikeId`)",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'f8b83a09c7403889253fcbc690b3f2f5')",
    )

    @Test
    fun v1DataSurvivesUpgrade() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("migration-test.db")
        file.parentFile?.mkdirs()
        file.delete()
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        v1Schema.forEach { old.execSQL(it) }
        old.execSQL("PRAGMA user_version = 1")
        old.execSQL(
            "INSERT INTO HikeEntity (id, name, startTime, endTime, status, source, hasTimes, demCorrected, distanceM, totalMs, movingMs, ascentM, descentM, avgSpeedKmh, maxSpeedKmh) " +
                "VALUES (1, 'Old hike', 0, 1000, 'FINISHED', 'RECORDED', 0, 0, 0, 0, 0, 0, 0, 0, 0)",
        )
        old.execSQL("INSERT INTO TrackPointEntity (hikeId, segment, time, lat, lon) VALUES (1, 0, 0, 47.0, 11.0)")
        old.execSQL("INSERT INTO TrackPointEntity (hikeId, segment, time, lat, lon) VALUES (1, 0, 1000, 47.001, 11.0)")
        old.execSQL("INSERT INTO StopEntity (hikeId, start, `end`) VALUES (1, 0, 500)")
        old.close()

        val db = Room.databaseBuilder(ctx, WalkwalkDb::class.java, "migration-test.db")
            .addMigrations(WalkwalkDb.MIGRATION_1_2, WalkwalkDb.MIGRATION_2_3).allowMainThreadQueries().build()
        try {
            val repo = HikeRepository(db)
            val hike = repo.finishedHikes().single()
            assertEquals("Old hike", hike.name)
            assertNull(hike.bundleId)
            assertNull(hike.routeName)
            repo.nameRoute(listOf(hike.id), "A nach B")
            assertEquals("A nach B", repo.finishedHikes().single().routeName)
            assertEquals(2, repo.points(hike.id).size)
            assertEquals(1, repo.stops(hike.id).size)
            repo.addLandmark(hike.id, 48.0, 16.0, null)
            assertEquals(48.0, repo.landmarks(hike.id).single().lat, 0.0)
        } finally {
            db.close()
        }
    }
}
