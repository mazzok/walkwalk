package com.walkwalk.domain

data class HikeStats(
    val distanceM: Double,
    val totalMs: Long,
    val movingMs: Long,
    val ascentM: Double,
    val descentM: Double,
    val minAltM: Double?,
    val maxAltM: Double?,
    /** distance / moving time */
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
)

object Stats {
    /** Speed at each point: distance covered within ±windowMs/2 (same segment) divided by the elapsed time. */
    fun speedsKmh(points: List<TrackPoint>, windowMs: Long = 30_000): DoubleArray {
        val cum = Geo.cumulativeDistances(points)
        val half = windowMs / 2
        val out = DoubleArray(points.size)
        var lo = 0
        var hi = 0
        for (i in points.indices) {
            val t = points[i].time
            val seg = points[i].segment
            while (lo < i && (points[lo].time < t - half || points[lo].segment != seg)) lo++
            if (hi < i) hi = i
            while (hi + 1 < points.size && points[hi + 1].time <= t + half && points[hi + 1].segment == seg) hi++
            // fixes sparser than the window would leave a lone point: reach for its neighbours instead
            val from = if (lo == hi && i > 0 && points[i - 1].segment == seg) i - 1 else lo
            val to = if (lo == hi && i + 1 < points.size && points[i + 1].segment == seg) i + 1 else hi
            val dt = points[to].time - points[from].time
            out[i] = if (dt <= 0) 0.0 else (cum[to] - cum[from]) / (dt / 1000.0) * 3.6
        }
        return out
    }

    fun inStop(time: Long, stops: List<Stop>): Boolean = stops.any { time >= it.start && time <= it.end }

    fun compute(points: List<TrackPoint>, stops: List<Stop>): HikeStats {
        val distance = Geo.cumulativeDistances(points).lastOrNull() ?: 0.0
        val total = points.groupBy { it.segment }.values.sumOf { seg -> seg.last().time - seg.first().time }
        val stopped = stops.sumOf { it.end - it.start }
        val moving = (total - stopped).coerceAtLeast(0)

        val alts = points.mapNotNull { it.altitude }.toDoubleArray()
        val smoothed = Altitude.smooth(alts)
        val (ascent, descent) = Altitude.ascentDescent(smoothed)

        val speeds = speedsKmh(points)
        val maxSpeed = points.indices.filter { !inStop(points[it].time, stops) }.maxOfOrNull { speeds[it] } ?: 0.0

        return HikeStats(
            distanceM = distance,
            totalMs = total,
            movingMs = moving,
            ascentM = ascent,
            descentM = descent,
            minAltM = smoothed.minOrNull(),
            maxAltM = smoothed.maxOrNull(),
            avgSpeedKmh = if (moving == 0L) 0.0 else distance / (moving / 1000.0) * 3.6,
            maxSpeedKmh = maxSpeed,
        )
    }
}
