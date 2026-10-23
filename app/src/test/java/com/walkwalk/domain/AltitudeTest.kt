package com.walkwalk.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AltitudeTest {
    @Test
    fun baroAltitudeAtStandardPressure() = assertEquals(0.0, Altitude.baroAltitude(1013.25), 0.01)

    @Test
    fun baroAltitudeAt900hPa() = assertEquals(989.0, Altitude.baroAltitude(900.0), 1.0)

    @Test
    fun fuseUsesBaroShapeAnchoredToGps() {
        // gps noisy around 1000, pressure falling by 1 hPa (~9.2 m up at 900 hPa)
        val pts = (0 until 12).map { i ->
            p(i * 2000L, gpsAlt = 1000.0 + if (i % 2 == 0) 15 else -15, pressure = 900.0 - i / 11.0)
        }
        val f = Altitude.fuse(pts)
        assertEquals(1000.0, f.first().altitude!!, 1.0)
        assertEquals(f.first().altitude!! + 9.2, f.last().altitude!!, 0.5)
    }

    @Test
    fun fuseFallsBackToGpsWithoutBarometer() {
        val f = Altitude.fuse(listOf(p(0, gpsAlt = 500.0), p(1000, gpsAlt = 510.0)))
        assertEquals(listOf(500.0, 510.0), f.map { it.altitude })
    }

    @Test
    fun ascentIgnoresJitterBelowThreshold() {
        val v = doubleArrayOf(100.0, 103.0, 99.0, 102.0, 100.0)
        assertEquals(0.0 to 0.0, Altitude.ascentDescent(v))
    }

    @Test
    fun ascentCountsRealClimb() {
        val v = doubleArrayOf(100.0, 110.0, 120.0, 115.0, 100.0)
        assertEquals(20.0 to 20.0, Altitude.ascentDescent(v))
    }

    @Test
    fun smoothAveragesNeighbours() {
        val s = Altitude.smooth(doubleArrayOf(0.0, 0.0, 10.0, 0.0, 0.0), window = 5)
        assertEquals(2.0, s[2], 1e-9)
        assertEquals(10.0 / 3, s[0], 1e-9)
    }
}
