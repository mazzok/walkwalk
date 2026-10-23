package com.walkwalk.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [HikeEntity::class, TrackPointEntity::class, StopEntity::class], version = 1, exportSchema = false)
abstract class WalkwalkDb : RoomDatabase() {
    abstract fun hikeDao(): HikeDao

    companion object {
        fun create(context: Context): WalkwalkDb =
            Room.databaseBuilder(context, WalkwalkDb::class.java, "walkwalk.db").build()
    }
}
