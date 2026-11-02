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

    fun setTrackColor(argb: Int) {
        prefs.edit().putInt(KEY_TRACK_COLOR, argb).apply()
        _trackColor.value = argb
    }

    fun setGhostMode(on: Boolean) {
        prefs.edit().putBoolean(KEY_GHOST_MODE, on).apply()
        _ghostMode.value = on
    }

    fun setGhostDropM(m: Int) {
        prefs.edit().putInt(KEY_GHOST_DROP_M, m).apply()
        _ghostDropM.value = m
    }

    companion object {
        val DROP_OPTIONS = listOf(0, 100, 200, 500)
        const val DEFAULT_DROP_M = 200

        private const val KEY_TRACK_COLOR = "track_color"
        private const val KEY_GHOST_MODE = "ghost_mode"
        private const val KEY_GHOST_DROP_M = "ghost_drop_m"
    }
}
