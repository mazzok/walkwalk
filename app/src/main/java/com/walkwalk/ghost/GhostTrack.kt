package com.walkwalk.ghost

import com.walkwalk.domain.Geo
import com.walkwalk.domain.TrackPoint
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Real-time (uncompressed) timeline of an old hike. Stops keep their full duration; gaps between
 * segments (manual pauses) are removed so the ghost never waits through them.
 */
class GhostTrack(val points: List<TrackPoint>) {

    private val elapsed = LongArray(points.size)
    private val cumDist = Geo.cumulativeDistances(points)

    init {
        for (i in 1 until points.size) {
            elapsed[i] = elapsed[i - 1] +
                if (points[i].segment == points[i - 1].segment) points[i].time - points[i - 1].time else 0L
        }
    }

    val totalMs: Long = elapsed.lastOrNull() ?: 0L
    val totalDistanceM: Double = cumDist.lastOrNull() ?: 0.0

    /** Elapsed time at the [i]-th point, on the same clock as [positionAt]. */
    fun elapsedAtIndex(i: Int): Long = elapsed[i]

    fun positionAt(clockMs: Long): Pair<Double, Double> {
        if (points.isEmpty()) return 0.0 to 0.0
        val t = clockMs.coerceIn(0L, totalMs)
        // last index with elapsed <= t
        var lo = 0
        var hi = points.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (elapsed[mid] <= t) lo = mid else hi = mid - 1
        }
        val a = points[lo]
        val next = lo + 1
        if (next >= points.size || points[next].segment != a.segment || elapsed[next] == elapsed[lo]) return a.lat to a.lon
        val f = (t - elapsed[lo]).toDouble() / (elapsed[next] - elapsed[lo])
        val b = points[next]
        return (a.lat + (b.lat - a.lat) * f) to (a.lon + (b.lon - a.lon) * f)
    }

    fun elapsedAtDistance(distanceM: Double): Long {
        if (points.isEmpty() || distanceM <= 0.0) return 0L
        // first index with cumDist >= distanceM
        var lo = 0
        var hi = points.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cumDist[mid] >= distanceM) hi = mid else lo = mid + 1
        }
        if (lo >= points.size) return totalMs
        if (lo == 0) return 0L
        val d0 = cumDist[lo - 1]
        val d1 = cumDist[lo]
        val f = if (d1 > d0) (distanceM - d0) / (d1 - d0) else 0.0
        return elapsed[lo - 1] + ((elapsed[lo] - elapsed[lo - 1]) * f).toLong()
    }

    fun distanceToTrackM(lat: Double, lon: Double): Double {
        if (points.isEmpty()) return Double.MAX_VALUE
        val mPerDegLat = Math.PI / 180.0 * EARTH_RADIUS_M
        val mPerDegLon = mPerDegLat * cos(Math.toRadians(lat))
        fun x(p: TrackPoint) = (p.lon - lon) * mPerDegLon
        fun y(p: TrackPoint) = (p.lat - lat) * mPerDegLat
        var best = hypot(x(points[0]), y(points[0]))
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            if (a.segment != b.segment) {
                best = minOf(best, hypot(x(b), y(b)))
                continue
            }
            val ax = x(a); val ay = y(a)
            val dx = x(b) - ax
            val dy = y(b) - ay
            val len2 = dx * dx + dy * dy
            val f = if (len2 > 0.0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            best = minOf(best, hypot(ax + dx * f, ay + dy * f))
        }
        return best
    }

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
    }
}
