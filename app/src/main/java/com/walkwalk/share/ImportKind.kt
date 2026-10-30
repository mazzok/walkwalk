package com.walkwalk.share

enum class ImportKind { BUNDLE, GPX }

/** A .walkwalk bundle is a zip; anything else is tried as GPX. */
fun importKind(head: ByteArray): ImportKind =
    if (head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte())
        ImportKind.BUNDLE else ImportKind.GPX
