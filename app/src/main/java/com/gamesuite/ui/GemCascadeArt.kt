package com.gamesuite.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.gamesuite.games.gemcascade.Cell
import com.gamesuite.games.gemcascade.GemKinds
import com.gamesuite.games.gemcascade.GemShape
import com.gamesuite.games.gemcascade.PieceType
import com.gamesuite.games.gemcascade.SpecialKind
import kotlin.math.cos
import kotlin.math.sin

/** The flat 2D look of Gem Cascade, drawn from engine cells. Every gem kind has its own shape AND colour. */
internal class GemPaints {
    val path = Path()
    val ink = Color(0xCC101014)
    val white = Color.White
    val stone = Color(0xFF8A8F98)
    val stoneDark = Color(0xFF4A4E57)
    val gold = Color(0xFFFFC107)
    val wing = Color(0xFFFF5FA2)
    val spider = Color(0xFF2B2B33)
    val ice = Color(0xFF9FDCFF)
}

internal fun gemColor(kind: Int): Color = Color(GemKinds.argb[kind.coerceIn(0, GemKinds.COUNT - 1)])

/** Draws one piece centred at [c] inside a cell of side [s]. [scale] and [alpha] drive pops and fades. */
internal fun DrawScope.drawPiece(cell: Cell, c: Offset, s: Float, scale: Float, alpha: Float, p: GemPaints, text: TextMeasurer) {
    if (alpha <= 0f || scale <= 0f) return
    val r = s * 0.37f * scale
    when (cell.type) {
        PieceType.EMPTY -> Unit
        PieceType.GEM -> drawGem(cell, c, r, alpha, p, text, s)
        PieceType.STONE -> {
            drawRoundRect(p.stone.copy(alpha = alpha), Offset(c.x - r * 1.1f, c.y - r * 1.1f), Size(r * 2.2f, r * 2.2f), CornerRadius(r * 0.35f))
            drawRoundRect(p.stoneDark.copy(alpha = alpha), Offset(c.x - r * 1.1f, c.y - r * 1.1f), Size(r * 2.2f, r * 2.2f), CornerRadius(r * 0.35f), style = Stroke(s * 0.04f))
            for (i in 0 until cell.hp.coerceIn(0, 3)) {
                drawCircle(p.white.copy(alpha = alpha), r * 0.13f, Offset(c.x + (i - (cell.hp - 1) / 2f) * r * 0.5f, c.y))
            }
        }
        PieceType.SPIDER -> {
            drawCircle(p.spider.copy(alpha = alpha), r * 0.6f, c)
            for (i in 0 until 4) {
                val dy = (i - 1.5f) * r * 0.45f
                drawLine(p.spider.copy(alpha = alpha), Offset(c.x - r * 0.5f, c.y + dy * 0.4f), Offset(c.x - r * 1.1f, c.y + dy), s * 0.04f)
                drawLine(p.spider.copy(alpha = alpha), Offset(c.x + r * 0.5f, c.y + dy * 0.4f), Offset(c.x + r * 1.1f, c.y + dy), s * 0.04f)
            }
            drawCircle(Color(0xFFFF4D4D).copy(alpha = alpha), r * 0.12f, Offset(c.x - r * 0.2f, c.y - r * 0.15f))
            drawCircle(Color(0xFFFF4D4D).copy(alpha = alpha), r * 0.12f, Offset(c.x + r * 0.2f, c.y - r * 0.15f))
        }
        PieceType.BUTTERFLY -> {
            val w = p.wing.copy(alpha = alpha)
            p.path.rewind()
            p.path.moveTo(c.x, c.y); p.path.lineTo(c.x - r * 1.1f, c.y - r * 0.9f); p.path.lineTo(c.x - r * 0.9f, c.y + r * 0.5f); p.path.close()
            p.path.moveTo(c.x, c.y); p.path.lineTo(c.x + r * 1.1f, c.y - r * 0.9f); p.path.lineTo(c.x + r * 0.9f, c.y + r * 0.5f); p.path.close()
            drawPath(p.path, w)
            drawLine(p.ink.copy(alpha = alpha), Offset(c.x, c.y - r * 0.6f), Offset(c.x, c.y + r * 0.7f), s * 0.06f)
        }
        PieceType.TREASURE -> {
            p.path.rewind()
            p.path.moveTo(c.x, c.y - r * 1.1f); p.path.lineTo(c.x + r, c.y); p.path.lineTo(c.x, c.y + r * 1.1f); p.path.lineTo(c.x - r, c.y); p.path.close()
            drawPath(p.path, p.gold.copy(alpha = alpha))
            drawPath(p.path, p.ink.copy(alpha = alpha * 0.6f), style = Stroke(s * 0.04f))
            drawCircle(p.white.copy(alpha = alpha), r * 0.14f, Offset(c.x - r * 0.25f, c.y - r * 0.3f))
        }
    }
}

private fun DrawScope.drawGem(cell: Cell, c: Offset, r: Float, alpha: Float, p: GemPaints, text: TextMeasurer, s: Float) {
    val fill = if (cell.special == SpecialKind.PRISM) p.white else gemColor(cell.kind)
    val col = fill.copy(alpha = alpha)
    val outline = p.ink.copy(alpha = alpha * 0.7f)
    val stroke = Stroke(s * 0.045f)
    when (GemKinds.shapes[cell.kind.coerceIn(0, GemKinds.COUNT - 1)]) {
        GemShape.CIRCLE -> { drawCircle(col, r, c); drawCircle(outline, r, c, style = stroke) }
        GemShape.SQUARE -> {
            drawRoundRect(col, Offset(c.x - r * 0.9f, c.y - r * 0.9f), Size(r * 1.8f, r * 1.8f), CornerRadius(r * 0.25f))
            drawRoundRect(outline, Offset(c.x - r * 0.9f, c.y - r * 0.9f), Size(r * 1.8f, r * 1.8f), CornerRadius(r * 0.25f), style = stroke)
        }
        GemShape.TRIANGLE -> poly(c, r * 1.15f, 3, -90f, col, outline, stroke, p)
        GemShape.DIAMOND -> poly(c, r * 1.15f, 4, -90f, col, outline, stroke, p)
        GemShape.STAR -> star(c, r * 1.2f, col, outline, stroke, p)
        GemShape.HEXAGON -> poly(c, r * 1.05f, 6, 0f, col, outline, stroke, p)
        GemShape.CROSS -> {
            val t = r * 0.62f
            drawRoundRect(col, Offset(c.x - r, c.y - t / 2f * 1.0f), Size(r * 2f, t), CornerRadius(t * 0.3f))
            drawRoundRect(col, Offset(c.x - t / 2f, c.y - r), Size(t, r * 2f), CornerRadius(t * 0.3f))
            drawRoundRect(outline, Offset(c.x - r, c.y - t / 2f), Size(r * 2f, t), CornerRadius(t * 0.3f), style = stroke)
            drawRoundRect(outline, Offset(c.x - t / 2f, c.y - r), Size(t, r * 2f), CornerRadius(t * 0.3f), style = stroke)
        }
    }
    val a = alpha
    when (cell.special) {
        SpecialKind.NONE -> Unit
        SpecialKind.FLARE_ROW -> {
            drawLine(p.white.copy(alpha = a), Offset(c.x - r * 1.2f, c.y), Offset(c.x + r * 1.2f, c.y), s * 0.07f)
            drawLine(p.ink.copy(alpha = a * 0.5f), Offset(c.x - r * 1.2f, c.y), Offset(c.x + r * 1.2f, c.y), s * 0.02f)
        }
        SpecialKind.FLARE_COL -> {
            drawLine(p.white.copy(alpha = a), Offset(c.x, c.y - r * 1.2f), Offset(c.x, c.y + r * 1.2f), s * 0.07f)
            drawLine(p.ink.copy(alpha = a * 0.5f), Offset(c.x, c.y - r * 1.2f), Offset(c.x, c.y + r * 1.2f), s * 0.02f)
        }
        SpecialKind.PRISM -> {
            val colours = listOf(0xFFE53935, 0xFFFFB300, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA, 0xFFFF7043)
            for (i in colours.indices) {
                drawArc(Color(colours[i]).copy(alpha = a), i * 60f, 60f, true, Offset(c.x - r * 0.75f, c.y - r * 0.75f), Size(r * 1.5f, r * 1.5f))
            }
        }
        SpecialKind.BURST -> {
            drawCircle(p.white.copy(alpha = a), r * 0.95f, c, style = Stroke(s * 0.06f))
            drawCircle(p.white.copy(alpha = a), r * 0.22f, c)
        }
        SpecialKind.NOVA -> star(c, r * 1.05f, p.white.copy(alpha = a * 0.9f), outline, Stroke(s * 0.03f), p, points = 8)
    }
    if (cell.locked) {
        val k = r * 1.05f
        val g = p.stoneDark.copy(alpha = a)
        drawRoundRect(g, Offset(c.x - k, c.y - k), Size(k * 2, k * 2), CornerRadius(k * 0.2f), style = Stroke(s * 0.05f))
        for (i in -1..1) {
            drawLine(g, Offset(c.x + i * k * 0.6f, c.y - k), Offset(c.x + i * k * 0.6f, c.y + k), s * 0.035f)
            drawLine(g, Offset(c.x - k, c.y + i * k * 0.6f), Offset(c.x + k, c.y + i * k * 0.6f), s * 0.035f)
        }
    }
    if (cell.timer > 0) {
        val b = Offset(c.x + r * 0.7f, c.y + r * 0.7f)
        drawCircle(p.spider.copy(alpha = a), r * 0.5f, b)
        drawCircle(p.white.copy(alpha = a), r * 0.5f, b, style = Stroke(s * 0.02f))
        val layout = text.measure(cell.timer.toString(), TextStyle(color = p.white.copy(alpha = a), fontSize = (r * 0.62f).toSp(), fontWeight = FontWeight.Bold))
        drawText(layout, topLeft = Offset(b.x - layout.size.width / 2f, b.y - layout.size.height / 2f))
    }
}

private fun DrawScope.poly(c: Offset, r: Float, n: Int, startDeg: Float, fill: Color, outline: Color, stroke: Stroke, p: GemPaints) {
    p.path.rewind()
    for (i in 0 until n) {
        val a = Math.toRadians((startDeg + i * 360.0 / n))
        val x = c.x + r * cos(a).toFloat()
        val y = c.y + r * sin(a).toFloat()
        if (i == 0) p.path.moveTo(x, y) else p.path.lineTo(x, y)
    }
    p.path.close()
    drawPath(p.path, fill)
    drawPath(p.path, outline, style = stroke)
}

private fun DrawScope.star(c: Offset, r: Float, fill: Color, outline: Color, stroke: Stroke, p: GemPaints, points: Int = 5) {
    p.path.rewind()
    val n = points * 2
    for (i in 0 until n) {
        val rad = if (i % 2 == 0) r else r * 0.48f
        val a = Math.toRadians(-90.0 + i * 360.0 / n)
        val x = c.x + rad * cos(a).toFloat()
        val y = c.y + rad * sin(a).toFloat()
        if (i == 0) p.path.moveTo(x, y) else p.path.lineTo(x, y)
    }
    p.path.close()
    drawPath(p.path, fill)
    drawPath(p.path, outline, style = stroke)
}

/** Ice under a cell: a pale tile, with one inner ring per extra layer. */
internal fun DrawScope.drawIce(topLeft: Offset, s: Float, layers: Int, p: GemPaints) {
    if (layers <= 0) return
    drawRoundRect(p.ice.copy(alpha = 0.38f), topLeft, Size(s, s), CornerRadius(s * 0.12f))
    drawRoundRect(p.ice.copy(alpha = 0.9f), topLeft, Size(s, s), CornerRadius(s * 0.12f), style = Stroke(s * 0.04f))
    for (i in 1 until layers) {
        val inset = s * 0.12f * i
        drawRoundRect(p.ice.copy(alpha = 0.8f), Offset(topLeft.x + inset, topLeft.y + inset), Size(s - 2 * inset, s - 2 * inset), CornerRadius(s * 0.08f), style = Stroke(s * 0.025f))
    }
}
