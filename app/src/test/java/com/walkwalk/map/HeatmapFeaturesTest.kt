package com.walkwalk.map

import com.walkwalk.domain.AccelArrow
import com.walkwalk.domain.SpeedHeatmap
import com.walkwalk.domain.Stop
import com.walkwalk.domain.p
import com.walkwalk.domain.straightLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.LineString

class HeatmapFeaturesTest {
    @Test
    fun arrowFeaturesCarryTheirPositionBearingAndSize() {
        val f = arrowFeatures(listOf(AccelArrow(47.5, 11.5, 90.0, 0.7))).features()!!.single()
        val at = f.geometry() as org.maplibre.geojson.Point
        assertEquals(47.5, at.latitude(), 1e-9)
        assertEquals(11.5, at.longitude(), 1e-9)
        assertEquals(90.0, f.getNumberProperty("bearing").toDouble(), 1e-9)
        assertEquals(0.7, f.getNumberProperty("size").toDouble(), 1e-9)
    }

    @Test
    fun oneTwoPointLinePerEdge() {
        val heat = SpeedHeatmap.of(straightLine(1.0, 5_000, 5), emptyList())
        val f = heatmapFeatures(heat).features()!!
        assertEquals(4, f.size)
        assertEquals(2, (f[0].geometry() as LineString).coordinates().size)
    }

    @Test
    fun slowestEdgeIsBlueAndFastestIsRed() {
        // 1 m/s for 30 s, then 2 m/s
        val slow = straightLine(1.0, 5_000, 8)
        val fast = straightLine(2.0, 5_000, 8, startTime = 35_000, startLat = slow.last().lat + 5.0 / 111_195.0)
        val f = heatmapFeatures(SpeedHeatmap.of(slow + fast, emptyList())).features()!!
        val colors = f.map { it.getStringProperty("color") }
        assertTrue(colors.contains("#0000FF"))
        assertTrue(colors.contains("#FF0000"))
        // the slow half starts bluer than the fast half ends up
        assertTrue(colors.first() < colors.last())
    }

    @Test
    fun explicitRangeRecolours() {
        val heat = SpeedHeatmap.of(straightLine(1.0, 5_000, 10), emptyList())
        val low = heatmapFeatures(heat, 0.0..3.6).features()!!.map { it.getStringProperty("color") }
        val high = heatmapFeatures(heat, 3.6..7.2).features()!!.map { it.getStringProperty("color") }
        assertTrue(low.contains("#FF0000"))
        assertTrue(high.contains("#0000FF"))
    }

    @Test
    fun ghostHeatFeaturesContainEveryEdgeOfEveryHeatmap() {
        val a = SpeedHeatmap.of(straightLine(1.0, 5_000, 5), emptyList())
        val b = SpeedHeatmap.of(straightLine(2.0, 5_000, 8), emptyList())
        val f = ghostHeatFeatures(listOf(a, b), 3.6..7.2).features()!!
        assertEquals(4 + 7, f.size)
    }

    @Test
    fun restingEdgesAreGrey() {
        val pts = straightLine(1.0, 5_000, 10)
        val f = heatmapFeatures(SpeedHeatmap.of(pts, listOf(Stop(20_000, 30_000)))).features()!!
        assertEquals(HEATMAP_REST_HEX, f[4].getStringProperty("color"))
    }
}
