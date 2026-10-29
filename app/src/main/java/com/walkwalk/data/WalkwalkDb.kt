package com.walkwalk.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [HikeEntity::class, TrackPointEntity::class, StopEntity::class, LandmarkEntity::class, LandmarkPhotoEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class WalkwalkDb : RoomDatabase() {
    abstract fun hikeDao(): HikeDao
    abstract fun landmarkDao(): LandmarkDao

    companion object {
        // Never fall back to destructive migration: the v1 database holds real hikes.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `HikeEntity` ADD COLUMN `bundleId` TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS `LandmarkEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hikeId` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `time` INTEGER)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_LandmarkEntity_hikeId` ON `LandmarkEntity` (`hikeId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `LandmarkPhotoEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `landmarkId` INTEGER NOT NULL, `uri` TEXT NOT NULL, `takenAt` INTEGER)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_LandmarkPhotoEntity_landmarkId` ON `LandmarkPhotoEntity` (`landmarkId`)")
            }
        }

        fun create(context: Context): WalkwalkDb =
            Room.databaseBuilder(context, WalkwalkDb::class.java, "walkwalk.db").addMigrations(MIGRATION_1_2).build()
    }
}
