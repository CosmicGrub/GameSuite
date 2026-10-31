package com.gamesuite.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gamesuite.games.shogi.ShogiPiece
import com.gamesuite.games.shogi.ShogiPieceArt
import com.gamesuite.games.shogi.ShogiState
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** "Kanji" is the traditional lettering; "Symbols" draws how the piece moves (arrows) plus a Western letter. */
enum class ShogiPieceStyle { KANJI, SYMBOLS }

internal data class ShogiPalette(
    val background: Color,
    val text: Color,
    val boardTop: Color,
    val boardBottom: Color,
    val gridLine: Color,
    val pieceTop: Color,
    val pieceBottom: Color,
    val pieceEdge: Color,
    val inkSente: Color,
    val inkGote: Color,
    val inkPromoted: Color,
    val lastMove: Color,
    val legal: Color,
    val select: Color,
    val check: Color,
    val hint: Color,
    val tray: Color
)

internal fun shogiPalette(dark: Boolean): ShogiPalette = if (!dark) {
    ShogiPalette(
        background = Color(0xFFFBF1E6), text = Color(0xFF3A2E22),
        boardTop = Color(0xFFEBC272), boardBottom = Color(0xFFD9A649), gridLine = Color(0xFF5B3A17),
        pieceTop = Color(0xFFF8E6B4), pieceBottom = Color(0xFFE6C77E), pieceEdge = Color(0xFF6B4A1F),
        inkSente = Color(0xFF1E1B18), inkGote = Color(0xFF1A2742), inkPromoted = Color(0xFFB3261E),
        lastMove = Color(0x55F2C230), legal = Color(0xFF1F7A4D), select = Color(0xFF1B5FBF),
        check = Color(0xFFD32F2F), hint = Color(0xFF8E24AA), tray = Color(0x22000000)
    )
} else {
    ShogiPalette(
        background = Color(0xFF1C1712), text = Color(0xFFF3E9DB),
        boardTop = Color(0xFFB88A3E), boardBottom = Color(0xFF9A7030), gridLine = Color(0xFF2A1B0A),
        pieceTop = Color(0xFFEBD49A), pieceBottom = Color(0xFFD3B26B), pieceEdge = Color(0xFF3B2810),
        inkSente = Color(0xFF1E1B18), inkGote = Color(0xFF1A2742), inkPromoted = Color(0xFFA81F18),
        lastMove = Color(0x66F5CE4E), legal = Color(0xFF7FE0B0), select = Color(0xFF7FB2FF),
        check = Color(0xFFFF6E6E), hint = Color(0xFFD58BEA), tray = Color(0x33FFFFFF)
    )
}

private fun DrawScope.arrow(from: Offset, to: Offset, color: Color, stroke: Float, doubleHead: Boolean) {
    drawLine(color, from, to, stroke, StrokeCap.Round)
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = sqrt(dx * dx + dy * dy)
    if (len < 0.001f) return
    val ux = dx / len
    val uy = dy / len
    val head = stroke * 2.6f
    fun chevron(tip: Offset) {
        drawLine(color, tip, Offset(tip.x - ux * head + uy * head * 0.6f, tip.y - uy * head - ux * head * 0.6f), stroke, StrokeCap.Round)
        drawLine(color, tip, Offset(tip.x - ux * head - uy * head * 0.6f, tip.y - uy * head + ux * head * 0.6f), stroke, StrokeCap.Round)
    }
    chevron(to)
    if (doubleHead) chevron(Offset(to.x - ux * head * 1.15f, to.y - uy * head * 1.15f))
}

/**
 * One wooden shogi piece (a pentagon with bevel and soft shadow), drawn entirely with vector paths. [rotated]
 * turns it to face the opponent, as in the real game. Colour is never the only cue: a promoted piece also
 * changes character / gets a plus sign, and the owner shows in the piece's direction.
 */
@Composable
internal fun ShogiPieceToken(
    code: Int,
    size: Dp,
    rotated: Boolean,
    style: ShogiPieceStyle,
    palette: ShogiPalette,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val side = ShogiPiece.side(code)
    val type = ShogiPiece.type(code)
    val promoted = ShogiPiece.isPromoted(type)
    val ink = when {
        promoted -> palette.inkPromoted
        side == 0 -> palette.inkSente
        else -> palette.inkGote
    }
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val body = Path().apply {
            moveTo(w * 0.5f, w * 0.05f)
            lineTo(w * 0.83f, w * 0.24f)
            lineTo(w * 0.91f, w * 0.95f)
            lineTo(w * 0.09f, w * 0.95f)
            lineTo(w * 0.17f, w * 0.24f)
            close()
        }
        rotate(if (rotated) 180f else 0f, Offset(w / 2f, w / 2f)) {
            translate(w * 0.025f, w * 0.035f) { drawPath(body, Color.Black.copy(alpha = 0.30f)) }
            drawPath(body, Brush.verticalGradient(listOf(palette.pieceTop, palette.pieceBottom)))
            drawPath(body, palette.pieceEdge, style = Stroke(w * 0.035f, join = StrokeJoin.Round))
            scale(0.83f, Offset(w / 2f, w * 0.6f)) {
                drawPath(body, palette.pieceEdge.copy(alpha = 0.35f), style = Stroke(w * 0.014f, join = StrokeJoin.Round))
            }
            if (style == ShogiPieceStyle.KANJI) {
                val layout = measurer.measure(
                    ShogiPieceArt.kanji(type, side),
                    TextStyle(color = ink, fontSize = (w * 0.46f).toSp(), fontWeight = FontWeight.Bold)
                )
                drawText(layout, topLeft = Offset((w - layout.size.width) / 2f, w * 0.6f - layout.size.height / 2f))
            } else {
                val c = Offset(w * 0.5f, w * 0.6f)
                val stroke = w * 0.045f
                val r0 = w * 0.14f
                val moves = ShogiPieceArt.moves(type)
                fun unit(v: Pair<Int, Int>): Offset {
                    val len = sqrt((v.first * v.first + v.second * v.second).toFloat())
                    return Offset(v.second / len, v.first / len)
                }
                for (v in moves.steps) {
                    val u = unit(v)
                    arrow(c + u * r0, c + u * (r0 + w * 0.17f), ink, stroke, false)
                }
                for (v in moves.slides) {
                    val u = unit(v)
                    arrow(c + u * r0, c + u * (r0 + w * 0.27f), ink, stroke * 1.15f, true)
                }
                if (moves.knight) {
                    val p1 = c + Offset(0f, -w * 0.24f)
                    drawLine(ink, c + Offset(0f, -r0), p1, stroke, StrokeCap.Round)
                    arrow(p1, c + Offset(-w * 0.2f, -w * 0.38f), ink, stroke, false)
                    arrow(p1, c + Offset(w * 0.2f, -w * 0.38f), ink, stroke, false)
                }
                drawCircle(palette.pieceTop, r0, c)
                drawCircle(ink, r0, c, style = Stroke(stroke * 0.7f))
                val label = (if (promoted) "+" else "") + ShogiPieceArt.letter(type)
                val layout = measurer.measure(
                    label,
                    TextStyle(color = ink, fontSize = (w * if (promoted) 0.15f else 0.19f).toSp(), fontWeight = FontWeight.ExtraBold)
                )
                drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f))
            }
        }
    }
}

/** What the board draws on top of the pieces for one tap target. */
internal class ShogiHighlights(
    val selected: Int = -1,
    val targets: Set<Int> = emptySet(),
    val captureTargets: Set<Int> = emptySet(),
    val hintFrom: Int = -1,
    val hintTo: Int = -1,
    val checkSquare: Int = -1,
    val pulse: Float = 1f
)

/**
 * The wooden board with its pieces, last-move marks, legal-move dots (empty square) / corner brackets
 * (capture), selection square, check ring and hint outline, plus the flying piece for the latest move
 * ([flight] 0..1 with overshoot; 1 means at rest) and the captured piece drifting toward the capturer's hand.
 * A transparent button layer on top carries the touch targets and TalkBack text.
 */
@Composable
internal fun ShogiBoardView(
    s: ShogiState,
    viewSide: Int,
    cell: Dp,
    style: ShogiPieceStyle,
    palette: ShogiPalette,
    highlights: ShogiHighlights,
    flight: Float,
    descriptions: (Int) -> String,
    tapLabel: String,
    enabled: Boolean,
    onTap: (Int) -> Unit
) {
    val n = s.n
    val flipped = viewSide == 1
    val density = LocalDensity.current
    val cellPx = with(density) { cell.toPx() }
    fun col(sq: Int) = if (flipped) n - 1 - sq % n else sq % n
    fun row(sq: Int) = if (flipped) n - 1 - sq / n else sq / n
    fun xy(sq: Int) = Offset(col(sq) * cellPx, row(sq) * cellPx)
    val flying = flight < 0.999f && s.lastTo >= 0 && s.lastTo < n * n
    val total = cell * n

    Box(Modifier.size(total)) {
        Canvas(Modifier.size(total)) {
            drawRect(Brush.verticalGradient(listOf(palette.boardTop, palette.boardBottom)))
            // faint wood grain
            for (i in 0 until 14) {
                val y = size.height * (i + 0.5f) / 14f
                drawLine(palette.gridLine.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y + cellPx * 0.12f), cellPx * 0.05f)
            }
            for (i in 0..n) {
                val p = i * cellPx
                val sw = if (i == 0 || i == n) cellPx * 0.045f else cellPx * 0.022f
                drawLine(palette.gridLine, Offset(p, 0f), Offset(p, size.height), sw)
                drawLine(palette.gridLine, Offset(0f, p), Offset(size.width, p), sw)
            }
            if (n == 9) {
                for (r in intArrayOf(3, 6)) for (c in intArrayOf(3, 6)) drawCircle(palette.gridLine, cellPx * 0.07f, Offset(c * cellPx, r * cellPx))
            }
            for (sq in intArrayOf(s.lastFrom, s.lastTo)) {
                if (sq in 0 until n * n) drawRect(palette.lastMove, xy(sq), Size(cellPx, cellPx))
            }
        }

        // pieces at rest
        for (sq in 0 until n * n) {
            val code = s.cells[sq]
            if (code == 0) continue
            if (flying && sq == s.lastTo) continue
            androidx.compose.runtime.key(sq) {
                val lifted = sq == highlights.selected
                val lift by animateFloatAsState(if (lifted) 1.1f else 1f, tween(120), label = "lift")
                val o = xy(sq)
                ShogiPieceToken(
                    code, cell, ShogiPiece.side(code) != viewSide, style, palette,
                    Modifier
                        .offset { IntOffset(o.x.roundToInt(), o.y.roundToInt()) }
                        .graphicsLayer { scaleX = lift; scaleY = lift }
                )
            }
        }

        // the piece that just moved, in flight (a dropped piece arrives from the mover's hand tray)
        if (flying) {
            val code = s.cells[s.lastTo]
            if (code != 0) {
                val mover = ShogiPiece.side(code)
                val from = if (s.lastFrom >= 0) xy(s.lastFrom) else Offset(
                    (n - 1) * cellPx / 2f, if (mover == viewSide) (n + 0.5f) * cellPx else -1.5f * cellPx
                )
                val to = xy(s.lastTo)
                val f = flight
                val o = Offset(from.x + (to.x - from.x) * f, from.y + (to.y - from.y) * f)
                ShogiPieceToken(
                    code, cell, mover != viewSide, style, palette,
                    Modifier
                        .offset { IntOffset(o.x.roundToInt(), o.y.roundToInt()) }
                        .graphicsLayer { val k = 1f + 0.12f * (1f - f); scaleX = k; scaleY = k }
                )
            }
            if (s.lastCaptured != 0) {
                val cap = s.lastCaptured
                val capturerBottom = ShogiPiece.side(cap) != viewSide
                val to = xy(s.lastTo)
                val f = flight.coerceIn(0f, 1f)
                val o = Offset(to.x, to.y + (if (capturerBottom) 1f else -1f) * cellPx * 1.3f * f)
                ShogiPieceToken(
                    cap, cell, ShogiPiece.side(cap) != viewSide, style, palette,
                    Modifier
                        .offset { IntOffset(o.x.roundToInt(), o.y.roundToInt()) }
                        .graphicsLayer { alpha = 1f - f; val k = 1f - 0.35f * f; scaleX = k; scaleY = k }
                )
            }
        }

        // legal moves, selection, check, hint
        Canvas(Modifier.size(total)) {
            fun center(sq: Int) = xy(sq) + Offset(cellPx / 2f, cellPx / 2f)
            if (highlights.checkSquare >= 0) {
                val o = xy(highlights.checkSquare)
                drawRect(palette.check, o + Offset(cellPx * 0.03f, cellPx * 0.03f), Size(cellPx * 0.94f, cellPx * 0.94f), style = Stroke(cellPx * 0.07f))
                drawCircle(palette.check.copy(alpha = 0.18f * highlights.pulse), cellPx * 0.5f, center(highlights.checkSquare))
            }
            for (sq in highlights.targets) {
                if (sq in highlights.captureTargets) {
                    val o = xy(sq)
                    val L = cellPx * 0.28f
                    val m = cellPx * 0.06f
                    val sw = cellPx * 0.07f
                    val c = palette.legal.copy(alpha = highlights.pulse)
                    drawLine(c, o + Offset(m, m), o + Offset(m + L, m), sw); drawLine(c, o + Offset(m, m), o + Offset(m, m + L), sw)
                    drawLine(c, o + Offset(cellPx - m, m), o + Offset(cellPx - m - L, m), sw); drawLine(c, o + Offset(cellPx - m, m), o + Offset(cellPx - m, m + L), sw)
                    drawLine(c, o + Offset(m, cellPx - m), o + Offset(m + L, cellPx - m), sw); drawLine(c, o + Offset(m, cellPx - m), o + Offset(m, cellPx - m - L), sw)
                    drawLine(c, o + Offset(cellPx - m, cellPx - m), o + Offset(cellPx - m - L, cellPx - m), sw); drawLine(c, o + Offset(cellPx - m, cellPx - m), o + Offset(cellPx - m, cellPx - m - L), sw)
                } else {
                    drawCircle(palette.legal.copy(alpha = 0.55f + 0.45f * highlights.pulse), cellPx * 0.13f, center(sq))
                }
            }
            if (highlights.selected >= 0) {
                val o = xy(highlights.selected)
                drawRect(palette.select, o + Offset(cellPx * 0.04f, cellPx * 0.04f), Size(cellPx * 0.92f, cellPx * 0.92f), style = Stroke(cellPx * 0.07f))
            }
            for (sq in intArrayOf(highlights.hintFrom, highlights.hintTo)) {
                if (sq >= 0) {
                    drawRect(
                        palette.hint, xy(sq) + Offset(cellPx * 0.06f, cellPx * 0.06f), Size(cellPx * 0.88f, cellPx * 0.88f),
                        style = Stroke(cellPx * 0.06f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(cellPx * 0.14f, cellPx * 0.1f)))
                    )
                }
            }
        }

        // touch layer
        for (sq in 0 until n * n) {
            val o = xy(sq)
            val description = descriptions(sq)
            Box(
                Modifier
                    .offset { IntOffset(o.x.roundToInt(), o.y.roundToInt()) }
                    .size(cell)
                    .semantics { contentDescription = description }
                    .clickable(enabled = enabled, onClickLabel = tapLabel, role = Role.Button) { onTap(sq) }
            )
        }
    }
}

/** Slow ease-out with a little overshoot, for the moved piece settling on its square. */
internal fun shogiOvershoot(t: Float): Float {
    val c1 = 1.2f
    val c3 = c1 + 1f
    val u = t - 1f
    return 1f + c3 * u * u * u + c1 * u * u
}

internal val ShogiMoveEasing = FastOutSlowInEasing

/** A soft tray behind a hand row. */
@Composable
internal fun ShogiTray(palette: ShogiPalette, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.background(palette.tray, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))) { content() }
}
