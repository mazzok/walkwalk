package com.walkwalk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput

private val FILL = Color(0x9999CC66)
private val LINE = Color(0xFF6A9A3A)
private val CURSOR = Color(0xFF0066CC)
private val STOP = Color(0xFFE0A030)

/** Altitude over distance with a cursor; tapping or dragging horizontally reports a distance. */
@Composable
fun ElevationProfile(
    profile: Profile,
    cursorDistanceM: Double,
    onSeekDistance: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val seek by rememberUpdatedState(onSeekDistance)
    val maxDist = profile.distances.last().coerceAtLeast(1.0)
    val minAlt = profile.altitudes.min()
    val altRange = (profile.altitudes.max() - minAlt).coerceAtLeast(20.0)

    Canvas(
        modifier
            .pointerInput(maxDist) { detectTapGestures { seek(it.x / size.width * maxDist) } }
            .pointerInput(maxDist) {
                detectHorizontalDragGestures { change, _ -> seek((change.position.x / size.width).coerceIn(0f, 1f) * maxDist) }
            },
    ) {
        fun x(d: Double) = (d / maxDist * size.width).toFloat()
        fun y(a: Double) = (size.height - (a - minAlt) / altRange * size.height * 0.9).toFloat()

        val outline = Path().apply {
            moveTo(0f, y(profile.altitudes[0]))
            for (i in profile.distances.indices) lineTo(x(profile.distances[i]), y(profile.altitudes[i]))
        }
        val fill = Path().apply {
            addPath(outline)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, FILL)
        drawPath(outline, LINE, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        profile.stopDistances.forEach { d ->
            drawLine(STOP, Offset(x(d), 0f), Offset(x(d), size.height), strokeWidth = 6f)
        }
        val cx = x(cursorDistanceM)
        drawLine(CURSOR, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 4f)
    }
}
