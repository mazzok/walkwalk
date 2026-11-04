package com.walkwalk.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppSettingsTest {
    private val prefs: SharedPreferences = ApplicationProvider.getApplicationContext<android.app.Application>()
        .getSharedPreferences("settings-test", Context.MODE_PRIVATE)

    @Test
    fun defaults() {
        val settings = AppSettings(prefs)
        assertEquals(DEFAULT_TRACK_COLOR, settings.trackColor.value)
        assertEquals(false, settings.ghostMode.value)
        assertEquals(200, settings.ghostDropM.value)
    }

    @Test
    fun valuesPersistAcrossInstances() {
        val first = AppSettings(prefs)
        first.setTrackColor(TRACK_SWATCHES[5])
        first.setGhostMode(true)
        first.setGhostDropM(500)

        val second = AppSettings(prefs)
        assertEquals(TRACK_SWATCHES[5], second.trackColor.value)
        assertEquals(true, second.ghostMode.value)
        assertEquals(500, second.ghostDropM.value)
    }

    @Test
    fun raceHikeIdPersists() {
        AppSettings(prefs).setRaceHikeId(7)
        assertEquals(7L, AppSettings(prefs).raceHikeId.value)
        AppSettings(prefs).setRaceHikeId(null)
        assertEquals(null, AppSettings(prefs).raceHikeId.value)
    }

    @Test
    fun flowsEmitOnChange() {
        val settings = AppSettings(prefs)
        settings.setGhostMode(true)
        assertEquals(true, settings.ghostMode.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun setGhostDropMRejectsUnsupportedValue() {
        AppSettings(prefs).setGhostDropM(123)
    }

    @Test
    fun unknownDropValueFallsBackToDefault() {
        prefs.edit().putInt("ghost_drop_m", 123).commit()
        assertEquals(200, AppSettings(prefs).ghostDropM.value)
    }
}
