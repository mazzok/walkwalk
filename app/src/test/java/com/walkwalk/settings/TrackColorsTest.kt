package com.walkwalk.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackColorsTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun blackCheckOnLightSwatches() {
        assertEquals(black, checkColorOn(0xFFF9A825.toInt())) // yellow
        assertEquals(black, checkColorOn(white))
    }

    @Test
    fun whiteCheckOnDarkSwatches() {
        assertEquals(white, checkColorOn(0xFF212121.toInt()))
        assertEquals(white, checkColorOn(0xFF1565C0.toInt()))
        assertEquals(white, checkColorOn(0xFFCC3333.toInt()))
        assertEquals(white, checkColorOn(0xFF7B1FA2.toInt()))
    }
}
