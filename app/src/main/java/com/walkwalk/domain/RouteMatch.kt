package com.walkwalk.domain

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class LatLon(val lat: Double, val lon: Double)

/** A hike's track, resampled for route comparison. */
data class RouteShape(val hikeId: Long, val points: List<LatLon>)

/** Finds hikes that took the same route, in the same direction. */
object RouteMatch {
    const val STEP_M = 10.0
    const val MAX_POINTS = 500

    /**
     * Points evenly spaced along the track (segment gaps are bridged), so the recording rate and stops do not
     * matter. Long tracks get a coarser step to stay within [maxPoints].
     */
    fun resample(points: List<TrackPoint>, stepM: Double = STEP_M, maxPoints: Int = MAX_POINTS): List<LatLon> {
        if (points.isEmpty()) return emptyList()
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) cum[i] = cum[i - 1] + Geo.distance(points[i - 1], points[i])
        val total = cum.last()
        val first = LatLon(points.first().lat, points.first().lon)
        if (total <= 0.0) return listOf(first)
        val step = max(stepM, total / maxPoints)
        val n = floor(total / step + 1e-9).toInt()
        val out = ArrayList<LatLon>(n + 2)
        var j = 0
        for (k in 0..n) {
            val at = k * step
            while (j < points.size - 2 && cum[j + 1] < at) j++
            val span = cum[j + 1] - cum[j]
            val f = if (span > 0) ((at - cum[j]) / span).coerceIn(0.0, 1.0) else 0.0
            val a = points[j]
            val b = points[j + 1]
            out += LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
        }
        if (total - n * step > 1e-6) out += LatLon(points.last().lat, points.last().lon)
        return out
    }

    /**
     * Discrete Fréchet distance in metres: the shortest leash that lets two walkers cover [a] and [b] start to
     * end without stepping back. Null when it exceeds [limitM] (computation stops early then).
     */
    fun frechetM(a: List<LatLon>, b: List<LatLon>, limitM: Double): Double? {
        if (a.isEmpty() || b.isEmpty()) return null
        fun d(i: Int, j: Int) = Geo.distance(a[i].lat, a[i].lon, b[j].lat, b[j].lon)
        // necessary for any leash: both starts and both ends are within reach
        if (d(0, 0) > limitM || d(a.lastIndex, b.lastIndex) > limitM) return null
        val inf = Double.POSITIVE_INFINITY
        var prev = DoubleArray(b.size) { inf }
        var cur = DoubleArray(b.size)
        for (i in a.indices) {
            var any = false
            for (j in b.indices) {
                val reach = when {
                    i == 0 && j == 0 -> 0.0
                    i == 0 -> cur[j - 1]
                    j == 0 -> prev[0]
                    else -> min(prev[j], min(prev[j - 1], cur[j - 1]))
                }
                val v = if (reach == inf) inf else max(reach, d(i, j)).let { if (it > limitM) inf else it }
                cur[j] = v
                if (v != inf) any = true
            }
            if (!any) return null
            prev = cur.also { cur = prev }
        }
        return prev[b.lastIndex].takeIf { it != inf }
    }

    /**
     * Groups hikes on the same route. [shapes] should be oldest first: each hike joins the group whose first
     * hike (its leader) is nearest within [limitM], or starts a new group. Members keep their input order.
     */
    fun group(shapes: List<RouteShape>, limitM: Double): List<List<Long>> {
        val leaders = ArrayList<RouteShape>()
        val groups = ArrayList<MutableList<Long>>()
        for (s in shapes) {
            var best = -1
            var bestD = Double.POSITIVE_INFINITY
            leaders.forEachIndexed { g, leader ->
                val dist = frechetM(s.points, leader.points, limitM)
                if (dist != null && dist < bestD) { best = g; bestD = dist }
            }
            if (best >= 0) groups[best] += s.hikeId
            else { leaders += s; groups += mutableListOf(s.hikeId) }
        }
        return groups
    }
}
