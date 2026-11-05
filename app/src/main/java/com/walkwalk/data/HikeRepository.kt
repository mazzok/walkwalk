package com.walkwalk.data

import androidx.room.withTransaction
import com.walkwalk.domain.Altitude
import com.walkwalk.ghost.GhostCandidate
import com.walkwalk.domain.Geo
import com.walkwalk.domain.ParsedGpx
import com.walkwalk.domain.Stats
import com.walkwalk.domain.Stop
import com.walkwalk.domain.Stops
import com.walkwalk.domain.TrackPoint
import com.walkwalk.share.ReadBundle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class HikeRepository(private val db: WalkwalkDb) {
    private val dao = db.hikeDao()
    private val landmarkDao = db.landmarkDao()

    fun observeFinishedHikes(): Flow<List<HikeEntity>> = dao.observeFinished()
    suspend fun finishedHikes(): List<HikeEntity> = dao.finished()
    suspend fun activeHike(): HikeEntity? = dao.active()
    suspend fun hike(hikeId: Long): HikeEntity? = dao.hike(hikeId)

    suspend fun startHike(now: Long): Long = dao.insertHike(
        HikeEntity(name = defaultName(now), startTime = now, endTime = null, status = HikeStatus.RECORDING, source = HikeSource.RECORDED),
    )

    suspend fun appendPoint(hikeId: Long, p: TrackPoint) = dao.insertPoint(TrackPointEntity.from(hikeId, p))
    suspend fun lastSegment(hikeId: Long): Int? = dao.lastSegment(hikeId)
    suspend fun setStatus(hikeId: Long, status: HikeStatus) = dao.setStatus(hikeId, status)
    suspend fun rename(hikeId: Long, name: String) = dao.rename(hikeId, name)
    suspend fun nameRoute(hikeIds: List<Long>, name: String) = dao.setRouteName(hikeIds, name)

    suspend fun points(hikeId: Long): List<TrackPoint> = dao.points(hikeId).map { it.toPoint() }
    /** Finished timed hikes with at least 2 points, each with its earliest point. */
    suspend fun ghostCandidates(): List<GhostCandidate> {
        val firsts = dao.ghostFirstPoints().associateBy { it.hikeId }
        return dao.finished().mapNotNull { h -> firsts[h.id]?.let { GhostCandidate(h, it.toPoint()) } }
    }

    suspend fun stops(hikeId: Long): List<Stop> = dao.stops(hikeId).map { it.toStop() }

    /** Fuses altitude from the raw readings, detects stops, caches stats and marks the hike finished. */
    suspend fun finish(hikeId: Long, now: Long) = db.withTransaction {
        fuseAltitudes(hikeId)
        recompute(hikeId)
        val hike = dao.hike(hikeId) ?: return@withTransaction
        dao.updateHike(hike.copy(status = HikeStatus.FINISHED, endTime = now))
    }

    /** Re-detects stops and recomputes stats from the stored altitudes (keeps DEM corrections). */
    suspend fun recompute(hikeId: Long) = db.withTransaction {
        val pts = points(hikeId)
        val stops = Stops.detect(pts)
        dao.deleteStops(hikeId)
        dao.insertStops(stops.map { StopEntity(hikeId = hikeId, start = it.start, end = it.end) })
        val hike = dao.hike(hikeId) ?: return@withTransaction
        dao.updateHike(hike.withStats(Stats.compute(pts, stops)))
    }

    /** Returns false (and changes nothing) if the hike was deleted or changed since the altitudes were computed. */
    suspend fun setCorrectedAltitudes(hikeId: Long, altitudes: List<Double>): Boolean = db.withTransaction {
        val hike = dao.hike(hikeId) ?: return@withTransaction false
        val entities = dao.points(hikeId)
        if (entities.size != altitudes.size) return@withTransaction false
        entities.forEachIndexed { i, e -> dao.setAltitude(e.id, altitudes[i]) }
        dao.updateHike(hike.copy(demCorrected = true))
        true
    }

    fun observeLandmarks(hikeId: Long): Flow<List<Landmark>> =
        combine(landmarkDao.observeLandmarks(hikeId), landmarkDao.observePhotosOfHike(hikeId)) { lms, photos ->
            assemble(lms, photos)
        }

    suspend fun landmarks(hikeId: Long): List<Landmark> =
        assemble(landmarkDao.landmarks(hikeId), landmarkDao.photosOfHike(hikeId))

    private fun assemble(lms: List<LandmarkEntity>, photos: List<LandmarkPhotoEntity>): List<Landmark> {
        val byLandmark = photos.groupBy { it.landmarkId }
        return lms.map { Landmark(it.id, it.lat, it.lon, it.time, byLandmark[it.id].orEmpty()) }
    }

    suspend fun addLandmark(hikeId: Long, lat: Double, lon: Double, time: Long?): Long =
        landmarkDao.insertLandmark(LandmarkEntity(hikeId = hikeId, lat = lat, lon = lon, time = time))

    /** A landmark placed on the map takes the time of the nearest track point, if the track has real times. */
    suspend fun addLandmarkAt(hikeId: Long, lat: Double, lon: Double): Long {
        val hike = dao.hike(hikeId)
        val time = if (hike?.hasTimes == true) {
            dao.points(hikeId).minByOrNull { Geo.distance(lat, lon, it.lat, it.lon) }?.time
        } else null
        return addLandmark(hikeId, lat, lon, time)
    }

    suspend fun attachPhotos(landmarkId: Long, photos: List<Pair<String, Long?>>) = db.withTransaction {
        val existing = landmarkDao.photosOf(landmarkId).map { it.uri }.toMutableSet()
        val fresh = photos.filter { existing.add(it.first) }
        landmarkDao.insertPhotos(fresh.map { LandmarkPhotoEntity(landmarkId = landmarkId, uri = it.first, takenAt = it.second) })
    }

    suspend fun removePhoto(photoId: Long) = landmarkDao.deletePhoto(photoId)

    suspend fun deleteLandmark(landmarkId: Long) = db.withTransaction {
        landmarkDao.deletePhotosOf(landmarkId)
        landmarkDao.deleteLandmark(landmarkId)
    }

    suspend fun hikeByBundleId(bundleId: String): HikeEntity? = dao.hikeByBundleId(bundleId)

    suspend fun ensureBundleId(hikeId: Long): String = db.withTransaction {
        dao.hike(hikeId)?.bundleId ?: UUID.randomUUID().toString().also { dao.setBundleId(hikeId, it) }
    }

    suspend fun delete(hikeId: Long) = db.withTransaction {
        landmarkDao.deletePhotosOfHike(hikeId)
        landmarkDao.deleteLandmarksOfHike(hikeId)
        dao.deletePoints(hikeId)
        dao.deleteStops(hikeId)
        dao.deleteHike(hikeId)
    }

    suspend fun importGpx(parsed: ParsedGpx, fallbackName: String): Long {
        val (id, _) = insertImported(parsed, fallbackName)
        finishImport(id)
        return id
    }

    /**
     * Imports a bundle as a new hike. [resolvePhotos] maps the bundle's photo links to stored URIs once the new
     * hike id is known (photos are moved into that hike's folder there). The bundleId is set last, so an
     * interrupted import is never mistaken for a finished one; on failure the hike is removed again.
     */
    suspend fun importBundle(
        bundle: ReadBundle,
        fallbackName: String,
        resolvePhotos: (hikeId: Long) -> Map<String, String> = { bundle.photoUris },
    ): Long {
        val (id, landmarkIds) = insertImported(bundle.gpx, fallbackName)
        try {
            finishImport(id)
            val uris = resolvePhotos(id)
            bundle.gpx.waypoints.forEachIndexed { i, wp ->
                val photos = wp.links.mapNotNull { uris[it] }.map { it to null as Long? }
                if (photos.isNotEmpty()) attachPhotos(landmarkIds[i], photos)
            }
            dao.setBundleId(id, bundle.bundleId)
        } catch (e: Throwable) {
            // also on cancellation; a failing cleanup must not hide the original error
            try { withContext(NonCancellable) { delete(id) } } catch (_: Throwable) {}
            throw e
        }
        return id
    }

    private suspend fun finishImport(id: Long) {
        fuseAltitudes(id)
        recompute(id)
    }

    private suspend fun insertImported(parsed: ParsedGpx, fallbackName: String): Pair<Long, List<Long>> {
        val first = parsed.points.first()
        val last = parsed.points.last()
        var landmarkIds = emptyList<Long>()
        val id = db.withTransaction {
            val id = dao.insertHike(
                HikeEntity(
                    name = parsed.name ?: fallbackName, startTime = first.time, endTime = last.time,
                    status = HikeStatus.FINISHED, source = HikeSource.IMPORTED, hasTimes = parsed.hasTimes,
                ),
            )
            dao.insertPoints(parsed.points.map { TrackPointEntity.from(id, it) })
            landmarkIds = parsed.waypoints.map { addLandmark(id, it.lat, it.lon, it.time) }
            id
        }
        return id to landmarkIds
    }

    private suspend fun fuseAltitudes(hikeId: Long) = db.withTransaction {
        val entities = dao.points(hikeId)
        val fused = Altitude.fuse(entities.map { it.toPoint() })
        entities.forEachIndexed { i, e -> dao.setAltitude(e.id, fused[i].altitude) }
    }

    companion object {
        private val NAME_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH)

        fun defaultName(startTime: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            NAME_FORMAT.format(Instant.ofEpochMilli(startTime).atZone(zone))
    }
}
