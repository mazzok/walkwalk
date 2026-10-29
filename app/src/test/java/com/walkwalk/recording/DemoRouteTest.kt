package com.walkwalk.recording

import com.walkwalk.domain.Geo
import com.walkwalk.domain.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// org.json is only real under Robolectric
@RunWith(RobolectricTestRunner::class)
class DemoRouteTest {
    private val route = DemoMode.parseRoute(File("src/debug/assets/${DemoMode.ASSET}").readText())
    private val dt = DemoMode.FIX_EVERY_MS * DemoMode.SPEEDUP

    private fun point(i: Int) = route[i].let { TrackPoint(i * dt, it.lat, it.lon, it.elevation, null, 5f, 0) }

    @Test
    fun everyDemoFixPassesTheWalkingFilter() {
        for (i in 1 until route.size) assertTrue("fix $i rejected", Geo.acceptFix(point(i - 1), point(i)))
    }

    @Test
    fun demoCoversAKilometreEveryThirtySeconds() {
        val metres = (1 until route.size).sumOf { Geo.distance(point(it - 1), point(it)) }
        val realSeconds = (route.size - 1) * DemoMode.FIX_EVERY_MS / 1000.0
        assertEquals(1000.0 / 30, metres / realSeconds, 1.0)
    }
}
