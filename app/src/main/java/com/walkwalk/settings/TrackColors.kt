package com.walkwalk.settings

const val DEFAULT_TRACK_COLOR: Int = 0xFFCC3333.toInt()

/** Colors offered for the walked track; the first is the default. */
val TRACK_SWATCHES: List<Int> = listOf(
    0xFFCC3333, 0xFFE65100, 0xFFF9A825, 0xFF2E7D32,
    0xFF00796B, 0xFF1565C0, 0xFF3949AB, 0xFF7B1FA2,
    0xFFC2185B, 0xFF5D4037, 0xFF212121, 0xFF757575,
).map { it.toInt() }

/** Black or white, whichever reads better on top of [argb]. */
fun checkColorOn(argb: Int): Int {
    fun linear(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    // WCAG relative luminance; above ~0.179 black has the higher contrast
    val l = 0.2126 * linear((argb shr 16) and 0xFF) + 0.7152 * linear((argb shr 8) and 0xFF) + 0.0722 * linear(argb and 0xFF)
    return if (l > 0.179) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
}
