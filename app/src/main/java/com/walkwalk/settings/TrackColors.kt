package com.walkwalk.settings

const val DEFAULT_TRACK_COLOR: Int = 0xFFCC3333.toInt()

/** Colors offered for the walked track; the first is the default. */
val TRACK_SWATCHES: List<Int> = listOf(
    0xFFCC3333, 0xFFE65100, 0xFFF9A825, 0xFF2E7D32,
    0xFF00796B, 0xFF1565C0, 0xFF3949AB, 0xFF7B1FA2,
    0xFFC2185B, 0xFF5D4037, 0xFF212121, 0xFF757575,
).map { it.toInt() }
