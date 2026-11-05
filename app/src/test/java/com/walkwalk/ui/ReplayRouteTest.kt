package com.walkwalk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayRouteTest {
    @Test
    fun keptColoursSurviveTheRoute() {
        val kept = mapOf(3L to 0xFFCC3333.toInt(), 12L to 0xFF00897B.toInt())
        assertEquals(kept, ReplayRoute.decodeKept(ReplayRoute.encodeKept(kept)))
    }

    @Test
    fun aSwitchRouteNamesTheHikeTheColoursAndTheDistance() {
        val route = ReplayRoute.of(ReplaySwitch(7, mapOf(3L to -1), 1234.5))
        assertTrue(route.startsWith("replay/7?"))
        assertTrue("kept=3:-1" in route)
        assertTrue("atM=1234.5" in route)
    }

    @Test
    fun aPlainReplayHasNothingKept() {
        assertEquals(emptyMap<Long, Int>(), ReplayRoute.decodeKept(null))
        assertEquals(emptyMap<Long, Int>(), ReplayRoute.decodeKept(""))
        assertNull(ReplayRoute.decodeAtM(null))
    }
}
