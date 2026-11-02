package com.walkwalk.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walkwalk.data.HikeEntity
import com.walkwalk.data.HikeRepository
import com.walkwalk.domain.Geo
import com.walkwalk.domain.ReplayFrame
import com.walkwalk.domain.ReplayTimeline
import com.walkwalk.domain.SpeedHeatmap
import com.walkwalk.domain.Stop
import com.walkwalk.domain.TrackPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Altitude-vs-distance data for the elevation profile. Stops are given as distance positions. */
data class Profile(val distances: DoubleArray, val altitudes: DoubleArray, val stopDistances: List<Double>)

class ReplayViewModel(private val repo: HikeRepository, private val hikeId: Long) : ViewModel() {
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
            timeline = ReplayTimeline(pts, stops)
            _canPlay.value = h.hasTimes && durationMs > 0
            seek(_replayMs.value)
        }
    }

    fun seek(replayMs: Long) {
        val t = timeline ?: return
        val clamped = replayMs.coerceIn(0, t.durationMs)
        _replayMs.value = clamped
        _frame.value = t.frameAt(clamped)
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
