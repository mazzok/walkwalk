package com.walkwalk.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** User preferences backed by SharedPreferences, exposed as StateFlows. */
class AppSettings(private val prefs: SharedPreferences) {
    private val _trackColor = MutableStateFlow(prefs.getInt(KEY_TRACK_COLOR, DEFAULT_TRACK_COLOR))
    val trackColor: StateFlow<Int> = _trackColor

    private val _ghostMode = MutableStateFlow(prefs.getBoolean(KEY_GHOST_MODE, false))
    val ghostMode: StateFlow<Boolean> = _ghostMode

    // 0 means Off; any stored value outside DROP_OPTIONS falls back to the default.
    private val _ghostDropM = MutableStateFlow(
        prefs.getInt(KEY_GHOST_DROP_M, DEFAULT_DROP_M).takeIf { it in DROP_OPTIONS } ?: DEFAULT_DROP_M
    )
    val ghostDropM: StateFlow<Int> = _ghostDropM

    // how close two tracks must stay to count as the same route in the hike list; 0 means Off
    private val _routeGroupM = MutableStateFlow(
        prefs.getInt(KEY_ROUTE_GROUP_M, DEFAULT_ROUTE_GROUP_M).takeIf { it in ROUTE_GROUP_OPTIONS } ?: DEFAULT_ROUTE_GROUP_M
    )
    val routeGroupM: StateFlow<Int> = _routeGroupM

    // the hike started as a race, kept so a restarted recording carries on racing
    private val _raceHikeId = MutableStateFlow(prefs.getLong(KEY_RACE_HIKE_ID, -1).takeIf { it >= 0 })
    val raceHikeId: StateFlow<Long?> = _raceHikeId

    fun setTrackColor(argb: Int) {
        prefs.edit().putInt(KEY_TRACK_COLOR, argb).apply()
        _trackColor.value = argb
    }

    fun setGhostMode(on: Boolean) {
        prefs.edit().putBoolean(KEY_GHOST_MODE, on).apply()
        _ghostMode.value = on
    }

    fun setGhostDropM(m: Int) {
        require(m in DROP_OPTIONS) { "unsupported drop distance: $m" }
        prefs.edit().putInt(KEY_GHOST_DROP_M, m).apply()
        _ghostDropM.value = m
    }

    fun setRouteGroupM(m: Int) {
        require(m in ROUTE_GROUP_OPTIONS) { "unsupported route group distance: $m" }
        prefs.edit().putInt(KEY_ROUTE_GROUP_M, m).apply()
        _routeGroupM.value = m
    }

    fun setRaceHikeId(id: Long?) {
        prefs.edit().apply { if (id == null) remove(KEY_RACE_HIKE_ID) else putLong(KEY_RACE_HIKE_ID, id) }.apply()
        _raceHikeId.value = id
    }

    companion object {
        val DROP_OPTIONS = listOf(0, 100, 200, 500)
        const val DEFAULT_DROP_M = 200
        val ROUTE_GROUP_OPTIONS = listOf(0, 25, 50, 100, 200)
        const val DEFAULT_ROUTE_GROUP_M = 50

        private const val KEY_TRACK_COLOR = "track_color"
        private const val KEY_GHOST_MODE = "ghost_mode"
        private const val KEY_GHOST_DROP_M = "ghost_drop_m"
        private const val KEY_RACE_HIKE_ID = "race_hike_id"
        private const val KEY_ROUTE_GROUP_M = "route_group_m"
    }
}
