package com.walkwalk.ui

/** The replay destination; `kept` and `atM` are only set when switching from one replay to a ghost's. */
object ReplayRoute {
    const val PATTERN = "replay/{id}?kept={kept}&atM={atM}"

    fun of(s: ReplaySwitch): String = "replay/${s.hikeId}?kept=${encodeKept(s.kept)}&atM=${s.atM}"

    fun encodeKept(kept: Map<Long, Int>): String = kept.entries.joinToString(",") { "${it.key}:${it.value}" }

    fun decodeKept(s: String?): Map<Long, Int> =
        s.orEmpty().split(",").filter { it.isNotEmpty() }.associate { e ->
            val (id, color) = e.split(":")
            id.toLong() to color.toInt()
        }

    fun decodeAtM(s: String?): Double? = s?.toDoubleOrNull()
}
