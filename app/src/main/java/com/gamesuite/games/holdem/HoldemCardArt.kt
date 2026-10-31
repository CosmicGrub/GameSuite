package com.gamesuite.games.holdem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Original vector art for the Hold'em table: cards with drawn suit marks (no fonts, no images), chips, and
 * the felt. Everything is Canvas drawing so it scales to any card size and stays crisp.
 *
 * Suits are told apart by SHAPE first; colour is a second cue. The classic deck is red/black, and the
 * colour-blind-safe deck (Settings -> Accessibility) gives each suit its own Okabe-Ito colour as well.
 */

private val CardCream = Color(0xFFFBF7EC)
private val CardEdge = Color(0xFFB9AF98)
private val InkBlack = Color(0xFF1B1B24)
private val InkRed = Color(0xFFC62828)

/** The ink of [suit]. [fourColor] is the colour-blind-safe deck: black, vermillion, blue and bluish green. */
internal fun suitColor(suit: Int, fourColor: Boolean): Color =
    if (!fourColor) {
        if (suit == HoldemCards.HEARTS || suit == HoldemCards.DIAMONDS) InkRed else InkBlack
    } else {
        when (suit) {
            HoldemCards.SPADES -> Color(0xFF1B1B24)
            HoldemCards.HEARTS -> Color(0xFFD55E00)
            HoldemCards.DIAMONDS -> Color(0xFF0072B2)
            else -> Color(0xFF007A5A)
        }
    }

internal fun rankLabel(rank: Int): String = when (rank) {
    14 -> "A"; 13 -> "K"; 12 -> "Q"; 11 -> "J"; else -> rank.toString()
}

/** A filled suit mark centred on [c], about [r] from the centre to its widest point. */
internal fun DrawScope.drawSuit(suit: Int, c: Offset, r: Float, color: Color) {
    val p = Path()
    when (suit) {
        HoldemCards.DIAMONDS -> {
            p.moveTo(c.x, c.y - r * 1.1f)
            p.lineTo(c.x + r * 0.78f, c.y)
            p.lineTo(c.x, c.y + r * 1.1f)
            p.lineTo(c.x - r * 0.78f, c.y)
            p.close()
            drawPath(p, color)
        }
        HoldemCards.HEARTS -> {
            p.moveTo(c.x, c.y + r)
            p.cubicTo(c.x - r * 1.3f, c.y + r * 0.1f, c.x - r * 1.15f, c.y - r * 1.0f, c.x, c.y - r * 0.35f)
            p.cubicTo(c.x + r * 1.15f, c.y - r * 1.0f, c.x + r * 1.3f, c.y + r * 0.1f, c.x, c.y + r)
            p.close()
            drawPath(p, color)
        }
        HoldemCards.SPADES -> {
            p.moveTo(c.x, c.y - r)
            p.cubicTo(c.x + r * 1.3f, c.y - r * 0.15f, c.x + r * 1.15f, c.y + r * 0.85f, c.x, c.y + r * 0.4f)
            p.cubicTo(c.x - r * 1.15f, c.y + r * 0.85f, c.x - r * 1.3f, c.y - r * 0.15f, c.x, c.y - r)
            p.close()
            drawPath(p, color)
            val stem = Path()
            stem.moveTo(c.x - r * 0.14f, c.y + r * 0.35f)
            stem.lineTo(c.x - r * 0.38f, c.y + r * 1.05f)
            stem.lineTo(c.x + r * 0.38f, c.y + r * 1.05f)
            stem.lineTo(c.x + r * 0.14f, c.y + r * 0.35f)
            stem.close()
            drawPath(stem, color)
        }
        else -> {
            val lobe = r * 0.5f
            drawCircle(color, lobe, Offset(c.x, c.y - r * 0.5f))
            drawCircle(color, lobe, Offset(c.x - r * 0.52f, c.y + r * 0.22f))
            drawCircle(color, lobe, Offset(c.x + r * 0.52f, c.y + r * 0.22f))
            drawCircle(color, lobe * 0.8f, Offset(c.x, c.y + r * 0.1f))
            val stem = Path()
            stem.moveTo(c.x - r * 0.12f, c.y + r * 0.2f)
            stem.lineTo(c.x - r * 0.4f, c.y + r * 1.05f)
            stem.lineTo(c.x + r * 0.4f, c.y + r * 1.05f)
            stem.lineTo(c.x + r * 0.12f, c.y + r * 0.2f)
            stem.close()
            drawPath(stem, color)
        }
    }
}

/**
 * One playing card, [width] wide (the height is 1.4 times that). [flip] runs 0 (back showing) to 1 (face
 * showing): in between the card turns about its vertical axis. [dim] greys a card out (it is not part of the
 * winning hand), [glow] rings it in gold (it is). [description] is the TalkBack label.
 */
@Composable
fun HoldemCardView(
    card: Int,
    width: Dp,
    modifier: Modifier = Modifier,
    flip: Float = 1f,
    fourColor: Boolean = false,
    dim: Boolean = false,
    glow: Boolean = false,
    description: String
) {
    val height = width * 1.4f
    val showFace = flip > 0.5f && HoldemCards.isValid(card)
    val rotation = if (flip < 0.5f) flip * 180f else (flip - 1f) * 180f
    Box(
        modifier
            .size(width, height)
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 14f * density
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .alpha(if (dim) 0.45f else 1f)
            .semantics { contentDescription = description }
    ) {
        if (showFace) CardFace(card, width, fourColor, glow) else CardBack(width, glow)
    }
}

@Composable
private fun CardBack(width: Dp, glow: Boolean) {
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height
        val corner = CornerRadius(w * 0.11f)
        drawRoundRect(Color(0x55000000), Offset(w * 0.03f, h * 0.025f), size, corner)
        drawRoundRect(CardCream, Offset.Zero, size, corner)
        val inset = w * 0.07f
        val body = Size(w - inset * 2, h - inset * 2)
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0xFF284B7A), Color(0xFF16294A)), Offset.Zero, Offset(w, h)),
            Offset(inset, inset), body, CornerRadius(w * 0.07f)
        )
        // a diamond lattice, clipped to the card's inner panel
        val line = Color(0x33FFFFFF)
        val step = w * 0.22f
        clipRect(inset, inset, w - inset, h - inset) {
            var x = -h
            while (x < w + h) {
                drawLine(line, Offset(x, inset), Offset(x + body.height, inset + body.height), w * 0.012f)
                drawLine(line, Offset(x + body.height, inset), Offset(x, inset + body.height), w * 0.012f)
                x += step
            }
        }
        val c = Offset(w / 2, h / 2)
        drawCircle(Color(0xFFE8C766), w * 0.2f, c)
        drawCircle(Color(0xFF16294A), w * 0.16f, c)
        drawSuit(HoldemCards.SPADES, c, w * 0.1f, Color(0xFFE8C766))
        drawRoundRect(if (glow) Color(0xFFFFD866) else CardEdge, Offset.Zero, size, corner, style = Stroke(if (glow) w * 0.045f else w * 0.014f))
    }
}

@Composable
private fun CardFace(card: Int, width: Dp, fourColor: Boolean, glow: Boolean) {
    val rank = HoldemCards.rank(card)
    val suit = HoldemCards.suit(card)
    val ink = suitColor(suit, fourColor)
    val label = rankLabel(rank)
    val small = width < 44.dp
    Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val corner = CornerRadius(w * 0.11f)
            drawRoundRect(Color(0x55000000), Offset(w * 0.03f, h * 0.025f), size, corner)
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0xFFFFFDF6), CardCream)), Offset.Zero, size, corner
            )
            if (!small) {
                drawSuit(suit, Offset(w / 2, h / 2), w * (if (rank >= 11) 0.27f else 0.24f), ink.copy(alpha = if (rank >= 11) 0.9f else 1f))
            }
            drawRoundRect(if (glow) Color(0xFFFFD866) else CardEdge, Offset.Zero, size, corner, style = Stroke(if (glow) w * 0.05f else w * 0.014f))
        }
        val rankSize = (width.value * (if (small) 0.44f else 0.3f)).sp
        CornerIndex(label, suit, ink, rankSize, width, Modifier.align(Alignment.TopStart))
        if (!small) {
            CornerIndex(label, suit, ink, rankSize, width, Modifier.align(Alignment.BottomEnd).rotate(180f))
        }
    }
}

@Composable
private fun BoxScope.CornerIndex(label: String, suit: Int, ink: Color, rankSize: androidx.compose.ui.unit.TextUnit, width: Dp, modifier: Modifier) {
    val markSize = width * 0.2f
    androidx.compose.foundation.layout.Column(
        modifier.padding(start = width * 0.07f, top = width * 0.04f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = ink, fontSize = rankSize, fontWeight = FontWeight.ExtraBold, lineHeight = rankSize)
        Canvas(Modifier.size(markSize, markSize)) {
            drawSuit(suit, Offset(size.width / 2, size.height / 2), size.minDimension * 0.42f, ink)
        }
    }
}

/** An empty board slot: a dashed outline where a community card will land. */
@Composable
fun HoldemCardSlot(width: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width, width * 1.4f).clearAndSetSemantics { }) {
        val w = size.width
        drawRoundRect(
            Color(0x33FFFFFF), Offset.Zero, size, CornerRadius(w * 0.11f),
            style = Stroke(w * 0.035f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(w * 0.14f, w * 0.1f)))
        )
    }
}

// ---- chips ----------------------------------------------------------------------------------------

/** Chip denominations, largest first, with a colour and a rim pattern each (the pattern is the colour-blind-safe cue). */
internal val CHIP_VALUES = intArrayOf(25_000, 5_000, 1_000, 500, 100, 25, 5, 1)
private val CHIP_COLORS = arrayOf(
    Color(0xFFD97CB0), Color(0xFF3F7BD1), Color(0xFFE0B23A), Color(0xFF7B52C2),
    Color(0xFF2B2B33), Color(0xFF2E9E5B), Color(0xFFD6453D), Color(0xFFF2EEE4)
)
private val CHIP_NOTCHES = intArrayOf(10, 9, 8, 7, 6, 5, 4, 3)

/** The chips that make [amount] (largest denominations first), as (denomination index, count) pairs, at most [maxChips] drawn. */
internal fun chipBreakdown(amount: Int, maxChips: Int = 15): List<Pair<Int, Int>> {
    var left = amount.coerceAtLeast(0)
    val raw = ArrayList<Pair<Int, Int>>()
    for (i in CHIP_VALUES.indices) {
        val n = left / CHIP_VALUES[i]
        if (n > 0) raw += i to n
        left -= n * CHIP_VALUES[i]
    }
    var total = raw.sumOf { it.second }
    if (total <= maxChips) return raw
    // Too many to draw: keep the order of magnitude but cap each denomination.
    val capped = raw.map { it.first to minOf(it.second, 4) }.toMutableList()
    total = capped.sumOf { it.second }
    while (total > maxChips && capped.isNotEmpty()) {
        val last = capped.lastIndex
        capped[last] = capped[last].first to (capped[last].second - 1)
        if (capped[last].second <= 0) capped.removeAt(last)
        total = capped.sumOf { it.second }
    }
    return capped
}

private fun shade(c: Color, f: Float): Color = if (f >= 0f) lerp(c, Color.White, f) else lerp(c, Color.Black, -f)

/** One chip of denomination [index] with its top face centred at [center], [w] wide. [top] draws the rim pattern. */
internal fun DrawScope.drawChip(center: Offset, w: Float, index: Int, top: Boolean, alpha: Float = 1f) {
    val color = CHIP_COLORS[index]
    val h = w * 0.5f
    val side = w * 0.13f
    drawOval(Color(0x44000000).copy(alpha = 0.27f * alpha), Offset(center.x - w / 2 + w * 0.04f, center.y - h / 2 + side + h * 0.1f), Size(w, h))
    drawOval(shade(color, -0.45f).copy(alpha = alpha), Offset(center.x - w / 2, center.y - h / 2 + side), Size(w, h))
    drawRect(shade(color, -0.45f).copy(alpha = alpha), Offset(center.x - w / 2, center.y), Size(w, side))
    drawOval(color.copy(alpha = alpha), Offset(center.x - w / 2, center.y - h / 2), Size(w, h))
    if (top) {
        val notches = CHIP_NOTCHES[index]
        val rimColor = if (index == CHIP_VALUES.lastIndex) Color(0xFFB34A3F) else Color.White
        val sweep = 360f / notches
        for (i in 0 until notches) {
            drawArc(
                rimColor.copy(alpha = 0.92f * alpha), startAngle = i * sweep, sweepAngle = sweep * 0.4f, useCenter = false,
                topLeft = Offset(center.x - w * 0.46f, center.y - h * 0.46f), size = Size(w * 0.92f, h * 0.92f),
                style = Stroke(w * 0.09f)
            )
        }
        drawOval(
            rimColor.copy(alpha = 0.55f * alpha), Offset(center.x - w * 0.27f, center.y - h * 0.27f), Size(w * 0.54f, h * 0.54f),
            style = Stroke(w * 0.025f)
        )
    }
}

/** A stack of chips worth [amount], up to three columns wide. Its own size is [chipWidth] wide per column. */
@Composable
fun HoldemChipStack(amount: Int, modifier: Modifier = Modifier, chipWidth: Dp = 24.dp, description: String? = null) {
    val parts = chipBreakdown(amount)
    val columns = parts.size.coerceIn(1, 3)
    val maxHeightChips = (parts.maxOfOrNull { it.second } ?: 1).coerceAtMost(8)
    val width = chipWidth * (if (columns == 1) 1f else 0.78f * columns + 0.22f)
    val height = chipWidth * 0.5f + chipWidth * 0.17f * maxHeightChips + chipWidth * 0.2f
    var m = modifier.size(width, height)
    m = if (description != null) m.semantics { contentDescription = description } else m.clearAndSetSemantics { }
    Canvas(m) {
        drawChipStack(parts, size.width, size.height, chipWidth.toPx())
    }
}

internal fun DrawScope.drawChipStack(parts: List<Pair<Int, Int>>, boxW: Float, boxH: Float, chipW: Float, alpha: Float = 1f) {
    val columns = parts.size.coerceIn(1, 3)
    // Merge any denominations beyond three columns into the last column.
    val cols: List<List<Int>> = List(columns) { col ->
        val picked = if (col < columns - 1) listOf(parts[col]) else parts.drop(col)
        picked.flatMap { p -> List(minOf(p.second, 8)) { p.first } }.take(8)
    }
    val step = chipW * 0.78f
    val startX = boxW / 2 - (columns - 1) * step / 2
    for ((ci, stack) in cols.withIndex()) {
        val cx = startX + ci * step
        val baseY = boxH - chipW * 0.45f - (if (ci % 2 == 1) chipW * 0.12f else 0f)
        stack.forEachIndexed { level, denom ->
            drawChip(Offset(cx, baseY - level * chipW * 0.17f), chipW, denom, top = level == stack.lastIndex, alpha = alpha)
        }
    }
}

// ---- table ----------------------------------------------------------------------------------------

/** The felt table: a wooden rail, a leather edge, deep green felt with a faint betting line. */
@Composable
fun HoldemFelt(modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height
        val rail = minOf(w, h) * 0.05f
        drawOval(
            Brush.verticalGradient(listOf(Color(0xFF6B4226), Color(0xFF3B2314)), 0f, h),
            Offset.Zero, Size(w, h)
        )
        drawOval(Color(0xFF1C1209), Offset(rail * 0.7f, rail * 0.7f), Size(w - rail * 1.4f, h - rail * 1.4f))
        val feltTopLeft = Offset(rail, rail)
        val feltSize = Size(w - rail * 2, h - rail * 2)
        drawOval(
            Brush.radialGradient(
                listOf(Color(0xFF168A68), Color(0xFF0D6149), Color(0xFF07382B)),
                center = Offset(w / 2, h * 0.45f), radius = maxOf(w, h) * 0.62f
            ),
            feltTopLeft, feltSize
        )
        // betting line and a soft inner shadow
        val lineInset = rail * 3.2f
        drawOval(
            Color(0x2EFFFFFF), Offset(lineInset, lineInset), Size(w - lineInset * 2, h - lineInset * 2),
            style = Stroke(minOf(w, h) * 0.006f + 1f)
        )
        drawOval(Color(0x33000000), feltTopLeft, feltSize, style = Stroke(rail * 0.5f))
    }
}

/** A round dealer button reading "D" (decorative; the seat's own label says who has it). */
@Composable
fun HoldemDealerButton(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2
            drawCircle(Color(0x55000000), r, Offset(r + r * 0.06f, r + r * 0.1f))
            drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFE2DAC2)), Offset(r * 0.8f, r * 0.7f), r * 1.2f), r, Offset(r, r))
            drawCircle(Color(0xFFB9AF98), r * 0.78f, Offset(r, r), style = Stroke(r * 0.07f))
        }
        Text("D", color = InkBlack, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.5f).sp)
    }
}
