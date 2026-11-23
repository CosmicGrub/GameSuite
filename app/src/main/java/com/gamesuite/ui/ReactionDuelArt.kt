package com.gamesuite.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Original vector art of Reaction Duel: glossy arcade pads with a bevel and a dish, and five shape glyphs that carry
 * their meaning in SHAPE (every colour is also a different shape and has a written label), plus the state glyphs
 * (hourglass, play, cross, check, star) so a pad's state never depends on its colour alone.
 */

/** The four symbol colours (Okabe-Ito, colour-blind safe). Colour is decoration: shape and label identify a symbol. */
internal val RdSymbolColours = listOf(Color(0xFFD55E00), Color(0xFF0072B2), Color(0xFFF0E442), Color(0xFF009E73))

internal fun rdShade(c: Color, f: Float): Color =
    if (f >= 0f) androidx.compose.ui.graphics.lerp(c, Color.White, f) else androidx.compose.ui.graphics.lerp(c, Color.Black, -f)

/** The outline path of shape [shape] (0 circle, 1 square, 2 triangle, 3 diamond, 4 star) of radius [r] around [c]. */
internal fun rdShapePath(shape: Int, c: Offset, r: Float): Path {
    val p = Path()
    when (shape) {
        0 -> p.addOval(androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r))
        1 -> p.addRoundRect(
            androidx.compose.ui.geometry.RoundRect(c.x - r * 0.88f, c.y - r * 0.88f, c.x + r * 0.88f, c.y + r * 0.88f, CornerRadius(r * 0.18f))
        )
        2 -> {
            p.moveTo(c.x, c.y - r); p.lineTo(c.x + r * 1.02f, c.y + r * 0.82f); p.lineTo(c.x - r * 1.02f, c.y + r * 0.82f); p.close()
        }
        3 -> {
            p.moveTo(c.x, c.y - r * 1.08f); p.lineTo(c.x + r * 0.9f, c.y); p.lineTo(c.x, c.y + r * 1.08f); p.lineTo(c.x - r * 0.9f, c.y); p.close()
        }
        else -> {
            for (i in 0 until 10) {
                val a = -PI / 2 + i * PI / 5
                val rr = if (i % 2 == 0) r * 1.1f else r * 0.46f
                val x = c.x + (cos(a) * rr).toFloat()
                val y = c.y + (sin(a) * rr).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
        }
    }
    return p
}

/** A glossy enamel shape with a dark rim: fill [colour], lit from the top left. */
internal fun DrawScope.rdDrawSymbol(shape: Int, colour: Color, c: Offset, r: Float, alpha: Float = 1f) {
    if (r < 1f) return
    val path = rdShapePath(shape, c, r)
    drawPath(rdShapePath(shape, Offset(c.x + r * 0.06f, c.y + r * 0.1f), r), Color(0x55000000).copy(alpha = 0.33f * alpha))
    drawPath(
        path,
        Brush.radialGradient(listOf(rdShade(colour, 0.45f), colour, rdShade(colour, -0.35f)), center = Offset(c.x - r * 0.3f, c.y - r * 0.4f), radius = r * 1.7f),
        alpha = alpha
    )
    drawPath(path, rdShade(colour, -0.6f).copy(alpha = 0.85f * alpha), style = Stroke(r * 0.1f))
    drawCircle(Color.White.copy(alpha = 0.45f * alpha), r * 0.14f, Offset(c.x - r * 0.38f, c.y - r * 0.42f))
}

/** An outlined (hollow) shape, used for the "target" reminder chip. */
internal fun DrawScope.rdDrawSymbolOutline(shape: Int, colour: Color, c: Offset, r: Float) {
    drawPath(rdShapePath(shape, c, r), colour, style = Stroke(r * 0.28f))
}

/**
 * A big arcade pad in the rect at [topLeft] of [size]: a drop shadow, a bevelled body in [base], a darker dish and
 * a highlight. [pressed] sinks it; [glow] (0 to 1) draws a steady soft halo (never a blink).
 */
internal fun DrawScope.rdDrawPad(topLeft: Offset, size: Size, base: Color, pressed: Boolean, glow: Float = 0f, corner: Float = 0.2f) {
    val cr = CornerRadius(size.minDimension * corner)
    val sink = if (pressed) size.minDimension * 0.025f else 0f
    val lift = size.minDimension * 0.04f - sink
    drawRoundRect(Color(0x66000000), topLeft + Offset(0f, size.minDimension * 0.05f), size, cr)
    if (glow > 0f) {
        drawRoundRect(base.copy(alpha = 0.28f * glow), topLeft - Offset(size.minDimension * 0.03f, size.minDimension * 0.03f), Size(size.width + size.minDimension * 0.06f, size.height + size.minDimension * 0.06f), cr)
    }
    drawRoundRect(rdShade(base, -0.4f), topLeft + Offset(0f, lift), size, cr)
    val body = Size(size.width, size.height - lift)
    drawRoundRect(Brush.verticalGradient(listOf(rdShade(base, 0.3f), base, rdShade(base, -0.12f)), startY = topLeft.y, endY = topLeft.y + body.height), topLeft + Offset(0f, sink), body, cr)
    val inset = size.minDimension * 0.07f
    drawRoundRect(
        rdShade(base, -0.18f).copy(alpha = 0.55f), topLeft + Offset(inset, inset + sink), Size(body.width - 2 * inset, body.height - 2 * inset),
        CornerRadius(cr.x * 0.7f), style = Stroke(size.minDimension * 0.012f)
    )
    drawRoundRect(Color.White.copy(alpha = 0.16f), topLeft + Offset(inset * 1.4f, inset * 0.9f + sink), Size(body.width - inset * 2.8f, body.height * 0.18f), CornerRadius(cr.x * 0.6f))
}

internal fun DrawScope.rdDrawHourglass(c: Offset, r: Float, colour: Color) {
    val top = Path().apply { moveTo(c.x - r * 0.7f, c.y - r); lineTo(c.x + r * 0.7f, c.y - r); lineTo(c.x, c.y); close() }
    val bottom = Path().apply { moveTo(c.x - r * 0.7f, c.y + r); lineTo(c.x + r * 0.7f, c.y + r); lineTo(c.x, c.y); close() }
    drawPath(top, colour.copy(alpha = 0.55f)); drawPath(bottom, colour)
    drawPath(top, colour, style = Stroke(r * 0.14f)); drawPath(bottom, colour, style = Stroke(r * 0.14f))
    drawLine(colour, Offset(c.x - r * 0.85f, c.y - r), Offset(c.x + r * 0.85f, c.y - r), r * 0.18f, StrokeCap.Round)
    drawLine(colour, Offset(c.x - r * 0.85f, c.y + r), Offset(c.x + r * 0.85f, c.y + r), r * 0.18f, StrokeCap.Round)
}

internal fun DrawScope.rdDrawPlay(c: Offset, r: Float, colour: Color) {
    val p = Path().apply { moveTo(c.x - r * 0.55f, c.y - r * 0.8f); lineTo(c.x + r * 0.85f, c.y); lineTo(c.x - r * 0.55f, c.y + r * 0.8f); close() }
    drawPath(p, colour)
}

internal fun DrawScope.rdDrawCross(c: Offset, r: Float, colour: Color) {
    drawLine(colour, Offset(c.x - r * 0.7f, c.y - r * 0.7f), Offset(c.x + r * 0.7f, c.y + r * 0.7f), r * 0.3f, StrokeCap.Round)
    drawLine(colour, Offset(c.x + r * 0.7f, c.y - r * 0.7f), Offset(c.x - r * 0.7f, c.y + r * 0.7f), r * 0.3f, StrokeCap.Round)
}

internal fun DrawScope.rdDrawCheck(c: Offset, r: Float, colour: Color) {
    val p = Path().apply { moveTo(c.x - r * 0.8f, c.y); lineTo(c.x - r * 0.25f, c.y + r * 0.6f); lineTo(c.x + r * 0.85f, c.y - r * 0.6f) }
    drawPath(p, colour, style = Stroke(r * 0.3f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

/** A thick cursor ring drawn around a pad (the one-switch scan marker): a ring AND corner ticks, so it is not colour alone. */
internal fun DrawScope.rdDrawCursor(topLeft: Offset, size: Size, colour: Color) {
    val cr = CornerRadius(size.minDimension * 0.22f)
    drawRoundRect(colour, topLeft - Offset(size.minDimension * 0.04f, size.minDimension * 0.04f), Size(size.width + size.minDimension * 0.08f, size.height + size.minDimension * 0.08f), cr, style = Stroke(size.minDimension * 0.035f))
}
