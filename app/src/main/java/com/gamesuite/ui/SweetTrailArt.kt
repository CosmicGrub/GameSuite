package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gamesuite.games.sweettrail.SweetTrailBoard
import com.gamesuite.games.sweettrail.SweetTrailLayout
import com.gamesuite.games.sweettrail.SweetTrailRules
import com.gamesuite.games.sweettrail.SweetTrailState
import com.gamesuite.games.sweettrail.TrailCard
import com.gamesuite.games.sweettrail.TrailCardKind
import com.gamesuite.games.sweettrail.TrailColour
import com.gamesuite.ui.effects.ParticleBurst
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Original vector art for Sweet Trail, drawn on a Canvas: a warm cream board with a winding road of fruit-coloured
 * squares (every colour also has its own pattern, so colour is never the only cue), a jelly bridge, a pretzel
 * ladder and a cloud zipline for the shortcuts, a gooey puddle blob, six little landmark icons, the start strip and
 * the Party Table, the card faces and back, and the shared glossy seat pieces (each seat is a colour AND a shape).
 * There is no text on the canvas: the screen has almost no words and TalkBack gets labels from the screen.
 */

internal class SweetTrailTheme(
    val bg: Color, val text: Color, val card: Color, val road: Color, val roadEdge: Color, val rim: Color,
    val cardBack: Color, val gold: Color, val goo: Color, val gooDark: Color
)

internal fun sweetTrailTheme(dark: Boolean): SweetTrailTheme = if (!dark) SweetTrailTheme(
    bg = Color(0xFFFFF4DC), text = Color(0xFF2B1B4A), card = Color(0xFFFFE9BD), road = Color(0xFFF1D9A6),
    roadEdge = Color(0xFFC9A55C), rim = Color(0xFF9C1B52), cardBack = Color(0xFF9C1B52), gold = Color(0xFFE5B800),
    goo = Color(0xFF38B2A6), gooDark = Color(0xFF1B6F67)
) else SweetTrailTheme(
    bg = Color(0xFF1F1830), text = Color(0xFFF5ECFF), card = Color(0xFF33284D), road = Color(0xFF4A3D69),
    roadEdge = Color(0xFF6E5C99), rim = Color(0xFFF08FB4), cardBack = Color(0xFFB02B66), gold = Color(0xFFFFD84D),
    goo = Color(0xFF4FCDBF), gooDark = Color(0xFF217A71)
)

/** The fill of a colour (the same in both themes: the squares are the picture). */
internal fun trailColourFill(c: TrailColour): Color = when (c) {
    TrailColour.CHERRY -> Color(0xFFD93A4A)
    TrailColour.TANGERINE -> Color(0xFFF59A23)
    TrailColour.LEMON -> Color(0xFFF4D03F)
    TrailColour.LIME -> Color(0xFF7CC242)
    TrailColour.BLUEBERRY -> Color(0xFF3F6FD8)
    TrailColour.GRAPE -> Color(0xFF8E4FBF)
}

private fun trailPatternInk(c: TrailColour): Color = when (c) {
    TrailColour.BLUEBERRY, TrailColour.GRAPE, TrailColour.CHERRY -> Color.White.copy(alpha = 0.85f)
    else -> Color(0xFF3A2A12).copy(alpha = 0.6f)
}

/** Where a piece rests or hops, in cell units. */
internal fun stCenter(square: Int): Offset = Offset(SweetTrailLayout.centerX(square), SweetTrailLayout.centerY(square))

internal fun stPiecePos(seat: Int, square: Int, crowd: Int): Offset {
    val p = SweetTrailLayout.piecePosition(seat, square, crowd)
    return Offset(p.first, p.second)
}

/** The control point of a shortcut's arc between squares [startSq] and [endSq] (cell units); drawing and gliding share it. */
internal fun stShortcutControl(startSq: Int, endSq: Int): Offset =
    bendControlOf(stCenter(startSq), stCenter(endSq), SweetTrailLayout.shortcutBend(startSq))

/**
 * A hop of one piece between two points (cell units). [lift] is the arc height in cells, [bounces] how many small
 * hops fit in the leg (a dance is several on the spot), [control] bends the path into a shortcut's arc.
 */
internal class SweetTrailLeg(
    val seat: Int, val from: Offset, val to: Offset, val lift: Float, val bounces: Int = 1, val control: Offset? = null
)

internal fun bendControlOf(a: Offset, b: Offset, bend: Float): Offset {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = hypot(dx, dy).coerceAtLeast(0.0001f)
    return Offset((a.x + b.x) / 2f + (-dy / len) * bend, (a.y + b.y) / 2f + (dx / len) * bend)
}

private fun quadPoint(a: Offset, c: Offset, b: Offset, t: Float): Offset {
    val m = 1f - t
    return Offset(m * m * a.x + 2f * m * t * c.x + t * t * b.x, m * m * a.y + 2f * m * t * c.y + t * t * b.y)
}

private fun quadTan(a: Offset, c: Offset, b: Offset, t: Float): Offset {
    val m = 1f - t
    val x = 2f * m * (c.x - a.x) + 2f * t * (b.x - c.x)
    val y = 2f * m * (c.y - a.y) + 2f * t * (b.y - c.y)
    val len = hypot(x, y).coerceAtLeast(0.0001f)
    return Offset(x / len, y / len)
}

private fun DrawScope.arrow(at: Offset, dir: Offset, size: Float, fill: Color, outline: Color) {
    val perp = Offset(-dir.y, dir.x)
    val tip = Offset(at.x + dir.x * size, at.y + dir.y * size)
    val l = Offset(at.x - dir.x * size * 0.4f + perp.x * size * 0.75f, at.y - dir.y * size * 0.4f + perp.y * size * 0.75f)
    val r = Offset(at.x - dir.x * size * 0.4f - perp.x * size * 0.75f, at.y - dir.y * size * 0.4f - perp.y * size * 0.75f)
    val p = Path().apply { moveTo(tip.x, tip.y); lineTo(l.x, l.y); lineTo(r.x, r.y); close() }
    drawPath(p, outline, style = Stroke(size * 0.35f, join = StrokeJoin.Round))
    drawPath(p, fill)
}

/** The pattern of colour [c] inside the box at [topLeft] ([w] x [h]): dots, stripes, rings, waves, checks, diamonds. */
internal fun DrawScope.drawTrailPattern(c: TrailColour, topLeft: Offset, w: Float, h: Float, ink: Color = trailPatternInk(c)) {
    val u = minOf(w, h)
    val cx = topLeft.x + w / 2f
    val cy = topLeft.y + h / 2f
    when (c) {
        TrailColour.CHERRY -> {
            val r = u * 0.1f
            drawCircle(ink, r, Offset(cx - u * 0.22f, cy - u * 0.14f))
            drawCircle(ink, r, Offset(cx + u * 0.22f, cy - u * 0.14f))
            drawCircle(ink, r, Offset(cx, cy + u * 0.2f))
        }
        TrailColour.TANGERINE -> clipRect(topLeft.x, topLeft.y, topLeft.x + w, topLeft.y + h) {
            for (k in -2..2) {
                val off = k * u * 0.3f
                drawLine(ink, Offset(cx + off - u * 0.5f, cy + u * 0.5f), Offset(cx + off + u * 0.5f, cy - u * 0.5f), strokeWidth = u * 0.1f, cap = StrokeCap.Round)
            }
        }
        TrailColour.LEMON -> {
            drawCircle(ink, u * 0.3f, Offset(cx, cy), style = Stroke(u * 0.09f))
            drawCircle(ink, u * 0.12f, Offset(cx, cy), style = Stroke(u * 0.08f))
        }
        TrailColour.LIME -> {
            for (row in 0..1) {
                val y = cy + (row - 0.5f) * u * 0.34f
                val x0 = cx - u * 0.35f
                val p = Path().apply {
                    moveTo(x0, y)
                    quadraticBezierTo(x0 + u * 0.175f, y - u * 0.16f, x0 + u * 0.35f, y)
                    quadraticBezierTo(x0 + u * 0.525f, y + u * 0.16f, x0 + u * 0.7f, y)
                }
                drawPath(p, ink, style = Stroke(u * 0.09f, cap = StrokeCap.Round))
            }
        }
        TrailColour.BLUEBERRY -> {
            val s = u * 0.22f
            drawRect(ink, Offset(cx - s, cy - s), Size(s, s))
            drawRect(ink, Offset(cx, cy), Size(s, s))
            drawRect(ink.copy(alpha = ink.alpha * 0.45f), Offset(cx, cy - s), Size(s, s))
            drawRect(ink.copy(alpha = ink.alpha * 0.45f), Offset(cx - s, cy), Size(s, s))
        }
        TrailColour.GRAPE -> {
            for (k in 0..1) {
                val dx = (k - 0.5f) * u * 0.36f
                val r = u * 0.2f
                val p = Path().apply {
                    moveTo(cx + dx, cy - r); lineTo(cx + dx + r * 0.8f, cy); lineTo(cx + dx, cy + r); lineTo(cx + dx - r * 0.8f, cy); close()
                }
                drawPath(p, ink)
            }
        }
    }
}

/** A small icon of landmark [index] (0 Cookie Hill ... 5 Cake Gate) centred on [c]. */
internal fun DrawScope.drawLandmarkIcon(index: Int, c: Offset, r: Float, ink: Color) {
    when (index) {
        0 -> {
            val p = Path().apply {
                moveTo(c.x - r, c.y + r * 0.7f); quadraticBezierTo(c.x, c.y - r * 1.5f, c.x + r, c.y + r * 0.7f); close()
            }
            drawPath(p, ink)
            drawCircle(Color.White.copy(alpha = 0.85f), r * 0.14f, Offset(c.x - r * 0.2f, c.y))
            drawCircle(Color.White.copy(alpha = 0.85f), r * 0.14f, Offset(c.x + r * 0.25f, c.y + r * 0.25f))
        }
        1 -> {
            drawCircle(ink, r * 0.28f, Offset(c.x + r * 0.45f, c.y - r * 0.55f))
            for (k in 0..1) {
                val y = c.y + r * (0.15f + k * 0.5f)
                val p = Path().apply {
                    moveTo(c.x - r, y)
                    quadraticBezierTo(c.x - r * 0.5f, y - r * 0.3f, c.x, y)
                    quadraticBezierTo(c.x + r * 0.5f, y + r * 0.3f, c.x + r, y)
                }
                drawPath(p, ink, style = Stroke(r * 0.2f, cap = StrokeCap.Round))
            }
        }
        2 -> {
            drawRect(ink, Offset(c.x - r * 0.4f, c.y - r * 0.2f), Size(r * 0.8f, r * 1.1f))
            val roof = Path().apply { moveTo(c.x - r * 0.6f, c.y - r * 0.2f); lineTo(c.x, c.y - r * 1.1f); lineTo(c.x + r * 0.6f, c.y - r * 0.2f); close() }
            drawPath(roof, ink)
            drawRect(Color.White.copy(alpha = 0.85f), Offset(c.x - r * 0.12f, c.y + r * 0.15f), Size(r * 0.24f, r * 0.34f))
        }
        3 -> {
            drawArc(ink, 180f, 180f, true, Offset(c.x - r * 0.9f, c.y - r * 0.9f), Size(r * 1.8f, r * 1.8f))
            drawRect(ink, Offset(c.x - r * 0.9f, c.y), Size(r * 1.8f, r * 0.8f))
            drawArc(Color.White.copy(alpha = 0.8f), 180f, 180f, true, Offset(c.x - r * 0.4f, c.y - r * 0.2f), Size(r * 0.8f, r * 0.8f))
            drawRect(Color.White.copy(alpha = 0.8f), Offset(c.x - r * 0.4f, c.y + r * 0.2f), Size(r * 0.8f, r * 0.6f))
        }
        4 -> {
            for (k in 0 until 5) {
                val a = -PI / 2 + k * 2 * PI / 5
                drawCircle(ink, r * 0.3f, Offset(c.x + (kotlin.math.cos(a) * r * 0.55f).toFloat(), c.y + (sin(a) * r * 0.55f).toFloat()))
            }
            drawCircle(Color.White.copy(alpha = 0.9f), r * 0.22f, c)
        }
        else -> {
            drawRect(ink, Offset(c.x - r * 0.9f, c.y - r * 0.1f), Size(r * 0.3f, r * 1.0f))
            drawRect(ink, Offset(c.x + r * 0.6f, c.y - r * 0.1f), Size(r * 0.3f, r * 1.0f))
            drawArc(ink, 180f, 180f, false, Offset(c.x - r * 0.9f, c.y - r * 0.8f), Size(r * 1.8f, r * 1.8f), style = Stroke(r * 0.3f))
        }
    }
}

/** One card (null = the back) drawn into the box at [topLeft] ([w] x [h]). */
internal fun DrawScope.drawTrailCard(card: TrailCard?, theme: SweetTrailTheme, topLeft: Offset, w: Float, h: Float) {
    val corner = CornerRadius(w * 0.14f)
    drawRoundRect(Color.Black.copy(alpha = 0.18f), Offset(topLeft.x + w * 0.02f, topLeft.y + h * 0.03f), Size(w, h), corner)
    if (card == null) {
        drawRoundRect(Color(0xFFFFFDF7), topLeft, Size(w, h), corner)
        val inner = w * 0.07f
        drawRoundRect(theme.cardBack, Offset(topLeft.x + inner, topLeft.y + inner), Size(w - inner * 2, h - inner * 2), CornerRadius(w * 0.1f))
        val c = Offset(topLeft.x + w / 2f, topLeft.y + h / 2f)
        drawCircle(Color.White.copy(alpha = 0.5f), w * 0.3f, c, style = Stroke(w * 0.03f))
        drawCircle(Color.White.copy(alpha = 0.35f), w * 0.2f, c, style = Stroke(w * 0.03f))
        drawSeatGlyph(4, c, w * 0.17f)
        return
    }
    drawRoundRect(Color(0xFFFFFDF7), topLeft, Size(w, h), corner)
    val m = w * 0.08f
    val ix = topLeft.x + m
    val iy = topLeft.y + m
    val iw = w - m * 2
    val ih = h - m * 2
    val r = CornerRadius(w * 0.1f)
    when (card.kind) {
        TrailCardKind.SINGLE, TrailCardKind.DOUBLE -> {
            val colour = card.colour ?: TrailColour.CHERRY
            drawRoundRect(trailColourFill(colour), Offset(ix, iy), Size(iw, ih), r)
            if (card.kind == TrailCardKind.DOUBLE) {
                drawTrailPattern(colour, Offset(ix, iy), iw, ih / 2f)
                drawTrailPattern(colour, Offset(ix, iy + ih / 2f), iw, ih / 2f)
                drawLine(Color.White.copy(alpha = 0.9f), Offset(ix + iw * 0.1f, iy + ih / 2f), Offset(ix + iw * 0.9f, iy + ih / 2f), strokeWidth = w * 0.025f, cap = StrokeCap.Round)
            } else {
                drawTrailPattern(colour, Offset(ix, iy), iw, ih)
            }
        }
        TrailCardKind.LANDMARK -> {
            drawRoundRect(Color(0xFFFFE9A8), Offset(ix, iy), Size(iw, ih), r)
            drawSeatGlyph(4, Offset(ix + iw / 2f, iy + ih * 0.2f), w * 0.1f)
            val idx = card.landmark?.ordinal ?: 0
            drawLandmarkIcon(idx, Offset(ix + iw / 2f, iy + ih * 0.58f), w * 0.3f, Color(0xFF7A3B12))
        }
    }
    drawRoundRect(Color.Black.copy(alpha = 0.18f), Offset(ix, iy), Size(iw, ih), r, style = Stroke(w * 0.015f))
}

private fun DrawScope.drawShortcut(startSq: Int, endSq: Int, u: Float) {
    val a = stCenter(startSq).let { Offset(it.x * u, it.y * u) }
    val b = stCenter(endSq).let { Offset(it.x * u, it.y * u) }
    val cc = stShortcutControl(startSq, endSq).let { Offset(it.x * u, it.y * u) }
    val path = Path().apply { moveTo(a.x, a.y); quadraticBezierTo(cc.x, cc.y, b.x, b.y) }
    when (SweetTrailBoard.SHORTCUT_NAMES[startSq]) {
        "jelly_bridge" -> {
            drawPath(path, Color.Black.copy(alpha = 0.2f), style = Stroke(u * 0.36f, cap = StrokeCap.Round))
            drawPath(path, Color(0xFFE8506B), style = Stroke(u * 0.3f, cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(floatArrayOf(u * 0.13f, u * 0.05f))))
            drawPath(path, Color.White.copy(alpha = 0.55f), style = Stroke(u * 0.05f, cap = StrokeCap.Round))
        }
        "pretzel_ladder" -> {
            val ctrlL = Offset(cc.x - u * 0.12f, cc.y)
            val ctrlR = Offset(cc.x + u * 0.12f, cc.y)
            val aL = Offset(a.x - u * 0.12f, a.y); val bL = Offset(b.x - u * 0.12f, b.y)
            val aR = Offset(a.x + u * 0.12f, a.y); val bR = Offset(b.x + u * 0.12f, b.y)
            val railL = Path().apply { moveTo(aL.x, aL.y); quadraticBezierTo(ctrlL.x, ctrlL.y, bL.x, bL.y) }
            val railR = Path().apply { moveTo(aR.x, aR.y); quadraticBezierTo(ctrlR.x, ctrlR.y, bR.x, bR.y) }
            drawPath(railL, Color(0xFFB5762F), style = Stroke(u * 0.07f, cap = StrokeCap.Round))
            drawPath(railR, Color(0xFFB5762F), style = Stroke(u * 0.07f, cap = StrokeCap.Round))
            for (k in 1..9) {
                val t = k / 10f
                drawLine(Color(0xFFE0A85C), quadPoint(aL, ctrlL, bL, t), quadPoint(aR, ctrlR, bR, t), strokeWidth = u * 0.07f, cap = StrokeCap.Round)
            }
        }
        else -> {
            drawPath(path, Color(0xFF3F8FD0), style = Stroke(u * 0.05f, cap = StrokeCap.Round))
            val mid = quadPoint(a, cc, b, 0.5f)
            drawLine(Color(0xFF3F8FD0), mid, Offset(mid.x, mid.y + u * 0.2f), strokeWidth = u * 0.04f)
            drawCircle(Color(0xFFFFFFFF), u * 0.1f, Offset(mid.x, mid.y + u * 0.26f))
            drawCircle(Color(0xFF3F8FD0), u * 0.1f, Offset(mid.x, mid.y + u * 0.26f), style = Stroke(u * 0.035f))
            drawCircle(Color.White.copy(alpha = 0.9f), u * 0.12f, Offset(a.x - u * 0.08f, a.y - u * 0.08f))
            drawCircle(Color.White.copy(alpha = 0.9f), u * 0.09f, Offset(a.x + u * 0.06f, a.y - u * 0.12f))
        }
    }
    drawCircle(Color(0xFF3A2A12).copy(alpha = 0.75f), u * 0.1f, a)
    drawCircle(Color.White, u * 0.05f, a)
    drawCircle(Color(0xFF3A2A12).copy(alpha = 0.75f), u * 0.1f, b, style = Stroke(u * 0.05f))
    val at = quadPoint(a, cc, b, 0.8f)
    arrow(at, quadTan(a, cc, b, 0.8f), u * 0.16f, Color.White, Color(0xFF3A2A12).copy(alpha = 0.7f))
}

private fun DrawScope.drawPuddle(center: Offset, u: Float, theme: SweetTrailTheme) {
    val blob = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(center.x - u * 0.42f, center.y - u * 0.3f, center.x + u * 0.42f, center.y + u * 0.34f))
        addOval(androidx.compose.ui.geometry.Rect(center.x - u * 0.12f, center.y - u * 0.42f, center.x + u * 0.36f, center.y + u * 0.1f))
        addOval(androidx.compose.ui.geometry.Rect(center.x - u * 0.46f, center.y - u * 0.05f, center.x - u * 0.1f, center.y + u * 0.3f))
    }
    drawPath(blob, theme.gooDark.copy(alpha = 0.95f), style = Stroke(u * 0.09f, join = StrokeJoin.Round))
    drawPath(blob, theme.goo)
    drawCircle(Color.White.copy(alpha = 0.6f), u * 0.07f, Offset(center.x - u * 0.2f, center.y - u * 0.12f))
    drawCircle(Color.White.copy(alpha = 0.45f), u * 0.045f, Offset(center.x + u * 0.12f, center.y + u * 0.08f))
    drawCircle(theme.gooDark.copy(alpha = 0.55f), u * 0.05f, Offset(center.x + u * 0.22f, center.y - u * 0.2f))
}

private fun DrawScope.drawFlag(at: Offset, u: Float, color: Color) {
    drawLine(Color(0xFF3A2A12), at, Offset(at.x, at.y - u * 0.7f), strokeWidth = u * 0.06f, cap = StrokeCap.Round)
    val p = Path().apply { moveTo(at.x, at.y - u * 0.7f); lineTo(at.x + u * 0.5f, at.y - u * 0.52f); lineTo(at.x, at.y - u * 0.34f); close() }
    drawPath(p, color)
}

private fun DrawScope.drawTableAndCake(u: Float, theme: SweetTrailTheme) {
    val banner = Offset(u * 0.1f, u * 0.08f)
    drawRoundRect(theme.gold.copy(alpha = 0.55f), banner, Size(u * 3.8f, u * 0.84f), CornerRadius(u * 0.3f))
    drawRoundRect(theme.roadEdge, banner, Size(u * 3.8f, u * 0.84f), CornerRadius(u * 0.3f), style = Stroke(u * 0.05f))
    // the party table with a cake
    val tx = u * 2.9f
    drawRoundRect(Color(0xFF8B5A2B), Offset(tx - u * 0.55f, u * 0.62f), Size(u * 1.1f, u * 0.14f), CornerRadius(u * 0.06f))
    drawRoundRect(Color(0xFFFFFFFF), Offset(tx - u * 0.3f, u * 0.36f), Size(u * 0.6f, u * 0.26f), CornerRadius(u * 0.06f))
    drawRoundRect(Color(0xFFF08FB4), Offset(tx - u * 0.22f, u * 0.2f), Size(u * 0.44f, u * 0.18f), CornerRadius(u * 0.05f))
    drawLine(Color(0xFFE5B800), Offset(tx, u * 0.2f), Offset(tx, u * 0.1f), strokeWidth = u * 0.05f, cap = StrokeCap.Round)
    drawCircle(Color(0xFFFF8A3D), u * 0.04f, Offset(tx, u * 0.09f))
    drawSeatGlyph(4, Offset(u * 3.55f, u * 0.5f), u * 0.18f)
}

/**
 * The board: the static layer (road, squares with their patterns, shortcuts, puddles, landmarks, start strip and
 * Party Table) is cached on (theme, rules); the piece layer on top is redrawn every frame. [leg] / [legT] /
 * [pulse] are read inside the draw so a hop or the idle pulse never recomposes. Cells are [cellDp] square; the box
 * is [SweetTrailLayout.COLUMNS] wide and [SweetTrailLayout.ROWS] tall. With a screen reader on, [squareLabels]
 * (one per square 0..61) replaces the single [description] so every square can be visited.
 */
@Composable
internal fun SweetTrailBoardView(
    s: SweetTrailState,
    shownPos: List<Int>,
    leg: () -> SweetTrailLeg?,
    legT: Animatable<Float, *>,
    pulse: State<Float>,
    cellDp: Dp,
    theme: SweetTrailTheme,
    seatColors: List<Color>,
    description: String,
    squareLabels: List<String>?,
    burst: ParticleBurst,
    modifier: Modifier = Modifier
) {
    val rules: SweetTrailRules = s.rules
    val trackLayer = remember(theme, rules) {
        Modifier.fillMaxSize().drawWithCache {
            val u = size.width / SweetTrailLayout.COLUMNS
            onDrawBehind {
                // the road: a fat rounded band through every square centre, start to Party Table
                val road = Path()
                for (sq in 0..SweetTrailBoard.PATH_SQUARES) {
                    val c = if (sq == 0) stCenter(1).let { Offset(it.x, it.y + 1f) } else stCenter(sq)
                    if (sq == 0) road.moveTo(c.x * u, c.y * u) else road.lineTo(c.x * u, c.y * u)
                }
                val top = stCenter(SweetTrailBoard.PATH_SQUARES)
                road.lineTo(top.x * u, 0.9f * u)
                drawPath(road, theme.roadEdge, style = Stroke(u * 1.0f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(road, theme.road, style = Stroke(u * 0.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                // start strip and Party Table
                drawRoundRect(theme.card, Offset(u * 0.1f, SweetTrailLayout.ROWS * u - u * 0.92f), Size(size.width - u * 0.2f, u * 0.84f), CornerRadius(u * 0.3f))
                drawFlag(Offset(u * 9.2f, SweetTrailLayout.ROWS * u - u * 0.15f), u, theme.rim)
                drawTableAndCake(u, theme)
                // squares
                for (sq in 1..SweetTrailBoard.PATH_SQUARES) {
                    val colour = SweetTrailBoard.colourAt(sq) ?: continue
                    val col = SweetTrailLayout.column(sq)
                    val row = SweetTrailLayout.row(sq)
                    val pad = u * 0.07f
                    val tl = Offset(col * u + pad, row * u + pad)
                    val sz = u - pad * 2
                    drawRoundRect(trailColourFill(colour), tl, Size(sz, sz), CornerRadius(u * 0.2f))
                    drawTrailPattern(colour, tl, sz, sz)
                    drawRoundRect(Color.Black.copy(alpha = 0.22f), tl, Size(sz, sz), CornerRadius(u * 0.2f), style = Stroke(u * 0.035f))
                }
                // puddles
                for (sq in rules.puddleSquares) drawPuddle(Offset(stCenter(sq).x * u, stCenter(sq).y * u), u, theme)
                // shortcuts
                if (rules.shortcutsOn) for ((a, b) in SweetTrailBoard.SHORTCUTS) drawShortcut(a, b, u)
                // landmarks: a round badge in the corner of the square
                com.gamesuite.games.sweettrail.TrailLandmark.values().forEachIndexed { i, lm ->
                    val c = stCenter(lm.square)
                    val at = Offset((c.x + 0.28f) * u, (c.y - 0.28f) * u)
                    drawCircle(Color.White.copy(alpha = 0.95f), u * 0.24f, at)
                    drawCircle(Color(0xFF3A2A12).copy(alpha = 0.6f), u * 0.24f, at, style = Stroke(u * 0.03f))
                    drawLandmarkIcon(i, at, u * 0.16f, Color(0xFF7A3B12))
                }
            }
        }
    }
    val boardSemantics = if (squareLabels == null) Modifier.semantics { contentDescription = description } else Modifier
    Box(modifier.size(cellDp * SweetTrailLayout.COLUMNS, cellDp * SweetTrailLayout.ROWS).then(boardSemantics)) {
        Box(trackLayer)
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / SweetTrailLayout.COLUMNS
            val moving = leg()
            val t = legT.value
            for (seat in s.players.indices) {
                if (moving != null && moving.seat == seat) continue
                val sq = shownPos.getOrElse(seat) { 0 }
                val crowd = shownPos.count { it == sq }
                val p = stPiecePos(seat, sq, crowd)
                val r = if (crowd > 1 && sq in 1..SweetTrailBoard.PATH_SQUARES) u * 0.2f else u * 0.3f
                val color = seatColors[seat % seatColors.size]
                if (seat == s.currentPlayerIndex && !s.over) {
                    drawCircle(color.copy(alpha = 0.35f + 0.55f * pulse.value), r * 1.5f, Offset(p.x * u, p.y * u), style = Stroke(u * 0.07f))
                }
                drawMarble(Offset(p.x * u, p.y * u), r, color, seat, glyph = 0.66f)
            }
            if (moving != null) {
                val ctl = moving.control
                val x: Float
                val y: Float
                if (ctl != null) {
                    val q = quadPoint(moving.from, ctl, moving.to, t)
                    x = q.x; y = q.y
                } else {
                    x = moving.from.x + (moving.to.x - moving.from.x) * t
                    y = moving.from.y + (moving.to.y - moving.from.y) * t
                }
                val lift = abs(sin(t * PI.toFloat() * moving.bounces)) * moving.lift
                val color = seatColors[moving.seat % seatColors.size]
                drawMarble(Offset(x * u, y * u), u * 0.3f, color, moving.seat, glyph = 0.66f, lift = lift * u)
            }
            drawBurst(burst)
        }
        if (squareLabels != null) {
            for (sq in 0..SweetTrailBoard.FINISH) {
                val c = stCenter(sq)
                Box(
                    Modifier
                        .offset(cellDp * (c.x - 0.5f), cellDp * (c.y - 0.5f))
                        .size(cellDp)
                        .semantics { contentDescription = squareLabels.getOrElse(sq) { "" } }
                )
            }
        }
    }
}

/** The card pile: a little stack of card backs; [thickness] 0..1 is how full the pile is. */
@Composable
internal fun SweetTrailPileArt(theme: SweetTrailTheme, thickness: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val layers = (1 + (thickness.coerceIn(0f, 1f) * 3f).toInt()).coerceIn(1, 4)
        val w = size.width * 0.78f
        val h = size.height * 0.84f
        for (k in layers - 1 downTo 0) {
            drawTrailCard(null, theme, Offset(size.width * 0.06f + k * size.width * 0.035f, size.height * 0.06f + (layers - 1 - k) * size.height * 0.03f), w, h)
        }
    }
}

/** One card that can flip: [flip] 0 = face down, 1 = face up; read only inside the layer and draw lambdas. */
@Composable
internal fun SweetTrailCardArt(card: () -> TrailCard?, flip: Animatable<Float, *>, theme: SweetTrailTheme, modifier: Modifier = Modifier) {
    Canvas(
        modifier.graphicsLayer {
            val t = flip.value
            rotationY = if (t < 0.5f) t * 180f else (t - 1f) * 180f
            cameraDistance = 14f * density
        }
    ) {
        val shown = if (flip.value < 0.5f) null else card()
        drawTrailCard(shown, theme, Offset(size.width * 0.06f, size.height * 0.04f), size.width * 0.86f, size.height * 0.9f)
    }
}

/** A check mark: "keep this card". */
@Composable
internal fun SweetTrailKeepIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply { moveTo(w * 0.2f, h * 0.55f); lineTo(w * 0.42f, h * 0.76f); lineTo(w * 0.8f, h * 0.27f) }
        drawPath(p, color, style = Stroke(w * 0.15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A star with a small sparkle: "use the wish". */
@Composable
internal fun SweetTrailWishIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawSeatGlyph(4, Offset(w * 0.46f, w * 0.52f), w * 0.3f)
        drawCircle(color, w * 0.05f, Offset(w * 0.82f, w * 0.2f))
        drawCircle(color, w * 0.035f, Offset(w * 0.2f, w * 0.18f))
    }
}

/** The small "wish ready" marker on a player chip: a star. */
@Composable
internal fun SweetTrailWishBadge(size: Dp) {
    Canvas(Modifier.size(size)) { drawSeatGlyph(4, Offset(this.size.width / 2f, this.size.height / 2f), this.size.width * 0.4f) }
}
