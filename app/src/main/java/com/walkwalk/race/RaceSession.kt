package com.walkwalk.race

import android.util.Log
import com.walkwalk.data.HikeRepository
import com.walkwalk.domain.TrackPoint
import com.walkwalk.ghost.GhostTrack
import com.walkwalk.ghost.aheadBehindMs
import com.walkwalk.ghost.matchGhosts
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

sealed interface RaceEvent {
    data object NoRival : RaceEvent
    data class Switched(val rivalId: Long) : RaceEvent
}

/**
 * The race as last published. [timeMs] is the hike's own clock at [gapMs]; [eventSeq] grows with every new
 * [event], so a listener plays each event exactly once.
 */
data class RaceState(
    val hikeId: Long? = null,
    val rivalId: Long? = null,
    val rivalName: String? = null,
    val gapMs: Long? = null,
    val timeMs: Long = 0,
    val paused: Boolean = false,
    val event: RaceEvent? = null,
    val eventSeq: Int = 0,
)

/**
 * Races the current hike against one old hike: the fastest that started where it started and whose route the
 * hike still follows. The rival is locked once picked and replaced only when the hike leaves its route.
 * Loads ghosts whatever the ghost-mode setting says; read-only towards the repository, nothing is persisted.
 */
class RaceSession(
    private val repo: HikeRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val LOAD_RETRY_MS = 30_000L
    }

    private class Inputs(val track: List<TrackPoint>, val elapsedMs: Long, val distanceM: Double, val paused: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(RaceState())
    val state: StateFlow<RaceState> = _state

    // all guarded by mutex
    private var hikeId: Long? = null
    private var candidates: List<RaceCandidate>? = null
    private var rival: RaceCandidate? = null
    private var loadJob: Job? = null
    private var failedAt: Long? = null
    private var latest: Inputs? = null

    /** Waits for a load in progress, if any. */
    internal suspend fun awaitLoad() {
        loadJob?.join()
    }

    suspend fun update(hikeId: Long, track: List<TrackPoint>, elapsedMs: Long, distanceM: Double, paused: Boolean) {
        withContext(dispatcher) {
            mutex.withLock {
                if (this@RaceSession.hikeId != hikeId) {
                    resetLocked()
                    this@RaceSession.hikeId = hikeId
                    _state.value = RaceState(hikeId)
                }
                if (track.isEmpty()) return@withLock
                latest = Inputs(track, elapsedMs, distanceM, paused)
                val all = candidates
                if (all == null) {
                    startLoadLocked(hikeId, track.first())
                    return@withLock
                }
                val last = track.last()
                all.filterNot { it.dropped }.forEach { it.observe(elapsedMs, last.lat, last.lon) }
                if (rival?.dropped == true) choose(all)
                publishLocked()
            }
        }
    }

    fun reset() {
        scope.launch {
            mutex.withLock {
                resetLocked()
                _state.value = RaceState()
            }
        }
    }

    private fun resetLocked() {
        loadJob?.cancel()
        loadJob = null
        hikeId = null
        candidates = null
        rival = null
        failedAt = null
        latest = null
    }

    private fun startLoadLocked(forHike: Long, start: TrackPoint) {
        if (loadJob?.isActive == true) return
        failedAt?.let { if (clock() - it < LOAD_RETRY_MS) return }
        loadJob = scope.launch {
            val fresh = try {
                load(forHike, start)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("walkwalk", "race load failed", e)
                null
            }
            mutex.withLock {
                // a newer hike took over meanwhile: this result is stale
                if (hikeId != forHike) return@withLock
                if (fresh == null) {
                    failedAt = clock()
                    return@withLock
                }
                // after a restart the track may already be long: decide the rival as if it had been there all along
                latest?.let { inputs ->
                    val clockOf = GhostTrack(inputs.track)
                    for ((i, pt) in inputs.track.withIndex()) fresh.forEach { it.observe(clockOf.elapsedAtIndex(i), pt.lat, pt.lon) }
                }
                candidates = fresh
                rival = pickRival(fresh)
                if (rival == null) announce(RaceEvent.NoRival)
                publishLocked()
            }
        }
    }

    /** Replaces a dropped rival; NoRival is announced only once, as later picks can only stay empty. */
    private fun choose(all: List<RaceCandidate>) {
        val next = pickRival(all)
        rival = next
        announce(if (next != null) RaceEvent.Switched(next.id) else RaceEvent.NoRival)
    }

    private fun announce(event: RaceEvent) {
        _state.value = _state.value.copy(event = event, eventSeq = _state.value.eventSeq + 1)
    }

    private fun publishLocked() {
        val inputs = latest ?: return
        val r = rival
        _state.value = _state.value.copy(
            hikeId = hikeId,
            rivalId = r?.id,
            rivalName = r?.name,
            gapMs = r?.let { aheadBehindMs(it.track, inputs.distanceM, inputs.elapsedMs) },
            timeMs = inputs.elapsedMs,
            paused = inputs.paused,
        )
    }

    private suspend fun load(forHike: Long, start: TrackPoint): List<RaceCandidate> {
        val matched = matchGhosts(start, repo.ghostCandidates()).filter { it.hike.id != forHike }
        return matched.mapNotNull { c ->
            val pts = repo.points(c.hike.id)
            if (pts.size < 2) null else RaceCandidate(c.hike.id, c.hike.name, GhostTrack(pts))
        }
    }
}
