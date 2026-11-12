package com.arin.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Arin's logo: a rising peak with an orbiting dot. Same shape as the app icon. */
@Composable
fun ArinMark(size: Dp = 28.dp, peak: Color = ArinColors.Teal, dot: Color = ArinColors.Violet, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.13f
        val path = Path().apply {
            moveTo(w * 0.16f, w * 0.84f)
            lineTo(w * 0.5f, w * 0.18f)
            lineTo(w * 0.84f, w * 0.84f)
        }
        drawPath(path, peak, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(peak, Offset(w * 0.33f, w * 0.62f), Offset(w * 0.67f, w * 0.62f), strokeWidth = stroke * 0.85f, cap = StrokeCap.Round)
        drawCircle(dot, radius = w * 0.11f, center = Offset(w * 0.84f, w * 0.15f))
    }
}
