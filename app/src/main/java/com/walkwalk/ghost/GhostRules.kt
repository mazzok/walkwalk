package com.walkwalk.ghost

import com.walkwalk.data.HikeEntity
import com.walkwalk.domain.Geo
import com.walkwalk.domain.TrackPoint

const val MATCH_RADIUS_M = 150.0
const val MIN_COMPARE_DISTANCE_M = 50.0
const val OFF_ROUTE_HOLD_MS = 60_000L

data class GhostCandidate(val hike: HikeEntity, val first: TrackPoint)

/** Candidates whose first point lies within [MATCH_RADIUS_M] of [start], input order kept. */
fun matchGhosts(start: TrackPoint, candidates: List<GhostCandidate>): List<GhostCandidate> =
    candidates.filter { Geo.distance(start, it.first) <= MATCH_RADIUS_M }

/** Positive = user is behind (slower) than the ghost, negative = ahead; null until enough distance. */
fun aheadBehindMs(ghost: GhostTrack, userDistanceM: Double, userElapsedMs: Long): Long? {
    if (userDistanceM < MIN_COMPARE_DISTANCE_M) return null
    return userElapsedMs - ghost.elapsedAtDistance(userDistanceM)
}

/** Reports true (and stays true) once the user has been farther than [limitM] from the track for [OFF_ROUTE_HOLD_MS]. */
class OffRouteTracker(private val limitM: Int) {
    private var farSince: Long? = null
    private var dropped = false

    fun update(elapsedMs: Long, distanceToTrackM: Double): Boolean {
        if (limitM <= 0) return false
        if (dropped) return true
        if (distanceToTrackM > limitM) {
            val since = farSince ?: elapsedMs.also { farSince = it }
            if (elapsedMs - since >= OFF_ROUTE_HOLD_MS) dropped = true
        } else {
            farSince = null
        }
        return dropped
    }
}
