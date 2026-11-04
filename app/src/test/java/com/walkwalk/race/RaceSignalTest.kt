package com.walkwalk.race

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceSignalTest {
    @Test
    fun intervalEndsAndMiddle() {
        assertEquals(250L, beepIntervalMs(0))
        assertEquals(4000L, beepIntervalMs(120_000))
        assertEquals(4000L, beepIntervalMs(-120_000))
        assertEquals(4000L, beepIntervalMs(600_000))
        assertEquals(2125L, beepIntervalMs(60_000))
    }

    @Test
    fun patternBySide() {
        assertEquals(BeepPattern.SINGLE, beepPattern(5_000)) // user behind: rival ahead
        assertEquals(BeepPattern.DOUBLE, beepPattern(-5_000))
    }

    /** Feeds |gap| samples one per second starting at t=0, returns the last bend. */
    private fun DopplerTracker.feed(vararg gapsS: Double, startS: Int = 0): Double {
        var out = 0.0
        gapsS.forEachIndexed { i, g -> out = update((startS + i) * 1_000L, (g * 1000).toLong()) }
        return out
    }

    @Test
    fun dopplerPositiveWhileClosing() {
        assertEquals(3.0, DopplerTracker().feed(30.0, 29.8, 29.6, 29.4, 29.2, 29.0), 1e-9) // -0.2 s/s, clamped
        assertEquals(1.5, DopplerTracker().feed(30.0, 29.9, 29.8, 29.7, 29.6, 29.5), 0.01) // -0.1 s/s
    }

    @Test
    fun dopplerNegativeWhileOpening() {
        assertEquals(-1.5, DopplerTracker().feed(10.0, 10.1, 10.2, 10.3, 10.4, 10.5), 0.01)
    }

    @Test
    fun dopplerDropsAtPass() {
        val d = DopplerTracker()
        val before = d.feed(4.0, 3.0, 2.0) // rival ahead, closing
        assertTrue(before > 0)
        val after = d.feed(1.0, 0.0, -1.0, -2.0, -3.0, -4.0, -5.0, -6.0, -7.0, -8.0, startS = 3) // passed, opening
        assertTrue(after < 0)
    }

    @Test
    fun dopplerDropsTheMomentOfThePass() {
        val d = DopplerTracker()
        // user gaining 1 s per second on a rival 5 s ahead; passes at t=5
        val before = d.feed(5.0, 4.0, 3.0, 2.0, 1.0)
        assertTrue(before > 0)
        assertTrue(d.update(6_000, -1_000) < 0) // first fix past the rival
    }

    @Test
    fun dopplerSmoothsGpsJitter() {
        val d = DopplerTracker()
        // steady closing at 0.1 s/s (1.5 semitones) with ±1 s of jitter; the window's two ends land on opposite jitter
        val gaps = (0..11).map { 30.0 - 0.1 * it + if (it % 4 < 2) 1.0 else -1.0 }
        val bend = d.feed(*gaps.toDoubleArray())
        assertEquals(1.5, bend, 1.0)
    }

    @Test
    fun dopplerUsesTimestampsNotSampleCount() {
        val d = DopplerTracker()
        d.update(0, 30_000)
        // one sparse fix 30 s later: the gap closed 3 s in 30 s = -0.1 s/s
        assertEquals(1.5, d.update(30_000, 27_000), 0.01)
    }

    @Test
    fun dopplerNeutralUntilTwoSecondsOfSamples() {
        val d = DopplerTracker()
        d.update(0, 30_000)
        assertEquals(0.0, d.update(1_000, 29_000), 0.0)
    }

    @Test
    fun dopplerResetsOnNullGap() {
        val d = DopplerTracker()
        d.feed(30.0, 29.8, 29.6, 29.4)
        assertEquals(0.0, d.update(4_000, null), 0.0)
        assertEquals(0.0, d.update(5_000, 20_000), 0.0)
    }

    @Test
    fun duckHysteresis() {
        val g = DuckGate()
        assertFalse(g.update(50_000))
        assertTrue(g.update(29_000))
        assertTrue(g.update(-35_000))
        assertFalse(g.update(41_000))
        assertFalse(g.update(35_000))
        assertTrue(g.update(10_000))
        assertFalse(g.update(null))
    }
}
