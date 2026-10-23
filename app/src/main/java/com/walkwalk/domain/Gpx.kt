package com.walkwalk.domain

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

data class ParsedGpx(val name: String?, val points: List<TrackPoint>, val hasTimes: Boolean)

class GpxException(msg: String) : Exception(msg)

object GpxWriter {
    fun write(name: String, points: List<TrackPoint>, out: OutputStream) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n")
        w.write("""<gpx version="1.1" creator="walkwalk" xmlns="http://www.topografix.com/GPX/1/1">""" + "\n")
        w.write("  <trk>\n    <name>${escape(name)}</name>\n")
        points.groupBy { it.segment }.toSortedMap().values.forEach { seg ->
            w.write("    <trkseg>\n")
            for (p in seg) {
                w.write("""      <trkpt lat="${p.lat}" lon="${p.lon}">""")
                (p.altitude ?: p.gpsAltitude)?.let { w.write("<ele>${"%.1f".format(java.util.Locale.ROOT, it)}</ele>") }
                w.write("<time>${Instant.ofEpochMilli(p.time)}</time></trkpt>\n")
            }
            w.write("    </trkseg>\n")
        }
        w.write("  </trk>\n</gpx>\n")
        w.flush()
    }

    private fun escape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

object GpxParser {
    private class RawPoint(val lat: Double, val lon: Double, var ele: Double? = null, var time: Long? = null, val segment: Int)

    /** Every trkseg and every rte becomes its own segment. Namespaces are ignored. */
    fun parse(input: InputStream): ParsedGpx {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        val raw = mutableListOf<RawPoint>()
        var name: String? = null
        var sawGpx = false
        var segment = -1
        var current: RawPoint? = null
        val stack = ArrayDeque<String>()
        try {
            parser.setInput(input, null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name
                        when (tag) {
                            "gpx" -> sawGpx = true
                            "trkseg", "rte" -> segment++
                            "trkpt", "rtept" -> {
                                if (segment < 0) segment = 0
                                current = RawPoint(attr(parser, "lat"), attr(parser, "lon"), segment = segment)
                            }
                            "ele" -> if (current != null && stack.lastOrNull() in POINT_TAGS) current.ele = parser.nextText().trim().toDoubleOrNull()
                            "time" -> if (current != null && stack.lastOrNull() in POINT_TAGS) current.time = parseTime(parser.nextText().trim())
                            "name" -> if (name == null && stack.lastOrNull() in setOf("trk", "rte", "metadata")) name = parser.nextText().trim().ifEmpty { null }
                        }
                        // nextText() consumed the end tag of ele/time/name, so only push tags still open
                        if (parser.eventType == XmlPullParser.START_TAG) stack.addLast(tag)
                    }
                    XmlPullParser.END_TAG -> {
                        stack.removeLastOrNull()
                        if (parser.name in POINT_TAGS) { current?.let { raw += it }; current = null }
                    }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            throw GpxException("Not a readable GPX file: ${e.message}")
        }
        if (!sawGpx) throw GpxException("Not a GPX file")
        if (raw.isEmpty()) throw GpxException("GPX file contains no points")

        val hasTimes = raw.all { it.time != null }
        val points = raw.mapIndexed { i, r ->
            TrackPoint(
                time = if (hasTimes) r.time!! else i * 1000L,
                lat = r.lat, lon = r.lon, gpsAltitude = r.ele, pressureHpa = null, accuracy = null, segment = r.segment,
            )
        }
        return ParsedGpx(name, points, hasTimes)
    }

    private val POINT_TAGS = setOf("trkpt", "rtept")

    private fun attr(parser: XmlPullParser, name: String): Double =
        parser.getAttributeValue(null, name)?.toDoubleOrNull() ?: throw GpxException("Point without $name")

    private fun parseTime(s: String): Long? = try {
        Instant.parse(s).toEpochMilli()
    } catch (e: DateTimeParseException) {
        try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (e: DateTimeParseException) { null }
    }
}
