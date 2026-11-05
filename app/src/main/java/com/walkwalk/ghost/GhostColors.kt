package com.walkwalk.ghost

import kotlin.math.sqrt

val GHOST_PALETTE: List<Int> = listOf(
    0xFF7B3FA0, 0xFF00897B, 0xFFF9A825, 0xFFEF6C00,
    0xFF6D4C41, 0xFF455A64, 0xFFD81B60, 0xFF7CB342,
).map { it.toInt() }

private const val MIN_COLOR_DISTANCE = 80.0

private fun rgbDistance(a: Int, b: Int): Double {
    val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
    val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
    val db = (a and 0xFF) - (b and 0xFF)
    return sqrt((dr * dr + dg * dg + db * db).toDouble())
}

/** [count] ghost colors, skipping palette entries too close to [trackColor]; cycles when count exceeds the palette. */
fun ghostColors(count: Int, trackColor: Int): List<Int> {
    if (count <= 0) return emptyList()
    val usable = usableColors(trackColor)
    return List(count) { usable[it % usable.size] }
}

/**
 * Colours for the hikes [ids]: a hike in [kept] keeps its colour, the others take the usable palette entries
 * no kept colour already holds, in order. With nothing kept this is [ghostColors].
 */
fun ghostColors(ids: List<Long>, kept: Map<Long, Int>, trackColor: Int): List<Int> {
    val free = usableColors(trackColor).filter { it !in kept.values }.ifEmpty { usableColors(trackColor) }
    var next = 0
    return ids.map { kept[it] ?: free[next++ % free.size] }
}

private fun usableColors(trackColor: Int): List<Int> =
    GHOST_PALETTE.filter { rgbDistance(it, trackColor) >= MIN_COLOR_DISTANCE }.ifEmpty { GHOST_PALETTE }
