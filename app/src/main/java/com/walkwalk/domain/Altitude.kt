package com.walkwalk.domain

import kotlin.math.pow

object Altitude {
    private const val ANCHOR_FIXES = 10
    private const val BARO_SHARE = 0.8

    /** International Standard Atmosphere altitude for a pressure in hPa. */
    fun baroAltitude(pressureHpa: Double): Double =
        44330.0 * (1.0 - (pressureHpa / 1013.25).pow(1.0 / 5.255))

    /**
     * Fills [TrackPoint.altitude]. With a barometer on most points the shape comes from pressure and the
     * absolute level from the mean of the first GPS altitudes; otherwise GPS altitude is used directly.
     */
    fun fuse(points: List<TrackPoint>): List<TrackPoint> {
        if (points.isEmpty()) return points
        val withPressure = points.count { it.pressureHpa != null }
        if (withPressure < BARO_SHARE * points.size) return points.map { it.copy(altitude = it.gpsAltitude) }

        val baro = interpolateMissing(points.map { p -> p.pressureHpa?.let { baroAltitude(it) } }, points)
        val anchorIdx = points.indices.filter { points[it].gpsAltitude != null }.take(ANCHOR_FIXES)
        val offset = if (anchorIdx.isEmpty()) {
            0.0 // no GPS altitude at all: keep the standard-atmosphere level, DEM correction fixes it later
        } else {
            anchorIdx.map { points[it].gpsAltitude!! }.average() - baro[anchorIdx.first()]
        }
        return points.mapIndexed { i, p -> p.copy(altitude = baro[i] + offset) }
    }

    /** Linear interpolation over time for null entries; edges take the nearest known value. */
    private fun interpolateMissing(values: List<Double?>, points: List<TrackPoint>): DoubleArray {
        val known = values.indices.filter { values[it] != null }
        val out = DoubleArray(values.size)
        var k = 0
        for (i in values.indices) {
            val v = values[i]
            if (v != null) { out[i] = v; continue }
            while (k < known.size && known[k] < i) k++
            val after = known.getOrNull(k)
            val before = known.getOrNull(k - 1)
            out[i] = when {
                before == null -> values[after!!]!!
                after == null -> values[before]!!
                else -> {
                    val t0 = points[before].time; val t1 = points[after].time
                    val f = if (t1 == t0) 0.0 else (points[i].time - t0).toDouble() / (t1 - t0)
                    values[before]!! + f * (values[after]!! - values[before]!!)
                }
            }
        }
        return out
    }

    /** Centred moving average; at the edges only the available neighbours are averaged. */
    fun smooth(values: DoubleArray, window: Int = 5): DoubleArray {
        val half = window / 2
        return DoubleArray(values.size) { i ->
            val from = maxOf(0, i - half)
            val to = minOf(values.size - 1, i + half)
            var sum = 0.0
            for (j in from..to) sum += values[j]
            sum / (to - from + 1)
        }
    }

    /** Total ascent and descent with hysteresis, so changes smaller than [threshold] are ignored. */
    fun ascentDescent(smoothed: DoubleArray, threshold: Double = 5.0): Pair<Double, Double> {
        if (smoothed.isEmpty()) return 0.0 to 0.0
        var ref = smoothed[0]
        var ascent = 0.0
        var descent = 0.0
        for (v in smoothed) {
            if (v - ref >= threshold) { ascent += v - ref; ref = v }
            else if (ref - v >= threshold) { descent += ref - v; ref = v }
        }
        return ascent to descent
    }
}
