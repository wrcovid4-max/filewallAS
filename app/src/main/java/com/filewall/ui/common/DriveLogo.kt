package com.filewall.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp

/**
 * The Google Drive triangle, drawn in its three colours. A simplified drawing: swap in the
 * official Drive artwork before release, as Google's brand guidelines ask.
 */
@Composable
fun DriveLogo(iconSize: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(iconSize)) {
        val w = this.size.width
        val h = this.size.height
        fun shape(color: Color, vararg points: Pair<Float, Float>) {
            val path = Path()
            points.forEachIndexed { i, (x, y) ->
                if (i == 0) path.moveTo(x * w, y * h) else path.lineTo(x * w, y * h)
            }
            path.close()
            drawPath(path, color)
        }
        shape(Color(0xFF34A853), 0.34f to 0f, 0f to 0.72f, 0.5f to 0.72f)
        shape(Color(0xFFFBBC04), 0.34f to 0f, 0.66f to 0f, 1f to 0.72f, 0.5f to 0.72f)
        shape(Color(0xFF4285F4), 0f to 0.72f, 1f to 0.72f, 0.85f to 1f, 0.15f to 1f)
    }
}
