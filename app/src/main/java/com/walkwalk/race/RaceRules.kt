package com.walkwalk.race

import com.walkwalk.ghost.GhostTrack
import com.walkwalk.ghost.OffRouteTracker

/** Racing drops a ghost at this distance from its route, whatever the ghost-mode drop setting says. */
const val RACE_DROP_M = 100

/** An old hike that could be raced against; [dropped] once the user has left its route. */
class RaceCandidate(val id: Long, val name: String, val track: GhostTrack) {
    val tracker = OffRouteTracker(RACE_DROP_M)
    var dropped = false

    /** Metres per millisecond on the same clock as the gap: stops count, manual pauses don't. */
    val pace: Double = if (track.totalMs > 0) track.totalDistanceM / track.totalMs else 0.0
}

fun RaceCandidate.observe(elapsedMs: Long, lat: Double, lon: Double) {
    if (tracker.update(elapsedMs, track.distanceToTrackM(lat, lon))) dropped = true
}

/** The fastest candidate still on route; ties keep input order. */
fun pickRival(candidates: List<RaceCandidate>): RaceCandidate? =
    candidates.filterNot { it.dropped }.maxByOrNull { it.pace }
