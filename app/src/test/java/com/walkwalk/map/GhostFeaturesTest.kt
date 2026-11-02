package com.walkwalk.map

import com.walkwalk.domain.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

class GhostFeaturesTest {
    private fun tp(lat: Double, lon: Double, seg: Int) =
        TrackPoint(0L, lat, lon, null, null, null, seg)

    @Test
    fun colorHexDropsAlphaAndUppercases() {
        assertEquals("#CC3333", colorHex(0xFFCC3333.toInt()))
        assertEquals("#002200", colorHex(0x80002200.toInt()))
    }

    @Test
    fun dotFeaturesCarryIdColorAndCoordinates() {
        val f = ghostDotFeatures(listOf(
            GhostMarker(1, 0xFFCC3333.toInt(), 47.0, 11.0),
            GhostMarker(2, 0xFF002200.toInt(), 48.5, 12.5),
        )).features()!!
        assertEquals(2, f.size)
        val p = f[1].geometry() as Point
        assertEquals(12.5, p.longitude(), 0.0)
        assertEquals(48.5, p.latitude(), 0.0)
        assertEquals(1L, f[0].getNumberProperty("id").toLong())
        assertEquals(2L, f[1].getNumberProperty("id").toLong())
        assertEquals("#CC3333", f[0].getStringProperty("color"))
        assertEquals("#002200", f[1].getStringProperty("color"))
    }

    @Test
    fun dotFeaturesEmpty() {
        assertEquals(0, ghostDotFeatures(emptyList()).features()!!.size)
    }

    @Test
    fun trailSkipsSinglePointSegments() {
        val pts = listOf(tp(1.0, 2.0, 0), tp(1.1, 2.1, 0), tp(1.2, 2.2, 0), tp(5.0, 6.0, 1))
        val f = ghostTrailFeatures(listOf(GhostTrail(7, 0xFFCC3333.toInt(), pts))).features()!!
        assertEquals(1, f.size)
        assertEquals(3, (f[0].geometry() as LineString).coordinates().size)
        assertEquals(7L, f[0].getNumberProperty("id").toLong())
        assertEquals("#CC3333", f[0].getStringProperty("color"))
    }

    @Test
    fun trailsKeepInputOrder() {
        val a = GhostTrail(1, 0xFF111111.toInt(), listOf(tp(1.0, 1.0, 0), tp(1.1, 1.1, 0)))
        val b = GhostTrail(2, 0xFF222222.toInt(), listOf(tp(2.0, 2.0, 0), tp(2.1, 2.1, 0)))
        val f = ghostTrailFeatures(listOf(a, b)).features()!!
        assertEquals(2, f.size)
        assertEquals(1L, f[0].getNumberProperty("id").toLong())
        assertEquals(2L, f[1].getNumberProperty("id").toLong())
    }
}
