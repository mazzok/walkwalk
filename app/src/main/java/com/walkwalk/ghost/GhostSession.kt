package com.walkwalk.ghost

import com.walkwalk.data.HikeRepository
import com.walkwalk.domain.TrackPoint
import com.walkwalk.settings.AppSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class GhostView(
    val id: Long,
    val name: String,
    val colorArgb: Int,
    val lat: Double,
    val lon: Double,
    val deltaMs: Long?,
    val finishedMs: Long?,
)

/**
 * Tracks the old hikes that started where the current hike started, and where each of them would be now.
 * Read-only towards the repository; never touches recording. State is rebuilt from the current track,
 * nothing is persisted.
 */
class GhostSession(
    private val repo: HikeRepository,
    private val settings: AppSettings,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private class Ghost(
        val id: Long,
        val name: String,
        val color: Int,
        val track: GhostTrack,
        val tracker: OffRouteTracker,
    ) {
        var dropped = false
    }

    private val mutex = Mutex()
    private val _ghosts = MutableStateFlow<List<GhostView>>(emptyList())
    val ghosts: StateFlow<List<GhostView>> = _ghosts

    private var hikeId: Long? = null
    private var initialized = false
    private var loaded = emptyList<Ghost>()

    suspend fun update(hikeId: Long, track: List<TrackPoint>, elapsedMs: Long, distanceM: Double) {
        withContext(dispatcher) {
            mutex.withLock {
                if (this@GhostSession.hikeId != hikeId) {
                    this@GhostSession.hikeId = hikeId
                    initialized = false
                    loaded = emptyList()
                    _ghosts.value = emptyList()
                }
                if (track.isEmpty()) return@withLock
                if (!initialized) {
                    initialized = true
                    loaded = if (settings.ghostMode.value) load(track.first()) else emptyList()
                }
                if (loaded.isEmpty()) return@withLock
                val last = track.last()
                val views = ArrayList<GhostView>(loaded.size)
                for (g in loaded) {
                    if (g.dropped) continue
                    if (g.tracker.update(elapsedMs, g.track.distanceToTrackM(last.lat, last.lon))) {
                        g.dropped = true
                        continue
                    }
                    val (lat, lon) = g.track.positionAt(elapsedMs)
                    views += GhostView(
                        id = g.id, name = g.name, colorArgb = g.color, lat = lat, lon = lon,
                        deltaMs = aheadBehindMs(g.track, distanceM, elapsedMs),
                        finishedMs = if (elapsedMs >= g.track.totalMs) g.track.totalMs else null,
                    )
                }
                _ghosts.value = views
            }
        }
    }

    /** The full recorded route of a ghost, for drawing; empty if unknown or dropped. */
    fun trail(id: Long): List<TrackPoint> = loaded.firstOrNull { it.id == id && !it.dropped }?.track?.points.orEmpty()

    private suspend fun load(start: TrackPoint): List<Ghost> {
        val tracks = matchGhosts(start, repo.ghostCandidates()).mapNotNull { c ->
            val pts = repo.points(c.hike.id)
            if (pts.size < 2) null else Triple(c.hike.id, c.hike.name, GhostTrack(pts))
        }
        val colors = ghostColors(tracks.size, settings.trackColor.value)
        val drop = settings.ghostDropM.value
        return tracks.mapIndexed { i, (id, name, t) -> Ghost(id, name, colors[i], t, OffRouteTracker(drop)) }
    }
}
