package com.walkwalk.domain

data class Stop(val start: Long, val end: Long)

object Stops {
    const val RADIUS_M = 20.0
    const val MIN_DURATION_MS = 120_000L

    /** A stop is a run of points within [RADIUS_M] of its first point lasting at least [MIN_DURATION_MS]. */
    fun detect(points: List<TrackPoint>): List<Stop> {
        val stops = mutableListOf<Stop>()
        var i = 0
        while (i < points.size) {
            val anchor = points[i]
            var last = i
            while (last + 1 < points.size &&
                points[last + 1].segment == anchor.segment &&
                Geo.distance(anchor, points[last + 1]) <= RADIUS_M
            ) last++
            if (points[last].time - anchor.time >= MIN_DURATION_MS) {
                val (from, to) = trimToCentre(points, i, last)
                if (points[to].time - points[from].time >= MIN_DURATION_MS) stops += Stop(points[from].time, points[to].time)
                i = last + 1
            } else {
                i++
            }
        }
        return stops
    }

    /** Drops approach/leave points at the run's edges that are farther than half the radius from its centre. */
    private fun trimToCentre(points: List<TrackPoint>, first: Int, last: Int): Pair<Int, Int> {
        val run = points.subList(first, last + 1)
        val lat = run.sumOf { it.lat } / run.size
        val lon = run.sumOf { it.lon } / run.size
        fun near(i: Int) = Geo.distance(lat, lon, points[i].lat, points[i].lon) <= RADIUS_M / 2
        var from = first
        var to = last
        while (from < to && !near(from)) from++
        while (to > from && !near(to)) to--
        return from to to
    }
}
