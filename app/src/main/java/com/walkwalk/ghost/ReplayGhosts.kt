package com.walkwalk.ghost

import com.walkwalk.data.HikeRepository
import com.walkwalk.domain.SpeedHeatmap
import com.walkwalk.domain.TrackPoint

/**
 * An old hike shown beside a replay. [vanishAtMs] is the moment on the replayed hike's own clock (see
 * [GhostTrack]) at which the replayed hike has been off this ghost's route long enough for it to go.
 */
class ReplayGhost(
    val id: Long,
    val name: String,
    val track: GhostTrack,
    val heat: SpeedHeatmap,
    val vanishAtMs: Long?,
) {
    /** Visibility depends only on the moment, so scrubbing back brings a vanished ghost back. */
    fun visibleAt(elapsedMs: Long): Boolean = vanishAtMs == null || elapsedMs < vanishAtMs
}

/**
 * The moment at which [hikePoints] has been farther than [limitM] from [ghost]'s route for
 * [OFF_ROUTE_HOLD_MS], or null if that never happens. Same rule as live ghost mode, run over the
 * finished hike; a [limitM] of 0 means the option is Off.
 */
fun vanishMomentMs(hikePoints: List<TrackPoint>, ghost: GhostTrack, limitM: Int): Long? {
    if (limitM <= 0 || hikePoints.isEmpty()) return null
    val clock = GhostTrack(hikePoints)
    val tracker = OffRouteTracker(limitM)
    for ((i, p) in hikePoints.withIndex()) {
        val t = clock.elapsedAtIndex(i)
        if (tracker.update(t, ghost.distanceToTrackM(p.lat, p.lon))) return t
    }
    return null
}

/** The ghosts visible at [elapsedMs]; colours follow a ghost's place in the full list, so they never shift. */
fun replayGhostViews(ghosts: List<ReplayGhost>, elapsedMs: Long, trackColor: Int): List<GhostView> {
    val colors = ghostColors(ghosts.size, trackColor)
    return ghosts.withIndex().filter { (_, g) -> g.visibleAt(elapsedMs) }.map { (i, g) ->
        val (lat, lon) = g.track.positionAt(elapsedMs)
        GhostView(
            id = g.id, name = g.name, colorArgb = colors[i], lat = lat, lon = lon,
            deltaMs = null,
            finishedMs = if (elapsedMs >= g.track.totalMs) g.track.totalMs else null,
        )
    }
}

/**
 * Every other saved hike that started within [MATCH_RADIUS_M] of where [hikePoints] starts, older or newer,
 * as a ghost with its vanish moment worked out against [hikePoints]. Hikes without two usable points are skipped.
 */
suspend fun loadReplayGhosts(repo: HikeRepository, hikeId: Long, hikePoints: List<TrackPoint>, dropM: Int): List<ReplayGhost> {
    val start = hikePoints.firstOrNull() ?: return emptyList()
    val candidates = repo.ghostCandidates().filter { it.hike.id != hikeId }
    return matchGhosts(start, candidates).mapNotNull { c ->
        val points = repo.points(c.hike.id)
        if (points.size < 2) return@mapNotNull null
        val track = GhostTrack(points)
        ReplayGhost(c.hike.id, c.hike.name, track, SpeedHeatmap.of(points, repo.stops(c.hike.id)), vanishMomentMs(hikePoints, track, dropM))
    }
}
