package com.walkwalk.map

import com.walkwalk.data.Landmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Point

class LandmarkFeaturesTest {
    @Test
    fun featuresCarryIdAndSelection() {
        val fc = landmarkFeatures(
            listOf(Landmark(1, 47.0, 11.0, null, emptyList()), Landmark(2, 48.5, 12.5, 5L, emptyList())),
            selectedId = 2,
        )
        val f = fc.features()!!
        assertEquals(2, f.size)
        val p = f[1].geometry() as Point
        assertEquals(12.5, p.longitude(), 0.0)
        assertEquals(48.5, p.latitude(), 0.0)
        assertFalse(f[0].getBooleanProperty("selected"))
        assertTrue(f[1].getBooleanProperty("selected"))
        assertEquals(1L, f[0].getNumberProperty("id").toLong())
        assertEquals(2L, f[1].getNumberProperty("id").toLong())
    }
}
