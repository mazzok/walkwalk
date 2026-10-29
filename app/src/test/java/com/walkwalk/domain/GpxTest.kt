package com.walkwalk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class GpxTest {
    private fun res(path: String): InputStream = javaClass.classLoader!!.getResourceAsStream(path)

    @Test
    fun roundTrip() {
        val pts = listOf(
            p(1_700_000_000_000, 47.1, 11.2, alt = 812.5, seg = 0),
            p(1_700_000_005_000, 47.1001, 11.2, alt = 813.0, seg = 1),
        )
        val out = ByteArrayOutputStream()
        GpxWriter.write("Test", pts, out)
        val back = GpxParser.parse(out.toByteArray().inputStream())
        assertEquals("Test", back.name)
        assertTrue(back.hasTimes)
        assertEquals(pts.map { it.time }, back.points.map { it.time })
        assertEquals(listOf(0, 1), back.points.map { it.segment })
        assertEquals(812.5, back.points[0].gpsAltitude!!, 0.01)
    }

    @Test
    fun coordinatesNearGreenwichAreWrittenAsDecimals() {
        val out = ByteArrayOutputStream()
        GpxWriter.write("London", listOf(p(0, 51.5, -0.0005)), out)
        val xml = out.toString(Charsets.UTF_8.name())
        assertTrue(xml, xml.contains("lon=\"-0.0005") && !xml.contains("E-"))
    }

    @Test
    fun parsesGarminFileWithExtensions() {
        val g = GpxParser.parse(res("gpx/garmin.gpx"))
        assertTrue(g.hasTimes)
        assertEquals("Zugspitze Hike", g.name)
        assertEquals(4, g.points.size)
        assertEquals(2, g.points.map { it.segment }.distinct().size)
        assertEquals(1001.4, g.points[0].gpsAltitude!!, 0.01)
        assertNull(g.points[3].gpsAltitude)
    }

    @Test
    fun parsesFileWithoutTimes() {
        val g = GpxParser.parse(res("gpx/no_times.gpx"))
        assertFalse(g.hasTimes)
        assertEquals(3, g.points.size)
        assertEquals(listOf(0L, 1000L, 2000L), g.points.map { it.time })
    }

    @Test
    fun parsesRouteOnly() = assertEquals(2, GpxParser.parse(res("gpx/route_only.gpx")).points.size)

    @Test
    fun waypointsRoundTrip() {
        val wps = listOf(
            GpxWaypoint(48.1, 16.1, 1_700_000_000_000, listOf("photos/1.jpg", "photos/2.jpg")),
            GpxWaypoint(48.2, -0.0005, null),
        )
        val out = ByteArrayOutputStream()
        GpxWriter.write("T", listOf(p(1_700_000_000_000, 47.1, 11.2)), out, wps)
        assertEquals(wps, GpxParser.parse(out.toByteArray().inputStream()).waypoints)
    }

    @Test
    fun waypointNameDescSymIgnored() {
        val xml = """<gpx version="1.1"><wpt lat="1.5" lon="2.5"><ele>10</ele><time>2024-01-01T00:00:00Z</time>
            <name>Summit</name><desc>d</desc><sym>Flag</sym></wpt>
            <trk><name>Track</name><trkseg><trkpt lat="1" lon="2"><time>2024-01-01T00:00:00Z</time></trkpt></trkseg></trk></gpx>"""
        val g = GpxParser.parse(xml.byteInputStream())
        assertEquals("Track", g.name)
        assertEquals(1, g.waypoints.size)
        assertEquals(1, g.points.size)
        assertEquals(1.5, g.waypoints[0].lat, 0.0)
    }

    @Test(expected = GpxException::class)
    fun fileWithOnlyWaypointsIsRejected() {
        GpxParser.parse("""<gpx version="1.1"><wpt lat="1" lon="2"/></gpx>""".byteInputStream())
    }

    @Test(expected = GpxException::class)
    fun rejectsNonGpx() {
        GpxParser.parse("<html/>".byteInputStream())
    }

    @Test(expected = GpxException::class)
    fun rejectsGarbage() {
        GpxParser.parse("not xml at all".byteInputStream())
    }
}
