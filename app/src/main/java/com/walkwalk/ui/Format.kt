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
    fun clock(epochMs: Long): String = CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    fun date(epochMs: Long): String = DATE.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}
