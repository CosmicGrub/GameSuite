package com.gamesuite.games.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Shared card face rendering — every card game draws its cards through this
 * so they share one visual language (shadow, rounded corners, face-down
 * back). Width/height are parameters so callers (rack, discard pile, board)
 * can size cards to their layout.
 *
 * [showThickness] fakes a real card's physical edge — a couple of thin,
 * cream-colored offset layers stacked behind the face — the way a card
 * looks resting on a table rather than a flat printed rectangle (the
 * reference points for this were the Xbox 360 UNO's tabletop card
 * rendering and the modern official mobile UNO app's own glossy cards).
 * Off by default so every existing caller is unaffected; UnoScreen.kt is
 * the first consumer, gated behind "3D Perspective Mode"
 * (LocalCard3DMode) — this is a depth/rendering concern, the same bucket
 * card3DFlip/tablePerspectiveTilt already live in, not a motion one.
 *
 * A card whose [CardVisual.style] is [CardStyle.UNO] is drawn like a printed UNO card instead of
 * a flat color tile: cream frame, colored field, slanted white center oval carrying the rank
 * mark, mirrored corner marks, and (face-down) the black card back with the red "UNO" oval.
 */
@Composable
fun PlayingCardView(
    card: CardVisual,
    modifier: Modifier = Modifier,
    width: Dp = 64.dp,
    height: Dp = 92.dp,
    showThickness: Boolean = false
) {
    val uno = card.style == CardStyle.UNO
    // A printed card's corner radius tracks its width; the flat tile keeps its fixed 10dp.
    val corner = if (uno) width * 0.11f else 10.dp
    val shape = RoundedCornerShape(corner)
    Box(modifier = modifier.size(width = width, height = height)) {
        if (showThickness) {
            // Two offset edge layers, cream/card-stock colored regardless of the
            // face's own color -- a real card's edge is the paper, not the ink.
            // Drawn smallest-offset-on-top so the stack reads as receding away
            // from the viewer, not floating in front.
            Box(
                Modifier
                    .offset(x = 2.2.dp, y = 2.2.dp)
                    .size(width, height)
                    .clip(shape)
                    .background(Color(0xFFCFC6AE))
            )
            Box(
                Modifier
                    .offset(x = 1.1.dp, y = 1.1.dp)
                    .size(width, height)
                    .clip(shape)
                    .background(Color(0xFFE3DBC5))
            )
        }
        Box(
            modifier = Modifier
                .size(width = width, height = height)
                .shadow(elevation = 3.dp, shape = shape)
                .clip(shape)
                .background(
                    when {
                        uno -> UNO_FRAME
                        card.faceDown -> Color(0xFF2B2B2B)
                        else -> card.backgroundColor
                    }
                )
                .border(1.dp, Color.Black.copy(alpha = 0.15f), shape),
            contentAlignment = Alignment.Center
        ) {
            when {
                uno && card.faceDown -> UnoCardBack(width)
                uno -> UnoCardFace(card, width)
                card.faceDown -> Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .background(Color(0xFF424242), RoundedCornerShape(6.dp))
                        .size(width - 16.dp, height - 16.dp)
                )
                else -> FlatCardFace(card, width)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.FlatCardFace(card: CardVisual, width: Dp) {
    // Derived from the width this card was actually given, not a fixed
    // constant — so a caller applying LocalCardScale (see CardScale.kt)
    // to width/height gets proportionally scaled, still-legible text for
    // free, at either end of the resize range, without this composable
    // needing to know about the scale setting itself.
    val labelFontSize = (width.value * 0.26f).coerceIn(9f, 34f).sp
    if (card.cornerIndex != null) {
        Text(
            card.cornerIndex,
            color = card.textColor,
            fontWeight = FontWeight.Bold,
            fontSize = labelFontSize * 0.6f,
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
        )
    }
    // Colorblind-safe mode's actual on-card fix (see colorblindGlyph's own KDoc) --
    // opposite corner from cornerIndex so the two never collide, drawn at the same
    // size/weight as that corner mark for a consistent "two marks, one card" language.
    if (card.colorblindGlyph != null) {
        Text(
            card.colorblindGlyph,
            color = card.textColor,
            fontWeight = FontWeight.Bold,
            fontSize = labelFontSize * 0.6f,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
        )
    }
    Text(
        card.label,
        color = card.textColor,
        fontWeight = FontWeight.Bold,
        fontSize = labelFontSize
    )
}

// ---- Printed-UNO-card style -------------------------------------------------------------

private val UNO_FRAME = Color(0xFFFBF8F1)
private val UNO_BACK_BLACK = Color(0xFF17131F)
private val UNO_RED = Color(0xFFD32F2F)
private val UNO_YELLOW = Color(0xFFFFD23F)
private val WILD_QUAD = listOf(Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFDD835))
private val glyphShadow = Shadow(color = Color.Black.copy(alpha = 0.32f), offset = Offset(2f, 3f), blurRadius = 4f)
private val cornerShadow = Shadow(color = Color.Black.copy(alpha = 0.62f), offset = Offset(1.5f, 2f), blurRadius = 3f)

/** A size given in dp, as a text size that ignores the system font scale -- text drawn INSIDE a card
 *  is sized against the card's own width, so a large font scale must not make it outgrow the card. */
@Composable
private fun fixedSp(dpValue: Float): TextUnit = with(LocalDensity.current) { dpValue.dp.toSp() }

/** Slant of the center oval, degrees clockwise -- the printed deck's own italic-ish lean. */
private const val OVAL_TILT = 26f

@Composable
private fun UnoCardBack(width: Dp) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(width * 0.065f)
            .clip(RoundedCornerShape(width * 0.085f))
            .background(UNO_BACK_BLACK),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ovalSize = Size(size.width * 0.76f, size.height * 0.62f)
            rotate(degrees = -OVAL_TILT, pivot = center) {
                drawOval(UNO_RED, topLeft = Offset((size.width - ovalSize.width) / 2f, (size.height - ovalSize.height) / 2f), size = ovalSize)
                drawOval(
                    UNO_YELLOW.copy(alpha = 0.9f),
                    topLeft = Offset((size.width - ovalSize.width) / 2f, (size.height - ovalSize.height) / 2f),
                    size = ovalSize,
                    style = Stroke(width = size.width * 0.028f)
                )
            }
        }
        // The wordmark only reads at sizes where it can actually be legible; on the small
        // opponent-fan cards the red oval alone is the recognizable tell.
        if (width >= 44.dp) {
            Text(
                "UNO",
                color = UNO_YELLOW,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                fontSize = fixedSp(width.value * 0.235f),
                style = TextStyle(shadow = glyphShadow),
                modifier = Modifier.rotate(-OVAL_TILT * 0.55f)
            )
        }
    }
}

@Composable
private fun UnoCardFace(card: CardVisual, width: Dp) {
    val face = card.backgroundColor
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(width * 0.065f)
            .clip(RoundedCornerShape(width * 0.085f))
            .background(face)
    ) {
        // Center oval (or the four-color wedge oval on a Wild) + vector glyphs.
        Canvas(Modifier.fillMaxSize()) {
            val ovalSize = Size(size.width * 0.72f, size.height * 0.96f)
            val topLeft = Offset((size.width - ovalSize.width) / 2f, (size.height - ovalSize.height) / 2f)
            rotate(degrees = OVAL_TILT, pivot = center) {
                if (card.glyph == CardGlyph.WILD) {
                    val starts = floatArrayOf(180f, 270f, 0f, 90f)
                    for (i in 0 until 4) {
                        drawArc(WILD_QUAD[i], startAngle = starts[i], sweepAngle = 90f, useCenter = true, topLeft = topLeft, size = ovalSize)
                    }
                    drawOval(Color.White.copy(alpha = 0.9f), topLeft = topLeft, size = ovalSize, style = Stroke(width = size.width * 0.03f))
                } else {
                    drawOval(Color.White, topLeft = topLeft, size = ovalSize)
                }
            }
            when (card.glyph) {
                CardGlyph.SKIP -> drawSkipGlyph(center, size.width * 0.27f, face)
                CardGlyph.REVERSE -> drawReverseGlyph(center, size.width * 0.34f, face)
                else -> Unit
            }
        }
        if (card.glyph == CardGlyph.TEXT) {
            val fontSize = (width.value * if (card.label.length > 1) 0.40f else 0.54f).coerceIn(12f, 92f)
            Text(
                card.label,
                color = face,
                fontWeight = FontWeight.Black,
                fontSize = fixedSp(fontSize),
                style = TextStyle(shadow = glyphShadow),
                modifier = Modifier.align(Alignment.Center)
            )
            // The printed deck underlines 6 and 9 so they can't be read upside-down.
            if (card.label == "6" || card.label == "9") {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .offset(y = (fontSize * 0.62f).dp)
                        .size(width = (fontSize * 0.42f).dp, height = (fontSize * 0.07f).coerceAtLeast(1.5f).dp)
                        .background(face, RoundedCornerShape(50))
                )
            }
        }
        // Mirrored corner marks: top-left upright, bottom-right rotated half a turn.
        val cornerSize = width * 0.30f
        Box(Modifier.align(Alignment.TopStart).padding(width * 0.035f)) { CornerMark(card, cornerSize) }
        Box(Modifier.align(Alignment.BottomEnd).padding(width * 0.035f).rotate(180f)) { CornerMark(card, cornerSize) }
        // Colorblind-safe mode's distinct shape, kept on the same top-end corner as the flat style.
        // It sits on a small dark chip: that corner is partly under the white center oval, and bare
        // white (or white on the yellow suit) was unreadable there -- the one place this whole
        // setting exists to make legible.
        if (card.colorblindGlyph != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(width * 0.035f)
                    .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(50))
                    .padding(horizontal = width * 0.05f, vertical = width * 0.008f)
            ) {
                Text(
                    card.colorblindGlyph,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = fixedSp((width.value * 0.17f).coerceAtLeast(8f))
                )
            }
        }
    }
}

@Composable
private fun CornerMark(card: CardVisual, size: Dp) {
    when (card.glyph) {
        CardGlyph.TEXT -> Text(
            card.cornerIndex ?: card.label,
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = fixedSp((size.value * if ((card.cornerIndex ?: card.label).length > 1) 0.56f else 0.78f).coerceAtLeast(8f)),
            // A heavier outline than the center mark's: the white corner numerals sit directly on the
            // suit color, and white on yellow needs the help.
            style = TextStyle(shadow = cornerShadow)
        )
        CardGlyph.SKIP -> Canvas(Modifier.size(size * 0.62f)) {
            drawSkipGlyph(center, this.size.minDimension * 0.42f, Color.White, stroke = this.size.minDimension * 0.16f)
        }
        CardGlyph.REVERSE -> Canvas(Modifier.size(size * 0.62f)) {
            drawReverseGlyph(center, this.size.minDimension * 0.92f, Color.White)
        }
        CardGlyph.WILD -> Canvas(Modifier.size(size * 0.5f)) {
            val r = this.size.minDimension / 2f
            val starts = floatArrayOf(180f, 270f, 0f, 90f)
            for (i in 0 until 4) {
                drawArc(WILD_QUAD[i], startAngle = starts[i], sweepAngle = 90f, useCenter = true, topLeft = Offset.Zero, size = Size(r * 2, r * 2))
            }
            drawCircle(Color.White, radius = r, style = Stroke(width = r * 0.22f))
        }
    }
}

/** A "no" sign: ring plus a diagonal bar. */
private fun DrawScope.drawSkipGlyph(center: Offset, radius: Float, color: Color, stroke: Float = radius * 0.34f) {
    drawCircle(color, radius = radius, center = center, style = Stroke(width = stroke))
    val d = radius * 0.71f
    drawLine(color, Offset(center.x - d, center.y + d), Offset(center.x + d, center.y - d), strokeWidth = stroke, cap = StrokeCap.Round)
}

/** Two opposed arrows (one up, one down), the printed deck's Reverse mark. */
private fun DrawScope.drawReverseGlyph(center: Offset, length: Float, color: Color) {
    val stroke = length * 0.16f
    val head = length * 0.26f
    val gap = length * 0.21f
    fun arrow(x: Float, up: Boolean) {
        val yStart = center.y + (if (up) length / 2f else -length / 2f)
        val yEnd = center.y + (if (up) -length / 2f else length / 2f)
        val dir = if (up) 1f else -1f
        drawLine(color, Offset(x, yStart), Offset(x, yEnd + dir * head * 0.6f), strokeWidth = stroke, cap = StrokeCap.Round)
        val tri = Path().apply {
            moveTo(x, yEnd)
            lineTo(x - head, yEnd + dir * head * 1.15f)
            lineTo(x + head, yEnd + dir * head * 1.15f)
            close()
        }
        drawPath(tri, color)
        drawPath(tri, color, style = Stroke(width = stroke * 0.5f, join = StrokeJoin.Round))
    }
    arrow(center.x - gap, up = true)
    arrow(center.x + gap, up = false)
}
