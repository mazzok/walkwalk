package com.walkwalk.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.walkwalk.domain.HikeStats
import com.walkwalk.domain.Stop
import com.walkwalk.domain.TrackPoint

enum class HikeStatus { RECORDING, PAUSED, FINISHED }

enum class HikeSource { RECORDED, IMPORTED }

@Entity
data class HikeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startTime: Long,
    val endTime: Long?,
    val status: HikeStatus,
    val source: HikeSource,
    val hasTimes: Boolean = true,
    val demCorrected: Boolean = false,
    // cached HikeStats
    val distanceM: Double = 0.0,
    val totalMs: Long = 0,
    val movingMs: Long = 0,
    val ascentM: Double = 0.0,
    val descentM: Double = 0.0,
    val minAltM: Double? = null,
    val maxAltM: Double? = null,
    val avgSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
) {
    fun withStats(s: HikeStats) = copy(
        distanceM = s.distanceM, totalMs = s.totalMs, movingMs = s.movingMs, ascentM = s.ascentM,
        descentM = s.descentM, minAltM = s.minAltM, maxAltM = s.maxAltM,
        avgSpeedKmh = s.avgSpeedKmh, maxSpeedKmh = s.maxSpeedKmh,
    )

    val stats: HikeStats
        get() = HikeStats(distanceM, totalMs, movingMs, ascentM, descentM, minAltM, maxAltM, avgSpeedKmh, maxSpeedKmh)
}

@Entity(indices = [Index("hikeId")])
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hikeId: Long,
    val segment: Int,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val gpsAltitude: Double?,
    val pressureHpa: Double?,
    val accuracy: Float?,
    val altitude: Double?,
) {
    fun toPoint() = TrackPoint(time, lat, lon, gpsAltitude, pressureHpa, accuracy, segment, altitude)

    companion object {
        fun from(hikeId: Long, p: TrackPoint) =
            TrackPointEntity(0, hikeId, p.segment, p.time, p.lat, p.lon, p.gpsAltitude, p.pressureHpa, p.accuracy, p.altitude)
    }
}

@Entity(indices = [Index("hikeId")])
data class StopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hikeId: Long,
    val start: Long,
    val end: Long,
) {
    fun toStop() = Stop(start, end)
}
