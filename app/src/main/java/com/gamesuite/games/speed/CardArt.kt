package com.gamesuite.games.speed

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.CornerRadius
import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.Suit

/**
 * Original card art for Speed and Matching, drawn entirely with vector paths so no font has to
 * carry the suit symbols: a cream card stock with a printed index in two corners and one big pip,
 * and a lattice-and-medallion back. A card's suit is always told by its SHAPE as well as its
 * colour, and [colorblind] swaps the red for a vermilion that stays apart from the near-black.
 */
object CardArtColors {
    val Stock = Color(0xFFFFFCF4)
    val StockEdge = Color(0x33000000)
    val Red = Color(0xFFC62828)
    val RedColorblind = Color(0xFFE05A00)
    val Black = Color(0xFF1B1B20)

    fun suit(suit: Suit, colorblind: Boolean): Color =
        if (suit.isRed) (if (colorblind) RedColorblind else Red) else Black
}

/** The card's height for a given width (a standard 5:7 playing-card proportion). */
fun cardHeightFor(width: Dp): Dp = width * 1.4f

/** A suit pip centred on [c], [s] is half its width. Hearts and spades are Bezier paths; clubs and diamonds are built from circles and a rhombus. */
fun DrawScope.drawSuit(suit: Suit, c: Offset, s: Float, color: Color) {
    when (suit) {
        Suit.HEARTS -> drawPath(heartPath(c, s, flip = false), color)
        Suit.DIAMONDS -> {
            val p = Path().apply {
                moveTo(c.x, c.y - s * 1.1f); lineTo(c.x + s * 0.78f, c.y); lineTo(c.x, c.y + s * 1.1f); lineTo(c.x - s * 0.78f, c.y); close()
            }
            drawPath(p, color)
        }
        Suit.SPADES -> {
            drawPath(heartPath(Offset(c.x, c.y - s * 0.12f), s, flip = true), color)
            drawPath(stemPath(c, s), color)
        }
        Suit.CLUBS -> {
            val r = s * 0.5f
            drawCircle(color, r, Offset(c.x, c.y - s * 0.52f))
            drawCircle(color, r, Offset(c.x - s * 0.6f, c.y + s * 0.2f))
            drawCircle(color, r, Offset(c.x + s * 0.6f, c.y + s * 0.2f))
            drawCircle(color, r * 0.7f, c)
            drawPath(stemPath(c, s), color)
        }
    }
}

private fun heartPath(c: Offset, s: Float, flip: Boolean): Path {
    val f = if (flip) -1f else 1f
    fun y(v: Float) = c.y + f * v * s
    return Path().apply {
        moveTo(c.x, y(0.95f))
        cubicTo(c.x - s * 1.3f, y(0.1f), c.x - s * 1.0f, y(-0.95f), c.x, y(-0.32f))
        cubicTo(c.x + s * 1.0f, y(-0.95f), c.x + s * 1.3f, y(0.1f), c.x, y(0.95f))
        close()
    }
}

private fun stemPath(c: Offset, s: Float): Path = Path().apply {
    moveTo(c.x - s * 0.3f, c.y + s * 1.05f); lineTo(c.x, c.y + s * 0.15f); lineTo(c.x + s * 0.3f, c.y + s * 1.05f); close()
}

/**
 * A face-up playing card [width] wide. [dim] greys a card that cannot be played right now;
 * [lift] raises it (a selected card). A placeholder card (id below zero) is drawn as a back.
 */
@Composable
fun SpeedCardFace(
    card: Card,
    width: Dp,
    modifier: Modifier = Modifier,
    colorblind: Boolean = false,
    dim: Boolean = false,
    elevation: Dp = 3.dp
) {
    if (card.id < 0) {
        SpeedCardBack(width = width, modifier = modifier)
        return
    }
    val height = cardHeightFor(width)
    val shape = RoundedCornerShape(width * 0.1f)
    val ink = CardArtColors.suit(card.suit, colorblind)
    val textSize = with(LocalDensity.current) { (width.toPx() * 0.26f).toSp() }
    Box(
        modifier
            .size(width, height)
            .shadow(elevation, shape)
            .clip(shape)
            .border(1.dp, CardArtColors.StockEdge, shape)
            .then(Modifier)
    ) {
        Canvas(Modifier.size(width, height)) {
            drawRect(if (dim) lerp(CardArtColors.Stock, Color(0xFF8A8F98), 0.28f) else CardArtColors.Stock)
            // soft inner frame
            drawRoundRect(Color(0x14000000), topLeft = Offset(size.width * 0.05f, size.height * 0.04f),
                size = Size(size.width * 0.9f, size.height * 0.92f), cornerRadius = CornerRadius(size.width * 0.07f), style = Stroke(size.width * 0.012f))
            val pipColor = if (dim) ink.copy(alpha = 0.55f) else ink
            drawSuit(card.suit, Offset(size.width / 2, size.height / 2), size.width * 0.27f, pipColor)
            // small pips under the corner indices
            drawSuit(card.suit, Offset(size.width * 0.17f, size.height * 0.26f), size.width * 0.075f, pipColor)
            drawSuit(card.suit, Offset(size.width * 0.83f, size.height * 0.74f), size.width * 0.075f, pipColor)
        }
        CornerIndex(card.rank.label, ink, textSize, Modifier.align(Alignment.TopStart).padding(start = width * 0.07f, top = width * 0.03f), dim)
        CornerIndex(card.rank.label, ink, textSize, Modifier.align(Alignment.BottomEnd).padding(end = width * 0.07f, bottom = width * 0.03f).rotate(180f), dim)
    }
}

@Composable
private fun BoxScope.CornerIndex(label: String, color: Color, size: androidx.compose.ui.unit.TextUnit, modifier: Modifier, dim: Boolean) {
    Text(
        label, color = if (dim) color.copy(alpha = 0.6f) else color, fontSize = size, fontWeight = FontWeight.ExtraBold,
        maxLines = 1, softWrap = false, modifier = modifier
    )
}

/** The card back: an indigo field, a gold lattice and a round medallion. [tint] recolours it (Matching uses a teal one). */
@Composable
fun SpeedCardBack(width: Dp, modifier: Modifier = Modifier, tint: Color = Color(0xFF2E3A8C), elevation: Dp = 3.dp) {
    val height = cardHeightFor(width)
    val shape = RoundedCornerShape(width * 0.1f)
    Canvas(
        modifier
            .size(width, height)
            .shadow(elevation, shape)
            .clip(shape)
            .border(1.dp, CardArtColors.StockEdge, shape)
    ) { drawCardBack(tint) }
}

fun DrawScope.drawCardBack(tint: Color) {
    val w = size.width
    val h = size.height
    drawRect(Brush.linearGradient(listOf(lerp(tint, Color.White, 0.12f), lerp(tint, Color.Black, 0.25f)), Offset.Zero, Offset(w, h)))
    val gold = Color(0xFFE9C46A)
    // cream frame
    drawRoundRect(CardArtColors.Stock, topLeft = Offset(w * 0.06f, w * 0.06f), size = Size(w * 0.88f, h - w * 0.12f),
        cornerRadius = CornerRadius(w * 0.07f), style = Stroke(w * 0.035f))
    // diamond lattice
    val step = w * 0.17f
    val left = w * 0.12f; val right = w * 0.88f; val top = w * 0.12f; val bottom = h - w * 0.12f
    var x = left - (bottom - top)
    while (x < right) {
        drawLineClipped(Offset(x, bottom), Offset(x + (bottom - top), top), gold.copy(alpha = 0.35f), w * 0.014f, left, right, top, bottom)
        drawLineClipped(Offset(x, top), Offset(x + (bottom - top), bottom), gold.copy(alpha = 0.35f), w * 0.014f, left, right, top, bottom)
        x += step
    }
    // medallion
    val c = Offset(w / 2, h / 2)
    drawCircle(lerp(tint, Color.Black, 0.35f), w * 0.26f, c)
    drawCircle(gold, w * 0.26f, c, style = Stroke(w * 0.03f))
    drawCircle(gold, w * 0.17f, c, style = Stroke(w * 0.014f))
    val star = Path()
    for (i in 0 until 8) {
        val a = Math.PI / 4 * i - Math.PI / 2
        val r = if (i % 2 == 0) w * 0.15f else w * 0.065f
        val px = c.x + (Math.cos(a) * r).toFloat(); val py = c.y + (Math.sin(a) * r).toFloat()
        if (i == 0) star.moveTo(px, py) else star.lineTo(px, py)
    }
    star.close()
    drawPath(star, gold)
}

/** Draws the part of the segment a-b that falls inside the given box (the lattice must not spill over the frame). */
private fun DrawScope.drawLineClipped(a: Offset, b: Offset, color: Color, width: Float, l: Float, r: Float, t: Float, btm: Float) {
    // Liang-Barsky clip of a-b against the rectangle.
    var t0 = 0f; var t1 = 1f
    val dx = b.x - a.x; val dy = b.y - a.y
    val p = floatArrayOf(-dx, dx, -dy, dy)
    val q = floatArrayOf(a.x - l, r - a.x, a.y - t, btm - a.y)
    for (i in 0 until 4) {
        if (p[i] == 0f) { if (q[i] < 0f) return } else {
            val u = q[i] / p[i]
            if (p[i] < 0f) { if (u > t1) return; if (u > t0) t0 = u } else { if (u < t0) return; if (u < t1) t1 = u }
        }
    }
    drawLine(color, Offset(a.x + t0 * dx, a.y + t0 * dy), Offset(a.x + t1 * dx, a.y + t1 * dy), width)
}
