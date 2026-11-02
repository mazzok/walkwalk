package com.walkwalk.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)

    fun km(m: Double) = String.format(Locale.ROOT, "%.1f km", m / 1000)
    fun km2(m: Double) = String.format(Locale.ROOT, "%.2f", m / 1000)
    fun meters(m: Double?) = m?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "–"
    fun speed(kmh: Double) = String.format(Locale.ROOT, "%.1f", kmh)
    fun duration(ms: Long): String {
        val min = ms / 60_000
        return String.format(Locale.ROOT, "%d:%02d", min / 60, min % 60)
    }
    /** Signed m:ss ahead/behind; U+2212 minus, minutes not wrapped at 60. */
    fun delta(ms: Long): String {
        val sec = Math.abs(ms) / 1000
        return String.format(Locale.ROOT, "%s%d:%02d", if (ms < 0 && sec > 0) "−" else "+", sec / 60, sec % 60)
    }
    fun clock(epochMs: Long): String = CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    fun date(epochMs: Long): String = DATE.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** "Sun 1 Nov 2026, 09:12 and 13:45"; both dates when the range crosses midnight. */
    fun range(fromMs: Long, toMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val from = Instant.ofEpochMilli(fromMs).atZone(zone)
        val to = Instant.ofEpochMilli(toMs).atZone(zone)
        val end = if (from.toLocalDate() == to.toLocalDate()) CLOCK.format(to) else "${DATE.format(to)}, ${CLOCK.format(to)}"
        return "${DATE.format(from)}, ${CLOCK.format(from)} and $end"
    }
}
