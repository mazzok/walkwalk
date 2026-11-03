package com.walkwalk.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

/** An arrowhead on the track pointing along the walk; [size] runs from [AccelArrows.MIN_SIZE] to [AccelArrows.MAX_SIZE]. */
data class AccelArrow(val lat: Double, val lon: Double, val bearingDeg: Double, val size: Double)

/**
 * Arrowheads along a hike that show how much the speed is changing, whether up or down: big and close together
 * where it changes hard, small and far apart at a steady pace. One scale, the hike's own strongest change, serves
 * both directions, so equal changes look equal and a calm walk still shows its few pick-ups and slow-downs.
 */
object AccelArrows {
    const val MIN_SIZE = 0.2
    const val MAX_SIZE = 1.0

    private const val GAP_FAR_M = 250.0
    private const val GAP_NEAR_M = 25.0
    private const val REST_BELOW_KMH = 0.05

    /** Changes slower than this (km/h per second) count as a steady pace. */
    private const val STEADY_BELOW = 0.01

    /** One strong GPS jolt must not flatten everything else, so the scale stops at this share of the changes. */
    private const val SCALE_PERCENTILE = 0.95

    private class Edge(val a: TrackPoint, val b: TrackPoint, val lengthM: Double, val accel: Double)

    fun of(points: List<TrackPoint>, stops: List<Stop>): List<AccelArrow> {
        if (points.size < 2) return emptyList()
        val speeds = Stats.speedsKmh(points)
        val edges = (1 until points.size).mapNotNull { i ->
            val a = points[i - 1]
            val b = points[i]
            val dtS = (b.time - a.time) / 1000.0
            val resting = Stats.inStop(a.time, stops) || Stats.inStop(b.time, stops) || (speeds[i - 1] + speeds[i]) / 2 < REST_BELOW_KMH
            if (a.segment != b.segment || dtS <= 0 || resting) null
            else Edge(a, b, Geo.distance(a, b), (speeds[i] - speeds[i - 1]) / dtS)
        }
        val scale = scale(edges.map { abs(it.accel) }.filter { it > STEADY_BELOW })

        val out = ArrayList<AccelArrow>()
        var sinceLastM = 0.0
        for (e in edges) {
            val size = sizeFor(abs(e.accel), scale)
            val gap = GAP_NEAR_M + (GAP_FAR_M - GAP_NEAR_M) * (MAX_SIZE - size) / (MAX_SIZE - MIN_SIZE)
            val bearing = bearingDeg(e.a, e.b)
            var at = 0.0
            while (e.lengthM > 0 && sinceLastM + (e.lengthM - at) >= gap) {
                at += gap - sinceLastM
                sinceLastM = 0.0
                val f = at / e.lengthM
                out += AccelArrow(e.a.lat + (e.b.lat - e.a.lat) * f, e.a.lon + (e.b.lon - e.a.lon) * f, bearing, size)
            }
            sinceLastM += e.lengthM - at
        }
        return out
    }

    /** The change that maps to the full size step, or 0 when nothing changes that way. */
    private fun scale(changes: List<Double>): Double {
        if (changes.isEmpty()) return 0.0
        val sorted = changes.sorted()
        return sorted[((sorted.size - 1) * SCALE_PERCENTILE).roundToInt()]
    }

    private fun sizeFor(change: Double, scale: Double): Double =
        if (change > STEADY_BELOW && scale > 0) MIN_SIZE + (MAX_SIZE - MIN_SIZE) * min(change / scale, 1.0) else MIN_SIZE

    private fun bearingDeg(a: TrackPoint, b: TrackPoint): Double {
        val north = b.lat - a.lat
        val east = (b.lon - a.lon) * cos(Math.toRadians(a.lat))
        return (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
    }
}
