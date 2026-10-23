package com.walkwalk.domain

/** Test helpers for building tracks. 1e-5 deg latitude ≈ 1.11 m. */
const val M_PER_DEG_LAT = 111_195.0

fun p(
    time: Long,
    lat: Double = 47.0,
    lon: Double = 11.0,
    seg: Int = 0,
    acc: Float? = 5f,
    gpsAlt: Double? = null,
    pressure: Double? = null,
    alt: Double? = null,
) = TrackPoint(time, lat, lon, gpsAlt, pressure, acc, seg, alt)

/** Points heading north at a constant speed. */
fun straightLine(
    metersPerSec: Double,
    everyMs: Long,
    count: Int,
    startTime: Long = 0,
    seg: Int = 0,
    startLat: Double = 47.0,
    alt: Double? = null,
): List<TrackPoint> = (0 until count).map { i ->
    val t = i * everyMs
    p(startTime + t, startLat + metersPerSec * t / 1000.0 / M_PER_DEG_LAT, 11.0, seg = seg, alt = alt)
}

/** Walk at 1 m/s, stand still (tiny jitter), walk again. Points every 5 s. */
fun walkStandWalk(walkMs: Long = 300_000, standMs: Long = 600_000, everyMs: Long = 5_000): List<TrackPoint> {
    val first = straightLine(1.0, everyMs, (walkMs / everyMs).toInt() + 1)
    val standLat = first.last().lat
    val stand = (1..(standMs / everyMs).toInt()).map { i ->
        p(walkMs + i * everyMs, standLat + (if (i % 2 == 0) 2e-5 else -2e-5), 11.0)
    }
    val second = straightLine(1.0, everyMs, (walkMs / everyMs).toInt(), startTime = walkMs + standMs + everyMs, startLat = standLat + everyMs / 1000.0 / M_PER_DEG_LAT)
    return first + stand + second
}

/** seg0 walks 0..600 s, seg1 walks 1800..2400 s, points every 5 s at 1 m/s. */
fun twoSegments(): List<TrackPoint> {
    val a = straightLine(1.0, 5_000, 121, alt = 1000.0)
    val b = straightLine(1.0, 5_000, 121, startTime = 1_800_000, seg = 1, startLat = a.last().lat, alt = 1000.0)
    return a + b
}
