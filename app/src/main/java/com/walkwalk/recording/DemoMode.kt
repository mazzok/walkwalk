package com.walkwalk.recording

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/**
 * Debug demo: replays the bundled route (debug assets) in place of real GPS, as a time-lapse.
 * One fix per real second while the recorder's clock runs [SPEEDUP]× faster, so the route's ~33 m
 * spacing reads as walking pace (6 km/h) to the recorder while the map covers 1 km every 30 s.
 */
class DemoMode(private val context: Context) {
    @Volatile var enabled = false
        private set
    @Volatile private var realStart = 0L
    private var next = 0
    private val route by lazy { parseRoute(context.assets.open(ASSET).bufferedReader().use { it.readText() }) }

    /** Called before Start; the demo runs until the hike is stopped. */
    fun enable() {
        realStart = System.currentTimeMillis()
        next = 0
        enabled = true
    }

    fun disable() {
        enabled = false
    }

    /** The recorder's clock: wall time, sped up while the demo runs. */
    fun now(): Long {
        val real = System.currentTimeMillis()
        return if (enabled) realStart + (real - realStart) * SPEEDUP else real
    }

    fun location(gps: LocationSource) = object : LocationSource {
        override fun fixes(): Flow<Fix> = if (enabled) demoFixes() else gps.fixes()
    }

    // a phone lying on the desk would pin the barometric altitude, so the demo uses the route's heights
    fun pressure(baro: PressureSource) = object : PressureSource {
        override fun latestHpa(): Double? = if (enabled) null else baro.latestHpa()
        override fun start() { if (!enabled) baro.start() }
        override fun stop() = baro.stop()
    }

    // continues where it left off after Pause/Resume; the hike stays open at the end until Stop
    private fun demoFixes(): Flow<Fix> = flow {
        while (next < route.size) {
            val p = route[next++]
            emit(Fix(now(), p.lat, p.lon, p.elevation, ACCURACY_M))
            delay(FIX_EVERY_MS)
        }
    }

    data class RoutePoint(val lat: Double, val lon: Double, val elevation: Double)

    companion object {
        const val ASSET = "demo_route.json"
        const val SPEEDUP = 20L
        const val FIX_EVERY_MS = 1_000L
        private const val ACCURACY_M = 5f

        fun parseRoute(json: String): List<RoutePoint> {
            val points = JSONObject(json).getJSONArray("points")
            return List(points.length()) { i ->
                val p = points.getJSONArray(i)
                RoutePoint(p.getDouble(0), p.getDouble(1), p.getDouble(2))
            }
        }
    }
}
