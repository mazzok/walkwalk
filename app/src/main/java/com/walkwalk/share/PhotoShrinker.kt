package com.walkwalk.share

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import kotlin.math.max

object PhotoShrinker {
    /** JPEG bytes with the long edge at most [HikeBundle.MAX_EDGE_PX]; ImageDecoder applies the EXIF rotation. */
    fun shrink(source: ImageDecoder.Source): ByteArray {
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longEdge = max(info.size.width, info.size.height)
            if (longEdge > HikeBundle.MAX_EDGE_PX) { // never upscale
                val scale = HikeBundle.MAX_EDGE_PX.toDouble() / longEdge
                decoder.setTargetSize(
                    max(1, Math.round(info.size.width * scale).toInt()),
                    max(1, Math.round(info.size.height * scale).toInt()),
                )
            }
        }
        return ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.JPEG, HikeBundle.JPEG_QUALITY, it)
            bitmap.recycle()
        }.toByteArray()
    }
}
