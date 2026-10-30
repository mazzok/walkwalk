package com.walkwalk.share

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportKindTest {
    @Test fun zipMagicIsBundle() = assertEquals(ImportKind.BUNDLE, importKind(byteArrayOf(0x50, 0x4B, 3, 4)))
    @Test fun xmlIsGpx() = assertEquals(ImportKind.GPX, importKind("<?xml".toByteArray()))
    @Test fun emptyIsGpx() = assertEquals(ImportKind.GPX, importKind(ByteArray(0)))
}
