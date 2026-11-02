package com.walkwalk.ghost

import android.util.Log
import com.walkwalk.data.HikeRepository
import com.walkwalk.domain.TrackPoint
import com.walkwalk.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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

/** The ghosts last published, and the hike they belong to. */
data class GhostState(val hikeId: Long?, val views: List<GhostView>) {
    /** The ghosts of [hikeId]; none while the session still holds another hike's. */
    fun viewsFor(hikeId: Long): List<GhostView> = if (this.hikeId == hikeId) views else emptyList()
}

/**
 * Tracks the old hikes that started where the current hike started, and where each of them would be now.
 * Read-only towards the repository; never touches recording. State is rebuilt from the current track,
 * nothing is persisted.
 *
 * The (possibly slow) load of the old hikes runs once per hike in a job this session owns, so the
 * per-second [update] never cancels or restarts it; a failed load is retried after [LOAD_RETRY_MS].
 */
class GhostSession(
    private val repo: HikeRepository,
    private val settings: AppSettings,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val LOAD_RETRY_MS = 30_000L
    }

    private class Ghost(
        val id: Long,
        val name: String,
        val track: GhostTrack,
        val tracker: OffRouteTracker,
    ) {
        @Volatile var dropped = false
    }

    private class Inputs(val track: List<TrackPoint>, val elapsedMs: Long, val distanceM: Double)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(GhostState(null, emptyList()))
    val state: StateFlow<GhostState> = _state

    // all guarded by mutex
    private var hikeId: Long? = null
    private var initialized = false
    private var loadJob: Job? = null
    private var failedAt: Long? = null
    private var latest: Inputs? = null
    @Volatile private var loaded = emptyList<Ghost>()

    /** Test hook, called at the start of every load. */
    internal var onLoadStart: suspend () -> Unit = {}

    /** Test hook, called at the start of every render. */
    internal var onRender: () -> Unit = {}

    /** Waits for a load in progress, if any. */
    internal suspend fun awaitLoad() {
        loadJob?.join()
    }

    suspend fun update(hikeId: Long, track: List<TrackPoint>, elapsedMs: Long, distanceM: Double) {
        withContext(dispatcher) {
            mutex.withLock {
                if (this@GhostSession.hikeId != hikeId) {
                    loadJob?.cancel()
                    loadJob = null
                    this@GhostSession.hikeId = hikeId
                    initialized = false
                    failedAt = null
                    loaded = emptyList()
                    _state.value = GhostState(hikeId, emptyList())
                }
                if (track.isEmpty()) return@withLock
                latest = Inputs(track, elapsedMs, distanceM)
                if (!initialized) {
                    startLoadLocked(hikeId, track.first())
                    return@withLock
                }
                renderLocked()
            }
        }
    }

    /** The full recorded route of a ghost, for drawing; empty if unknown or dropped. */
    fun trail(id: Long): List<TrackPoint> = loaded.firstOrNull { it.id == id && !it.dropped }?.track?.points.orEmpty()

    private fun startLoadLocked(forHike: Long, start: TrackPoint) {
        if (loadJob?.isActive == true) return
        failedAt?.let { if (clock() - it < LOAD_RETRY_MS) return }
        if (!settings.ghostMode.value) {
            loaded = emptyList()
            initialized = true
            return
        }
        loadJob = scope.launch {
            val fresh = try {
                onLoadStart()
                load(start)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("walkwalk", "ghost load failed", e)
                null
            }
            mutex.withLock {
                // a newer hike took over meanwhile: this result is stale
                if (hikeId != forHike) return@withLock
                if (fresh == null) {
                    failedAt = clock()
                    return@withLock
                }
                try {
                    loaded = fresh
                    initialized = true
                    failedAt = null
                    renderLocked()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // this job has no exception handler: never let ghost work escape and take recording down
                    Log.w("walkwalk", "ghost render after load failed", e)
                    loaded = emptyList()
                    initialized = false
                    failedAt = clock()
                }
            }
        }
    }

    private fun renderLocked() {
        onRender()
        val inputs = latest ?: return
        if (loaded.isEmpty()) return
        val last = inputs.track.last()
        val elapsedMs = inputs.elapsedMs
        val views = ArrayList<GhostView>(loaded.size)
        // recomputed each render so a track color change mid-hike keeps ghosts distinct from it
        val colors = ghostColors(loaded.size, settings.trackColor.value)
        for ((i, g) in loaded.withIndex()) {
            if (g.dropped) continue
            if (g.tracker.update(elapsedMs, g.track.distanceToTrackM(last.lat, last.lon))) {
                g.dropped = true
                continue
            }
            val (lat, lon) = g.track.positionAt(elapsedMs)
            views += GhostView(
                id = g.id, name = g.name, colorArgb = colors[i], lat = lat, lon = lon,
                deltaMs = aheadBehindMs(g.track, inputs.distanceM, elapsedMs),
                finishedMs = if (elapsedMs >= g.track.totalMs) g.track.totalMs else null,
            )
        }
        _state.value = GhostState(hikeId, views)
    }

    private suspend fun load(start: TrackPoint): List<Ghost> {
        val tracks = matchGhosts(start, repo.ghostCandidates()).mapNotNull { c ->
            val pts = repo.points(c.hike.id)
            if (pts.size < 2) null else Triple(c.hike.id, c.hike.name, GhostTrack(pts))
        }
        val drop = settings.ghostDropM.value
        return tracks.map { (id, name, t) -> Ghost(id, name, t, OffRouteTracker(drop)) }
    }
}
