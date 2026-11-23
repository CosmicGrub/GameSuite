package com.gamesuite.ui

import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gamesuite.R
import com.gamesuite.games.backgammon.BackgammonMove
import com.gamesuite.games.backgammon.BackgammonPhase
import com.gamesuite.games.backgammon.BackgammonRules
import com.gamesuite.games.backgammon.BackgammonState
import com.gamesuite.uikit.fitBoard
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Colours of the Backgammon screen. The two sides also differ by SHAPE, never colour alone: White
 * checkers carry a ring, Black checkers a solid square (see [drawBgChecker]); legal destinations are
 * a dot (or a ring with a cross on a hit), the hint is a dashed ring with a star.
 */
internal data class BackgammonPalette(
    val background: Color,
    val textPrimary: Color,
    val frame: Color,
    val wood: Color,
    val barColor: Color,
    val trayColor: Color,
    val pointLight: Color,
    val pointDark: Color,
    val white: Color,
    val whiteEdge: Color,
    val whiteMark: Color,
    val black: Color,
    val blackEdge: Color,
    val blackMark: Color,
    val accent: Color,
    val selected: Color,
    val hint: Color
)

/**
 * The board's geometry in dp. The board is `14 x 10` cells in one orientation: along the MAIN axis
 * six points, the bar, six points, then the bear-off trays (12 + 1 + 1 cells); across it, the two
 * rows of triangles (4.5 cells each) with a one-cell gap. [vertical] turns the board a quarter
 * turn so a phone held upright can use its height: main runs top to bottom and the 12 points of a
 * row become a column. White's home board is always at the bottom-right, Black's at the top-right,
 * and the trays are the last band of the main axis, Black's across the top half, White's across the
 * bottom half.
 *
 * Absolute point index `i` (0..23) is White's point `i + 1`: indices 12..23 are the far row (left to
 * right), 11..0 the near row.
 */
private class BgGeometry(val vertical: Boolean, val cell: Float) {
    val mainLen = 14f * cell
    val crossLen = 10f * cell
    val width = if (vertical) crossLen else mainLen
    val height = if (vertical) mainLen else crossLen
    val tl = 4.5f * cell
    val dia = 0.9f * cell
    val barM = 6f * cell
    val trayM = 13f * cell

    fun rowOf(index: Int): Int = if (index >= 12) 0 else 1
    fun slotOf(index: Int): Int = if (index >= 12) index - 12 else 11 - index
    fun mStart(slot: Int): Float = (if (slot < 6) slot else slot + 1) * cell

    fun toXY(m: Float, c: Float): Offset = if (vertical) Offset(c, m) else Offset(m, c)

    fun rect(m0: Float, m1: Float, c0: Float, c1: Float): Rect {
        val a = toXY(m0, c0)
        val b = toXY(m1, c1)
        return Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }

    fun pointRect(index: Int): Rect {
        val m0 = mStart(slotOf(index))
        return if (rowOf(index) == 0) rect(m0, m0 + cell, 0f, tl) else rect(m0, m0 + cell, crossLen - tl, crossLen)
    }

    fun barRect(): Rect = rect(barM, barM + cell, 0f, crossLen)

    /** The tray of [side]: Black's is the top half of the band, White's the bottom half. */
    fun trayRect(side: Int): Rect =
        if (side == 1) rect(trayM, trayM + cell, 0f, crossLen / 2f) else rect(trayM, trayM + cell, crossLen / 2f, crossLen)

    private fun spacing(n: Int): Float = if (n <= 5) dia else (tl - dia) / (n - 1)

    /** Centre of the [j]th (0 = at the base) checker of a stack of [n] on point [index]. */
    fun stackCenter(index: Int, j: Int, n: Int): Offset {
        val m = mStart(slotOf(index)) + cell / 2f
        val off = dia / 2f + j * spacing(n)
        return toXY(m, if (rowOf(index) == 0) off else crossLen - off)
    }

    /** Centre of the [j]th checker of a stack of [n] on the bar; Black's grows up from the middle, White's down. */
    fun barCenter(side: Int, j: Int, n: Int): Offset {
        val m = barM + cell / 2f
        val off = dia / 2f + j * spacing(n)
        return toXY(m, if (side == 1) crossLen / 2f - off else crossLen / 2f + off)
    }

    private val trayThickness: Float get() = (crossLen / 2f - 0.4f * cell) / 15f

    fun trayChipRect(side: Int, j: Int): Rect {
        val t = trayThickness
        val c = if (side == 1) 0.2f * cell + t * (j + 0.5f) else crossLen - 0.2f * cell - t * (j + 0.5f)
        val m = trayM + cell / 2f
        return rect(m - 0.4f * cell, m + 0.4f * cell, c - t * 0.43f, c + t * 0.43f)
    }

    fun trayChipCenter(side: Int, j: Int): Offset {
        val r = trayChipRect(side, j)
        return Offset((r.left + r.right) / 2f, (r.top + r.bottom) / 2f)
    }
}

private fun Rect.scaled(d: Float): Rect = Rect(left * d, top * d, right * d, bottom * d)

private fun starPath(center: Offset, outer: Float): Path {
    val path = Path()
    val inner = outer * 0.45f
    for (k in 0 until 10) {
        val r = if (k % 2 == 0) outer else inner
        val angle = -PI / 2.0 + k * PI / 5.0
        val x = center.x + (r * cos(angle)).toFloat()
        val y = center.y + (r * sin(angle)).toFloat()
        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** A checker: White has a ring on it, Black a solid square, so the sides differ by shape too. */
internal fun DrawScope.drawBgChecker(center: Offset, radius: Float, side: Int, p: BackgammonPalette, colorblind: Boolean) {
    val d = density
    val fill = if (side == 0) p.white else p.black
    val edge = if (side == 0) p.whiteEdge else p.blackEdge
    val mark = if (side == 0) p.whiteMark else p.blackMark
    drawCircle(fill, radius, center)
    drawCircle(edge, radius, center, style = Stroke(width = (if (colorblind) 2.6f else 1.6f) * d))
    if (side == 0) {
        drawCircle(mark, radius * 0.55f, center, style = Stroke(width = radius * (if (colorblind) 0.22f else 0.16f)))
    } else {
        val h = radius * (if (colorblind) 0.5f else 0.42f)
        drawRect(mark, topLeft = Offset(center.x - h, center.y - h), size = Size(2f * h, 2f * h))
    }
}

private fun pointDescription(res: Resources, number: Int, signed: Int, suffix: String): String {
    val n = abs(signed)
    if (n == 0) return res.getString(R.string.backgammon_cd_point_empty, number, suffix)
    val what = when {
        signed > 0 && n == 1 -> res.getString(R.string.backgammon_checker_white_one)
        signed > 0 -> res.getString(R.string.backgammon_checker_white_many)
        n == 1 -> res.getString(R.string.backgammon_checker_black_one)
        else -> res.getString(R.string.backgammon_checker_black_many)
    }
    return res.getString(R.string.backgammon_cd_point, number, n, what, suffix)
}

/**
 * The board: 24 triangular points, the bar and the two bear-off trays, drawn on a Canvas and laid
 * over with one transparent tap zone per point, per bar and per tray so every one of them is a real
 * accessibility node ("Point 6, 3 white checkers, legal destination"). The board picks whichever
 * orientation (upright or sideways) gives the larger cell inside the space it is given, via
 * [fitBoard], and never exceeds that space.
 *
 * [selectedFrom] is the point (or [BackgammonMove.BAR]) the player picked; the board shows the
 * movable checkers (ring), the selected one (thick ring) and the legal destinations (dot, or ring
 * and cross when it hits). Moves animate as a checker flight unless [reducedMotion], in which case
 * the last move is only marked by a static ring.
 */
@Composable
internal fun BackgammonBoardView(
    state: BackgammonState,
    selectedFrom: Int?,
    hint: BackgammonMove?,
    canInteract: Boolean,
    palette: BackgammonPalette,
    colorblind: Boolean,
    reducedMotion: Boolean,
    flightMs: Int,
    onPointTap: (Int) -> Unit,
    onBarTap: () -> Unit,
    onTrayTap: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val availW = maxWidth.value
        val availH = maxHeight.value
        val fitV = remember(availW, availH) {
            fitBoard(availW, availH, columns = 10, rows = 14, minCellPx = 28f, maxCellPx = 64f)
        }
        val fitH = remember(availW, availH) {
            fitBoard(availW, availH, columns = 14, rows = 10, minCellPx = 28f, maxCellPx = 64f)
        }
        val vertical = fitV.cellPx >= fitH.cellPx
        val cell = if (vertical) fitV.cellPx else fitH.cellPx
        val g = remember(vertical, cell) { BgGeometry(vertical, cell) }
        BackgammonBoardContent(
            g = g,
            state = state,
            selectedFrom = selectedFrom,
            hint = hint,
            canInteract = canInteract,
            palette = palette,
            colorblind = colorblind,
            reducedMotion = reducedMotion,
            flightMs = flightMs,
            onPointTap = onPointTap,
            onBarTap = onBarTap,
            onTrayTap = onTrayTap
        )
    }
}

@Composable
private fun BackgammonBoardContent(
    g: BgGeometry,
    state: BackgammonState,
    selectedFrom: Int?,
    hint: BackgammonMove?,
    canInteract: Boolean,
    palette: BackgammonPalette,
    colorblind: Boolean,
    reducedMotion: Boolean,
    flightMs: Int,
    onPointTap: (Int) -> Unit,
    onBarTap: () -> Unit,
    onTrayTap: (Int) -> Unit
) {
    val s = state
    val seat = s.currentPlayerIndex
    val legal = if (canInteract) s.legalMoves else emptyList()
    val sources = legal.map { it.from }.toSet()
    val destMoves = if (selectedFrom != null) legal.filter { it.from == selectedFrom } else emptyList()
    val lastMove = s.lastMove

    val flight = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(s.moveSeq) }
    LaunchedEffect(s.moveSeq) {
        val animate = !reducedMotion && s.lastMove != null && s.moveSeq > seen
        seen = s.moveSeq
        if (animate) {
            flight.snapTo(0f)
            flight.animateTo(1f, tween(flightMs.coerceAtLeast(1), easing = FastOutSlowInEasing))
        } else {
            flight.snapTo(1f)
        }
    }
    // True for the frame between a move landing in the state and its animation starting.
    val pendingFlight = !reducedMotion && lastMove != null && s.moveSeq > seen

    val labelPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    Box(modifier = Modifier.size(g.width.dp, g.height.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val d = density
            val p = if (pendingFlight) 0f else flight.value
            val flying = lastMove != null && p < 1f && !reducedMotion
            val radius = g.dia / 2f * d

            // --- Board surface -----------------------------------------------------------------
            drawRoundRect(palette.wood, size = size, cornerRadius = CornerRadius(6f * d))
            drawRect(palette.barColor, topLeft = g.barRect().scaled(d).topLeft, size = g.barRect().scaled(d).size)
            for (side in 0..1) {
                val t = g.trayRect(side).scaled(d)
                drawRect(palette.trayColor, topLeft = t.topLeft, size = t.size)
            }

            // --- Points ------------------------------------------------------------------------
            for (i in 0 until 24) {
                val m0 = g.mStart(g.slotOf(i))
                val row = g.rowOf(i)
                val a: Offset
                val b: Offset
                val tip: Offset
                if (row == 0) {
                    a = g.toXY(m0, 0f); b = g.toXY(m0 + g.cell, 0f); tip = g.toXY(m0 + g.cell / 2f, g.tl)
                } else {
                    a = g.toXY(m0, g.crossLen); b = g.toXY(m0 + g.cell, g.crossLen); tip = g.toXY(m0 + g.cell / 2f, g.crossLen - g.tl)
                }
                val path = Path().apply {
                    moveTo(a.x * d, a.y * d); lineTo(b.x * d, b.y * d); lineTo(tip.x * d, tip.y * d); close()
                }
                val dark = (g.slotOf(i) + row) % 2 == 0
                drawPath(path, if (dark) palette.pointDark else palette.pointLight)
                if (destMoves.any { it.to == i }) {
                    drawPath(path, palette.accent.copy(alpha = 0.38f))
                }
            }
            // Frame.
            drawRoundRect(palette.frame, size = size, cornerRadius = CornerRadius(6f * d), style = Stroke(width = 4f * d))

            // --- Checkers on the points -------------------------------------------------------
            for (i in 0 until 24) {
                val v = s.board.signed(i)
                if (v == 0) continue
                val owner = if (v > 0) 0 else 1
                val n = abs(v)
                val hideTop = flying && lastMove != null && lastMove.to == i
                val drawN = if (hideTop) n - 1 else n
                for (j in 0 until drawN) {
                    drawBgChecker(g.stackCenter(i, j, n) * d, radius, owner, palette, colorblind)
                }
                if (n > 5 && drawN == n) {
                    val top = g.stackCenter(i, n - 1, n) * d
                    labelPaint.textSize = radius * 1.1f
                    labelPaint.color = (if (owner == 0) palette.whiteMark else palette.blackMark).toArgb()
                    drawContext.canvas.nativeCanvas.drawText(n.toString(), top.x, top.y + radius * 0.38f, labelPaint)
                }
            }

            // --- Bar ---------------------------------------------------------------------------
            for (side in 0..1) {
                val n = s.board.bar(side)
                for (j in 0 until n) {
                    drawBgChecker(g.barCenter(side, j, n) * d, radius, side, palette, colorblind)
                }
                if (n > 5) {
                    val top = g.barCenter(side, n - 1, n) * d
                    labelPaint.textSize = radius * 1.1f
                    labelPaint.color = (if (side == 0) palette.whiteMark else palette.blackMark).toArgb()
                    drawContext.canvas.nativeCanvas.drawText(n.toString(), top.x, top.y + radius * 0.38f, labelPaint)
                }
            }

            // --- Bear-off trays ----------------------------------------------------------------
            for (side in 0..1) {
                val n = s.board.off(side)
                for (j in 0 until n) {
                    val r = g.trayChipRect(side, j).scaled(d)
                    drawRoundRect(
                        if (side == 0) palette.white else palette.black,
                        topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(2f * d)
                    )
                    drawRoundRect(
                        if (side == 0) palette.whiteEdge else palette.blackEdge,
                        topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(2f * d), style = Stroke(width = 1f * d)
                    )
                }
            }

            // --- Movable checkers (rings) and the selection ------------------------------------
            if (canInteract && s.phase == BackgammonPhase.MOVING) {
                for (from in sources) {
                    val rawCenter: Offset = if (from == BackgammonMove.BAR) {
                        val n = s.board.bar(seat)
                        g.barCenter(seat, n - 1, n)
                    } else {
                        val n = s.board.count(seat, from)
                        g.stackCenter(from, n - 1, n)
                    }
                    val center = rawCenter * d
                    val isSelected = from == selectedFrom
                    drawCircle(
                        if (isSelected) palette.selected else palette.accent,
                        radius + (if (isSelected) 5f else 3f) * d,
                        center,
                        style = Stroke(width = (if (isSelected) 4.5f else 2.5f) * d)
                    )
                }
                // Destination markers: a dot, or a ring with a cross when the move hits a checker.
                for (m in destMoves) {
                    if (m.to == BackgammonMove.OFF) {
                        val t = g.trayRect(seat).scaled(d)
                        drawRect(palette.accent, topLeft = t.topLeft, size = t.size, style = Stroke(width = 4f * d))
                        continue
                    }
                    val own = s.board.count(seat, m.to)
                    if (m.hit) {
                        val c = g.stackCenter(m.to, 0, 1) * d
                        drawCircle(palette.accent, radius * 0.95f, c, style = Stroke(width = 3.5f * d))
                        val k = radius * 0.5f
                        drawLine(palette.accent, Offset(c.x - k, c.y - k), Offset(c.x + k, c.y + k), strokeWidth = 3.5f * d)
                        drawLine(palette.accent, Offset(c.x - k, c.y + k), Offset(c.x + k, c.y - k), strokeWidth = 3.5f * d)
                    } else {
                        val c = g.stackCenter(m.to, own, own + 1) * d
                        drawCircle(palette.accent, radius * 0.38f, c)
                    }
                }
                // Hint: dashed ring on the source and destination, plus a star on the destination.
                if (hint != null) {
                    val dash = Stroke(width = 3f * d, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f * d, 5f * d)))
                    val rawSrc: Offset = if (hint.from == BackgammonMove.BAR) {
                        val n = s.board.bar(seat)
                        g.barCenter(seat, n - 1, n)
                    } else {
                        val n = s.board.count(seat, hint.from)
                        g.stackCenter(hint.from, n - 1, n)
                    }
                    val src = rawSrc * d
                    drawCircle(palette.hint, radius + 7f * d, src, style = dash)
                    if (hint.to == BackgammonMove.OFF) {
                        val t = g.trayRect(seat).scaled(d)
                        drawRect(palette.hint, topLeft = t.topLeft, size = t.size, style = dash)
                        drawPath(starPath(t.center, radius * 0.8f), palette.hint)
                    } else {
                        val own = s.board.count(seat, hint.to)
                        val c = (if (hint.hit) g.stackCenter(hint.to, 0, 1) else g.stackCenter(hint.to, own, own + 1)) * d
                        drawCircle(palette.hint, radius + 7f * d, c, style = dash)
                        drawPath(starPath(c, radius * 0.6f), palette.hint)
                    }
                }
            }

            // --- The last move: a thin static ring, then (unless reduced motion) a flight ------
            if (lastMove != null && lastMove.to != BackgammonMove.OFF && !flying) {
                val n = s.board.count(seat, lastMove.to)
                if (n > 0) {
                    drawCircle(
                        palette.textPrimary.copy(alpha = 0.55f), radius + 2f * d,
                        g.stackCenter(lastMove.to, n - 1, n) * d, style = Stroke(width = 1.6f * d)
                    )
                }
            }
            if (flying && lastMove != null) {
                val srcCenter: Offset = if (lastMove.from == BackgammonMove.BAR) {
                    val now = s.board.bar(seat)
                    g.barCenter(seat, now, now + 1)
                } else {
                    val now = s.board.count(seat, lastMove.from)
                    g.stackCenter(lastMove.from, now, now + 1)
                }
                val dstCenter: Offset = if (lastMove.to == BackgammonMove.OFF) {
                    g.trayChipCenter(seat, (s.board.off(seat) - 1).coerceAtLeast(0))
                } else {
                    val n = s.board.count(seat, lastMove.to)
                    g.stackCenter(lastMove.to, (n - 1).coerceAtLeast(0), n.coerceAtLeast(1))
                }
                val pos = Offset(srcCenter.x + (dstCenter.x - srcCenter.x) * p, srcCenter.y + (dstCenter.y - srcCenter.y) * p) * d
                drawBgChecker(pos, radius * (1f + 0.18f * sin(PI * p).toFloat()), seat, palette, colorblind)
                if (lastMove.hit) {
                    drawCircle(
                        palette.accent.copy(alpha = 1f - p), radius * (0.6f + 1.1f * p), dstCenter * d,
                        style = Stroke(width = 3f * d)
                    )
                }
            }
        }

        // --- Accessibility / tap zones, one per point, bar and tray ---------------------------
        val res = LocalContext.current.resources
        LocalConfiguration.current // re-read strings when the locale changes
        val numberSeat = if (s.phase == BackgammonPhase.OVER || s.phase == BackgammonPhase.OPENING) 0 else seat
        for (i in 0 until 24) {
            val isSource = canInteract && i in sources
            val dest = destMoves.firstOrNull { it.to == i }
            val suffix = buildString {
                if (selectedFrom == i) append(res.getString(R.string.backgammon_cd_suffix_selected))
                else if (isSource) append(res.getString(R.string.backgammon_cd_suffix_movable))
                if (dest != null) append(res.getString(if (dest.hit) R.string.backgammon_cd_suffix_hit else R.string.backgammon_cd_suffix_destination))
                if (hint != null && canInteract && (hint.to == i || hint.from == i)) append(res.getString(R.string.backgammon_cd_suffix_hint))
            }
            val label = when {
                dest != null -> res.getString(R.string.backgammon_click_move)
                isSource -> res.getString(R.string.backgammon_click_select)
                else -> null
            }
            BgTapZone(
                rect = g.pointRect(i),
                description = pointDescription(res, BackgammonRules.pointNumber(numberSeat, i), s.board.signed(i), suffix),
                clickLabel = label,
                onClick = { onPointTap(i) }
            )
        }
        val barSuffix = buildString {
            if (canInteract && BackgammonMove.BAR in sources) {
                append(res.getString(if (selectedFrom == BackgammonMove.BAR) R.string.backgammon_cd_suffix_selected else R.string.backgammon_cd_suffix_movable))
            }
        }
        BgTapZone(
            rect = g.barRect(),
            description = res.getString(R.string.backgammon_cd_bar, s.board.bar(0), s.board.bar(1), barSuffix),
            clickLabel = if (canInteract && BackgammonMove.BAR in sources) res.getString(R.string.backgammon_click_select) else null,
            onClick = onBarTap
        )
        for (side in 0..1) {
            val canBearOff = destMoves.any { it.to == BackgammonMove.OFF } && side == seat
            val suffix = if (canBearOff) res.getString(R.string.backgammon_cd_suffix_destination) else ""
            BgTapZone(
                rect = g.trayRect(side),
                description = res.getString(
                    R.string.backgammon_cd_tray,
                    res.getString(if (side == 0) R.string.backgammon_side_white else R.string.backgammon_side_black),
                    s.board.off(side),
                    suffix
                ),
                clickLabel = if (canBearOff) res.getString(R.string.backgammon_click_bear_off) else null,
                onClick = { onTrayTap(side) }
            )
        }
    }
}

/** A transparent, accessible, tappable rectangle over a part of the board. */
@Composable
private fun BgTapZone(rect: Rect, description: String, clickLabel: String?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .offset(rect.left.dp, rect.top.dp)
            .size(rect.width.dp, rect.height.dp)
            .semantics { contentDescription = description }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = clickLabel,
                role = Role.Button,
                onClick = onClick
            )
    )
}
