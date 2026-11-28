package com.gamesuite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gamesuite.games.bumprun.BumpCardKind
import com.gamesuite.games.bumprun.BumpRunBoard
import com.gamesuite.games.bumprun.BumpRunState
import com.gamesuite.ui.effects.ParticleBurst
import kotlin.math.PI
import kotlin.math.sin

/*
 * Original vector art for Bump and Run, drawn on two Canvases (a cached track layer and one live layer for pawns,
 * highlights and confetti): a cream and sand ring of 36 squares, one coloured exit square and private lane per seat,
 * a corner Nest per seat, a centre Home per seat, and spring pads drawn as coils with an arrow. Pawns are the shared
 * glossy marbles: every seat has its own colour AND shape, and a number badge says which of its pawns it is.
 */

internal class BumpRunTheme(
    val bg: Color, val text: Color, val card: Color, val cellA: Color, val cellB: Color, val rim: Color,
    val pad: Color, val board: Color, val ink: Color
)

internal fun bumpRunTheme(dark: Boolean): BumpRunTheme = if (!dark) BumpRunTheme(
    bg = Color(0xFFF1F7F8), text = Color(0xFF14292E), card = Color(0xFFDCECEF),
    cellA = Color(0xFFFFF6DD), cellB = Color(0xFFF0E2BC), rim = Color(0xFF0B5A6B),
    pad = Color(0xFF8E5A00), board = Color(0xFFC9DDE0), ink = Color(0xFF1B1B1B)
) else BumpRunTheme(
    bg = Color(0xFF0F1A1D), text = Color(0xFFE6F3F5), card = Color(0xFF1F3338),
    cellA = Color(0xFF34464B), cellB = Color(0xFF2A3A3F), rim = Color(0xFF6FD0DE),
    pad = Color(0xFFFFC44D), board = Color(0xFF172A2E), ink = Color(0xFFF2F2F2)
)

/** Where things sit on the 10 x 10 board, in cell units (x across, y down). Pure geometry, shared by drawing and taps. */
internal object BrGeo {
    const val SIDE = 10

    /** The ring square's cell (column, row). */
    fun ringCell(sq: Int): Pair<Int, Int> = when {
        sq <= 9 -> sq to 0
        sq <= 18 -> 9 to sq - 9
        sq <= 27 -> (27 - sq) to 9
        else -> 0 to 36 - sq
    }

    fun ringCenter(sq: Int): Offset = ringCell(sq).let { Offset(it.first + 0.5f, it.second + 0.5f) }

    /** The three private lane cells of a side, in walking order, then its Home cell. */
    private val lanes: List<List<Pair<Int, Int>>> = listOf(
        listOf(4 to 1, 4 to 2, 4 to 3, 4 to 4),
        listOf(8 to 4, 7 to 4, 6 to 4, 5 to 4),
        listOf(5 to 8, 5 to 7, 5 to 6, 5 to 5),
        listOf(1 to 5, 2 to 5, 3 to 5, 4 to 5)
    )

    fun laneCells(side: Int): List<Pair<Int, Int>> = lanes[side]

    fun laneCenter(side: Int, step: Int): Offset = lanes[side][step].let { Offset(it.first + 0.5f, it.second + 0.5f) }

    fun homeCell(side: Int): Offset = laneCenter(side, 3)

    /** Centre of a side's 3 x 3 Nest block. */
    fun nestCenter(side: Int): Offset = when (side) {
        0 -> Offset(2.5f, 2.5f)
        1 -> Offset(7.5f, 2.5f)
        2 -> Offset(7.5f, 7.5f)
        else -> Offset(2.5f, 7.5f)
    }

    fun nestSlot(side: Int, k: Int): Offset {
        val c = nestCenter(side)
        val dx = if (k % 2 == 0) -0.58f else 0.58f
        val dy = if (k / 2 == 0) -0.58f else 0.58f
        return Offset(c.x + dx, c.y + dy)
    }

    fun homeSlot(side: Int, k: Int): Offset {
        val c = homeCell(side)
        return Offset(c.x + (if (k % 2 == 0) -0.2f else 0.2f), c.y + (if (k / 2 == 0) -0.2f else 0.2f))
    }

    /** Where pawn [k] of a seat on [side] rests at [progress]. */
    fun pawnPos(side: Int, k: Int, progress: Int): Offset = when {
        progress < 0 -> nestSlot(side, k)
        progress < BumpRunBoard.LOOP -> ringCenter(BumpRunBoard.squareOf(side, progress)!!)
        progress < BumpRunBoard.HOME -> laneCenter(side, progress - BumpRunBoard.LANE_START)
        else -> homeSlot(side, k)
    }
}

/** One pawn's hop between two points (cell units); [lift] is the arc height in cells. */
internal class BrLeg(val pawn: Int, val from: Offset, val to: Offset, val lift: Float)

/** A square a chosen pawn may land on (or a rival pawn to swap with): drawn as a ring plus an arrow so colour is never the only cue. */
internal class BrTarget(val at: Offset)

private fun DrawScope.chevron(c: Offset, r: Float, color: Color, stroke: Float) {
    val p = Path().apply {
        moveTo(c.x - r, c.y + r * 0.25f); lineTo(c.x, c.y - r * 0.55f); lineTo(c.x + r, c.y + r * 0.25f)
    }
    drawPath(p, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A spring: a zig-zag coil with an arrow, drawn inside one cell. */
private fun DrawScope.drawPad(c: Offset, u: Float, color: Color) {
    val w = u * 0.26f
    val p = Path().apply {
        moveTo(c.x - w, c.y + u * 0.3f)
        lineTo(c.x + w, c.y + u * 0.16f); lineTo(c.x - w, c.y + u * 0.02f); lineTo(c.x + w, c.y - u * 0.12f)
    }
    drawPath(p, color, style = Stroke(u * 0.07f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    chevron(Offset(c.x, c.y - u * 0.22f), u * 0.17f, color, u * 0.08f)
}

private fun cellRect(col: Int, row: Int, u: Float) = Offset(col * u, row * u)

@Composable
internal fun BumpRunBoardView(
    s: BumpRunState,
    pawns: List<Int>,
    shields: List<Boolean>,
    legs: () -> List<BrLeg>,
    legT: Animatable<Float, *>,
    pulse: State<Float>,
    cellDp: Dp,
    theme: BumpRunTheme,
    seatColors: List<Color>,
    movable: Set<Int>,
    targets: List<BrTarget>,
    selected: Int,
    description: String,
    burst: ParticleBurst,
    onTap: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val seats = s.players.size
    val sides = s.sides
    val per = s.rules.pawnsPerSeat
    val pads = s.rules.pads
    val track = remember(theme, seats, per, pads, seatColors, measurer) {
        Modifier.fillMaxSize().drawWithCache {
            val u = size.width / BrGeo.SIDE
            onDrawBehind {
                drawRoundRect(theme.board, Offset.Zero, size, CornerRadius(u * 0.4f))
                // lanes and Homes of the seats at the table
                for (i in 0 until seats) {
                    val side = sides[i]
                    val color = seatColors[i % seatColors.size]
                    BrGeo.laneCells(side).forEachIndexed { step, (c, r) ->
                        val home = step == 3
                        drawRoundRect(
                            color.copy(alpha = if (home) 0.55f else 0.28f), cellRect(c, r, u) + Offset(u * 0.06f, u * 0.06f),
                            Size(u * 0.88f, u * 0.88f), CornerRadius(u * 0.2f)
                        )
                        if (home) drawCircle(color, u * 0.34f, BrGeo.homeCell(side) * u, style = Stroke(u * 0.07f))
                    }
                    // the Nest block
                    val nc = BrGeo.nestCenter(side)
                    drawRoundRect(
                        color.copy(alpha = 0.2f), Offset((nc.x - 1.35f) * u, (nc.y - 1.35f) * u), Size(2.7f * u, 2.7f * u), CornerRadius(u * 0.5f)
                    )
                    drawRoundRect(
                        color, Offset((nc.x - 1.35f) * u, (nc.y - 1.35f) * u), Size(2.7f * u, 2.7f * u), CornerRadius(u * 0.5f), style = Stroke(u * 0.07f)
                    )
                    for (k in 0 until per) drawCircle(color.copy(alpha = 0.35f), u * 0.34f, BrGeo.nestSlot(side, k) * u)
                }
                // the ring
                for (sq in 0 until BumpRunBoard.LOOP) {
                    val (c, r) = BrGeo.ringCell(sq)
                    val topLeft = cellRect(c, r, u)
                    drawRoundRect(if (sq % 2 == 0) theme.cellA else theme.cellB, topLeft + Offset(u * 0.04f, u * 0.04f), Size(u * 0.92f, u * 0.92f), CornerRadius(u * 0.18f))
                }
                for (i in 0 until seats) {
                    val sq = BumpRunBoard.EXIT_SQUARES[sides[i]]
                    val (c, r) = BrGeo.ringCell(sq)
                    val color = seatColors[i % seatColors.size]
                    drawRoundRect(color.copy(alpha = 0.6f), cellRect(c, r, u) + Offset(u * 0.04f, u * 0.04f), Size(u * 0.92f, u * 0.92f), CornerRadius(u * 0.18f))
                    drawRoundRect(color, cellRect(c, r, u) + Offset(u * 0.04f, u * 0.04f), Size(u * 0.92f, u * 0.92f), CornerRadius(u * 0.18f), style = Stroke(u * 0.06f))
                }
                if (pads) for (sq in BumpRunBoard.PADS) drawPad(BrGeo.ringCenter(sq) * u, u, theme.pad)
            }
        }
    }
    val badgeStyle = remember(density, cellDp) {
        TextStyle(fontSize = with(density) { (cellDp.toPx() * 0.2f).toSp() }, fontWeight = FontWeight.Bold, color = Color.White)
    }
    val badges = remember(measurer, badgeStyle) { (1..4).map { measurer.measure(AnnotatedString(it.toString()), badgeStyle) } }
    val tap = rememberUpdatedState(onTap)

    Box(modifier.size(cellDp * BrGeo.SIDE, cellDp * BrGeo.SIDE).semantics { contentDescription = description }) {
        Box(track)
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { p -> tap.value(Offset(p.x / (size.width / BrGeo.SIDE.toFloat()), p.y / (size.width / BrGeo.SIDE.toFloat()))) }
            }
        ) {
            val u = size.width / BrGeo.SIDE
            val moving = legs()
            val t = legT.value
            val movingPawns = moving.map { it.pawn }.toSet()
            val r = u * 0.36f

            // destination hints under the pawns: ring plus an arrow
            for (tg in targets) {
                val c = tg.at * u
                drawCircle(theme.rim.copy(alpha = 0.25f + 0.25f * pulse.value), u * 0.46f, c)
                drawCircle(theme.rim, u * 0.46f, c, style = Stroke(u * 0.07f))
                chevron(Offset(c.x, c.y - u * 0.02f), u * 0.18f, theme.ink, u * 0.07f)
            }

            fun drawPawn(p: Int, center: Offset, lift: Float) {
                val seat = p / per
                val color = seatColors[seat % seatColors.size]
                val c = Offset(center.x * u, center.y * u)
                if (p in movable) drawCircle(theme.rim.copy(alpha = 0.3f + 0.5f * pulse.value), r * 1.35f, c, style = Stroke(u * 0.08f))
                if (p == selected) drawCircle(theme.ink, r * 1.5f, c, style = Stroke(u * 0.09f))
                drawMarble(c, r, color, seat % 4, glyph = 0.7f, lift = lift * u)
                if (shields.getOrElse(p) { false }) {
                    drawCircle(Color(0xFFFFD54F), r * 1.22f, Offset(c.x, c.y - lift * u), style = Stroke(u * 0.09f))
                    drawCircle(Color(0xFF6B4F00), r * 1.32f, Offset(c.x, c.y - lift * u), style = Stroke(u * 0.03f))
                }
                // number badge, lower right
                val bc = Offset(c.x + r * 0.78f, c.y - lift * u + r * 0.78f)
                drawCircle(Color(0xFF222222), u * 0.17f, bc)
                val n = badges[(p % per).coerceIn(0, 3)]
                drawText(n, topLeft = Offset(bc.x - n.size.width / 2f, bc.y - n.size.height / 2f))
            }

            for (p in pawns.indices) {
                if (p in movingPawns) continue
                val seat = p / per
                drawPawn(p, BrGeo.pawnPos(sides[seat], p % per, pawns[p]), 0f)
            }
            for (leg in moving) {
                val x = leg.from.x + (leg.to.x - leg.from.x) * t
                val y = leg.from.y + (leg.to.y - leg.from.y) * t
                val lift = (sin(PI * t).toFloat()) * leg.lift
                drawPawn(leg.pawn, Offset(x, y), lift)
            }
            drawBurst(burst)
        }
    }
}

/** The face of a card, as one rounded rectangle with a big mark: a number for a hop, a drawn symbol for the others. */
@Composable
internal fun BumpRunCardFace(
    kind: BumpCardKind?, value: Int, star: Boolean, theme: BumpRunTheme, size: Dp, label: String, modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = remember(density, size, theme) {
        TextStyle(fontSize = with(density) { (size.toPx() * 0.62f).toSp() }, fontWeight = FontWeight.Black, color = theme.ink)
    }
    val numeral = remember(value, style, measurer) { if (kind == BumpCardKind.HOP) measurer.measure(AnnotatedString(value.toString()), style) else null }
    Canvas(modifier.size(size * 0.78f, size).semantics { contentDescription = label }) {
        val w = this.size.width
        val h = this.size.height
        val face = if (kind == null) theme.rim else Color(0xFFFFFDF4)
        drawRoundRect(face, Offset.Zero, this.size, CornerRadius(w * 0.14f))
        drawRoundRect(theme.rim, Offset.Zero, this.size, CornerRadius(w * 0.14f), style = Stroke(w * 0.05f))
        val c = Offset(w / 2, h / 2)
        val ink = Color(0xFF1B1B1B)
        val stroke = Stroke(w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            null -> {
                // card back: a ring of dots
                for (i in 0 until 8) {
                    val a = i * PI / 4
                    drawCircle(Color.White.copy(alpha = 0.8f), w * 0.05f, Offset(c.x + (kotlin.math.cos(a) * w * 0.26f).toFloat(), c.y + (sin(a) * w * 0.26f).toFloat()))
                }
                drawCircle(Color.White.copy(alpha = 0.9f), w * 0.12f, c)
            }
            BumpCardKind.HOP -> numeral?.let { drawText(it, topLeft = Offset(c.x - it.size.width / 2f, c.y - it.size.height / 2f)) }
            BumpCardKind.SWAP -> {
                val a = Path().apply { moveTo(w * 0.22f, h * 0.4f); lineTo(w * 0.78f, h * 0.4f); moveTo(w * 0.66f, h * 0.28f); lineTo(w * 0.78f, h * 0.4f); lineTo(w * 0.66f, h * 0.52f) }
                val b = Path().apply { moveTo(w * 0.78f, h * 0.62f); lineTo(w * 0.22f, h * 0.62f); moveTo(w * 0.34f, h * 0.5f); lineTo(w * 0.22f, h * 0.62f); lineTo(w * 0.34f, h * 0.74f) }
                drawPath(a, ink, style = stroke); drawPath(b, ink, style = stroke)
            }
            BumpCardKind.CHASE -> {
                val a = Path().apply { moveTo(w * 0.2f, h * 0.7f); lineTo(w * 0.7f, h * 0.3f); moveTo(w * 0.46f, h * 0.28f); lineTo(w * 0.72f, h * 0.28f); lineTo(w * 0.72f, h * 0.54f) }
                drawPath(a, ink, style = stroke)
                drawCircle(Color(0xFFE08A00), w * 0.1f, Offset(w * 0.78f, h * 0.22f))
            }
            BumpCardKind.FREE_EXIT -> {
                drawArc(ink, 180f, 180f, false, Offset(w * 0.24f, h * 0.38f), Size(w * 0.52f, h * 0.44f), style = stroke)
                drawLine(ink, Offset(w * 0.24f, h * 0.6f), Offset(w * 0.76f, h * 0.6f), w * 0.09f, StrokeCap.Round)
                drawSeatGlyph(4, Offset(c.x, h * 0.3f), w * 0.13f)
            }
            BumpCardKind.AGAIN -> {
                drawArc(ink, 40f, 280f, false, Offset(w * 0.25f, h * 0.35f), Size(w * 0.5f, w * 0.5f), style = stroke)
                val tip = Path().apply { moveTo(w * 0.66f, h * 0.3f); lineTo(w * 0.78f, h * 0.46f); lineTo(w * 0.56f, h * 0.46f); close() }
                drawPath(tip, ink)
            }
            BumpCardKind.SHIELD -> {
                val sh = Path().apply {
                    moveTo(c.x, h * 0.24f); lineTo(w * 0.78f, h * 0.34f); lineTo(w * 0.74f, h * 0.58f)
                    quadraticBezierTo(w * 0.7f, h * 0.76f, c.x, h * 0.84f); quadraticBezierTo(w * 0.3f, h * 0.76f, w * 0.26f, h * 0.58f)
                    lineTo(w * 0.22f, h * 0.34f); close()
                }
                drawPath(sh, Color(0xFFFFD54F)); drawPath(sh, ink, style = Stroke(w * 0.06f, join = StrokeJoin.Round))
            }
        }
        if (star) drawSeatGlyph(4, Offset(w * 0.2f, h * 0.17f), w * 0.1f)
    }
}
