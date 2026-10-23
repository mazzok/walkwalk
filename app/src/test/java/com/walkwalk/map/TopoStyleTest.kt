package com.walkwalk.map

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TopoStyleTest {
    @Test
    fun styleHasTopoAndTrailLayersWithAttribution() {
        val s = JSONObject(TopoStyle.json())
        assertEquals(8, s.getInt("version"))
        val src = s.getJSONObject("sources")
        val otm = src.getJSONObject("otm")
        assertTrue(otm.getJSONArray("tiles").getString(0).contains("tile.opentopomap.org"))
        assertEquals(17, otm.getInt("maxzoom"))
        assertEquals(256, otm.getInt("tileSize"))
        assertTrue(otm.getString("attribution").contains("OpenTopoMap"))
        assertTrue(otm.getString("attribution").contains("OpenStreetMap"))
        val wmt = src.getJSONObject("wmt")
        assertTrue(wmt.getJSONArray("tiles").getString(0).contains("waymarkedtrails.org/hiking"))
        assertTrue(wmt.getString("attribution").contains("waymarkedtrails.org"))
        val layers = s.getJSONArray("layers")
        assertEquals(listOf("background", "otm", "wmt"), (0 until layers.length()).map { layers.getJSONObject(it).getString("id") })
    }
}
