package com.walkwalk.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HikeDao {
    @Insert suspend fun insertHike(hike: HikeEntity): Long
    @Update suspend fun updateHike(hike: HikeEntity)
    @Query("SELECT * FROM HikeEntity WHERE id = :id") suspend fun hike(id: Long): HikeEntity?
    @Query("SELECT * FROM HikeEntity WHERE status = 'FINISHED' ORDER BY startTime DESC")
    fun observeFinished(): Flow<List<HikeEntity>>
    @Query("SELECT * FROM HikeEntity WHERE status = 'FINISHED' ORDER BY startTime DESC")
    suspend fun finished(): List<HikeEntity>
    @Query("SELECT * FROM HikeEntity WHERE status IN ('RECORDING', 'PAUSED') ORDER BY startTime DESC LIMIT 1")
    suspend fun active(): HikeEntity?
    @Query("UPDATE HikeEntity SET status = :status WHERE id = :id") suspend fun setStatus(id: Long, status: HikeStatus)
    @Query("UPDATE HikeEntity SET name = :name WHERE id = :id") suspend fun rename(id: Long, name: String)
    @Query("DELETE FROM HikeEntity WHERE id = :id") suspend fun deleteHike(id: Long)

    @Insert suspend fun insertPoint(p: TrackPointEntity)
    @Insert suspend fun insertPoints(p: List<TrackPointEntity>)
    @Query("SELECT * FROM TrackPointEntity WHERE hikeId = :hikeId ORDER BY time, id")
    suspend fun points(hikeId: Long): List<TrackPointEntity>
    @Query("SELECT MAX(segment) FROM TrackPointEntity WHERE hikeId = :hikeId") suspend fun lastSegment(hikeId: Long): Int?
    @Query("UPDATE TrackPointEntity SET altitude = :altitude WHERE id = :id") suspend fun setAltitude(id: Long, altitude: Double?)
    @Query("DELETE FROM TrackPointEntity WHERE hikeId = :hikeId") suspend fun deletePoints(hikeId: Long)

    @Insert suspend fun insertStops(s: List<StopEntity>)
    @Query("SELECT * FROM StopEntity WHERE hikeId = :hikeId ORDER BY start") suspend fun stops(hikeId: Long): List<StopEntity>
    @Query("DELETE FROM StopEntity WHERE hikeId = :hikeId") suspend fun deleteStops(hikeId: Long)
}
