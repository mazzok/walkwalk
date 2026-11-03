package com.walkwalk.domain

data class ReplayFrame(
    val clockTime: Long,
    /** Real time since the first point. */
    val elapsedMs: Long,
    val lat: Double,
    val lon: Double,
    val altitude: Double?,
    val speedKmh: Double,
    /** Distance so far. */
    val distanceM: Double,
    /** Ascent so far. */
    val ascentM: Double,
    val inStop: Boolean,
    /** Like [elapsedMs] but without the pauses between segments: the clock a ghost's hike runs on. */
    val activeMs: Long,
)

/**
 * Maps compressed replay time to real hike time. Moving time plays 1:1, each stop collapses to
 * [STOP_REPLAY_MS], and gaps between segments (manual pauses) collapse to nothing.
 */
class ReplayTimeline(private val points: List<TrackPoint>, stops: List<Stop>) {

    private class Piece(val replayStart: Long, val replayLen: Long, val realStart: Long, val realLen: Long, val stop: Boolean)

    private val pieces: List<Piece>
    private val times = LongArray(points.size) { points[it].time }
    private val cumDist = Geo.cumulativeDistances(points)
    private val cumAscent: DoubleArray
    private val altitudes: DoubleArray?
    private val speeds = Stats.speedsKmh(points)
    // time since the first fix, counting only time inside segments
    private val active = LongArray(points.size).also { a ->
        for (i in 1 until points.size) {
            a[i] = a[i - 1] + if (points[i].segment == points[i - 1].segment) points[i].time - points[i - 1].time else 0L
        }
    }

    val durationMs: Long
    val stopMarkers: List<LongRange>

    init {
        val built = mutableListOf<Piece>()
        var replay = 0L
        fun add(realStart: Long, realEnd: Long, stop: Boolean) {
            val realLen = realEnd - realStart
            val replayLen = if (stop) STOP_REPLAY_MS else realLen
            built += Piece(replay, replayLen, realStart, realLen, stop)
            replay += replayLen
        }
        for (seg in segmentSpans()) {
            var cursor = seg.first
            for (s in stops.sortedBy { it.start }) {
                val start = maxOf(s.start, seg.first)
                val end = minOf(s.end, seg.last)
                if (end <= start || start < cursor) continue
                if (start > cursor) add(cursor, start, stop = false)
                add(start, end, stop = true)
                cursor = end
            }
            if (seg.last > cursor || built.isEmpty()) add(cursor, seg.last, stop = false)
        }
        pieces = built
        durationMs = replay
        stopMarkers = built.filter { it.stop }.map { it.replayStart..(it.replayStart + it.replayLen) }

        val alts = points.map { it.altitude }
        altitudes = if (alts.all { it != null }) Altitude.smooth(alts.map { it!! }.toDoubleArray()) else null
        cumAscent = DoubleArray(points.size)
        altitudes?.let { a ->
            var ref = a.firstOrNull() ?: 0.0
            var ascent = 0.0
            for (i in a.indices) {
                if (a[i] - ref >= 5.0) { ascent += a[i] - ref; ref = a[i] } else if (ref - a[i] >= 5.0) ref = a[i]
                cumAscent[i] = ascent
            }
        }
    }

    private fun segmentSpans(): List<LongRange> {
        if (points.isEmpty()) return emptyList()
        val spans = mutableListOf<LongRange>()
        var start = 0
        for (i in 1..points.size) {
            if (i == points.size || points[i].segment != points[start].segment) {
                spans += points[start].time..points[i - 1].time
                start = i
            }
        }
        return spans
    }

    fun frameAt(replayMs: Long): ReplayFrame {
        if (points.isEmpty()) return ReplayFrame(0, 0, 0.0, 0.0, null, 0.0, 0.0, 0.0, false, 0)
        val r = replayMs.coerceIn(0, durationMs)
        val piece = pieceForReplay(r)
        val real = if (piece.replayLen == 0L) piece.realStart
        else piece.realStart + ((r - piece.replayStart).toDouble() / piece.replayLen * piece.realLen).toLong()
        // during a stop the marker stays where the stop began
        val posTime = if (piece.stop) piece.realStart else real
        val (i, f) = locate(posTime)
        val j = minOf(i + 1, points.size - 1)
        fun lerp(a: Double, b: Double) = a + (b - a) * f
        return ReplayFrame(
            clockTime = real,
            elapsedMs = real - points.first().time,
            lat = lerp(points[i].lat, points[j].lat),
            lon = lerp(points[i].lon, points[j].lon),
            altitude = altitudes?.let { lerp(it[i], it[j]) } ?: points[i].altitude,
            speedKmh = if (piece.stop) 0.0 else lerp(speeds[i], speeds[j]),
            distanceM = lerp(cumDist[i], cumDist[j]),
            ascentM = cumAscent[i],
            inStop = piece.stop,
            activeMs = activeAt(real),
        )
    }

    fun replayMsForDistance(distanceM: Double): Long {
        if (points.isEmpty()) return 0
        var idx = cumDist.indexOfFirst { it >= distanceM }
        if (idx < 0) return durationMs
        if (idx == 0) return 0
        val d0 = cumDist[idx - 1]; val d1 = cumDist[idx]
        val f = if (d1 == d0) 0.0 else (distanceM - d0) / (d1 - d0)
        val real = times[idx - 1] + ((times[idx] - times[idx - 1]) * f).toLong()
        return replayForReal(real)
    }

    private fun replayForReal(real: Long): Long {
        val p = pieces.lastOrNull { it.realStart <= real } ?: return 0
        val into = (real - p.realStart).coerceAtMost(p.realLen)
        return p.replayStart + if (p.realLen == 0L) 0 else (into.toDouble() / p.realLen * p.replayLen).toLong()
    }

    private fun pieceForReplay(r: Long): Piece {
        var lo = 0
        var hi = pieces.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (pieces[mid].replayStart <= r) lo = mid else hi = mid - 1
        }
        return pieces[lo]
    }

    /** Index of the last point at or before [time] and the fraction towards the next point. */
    private fun activeAt(time: Long): Long {
        val (i, f) = locate(time)
        val j = minOf(i + 1, points.size - 1)
        return active[i] + ((active[j] - active[i]) * f).toLong()
    }

    private fun locate(time: Long): Pair<Int, Double> {
        var idx = times.binarySearch(time)
        if (idx >= 0) return idx to 0.0
        idx = (-idx - 2).coerceIn(0, points.size - 1)
        val next = minOf(idx + 1, points.size - 1)
        if (next == idx || points[next].segment != points[idx].segment) return idx to 0.0
        return idx to (time - times[idx]).toDouble() / (times[next] - times[idx])
    }

    companion object {
        const val STOP_REPLAY_MS = 30_000L
    }
}
