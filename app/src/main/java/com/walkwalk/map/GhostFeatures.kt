package com.walkwalk.map

/** "#RRGGBB" for a MapLibre color property; the alpha channel is dropped. */
fun colorHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)
