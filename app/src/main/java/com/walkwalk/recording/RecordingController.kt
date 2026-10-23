package com.walkwalk.recording

import com.walkwalk.data.HikeEntity
import com.walkwalk.data.HikeRepository
import com.walkwalk.data.HikeStatus
import com.walkwalk.domain.Altitude
import com.walkwalk.domain.Geo
import com.walkwalk.domain.TrackPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class Fix(val time: Long, val lat: Double, val lon: Double, val mslAltitude: Double?, val accuracy: Float?)

interface LocationSource {
    /** Cold flow of GPS fixes; GPS is on only while collected. */
    fun fixes(): kotlinx.coroutines.flow.Flow<Fix>
}

interface PressureSource {
    /** Latest barometer reading in hPa, or null without a barometer. */
    fun latestHpa(): Double?
    fun start() {}
    fun stop() {}
}

data class LiveState(
    val hikeId: Long,
    val status: HikeStatus,
    val elapsedMs: Long,
    val distanceM: Double,
    val altitudeM: Double?,
    val speedKmh: Double,
    val ascentM: Double,
    val track: List<TrackPoint>,
)

/**
 * Owns the recording state machine. Every accepted fix is written to storage immediately, so a killed
 * process loses nothing; [restoreActive] picks an unfinished hike back up in a new segment.
 */
class RecordingController(
    private val repo: HikeRepository,
    private val location: LocationSource,
    private val pressure: PressureSource,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val _state = MutableStateFlow<LiveState?>(null)
    val state: StateFlow<LiveState?> = _state

    private val mutex = Mutex()
    private var collectJob: Job? = null
    @Volatile private var generation = 0
    private var segment = 0
    private val live = LiveStats()

    suspend fun start(): Long = mutex.withLock {
        _state.value?.let { return it.hikeId }
        // an unfinished hike is never orphaned: Start continues it (it ends only with Stop)
        repo.activeHike()?.let { hike ->
            restoreLocked(hike)
            if (hike.status == HikeStatus.PAUSED) resumeLocked()
            return hike.id
        }
        val id = repo.startHike(clock())
        segment = 0
        live.reset(emptyList())
        publish(id, HikeStatus.RECORDING)
        collect(id)
        id
    }

    suspend fun pause() = mutex.withLock {
        val s = _state.value ?: return
        if (s.status != HikeStatus.RECORDING) return
        stopCollecting()
        repo.setStatus(s.hikeId, HikeStatus.PAUSED)
        publish(s.hikeId, HikeStatus.PAUSED)
    }

    suspend fun resume() = mutex.withLock { resumeLocked() }

    private suspend fun resumeLocked() {
        val s = _state.value ?: return
        if (s.status != HikeStatus.PAUSED) return
        segment = (repo.lastSegment(s.hikeId) ?: -1) + 1
        repo.setStatus(s.hikeId, HikeStatus.RECORDING)
        publish(s.hikeId, HikeStatus.RECORDING)
        collect(s.hikeId)
    }

    /** Ends the hike; the only way a recording ends. Returns the hike id, or null if nothing was recording. */
    suspend fun stop(): Long? = mutex.withLock {
        val s = _state.value ?: return null
        stopCollecting()
        // finishing must not be cut short by the caller going away
        try {
            withContext(NonCancellable) { repo.finish(s.hikeId, clock()) }
        } finally {
            _state.value = null
        }
        s.hikeId
    }

    /** Continues an unfinished hike after a restart. Returns false when there is nothing to continue. */
    suspend fun restoreActive(): Boolean = mutex.withLock {
        if (_state.value != null) return true
        val hike = repo.activeHike() ?: return false
        restoreLocked(hike)
        true
    }

    private suspend fun restoreLocked(hike: HikeEntity) {
        live.reset(repo.points(hike.id))
        segment = (repo.lastSegment(hike.id) ?: -1) + 1
        publish(hike.id, hike.status)
        if (hike.status == HikeStatus.RECORDING) collect(hike.id)
    }

    /** Stops GPS without touching the hike, e.g. when the system destroys the service. */
    fun detach() {
        stopCollecting()
        _state.value = null
    }

    private fun collect(hikeId: Long) {
        stopCollecting()
        pressure.start()
        val gen = ++generation
        collectJob = scope.launch {
            location.fixes().collect { fix -> mutex.withLock { onFixLocked(gen, hikeId, fix) } }
        }
    }

    private fun stopCollecting() {
        generation++
        collectJob?.cancel()
        collectJob = null
        pressure.stop()
    }

    private suspend fun onFixLocked(gen: Int, hikeId: Long, fix: Fix) {
        // a fix from a collector that was paused/stopped meanwhile is dropped
        if (gen != generation || _state.value?.status != HikeStatus.RECORDING) return
        val point = TrackPoint(fix.time, fix.lat, fix.lon, fix.mslAltitude, pressure.latestHpa(), fix.accuracy, segment)
        val prev = live.last?.takeIf { it.segment == segment }
        if (!Geo.acceptFix(prev, point)) return
        repo.appendPoint(hikeId, point)
        live.add(point)
        publish(hikeId, HikeStatus.RECORDING)
    }

    private fun publish(hikeId: Long, status: HikeStatus) {
        _state.value = LiveState(
            hikeId, status, live.elapsedMs, live.distanceM, live.altitudeM, live.speedKmh, live.ascentM,
            live.track.toList(),
        )
    }

    /** Cheap incremental stats for the live screen; the stored hike is recomputed properly on stop. */
    private class LiveStats {
        val track = mutableListOf<TrackPoint>()
        val last get() = track.lastOrNull()
        var distanceM = 0.0; private set
        var ascentM = 0.0; private set
        var altitudeM: Double? = null; private set
        private var closedSegmentsMs = 0L
        private var segmentStart: TrackPoint? = null
        private var ascentRef: Double? = null
        private var baroAnchor: Double? = null

        val elapsedMs get() = closedSegmentsMs + (segmentStart?.let { s -> last!!.time - s.time } ?: 0)

        val speedKmh: Double
            get() {
                val l = last ?: return 0.0
                val from = track.lastOrNull { it.segment == l.segment && l.time - it.time >= SPEED_WINDOW_MS }
                    ?: track.firstOrNull { it.segment == l.segment } ?: return 0.0
                val dt = l.time - from.time
                if (dt <= 0) return 0.0
                return Geo.distance(from, l) / (dt / 1000.0) * 3.6
            }

        fun reset(points: List<TrackPoint>) {
            track.clear(); distanceM = 0.0; ascentM = 0.0; altitudeM = null
            closedSegmentsMs = 0; segmentStart = null; ascentRef = null; baroAnchor = null
            points.forEach { add(it) }
        }

        fun add(p: TrackPoint) {
            val prev = last
            if (prev == null || prev.segment != p.segment) {
                if (prev != null && segmentStart != null) closedSegmentsMs += prev.time - segmentStart!!.time
                segmentStart = p
            } else {
                distanceM += Geo.distance(prev, p)
            }
            track += p
            altitudeM = liveAltitude(p)
            altitudeM?.let { a ->
                val ref = ascentRef
                if (ref == null) ascentRef = a
                else if (a - ref >= 5.0) { ascentM += a - ref; ascentRef = a }
                else if (ref - a >= 5.0) ascentRef = a
            }
        }

        private fun liveAltitude(p: TrackPoint): Double? {
            val hpa = p.pressureHpa ?: return p.gpsAltitude
            val baro = Altitude.baroAltitude(hpa)
            if (baroAnchor == null && p.gpsAltitude != null) baroAnchor = p.gpsAltitude - baro
            return baro + (baroAnchor ?: 0.0)
        }

        companion object {
            const val SPEED_WINDOW_MS = 30_000L
        }
    }
}
