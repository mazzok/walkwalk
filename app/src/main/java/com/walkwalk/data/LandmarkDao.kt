package com.walkwalk.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LandmarkDao {
    @Insert suspend fun insertLandmark(l: LandmarkEntity): Long
    @Insert suspend fun insertPhotos(p: List<LandmarkPhotoEntity>)

    // nulls last: SQLite has no NULLS LAST on old versions, so sort on the null flag first
    @Query("SELECT * FROM LandmarkEntity WHERE hikeId = :hikeId ORDER BY time IS NULL, time, id")
    fun observeLandmarks(hikeId: Long): Flow<List<LandmarkEntity>>
    @Query("SELECT * FROM LandmarkEntity WHERE hikeId = :hikeId ORDER BY time IS NULL, time, id")
    suspend fun landmarks(hikeId: Long): List<LandmarkEntity>

    @Query("SELECT * FROM LandmarkPhotoEntity WHERE landmarkId IN (SELECT id FROM LandmarkEntity WHERE hikeId = :hikeId) ORDER BY id")
    fun observePhotosOfHike(hikeId: Long): Flow<List<LandmarkPhotoEntity>>
    @Query("SELECT * FROM LandmarkPhotoEntity WHERE landmarkId IN (SELECT id FROM LandmarkEntity WHERE hikeId = :hikeId) ORDER BY id")
    suspend fun photosOfHike(hikeId: Long): List<LandmarkPhotoEntity>
    @Query("SELECT * FROM LandmarkPhotoEntity WHERE landmarkId = :landmarkId ORDER BY id")
    suspend fun photosOf(landmarkId: Long): List<LandmarkPhotoEntity>

    @Query("DELETE FROM LandmarkPhotoEntity WHERE id = :photoId") suspend fun deletePhoto(photoId: Long)
    @Query("DELETE FROM LandmarkPhotoEntity WHERE landmarkId = :landmarkId") suspend fun deletePhotosOf(landmarkId: Long)
    @Query("DELETE FROM LandmarkEntity WHERE id = :landmarkId") suspend fun deleteLandmark(landmarkId: Long)
    @Query("DELETE FROM LandmarkPhotoEntity WHERE landmarkId IN (SELECT id FROM LandmarkEntity WHERE hikeId = :hikeId)")
    suspend fun deletePhotosOfHike(hikeId: Long)
    @Query("DELETE FROM LandmarkEntity WHERE hikeId = :hikeId") suspend fun deleteLandmarksOfHike(hikeId: Long)
}
