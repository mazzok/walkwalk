package com.walkwalk.domain

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Plain decimal degrees (never scientific notation), 7 places ≈ 1 cm; trailing zeros dropped. */
fun decimal(deg: Double): String = java.math.BigDecimal(deg).setScale(7, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

const val MAX_ACCURACY_M = 50f
const val MAX_WALK_SPEED_MS = 30.0 / 3.6

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Haversine distance in metres. */
    fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun distance(a: TrackPoint, b: TrackPoint): Double = distance(a.lat, a.lon, b.lat, b.lon)

    /** Distance from the first point to each point; no distance is added across segment boundaries. */
    fun cumulativeDistances(points: List<TrackPoint>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val cur = points[i]
            out[i] = out[i - 1] + if (prev.segment == cur.segment) distance(prev, cur) else 0.0
        }
        return out
    }

    /** Ingestion filter: drop inaccurate fixes and fixes implying an impossible walking speed. */
    fun acceptFix(prev: TrackPoint?, next: TrackPoint): Boolean {
        if (next.accuracy != null && next.accuracy > MAX_ACCURACY_M) return false
        if (prev == null) return true
        val dt = (next.time - prev.time) / 1000.0
        if (dt <= 0) return false
        return distance(prev, next) / dt <= MAX_WALK_SPEED_MS
    }
}
