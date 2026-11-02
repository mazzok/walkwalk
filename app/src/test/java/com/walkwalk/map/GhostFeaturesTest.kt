package com.walkwalk.map

import org.junit.Assert.assertEquals
import org.junit.Test

class GhostFeaturesTest {
    @Test
    fun colorHexDropsAlphaAndUppercases() {
        assertEquals("#CC3333", colorHex(0xFFCC3333.toInt()))
        assertEquals("#002200", colorHex(0x80002200.toInt()))
    }
}
