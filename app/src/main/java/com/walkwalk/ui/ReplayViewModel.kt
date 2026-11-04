package com.walkwalk.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walkwalk.data.HikeEntity
import com.walkwalk.data.HikeRepository
import com.walkwalk.domain.AccelArrow
import com.walkwalk.domain.AccelArrows
import com.walkwalk.domain.Geo
import com.walkwalk.domain.ReplayFrame
import com.walkwalk.domain.ReplayTimeline
import com.walkwalk.domain.SpeedHeatmap
import com.walkwalk.domain.Stop
import com.walkwalk.domain.TrackPoint
import com.walkwalk.ghost.GhostView
import com.walkwalk.ghost.ReplayGhost
import com.walkwalk.ghost.loadReplayGhosts
import com.walkwalk.ghost.replayGhostViews
import com.walkwalk.map.GhostTrail
import com.walkwalk.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Altitude-vs-distance data for the elevation profile. Stops are given as distance positions. */
data class Profile(val distances: DoubleArray, val altitudes: DoubleArray, val stopDistances: List<Double>)

class ReplayViewModel(
    private val repo: HikeRepository,
    private val hikeId: Long,
    private val settings: AppSettings,
    private val ghostLoader: suspend (HikeRepository, Long, List<TrackPoint>, Int) -> List<ReplayGhost> = ::loadReplayGhosts,
    private val ghostDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _hike = MutableStateFlow<HikeEntity?>(null)
    val hike: StateFlow<HikeEntity?> = _hike
    private val _points = MutableStateFlow<List<TrackPoint>>(emptyList())
    val points: StateFlow<List<TrackPoint>> = _points
    private val _stopPositions = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val stopPositions: StateFlow<List<Pair<Double, Double>>> = _stopPositions
    private val _profile = MutableStateFlow<Profile?>(null)
    val profile: StateFlow<Profile?> = _profile

    private val _heatmap = MutableStateFlow<SpeedHeatmap?>(null)
    val heatmap: StateFlow<SpeedHeatmap?> = _heatmap
    private val _showHeatmap = MutableStateFlow(false)
    val showHeatmap: StateFlow<Boolean> = _showHeatmap
    private val _arrows = MutableStateFlow<List<AccelArrow>>(emptyList())
    val arrows: StateFlow<List<AccelArrow>> = _arrows
    private val _showArrows = MutableStateFlow(false)
    val showArrows: StateFlow<Boolean> = _showArrows

    // ghost mode is read once, when the replay opens
    private val ghostsEnabled = settings.ghostMode.value
    private var ghosts: List<ReplayGhost> = emptyList()
    private val _ghostViews = MutableStateFlow<List<GhostView>>(emptyList())
    val ghostViews: StateFlow<List<GhostView>> = _ghostViews
    private val _openTrails = MutableStateFlow<Set<Long>>(emptySet())
    val openTrails: StateFlow<Set<Long>> = _openTrails
    private val _shownTrails = MutableStateFlow<List<GhostTrail>>(emptyList())
    /** Trails the user opened whose ghost is still there; re-emitted only when that set changes. */
    val shownTrails: StateFlow<List<GhostTrail>> = _shownTrails
    private val _ghostHeatmaps = MutableStateFlow<List<SpeedHeatmap>>(emptyList())
    val ghostHeatmaps: StateFlow<List<SpeedHeatmap>> = _ghostHeatmaps
    private val _heatRange = MutableStateFlow<ClosedFloatingPointRange<Double>?>(null)
    /** One range for this hike and every shown ghost trail's whole track; null until the hike has loaded. */
    val heatRange: StateFlow<ClosedFloatingPointRange<Double>?> = _heatRange
    private var shownIds: List<Long>? = null

    private val _frame = MutableStateFlow<ReplayFrame?>(null)
    val frame: StateFlow<ReplayFrame?> = _frame
    private val _replayMs = MutableStateFlow(0L)
    val replayMs: StateFlow<Long> = _replayMs
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing
    private val _speedFactor = MutableStateFlow(DEFAULT_SPEED)
    val speedFactor: StateFlow<Int> = _speedFactor
    private val _canPlay = MutableStateFlow(false)
    val canPlay: StateFlow<Boolean> = _canPlay

    private var timeline: ReplayTimeline? = null
    private var playJob: Job? = null

    val durationMs: Long get() = timeline?.durationMs ?: 0L
    val stopMarkers: List<LongRange> get() = timeline?.stopMarkers ?: emptyList()

    init {
        reload()
    }

    /** (Re)loads the hike, e.g. after an altitude correction; keeps the current replay position. */
    fun reload() {
        viewModelScope.launch {
            val h = repo.hike(hikeId) ?: return@launch
            val pts = repo.points(hikeId)
            val stops = repo.stops(hikeId)
            _hike.value = h
            _points.value = pts
            _stopPositions.value = stops.mapNotNull { s -> pts.firstOrNull { it.time >= s.start }?.let { it.lat to it.lon } }
            _profile.value = buildProfile(pts, stops)
            _heatmap.value = SpeedHeatmap.of(pts, stops)
            _arrows.value = AccelArrows.of(pts, stops)
            timeline = ReplayTimeline(pts, stops)
            _canPlay.value = h.hasTimes && durationMs > 0
            seek(_replayMs.value)
            if (ghostsEnabled) loadGhosts(pts)
        }
    }

    /** Ghosts only read; a failure here is logged and replay carries on without them. */
    private fun loadGhosts(pts: List<TrackPoint>) {
        viewModelScope.launch {
            ghosts = try {
                withContext(ghostDispatcher) { ghostLoader(repo, hikeId, pts, settings.ghostDropM.value) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("walkwalk", "replay ghost load failed", e)
                emptyList()
            }
            shownIds = null
            _frame.value?.let { refreshGhosts(it) }
        }
    }

    fun toggleTrail(id: Long) {
        _openTrails.value = if (id in _openTrails.value) _openTrails.value - id else _openTrails.value + id
        _frame.value?.let { refreshGhosts(it) }
    }

    private fun refreshGhosts(f: ReplayFrame) {
        val views = replayGhostViews(ghosts, f.activeMs, settings.trackColor.value, f.distanceM)
        _ghostViews.value = views
        val shown = views.filter { it.id in _openTrails.value }
        val ids = shown.map { it.id }
        if (ids == shownIds) return
        shownIds = ids
        val byId = ghosts.associateBy { it.id }
        val picked = shown.mapNotNull { v -> byId[v.id]?.let { v to it } }
        _shownTrails.value = picked.map { (v, g) -> GhostTrail(g.id, v.colorArgb, g.track.points) }
        val heats = picked.map { it.second.heat }
        _ghostHeatmaps.value = heats
        _heatRange.value = _heatmap.value?.let { SpeedHeatmap.combinedRange(listOf(it) + heats) }
    }

    fun seek(replayMs: Long) {
        val t = timeline ?: return
        val clamped = replayMs.coerceIn(0, t.durationMs)
        _replayMs.value = clamped
        val f = t.frameAt(clamped)
        _frame.value = f
        refreshGhosts(f)
    }

    fun seekToDistance(m: Double) {
        val t = timeline ?: return
        seek(t.replayMsForDistance(m))
    }

    fun togglePlay() {
        if (_playing.value) {
            playJob?.cancel()
            _playing.value = false
            return
        }
        if (!_canPlay.value) return
        if (_replayMs.value >= durationMs) seek(0)
        _playing.value = true
        playJob = viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                val next = _replayMs.value + TICK_MS * _speedFactor.value
                seek(next)
                if (next >= durationMs) break
            }
            _playing.value = false
        }
    }

    fun toggleHeatmap() {
        _showHeatmap.value = !_showHeatmap.value
    }

    fun toggleArrows() {
        _showArrows.value = !_showArrows.value
    }

    fun setSpeed(factor: Int) {
        _speedFactor.value = factor
    }

    fun cycleSpeed() {
        setSpeed(SPEEDS[(SPEEDS.indexOf(_speedFactor.value) + 1) % SPEEDS.size])
    }

    private fun buildProfile(pts: List<TrackPoint>, stops: List<Stop>): Profile? {
        if (pts.size < 2 || pts.any { it.altitude == null }) return null
        val dist = Geo.cumulativeDistances(pts)
        val stopDist = stops.mapNotNull { s -> pts.indexOfFirst { it.time >= s.start }.takeIf { it >= 0 }?.let { dist[it] } }
        return Profile(dist, pts.map { it.altitude!! }.toDoubleArray(), stopDist)
    }

    companion object {
        val SPEEDS = listOf(10, 60, 300)
        const val DEFAULT_SPEED = 60
        const val TICK_MS = 50L
    }
}
