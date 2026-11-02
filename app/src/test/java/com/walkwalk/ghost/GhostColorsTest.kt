package com.walkwalk.ghost

import com.walkwalk.settings.DEFAULT_TRACK_COLOR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class GhostColorsTest {

    private fun dist(a: Int, b: Int): Double {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    @Test fun avoidsTrackColor() {
        val colors = ghostColors(3, DEFAULT_TRACK_COLOR)
        assertEquals(3, colors.size)
        assertTrue(colors.all { dist(it, DEFAULT_TRACK_COLOR) >= 80.0 })
    }

    @Test fun excludesExactPaletteMatch() {
        val colors = ghostColors(20, GHOST_PALETTE[0])
        assertFalse(colors.contains(GHOST_PALETTE[0]))
    }

    @Test fun cyclesToCount() {
        assertEquals(20, ghostColors(20, DEFAULT_TRACK_COLOR).size)
    }

    @Test fun zeroCountIsEmpty() {
        assertTrue(ghostColors(0, DEFAULT_TRACK_COLOR).isEmpty())
    }
}
