package com.gamesuite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import com.gamesuite.games.actitout.ActItOutCategory
import com.gamesuite.games.actitout.ActItOutGlyphs
import com.gamesuite.games.actitout.GlyphArc
import com.gamesuite.games.actitout.GlyphCircle
import com.gamesuite.games.actitout.GlyphLine
import com.gamesuite.games.actitout.GlyphPoly
import com.gamesuite.games.actitout.GlyphRect
import com.gamesuite.games.actitout.GlyphShape

/**
 * Draws a card's pictogram (see [ActItOutGlyphs]) in one ink colour on the card's paper colour. Decoration only:
 * the card's words carry the meaning for a screen reader, so this adds no semantics.
 */
@Composable
internal fun ActItOutGlyphView(glyph: String, category: ActItOutCategory, ink: Color, paper: Color, modifier: Modifier = Modifier) {
    val shapes = remember(glyph, category) { ActItOutGlyphs.forCard(glyph, category) }
    Canvas(modifier) { drawActItOutGlyph(shapes, ink, paper) }
}

internal fun DrawScope.drawActItOutGlyph(shapes: List<GlyphShape>, ink: Color, paper: Color) {
    val s = minOf(size.width, size.height)
    val ox = (size.width - s) / 2f
    val oy = (size.height - s) / 2f
    fun px(v: Float) = ox + v * s
    fun py(v: Float) = oy + v * s
    for (shape in shapes) {
        val color = if (shape.cut) paper else ink
        when (shape) {
            is GlyphCircle -> {
                val c = Offset(px(shape.cx), py(shape.cy))
                if (shape.fill) drawCircle(color, shape.r * s, c)
                else drawCircle(color, shape.r * s, c, style = Stroke(width = shape.width * s))
            }
            is GlyphRect -> {
                val tl = Offset(px(shape.left), py(shape.top))
                val sz = Size((shape.right - shape.left) * s, (shape.bottom - shape.top) * s)
                if (shape.fill) drawRect(color, tl, sz) else drawRect(color, tl, sz, style = Stroke(width = shape.width * s))
            }
            is GlyphLine -> drawLine(
                color, Offset(px(shape.x1), py(shape.y1)), Offset(px(shape.x2), py(shape.y2)),
                strokeWidth = shape.width * s, cap = StrokeCap.Round
            )
            is GlyphPoly -> {
                val p = Path()
                val pts = shape.points
                var i = 0
                while (i + 1 < pts.size) {
                    val x = px(pts[i])
                    val y = py(pts[i + 1])
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    i += 2
                }
                p.close()
                if (shape.fill) drawPath(p, color, style = Fill)
                else drawPath(p, color, style = Stroke(width = shape.width * s, join = StrokeJoin.Round))
            }
            is GlyphArc -> drawArc(
                color, shape.startDeg, shape.sweepDeg, useCenter = false,
                topLeft = Offset(px(shape.cx - shape.r), py(shape.cy - shape.r)),
                size = Size(2f * shape.r * s, 2f * shape.r * s),
                style = Stroke(width = shape.width * s, cap = StrokeCap.Round)
            )
        }
    }
}
