package com.gamesuite.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.gamesuite.games.echopads.EchoPads
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Drawing helpers of Echo Pads: the four pad shapes (circle, triangle, square, star) and the pad colours.
 * Every pad is told apart by shape and pitch as well as colour, so the game works for colour-blind players.
 */

/** The pad's colour (Okabe-Ito, colour-blind safe). */
internal fun echoPadColor(pad: Int): Color = Color(EchoPads.colours[pad.coerceIn(0, EchoPads.COUNT - 1)])

/** [c] blended [amount] (0..1) toward [toward]. */
internal fun echoBlend(c: Color, toward: Color, amount: Float): Color = lerp(c, toward, amount.coerceIn(0f, 1f))

/** The glyph colour that reads on pad [pad]: dark on the yellow pad, white on the others. */
internal fun echoGlyphColor(pad: Int): Color = if (pad == EchoPads.SQUARE) Color(0xFF1A1A1A) else Color.White

/** A pad's shape glyph centred at [center]; [radius] is roughly half its width. Filled, with a thin contrasting outline. */
internal fun DrawScope.drawEchoShape(shape: Int, fill: Color, outline: Color, center: Offset, radius: Float, strokePx: Float) {
    when (shape) {
        EchoPads.CIRCLE -> {
            drawCircle(fill, radius * 0.95f, center)
            drawCircle(outline, radius * 0.95f, center, style = Stroke(strokePx))
        }
        EchoPads.TRIANGLE -> {
            val p = Path().apply {
                moveTo(center.x, center.y - radius)
                lineTo(center.x + radius * 1.0f, center.y + radius * 0.8f)
                lineTo(center.x - radius * 1.0f, center.y + radius * 0.8f)
                close()
            }
            drawPath(p, fill)
            drawPath(p, outline, style = Stroke(strokePx))
        }
        EchoPads.SQUARE -> {
            val half = radius * 0.85f
            val tl = Offset(center.x - half, center.y - half)
            val sz = Size(half * 2, half * 2)
            drawRoundRect(fill, tl, sz, CornerRadius(half * 0.18f))
            drawRoundRect(outline, tl, sz, CornerRadius(half * 0.18f), style = Stroke(strokePx))
        }
        else -> {
            val p = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) radius * 1.05f else radius * 0.46f
                val a = -PI / 2 + i * PI / 5
                val x = center.x + (r * cos(a)).toFloat()
                val y = center.y + (r * sin(a)).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            drawPath(p, fill)
            drawPath(p, outline, style = Stroke(strokePx))
        }
    }
}
