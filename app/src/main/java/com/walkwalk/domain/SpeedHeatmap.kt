package com.walkwalk.domain

import kotlin.math.roundToInt

/** One stretch of track between two fixes. [speedKmh] is null while resting. */
data class SpeedEdge(val from: TrackPoint, val to: TrackPoint, val speedKmh: Double?)

/** Per-edge speeds of a hike plus the slowest and fastest moving speed, which scale the heatmap colours. */
class SpeedHeatmap private constructor(val edges: List<SpeedEdge>, val minKmh: Double, val maxKmh: Double) {
    companion object {
        private const val REST_BELOW_KMH = 0.05

        fun of(points: List<TrackPoint>, stops: List<Stop>): SpeedHeatmap {
            val speeds = Stats.speedsKmh(points)
            val edges = (1 until points.size).filter { points[it - 1].segment == points[it].segment }.map { i ->
                val a = points[i - 1]
                val b = points[i]
                val kmh = (speeds[i - 1] + speeds[i]) / 2
                val resting = Stats.inStop(a.time, stops) || Stats.inStop(b.time, stops) || kmh < REST_BELOW_KMH
                SpeedEdge(a, b, if (resting) null else kmh)
            }
            val moving = edges.mapNotNull { it.speedKmh }
            return SpeedHeatmap(edges, moving.minOrNull() ?: 0.0, moving.maxOrNull() ?: 0.0)
        }

        /** Linear blend from blue at [min] to red at [max]. */
        fun colorFor(kmh: Double, min: Double, max: Double): Int {
            val t = if (max <= min) 0.0 else ((kmh - min) / (max - min)).coerceIn(0.0, 1.0)
            val r = (255 * t).roundToInt()
            val b = (255 * (1 - t)).roundToInt()
            return (0xFF shl 24) or (r shl 16) or b
        }
    }
}
