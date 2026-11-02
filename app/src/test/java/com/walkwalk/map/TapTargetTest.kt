package com.walkwalk.map

import org.junit.Assert.assertEquals
import org.junit.Test

class TapTargetTest {
    @Test
    fun exactPinBeatsGhostSlop() = assertEquals(TapTarget.Pin(1), tapTarget(pinId = 1, ghostId = 2))

    @Test
    fun ghostWinsWhenNoPinIsHit() = assertEquals(TapTarget.Ghost(2), tapTarget(pinId = null, ghostId = 2))

    @Test
    fun pinAloneOpensPin() = assertEquals(TapTarget.Pin(1), tapTarget(pinId = 1, ghostId = null))

    @Test
    fun nothingHitIsAMapTap() = assertEquals(TapTarget.None, tapTarget(pinId = null, ghostId = null))
}
