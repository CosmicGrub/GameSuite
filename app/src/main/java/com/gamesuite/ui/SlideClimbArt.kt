package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gamesuite.games.slideclimb.SlideClimbBoard
import com.gamesuite.games.slideclimb.SlideClimbLayout
import com.gamesuite.games.slideclimb.SlideClimbState
import com.gamesuite.ui.effects.ParticleBurst
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Original vector art for Slide and Climb, drawn on a Canvas: a cream and mint checker track with a number in
 * every square, ropes drawn as twisted brown vines with leaves and an arrowhead pointing UP, slides drawn as wide
 * striped ramps with an arrowhead pointing DOWN (so ropes and slides differ in shape and width, never only in
 * colour), and the shared glossy seat pieces (each seat has its own colour AND shape).
 */

internal class SlideClimbTheme(
    val bg: Color, val text: Color, val card: Color, val cellA: Color, val cellB: Color, val rim: Color,
    val rope: Color, val ropeLight: Color, val leaf: Color, val slide: Color, val slideDark: Color, val gold: Color
)

internal fun slideClimbTheme(dark: Boolean): SlideClimbTheme = if (!dark) SlideClimbTheme(
    bg = Color(0xFFF6F2FC), text = Color(0xFF241B33), card = Color(0xFFE6DDF3),
    cellA = Color(0xFFFFF4CF), cellB = Color(0xFFD8EFE0), rim = Color(0xFF541380),
    rope = Color(0xFF6B3F16), ropeLight = Color(0xFFC89A5A), leaf = Color(0xFF2E7D32),
    slide = Color(0xFFF2994A), slideDark = Color(0xFFB8561A), gold = Color(0xFFE5B800)
) else SlideClimbTheme(
    bg = Color(0xFF17131F), text = Color(0xFFF0EAFB), card = Color(0xFF2A2238),
    cellA = Color(0xFF3B3452), cellB = Color(0xFF2F2A45), rim = Color(0xFFAF85CC),
    rope = Color(0xFFD9A867), ropeLight = Color(0xFF8A5A24), leaf = Color(0xFF81C784),
    slide = Color(0xFFE8893A), slideDark = Color(0xFFFFB878), gold = Color(0xFFFFD84D)
)

/** Where a piece rests, in cell units (x across, y down) on the 10 wide, 11 tall canvas; square 0 is the start strip. */
internal fun slideClimbCellCenter(square: Int): Offset {
    if (square <= 0) return Offset(0.9f, SlideClimbBoard.SIDE + 0.5f)
    val col = SlideClimbBoard.column(square)
    val rowTop = SlideClimbBoard.SIDE - 1 - SlideClimbBoard.row(square)
    return Offset(col + 0.5f, rowTop + 0.5f)
}

/** Where seat [seat]'s piece is drawn on [square] when [crowd] pieces share it (cell units). */
internal fun slideClimbPiecePos(seat: Int, square: Int, crowd: Int): Offset {
    val c = slideClimbCellCenter(square)
    if (square <= 0) return Offset(c.x + seat * 0.9f, c.y)
    if (crowd <= 1) return c
    return Offset(c.x + ((seat % 2) - 0.5f) * 0.36f, c.y + ((seat / 2) - 0.5f) * 0.36f)
}

/** A hop of one piece between two points (cell units); [lift] is the arc height in cells. */
internal class SlideClimbLeg(val seat: Int, val from: Offset, val to: Offset, val lift: Float)

private fun quad(a: Offset, c: Offset, b: Offset, t: Float): Offset {
    val m = 1f - t
    return Offset(m * m * a.x + 2f * m * t * c.x + t * t * b.x, m * m * a.y + 2f * m * t * c.y + t * t * b.y)
}

private fun quadTangent(a: Offset, c: Offset, b: Offset, t: Float): Offset {
    val m = 1f - t
    val x = 2f * m * (c.x - a.x) + 2f * t * (b.x - c.x)
    val y = 2f * m * (c.y - a.y) + 2f * t * (b.y - c.y)
    val len = hypot(x, y).coerceAtLeast(0.0001f)
    return Offset(x / len, y / len)
}

private fun bendControl(a: Offset, b: Offset, bend: Float): Offset {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = hypot(dx, dy).coerceAtLeast(0.0001f)
    return Offset((a.x + b.x) / 2f + (-dy / len) * bend, (a.y + b.y) / 2f + (dx / len) * bend)
}

private fun DrawScope.arrowHead(at: Offset, dir: Offset, size: Float, fill: Color, outline: Color) {
    val perp = Offset(-dir.y, dir.x)
    val tip = Offset(at.x + dir.x * size, at.y + dir.y * size)
    val l = Offset(at.x - dir.x * size * 0.4f + perp.x * size * 0.75f, at.y - dir.y * size * 0.4f + perp.y * size * 0.75f)
    val r = Offset(at.x - dir.x * size * 0.4f - perp.x * size * 0.75f, at.y - dir.y * size * 0.4f - perp.y * size * 0.75f)
    val p = Path().apply { moveTo(tip.x, tip.y); lineTo(l.x, l.y); lineTo(r.x, r.y); close() }
    drawPath(p, outline, style = Stroke(size * 0.35f, join = StrokeJoin.Round))
    drawPath(p, fill)
}

/** A slide: a wide striped ramp from its top [top] down to its foot [foot] (pixels), arrowhead pointing down. */
private fun DrawScope.drawSlide(top: Offset, foot: Offset, u: Float, bend: Float, theme: SlideClimbTheme) {
    val c = bendControl(top, foot, bend)
    val path = Path().apply { moveTo(top.x, top.y); quadraticBezierTo(c.x, c.y, foot.x, foot.y) }
    drawPath(path, Color.Black.copy(alpha = 0.22f), style = Stroke(u * 0.5f, cap = StrokeCap.Round))
    drawPath(path, theme.slideDark, style = Stroke(u * 0.44f, cap = StrokeCap.Round))
    drawPath(path, theme.slide, style = Stroke(u * 0.34f, cap = StrokeCap.Round))
    drawPath(
        path, Color.White.copy(alpha = 0.9f),
        style = Stroke(u * 0.05f, cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(floatArrayOf(u * 0.2f, u * 0.2f)))
    )
    drawCircle(theme.slideDark, u * 0.24f, top)
    drawCircle(theme.slide, u * 0.17f, top)
    val at = quad(top, c, foot, 0.84f)
    arrowHead(at, quadTangent(top, c, foot, 0.84f), u * 0.2f, Color.White, theme.slideDark)
}

/** A rope: a twisted brown vine with leaves from its foot [foot] up to its top [top] (pixels), arrowhead pointing up. */
private fun DrawScope.drawRope(foot: Offset, top: Offset, u: Float, bend: Float, theme: SlideClimbTheme) {
    val c = bendControl(foot, top, bend)
    val path = Path().apply { moveTo(foot.x, foot.y); quadraticBezierTo(c.x, c.y, top.x, top.y) }
    drawPath(path, Color.Black.copy(alpha = 0.2f), style = Stroke(u * 0.2f, cap = StrokeCap.Round))
    drawPath(path, theme.rope, style = Stroke(u * 0.15f, cap = StrokeCap.Round))
    drawPath(
        path, theme.ropeLight,
        style = Stroke(u * 0.07f, cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(floatArrayOf(u * 0.12f, u * 0.1f)))
    )
    for (k in 1..4) {
        val t = k / 5f
        val p = quad(foot, c, top, t)
        val d = quadTangent(foot, c, top, t)
        val side = if (k % 2 == 0) 1f else -1f
        val leafAt = Offset(p.x + (-d.y) * side * u * 0.15f, p.y + d.x * side * u * 0.15f)
        drawCircle(theme.leaf, u * 0.085f, leafAt)
        drawCircle(Color.White.copy(alpha = 0.35f), u * 0.03f, Offset(leafAt.x - u * 0.02f, leafAt.y - u * 0.025f))
    }
    drawCircle(theme.rope, u * 0.13f, foot)
    drawCircle(theme.ropeLight, u * 0.07f, foot)
    drawCircle(theme.rope, u * 0.13f, top)
    val at = quad(foot, c, top, 0.86f)
    arrowHead(at, quadTangent(foot, c, top, 0.86f), u * 0.17f, theme.leaf, Color.White)
}

/**
 * The board: the static track layer (squares, numbers, slides, ropes, finish and start strip) is cached, the
 * piece layer on top is redrawn every frame. [leg] / [legT] / [pulse] are read inside the draw so a hop or the
 * idle pulse never recomposes. The track layer's modifier is remembered on (theme, layout, measurer), so a
 * recomposition with the same theme and layout keeps the cached track instead of re-measuring 100 numbers.
 * Cells are [cellDp] square; the box is 10 cells wide and 11 tall (the bottom row is the start strip).
 */
@Composable
internal fun SlideClimbBoardView(
    s: SlideClimbState,
    shownPos: List<Int>,
    leg: () -> SlideClimbLeg?,
    legT: Animatable<Float, *>,
    pulse: State<Float>,
    cellDp: Dp,
    theme: SlideClimbTheme,
    seatColors: List<Color>,
    description: String,
    burst: ParticleBurst,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val layout: SlideClimbLayout = s.layout
    val trackLayer = remember(theme, layout, measurer) {
        Modifier.fillMaxSize().drawWithCache {
                val u = size.width / 10f
                val style = TextStyle(fontSize = (u * 0.26f).toSp(), fontWeight = FontWeight.Bold, color = theme.text.copy(alpha = 0.8f))
                val numbers = (1..SlideClimbBoard.SQUARES).map { measurer.measure(AnnotatedString(it.toString()), style) }
                onDrawBehind {
                    for (sq in 1..SlideClimbBoard.SQUARES) {
                        val col = SlideClimbBoard.column(sq)
                        val rowTop = SlideClimbBoard.SIDE - 1 - SlideClimbBoard.row(sq)
                        drawRect(if ((col + rowTop) % 2 == 0) theme.cellA else theme.cellB, Offset(col * u, rowTop * u), Size(u, u))
                    }
                    // finish square: gold with a star
                    val fc = slideClimbCellCenter(SlideClimbBoard.FINISH)
                    val fcol = SlideClimbBoard.column(SlideClimbBoard.FINISH)
                    val frow = SlideClimbBoard.SIDE - 1 - SlideClimbBoard.row(SlideClimbBoard.FINISH)
                    drawRect(theme.gold.copy(alpha = 0.55f), Offset(fcol * u, frow * u), Size(u, u))
                    drawSeatGlyph(4, Offset(fc.x * u, fc.y * u + u * 0.08f), u * 0.24f)
                    // start strip
                    drawRoundRect(
                        theme.card, Offset(u * 0.05f, SlideClimbBoard.SIDE * u + u * 0.06f),
                        Size(size.width - u * 0.1f, u * 0.88f), CornerRadius(u * 0.3f)
                    )
                    val sy = SlideClimbBoard.SIDE * u + u * 0.5f
                    arrowHead(Offset(size.width - u * 0.7f, sy), Offset(0f, -1f), u * 0.24f, theme.rim, theme.card)
                    // jumps: slides under ropes
                    val slides = layout.slides.entries.sortedBy { it.key }
                    slides.forEachIndexed { i, e ->
                        val top = slideClimbCellCenter(e.key)
                        val foot = slideClimbCellCenter(e.value)
                        drawSlide(Offset(top.x * u, top.y * u), Offset(foot.x * u, foot.y * u), u, u * 0.9f * (if (i % 2 == 0) 1f else -1f), theme)
                    }
                    val ropes = layout.ropes.entries.sortedBy { it.key }
                    ropes.forEachIndexed { i, e ->
                        val foot = slideClimbCellCenter(e.key)
                        val top = slideClimbCellCenter(e.value)
                        drawRope(Offset(foot.x * u, foot.y * u), Offset(top.x * u, top.y * u), u, u * 0.45f * (if (i % 2 == 0) -1f else 1f), theme)
                    }
                    for (sq in 1..SlideClimbBoard.SQUARES) {
                        val col = SlideClimbBoard.column(sq)
                        val rowTop = SlideClimbBoard.SIDE - 1 - SlideClimbBoard.row(sq)
                        drawText(numbers[sq - 1], topLeft = Offset(col * u + u * 0.07f, rowTop * u + u * 0.02f))
                    }
                }
            }
    }
    Box(modifier.size(cellDp * 10, cellDp * 11).semantics { contentDescription = description }) {
        Box(trackLayer)
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 10f
            val moving = leg()
            val t = legT.value
            for (seat in s.players.indices) {
                if (moving != null && moving.seat == seat) continue
                val sq = shownPos.getOrElse(seat) { 0 }
                val crowd = shownPos.count { it == sq }
                val p = slideClimbPiecePos(seat, sq, crowd)
                val r = if (crowd > 1 && sq > 0) u * 0.2f else u * 0.3f
                val color = seatColors[seat % seatColors.size]
                if (seat == s.currentPlayerIndex && !s.over) {
                    drawCircle(color.copy(alpha = 0.35f + 0.55f * pulse.value), r * 1.5f, Offset(p.x * u, p.y * u), style = Stroke(u * 0.07f))
                }
                drawMarble(Offset(p.x * u, p.y * u), r, color, seat, glyph = 0.66f)
            }
            if (moving != null) {
                val x = moving.from.x + (moving.to.x - moving.from.x) * t
                val y = moving.from.y + (moving.to.y - moving.from.y) * t
                val lift = sin(t * PI.toFloat()) * moving.lift
                val color = seatColors[moving.seat % seatColors.size]
                drawMarble(Offset(x * u, y * u), u * 0.3f, color, moving.seat, glyph = 0.66f, lift = lift * u)
            }
            drawBurst(burst)
        }
    }
}

/** A check mark: "keep this roll". */
@Composable
internal fun SlideClimbKeepIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.2f, h * 0.55f); lineTo(w * 0.42f, h * 0.76f); lineTo(w * 0.8f, h * 0.27f)
        }
        drawPath(p, color, style = Stroke(w * 0.15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A die inside a circular arrow: "spend the spare roll and roll again". */
@Composable
internal fun SlideClimbSpareIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val r = w * 0.42f
        val start = -60f
        val sweep = 280f
        drawArc(
            color, startAngle = start, sweepAngle = sweep, useCenter = false,
            topLeft = Offset(cx - r, cy - r), size = Size(r * 2f, r * 2f),
            style = Stroke(w * 0.09f, cap = StrokeCap.Round)
        )
        val endRad = Math.toRadians((start + sweep).toDouble())
        val ex = cx + (r * kotlin.math.cos(endRad)).toFloat()
        val ey = cy + (r * kotlin.math.sin(endRad)).toFloat()
        val tx = (-kotlin.math.sin(endRad)).toFloat()
        val ty = kotlin.math.cos(endRad).toFloat()
        arrowHead(Offset(ex, ey), Offset(tx, ty), w * 0.17f, color, color)
        val d = w * 0.17f
        drawRoundRect(color, Offset(cx - d, cy - d), Size(d * 2f, d * 2f), CornerRadius(d * 0.4f), style = Stroke(w * 0.06f))
        drawCircle(color, w * 0.04f, Offset(cx, cy))
    }
}

/** The small "spare roll ready" marker on a player chip: just a little die. */
@Composable
internal fun SlideClimbSpareBadge(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawRoundRect(color, Offset(w * 0.1f, w * 0.1f), Size(w * 0.8f, w * 0.8f), CornerRadius(w * 0.2f), style = Stroke(w * 0.12f))
        drawCircle(color, w * 0.09f, Offset(w * 0.3f, w * 0.3f))
        drawCircle(color, w * 0.09f, Offset(w * 0.5f, w * 0.5f))
        drawCircle(color, w * 0.09f, Offset(w * 0.7f, w * 0.7f))
    }
}

internal val SlideClimbPanelShape = RoundedCornerShape(24.dp)
