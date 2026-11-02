package com.walkwalk.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class FormatTest {
    private val zone = ZoneId.of("Europe/Vienna")
    private fun at(day: Int, hour: Int, min: Int) = ZonedDateTime.of(2026, 11, day, hour, min, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `range on one day names the date once`() {
        assertEquals("Sun 1 Nov 2026, 09:12 and 13:45", Format.range(at(1, 9, 12), at(1, 13, 45), zone))
    }

    @Test
    fun `delta signs ahead with plus and behind with a true minus`() {
        assertEquals("+2:30", Format.delta(150_000))
        assertEquals("−1:10", Format.delta(-70_000))
        assertEquals("+0:00", Format.delta(0))
        assertEquals("+75:00", Format.delta(4_500_000))
    }

    @Test
    fun `range over midnight names both dates`() {
        assertEquals("Sun 1 Nov 2026, 22:00 and Mon 2 Nov 2026, 02:30", Format.range(at(1, 22, 0), at(2, 2, 30), zone))
    }
}
