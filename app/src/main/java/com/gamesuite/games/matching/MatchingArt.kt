package com.gamesuite.games.matching

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.gamesuite.games.cards.Suit
import com.gamesuite.games.speed.CardArtColors
import com.gamesuite.games.speed.drawSuit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Original face art for Matching, all vector. A face is a number 0 until [MatchingRules.FACE_POOL];
 * each theme turns it into a picture:
 *  - CRITTERS: five animals (cat, bunny, bear, mouse, owl) in four coats (plain, striped, spotted,
 *    pale muzzle), so 20 faces differ by BOTH the shape of the ears and a marking, never colour alone.
 *  - SYMBOLS: ten bold shapes, each solid or as an outline: 20 faces told apart by shape and fill.
 *  - CARDS: a rank in red or black with the pips of that colour, so a pair is two cards of one rank and colour.
 */
object MatchingArt {
    const val SPECIES = 5
    const val COATS = 4
    const val SHAPES = 10

    fun species(face: Int) = face % SPECIES
    fun coat(face: Int) = (face / SPECIES) % COATS
    fun shape(face: Int) = face % SHAPES
    fun isOutline(face: Int) = face >= SHAPES
    fun cardRankIndex(face: Int) = face % 13
    fun cardIsRed(face: Int) = face < 13

    private val coatColors = listOf(Color(0xFFE9A15B), Color(0xFF9AA5B1), Color(0xFF9C6B4E), Color(0xFFF1E2C4))
    private val symbolColors = listOf(
        Color(0xFF0072B2), Color(0xFFD55E00), Color(0xFF009E73), Color(0xFFCC79A7), Color(0xFFB88700),
        Color(0xFF2B6CB0), Color(0xFFB83280), Color(0xFF2F855A), Color(0xFF6B46C1), Color(0xFFC05621)
    )
    private val ink = Color(0xFF1F2933)
    private val rankLabels = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")

    /** Draws [face] of [theme] into the box (the caller supplies the card stock behind it). */
    @Composable
    fun Face(theme: MatchingTheme, face: Int, colorblind: Boolean, modifier: Modifier = Modifier) {
        when (theme) {
            MatchingTheme.CRITTERS -> Canvas(modifier.fillMaxSize()) { drawCritter(species(face), coat(face), colorblind) }
            MatchingTheme.SYMBOLS -> Canvas(modifier.fillMaxSize()) { drawSymbol(shape(face), isOutline(face), colorblind) }
            MatchingTheme.CARDS -> CardPair(face, colorblind, modifier)
        }
    }

    @Composable
    private fun CardPair(face: Int, colorblind: Boolean, modifier: Modifier) {
        val red = cardIsRed(face)
        val color = if (red) (if (colorblind) CardArtColors.RedColorblind else CardArtColors.Red) else CardArtColors.Black
        BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val rankSize = (maxWidth.value * 0.42f).sp
            Canvas(Modifier.fillMaxSize()) {
                val s = size.width * 0.13f
                val suits = if (red) listOf(Suit.HEARTS, Suit.DIAMONDS) else listOf(Suit.SPADES, Suit.CLUBS)
                drawSuit(suits[0], Offset(size.width * 0.28f, size.height * 0.2f), s, color)
                drawSuit(suits[1], Offset(size.width * 0.72f, size.height * 0.8f), s, color)
            }
            Text(rankLabels[cardRankIndex(face)], color = color, fontWeight = FontWeight.ExtraBold, fontSize = rankSize, maxLines = 1, softWrap = false)
        }
    }

    // ---- critters ----

    private fun DrawScope.drawCritter(species: Int, coat: Int, colorblind: Boolean) {
        val w = size.width
        val h = size.height
        val c = Offset(w / 2, h * 0.56f)
        val fur = coatColors[coat]
        val dark = lerp(fur, Color.Black, 0.55f)
        val inner = Color(0xFFF4A6B7)
        val r = minOf(w, h) * 0.34f
        // backdrop disc
        drawCircle(lerp(fur, Color.White, 0.72f).copy(alpha = 0.55f), minOf(w, h) * 0.46f, Offset(w / 2, h / 2))
        // ears first (behind the head)
        when (species) {
            0 -> { // cat: pointed
                for (side in listOf(-1f, 1f)) {
                    val p = Path().apply {
                        moveTo(c.x + side * r * 0.95f, c.y - r * 0.35f); lineTo(c.x + side * r * 0.82f, c.y - r * 1.38f); lineTo(c.x + side * r * 0.12f, c.y - r * 0.92f); close()
                    }
                    drawPath(p, fur); drawPath(p, dark, style = Stroke(r * 0.07f))
                    val q = Path().apply {
                        moveTo(c.x + side * r * 0.8f, c.y - r * 0.6f); lineTo(c.x + side * r * 0.74f, c.y - r * 1.08f); lineTo(c.x + side * r * 0.38f, c.y - r * 0.86f); close()
                    }
                    drawPath(q, inner)
                }
            }
            1 -> { // bunny: long ears
                for (side in listOf(-1f, 1f)) {
                    val tl = Offset(c.x + side * r * 0.42f - r * 0.2f, c.y - r * 1.75f)
                    drawOval(fur, tl, Size(r * 0.4f, r * 1.5f))
                    drawOval(dark, tl, Size(r * 0.4f, r * 1.5f), style = Stroke(r * 0.06f))
                    drawOval(inner, Offset(tl.x + r * 0.08f, tl.y + r * 0.25f), Size(r * 0.24f, r * 1.0f))
                }
            }
            2 -> { // bear: small round ears
                for (side in listOf(-1f, 1f)) {
                    drawCircle(fur, r * 0.3f, Offset(c.x + side * r * 0.78f, c.y - r * 0.82f))
                    drawCircle(dark, r * 0.3f, Offset(c.x + side * r * 0.78f, c.y - r * 0.82f), style = Stroke(r * 0.06f))
                    drawCircle(inner, r * 0.14f, Offset(c.x + side * r * 0.78f, c.y - r * 0.82f))
                }
            }
            3 -> { // mouse: big round ears
                for (side in listOf(-1f, 1f)) {
                    drawCircle(fur, r * 0.52f, Offset(c.x + side * r * 0.9f, c.y - r * 0.78f))
                    drawCircle(dark, r * 0.52f, Offset(c.x + side * r * 0.9f, c.y - r * 0.78f), style = Stroke(r * 0.06f))
                    drawCircle(inner, r * 0.34f, Offset(c.x + side * r * 0.9f, c.y - r * 0.78f))
                }
            }
            else -> { // owl: ear tufts
                for (side in listOf(-1f, 1f)) {
                    val p = Path().apply {
                        moveTo(c.x + side * r * 0.9f, c.y - r * 0.55f); lineTo(c.x + side * r * 0.62f, c.y - r * 1.25f); lineTo(c.x + side * r * 0.2f, c.y - r * 0.9f); close()
                    }
                    drawPath(p, dark)
                }
            }
        }
        // head
        drawOval(fur, Offset(c.x - r, c.y - r * 0.92f), Size(r * 2f, r * 1.84f))
        drawOval(dark, Offset(c.x - r, c.y - r * 0.92f), Size(r * 2f, r * 1.84f), style = Stroke(r * 0.07f))
        // markings: the coat tells the faces of one animal apart
        when (coat) {
            1 -> for (i in -1..1) drawLine(dark, Offset(c.x + i * r * 0.28f, c.y - r * 0.9f), Offset(c.x + i * r * 0.2f, c.y - r * 0.45f), r * 0.09f, StrokeCap.Round)
            2 -> {
                drawCircle(dark, r * 0.11f, Offset(c.x - r * 0.62f, c.y + r * 0.25f)); drawCircle(dark, r * 0.08f, Offset(c.x - r * 0.45f, c.y + r * 0.5f))
                drawCircle(dark, r * 0.11f, Offset(c.x + r * 0.62f, c.y + r * 0.25f)); drawCircle(dark, r * 0.08f, Offset(c.x + r * 0.45f, c.y + r * 0.5f))
                drawCircle(dark, r * 0.09f, Offset(c.x, c.y - r * 0.7f))
            }
            3 -> drawOval(Color.White.copy(alpha = 0.85f), Offset(c.x - r * 0.5f, c.y + r * 0.02f), Size(r, r * 0.78f))
            else -> {
                drawCircle(Color(0x55F4A6B7), r * 0.18f, Offset(c.x - r * 0.62f, c.y + r * 0.32f)); drawCircle(Color(0x55F4A6B7), r * 0.18f, Offset(c.x + r * 0.62f, c.y + r * 0.32f))
            }
        }
        // eyes
        if (species == 4) {
            for (side in listOf(-1f, 1f)) {
                drawCircle(Color.White, r * 0.34f, Offset(c.x + side * r * 0.42f, c.y - r * 0.2f))
                drawCircle(dark, r * 0.34f, Offset(c.x + side * r * 0.42f, c.y - r * 0.2f), style = Stroke(r * 0.07f))
                drawCircle(ink, r * 0.15f, Offset(c.x + side * r * 0.42f, c.y - r * 0.2f))
            }
            val beak = Path().apply { moveTo(c.x - r * 0.14f, c.y + r * 0.12f); lineTo(c.x + r * 0.14f, c.y + r * 0.12f); lineTo(c.x, c.y + r * 0.42f); close() }
            drawPath(beak, Color(0xFFE5A100))
        } else {
            for (side in listOf(-1f, 1f)) {
                drawCircle(ink, r * 0.12f, Offset(c.x + side * r * 0.4f, c.y - r * 0.12f))
                drawCircle(Color.White, r * 0.045f, Offset(c.x + side * r * 0.4f - r * 0.035f, c.y - r * 0.16f))
            }
            val nose = if (species == 1 || species == 3) inner else ink
            drawOval(nose, Offset(c.x - r * 0.12f, c.y + r * 0.1f), Size(r * 0.24f, r * 0.17f))
            drawLine(ink, Offset(c.x, c.y + r * 0.27f), Offset(c.x, c.y + r * 0.4f), r * 0.045f, StrokeCap.Round)
            drawLine(ink, Offset(c.x, c.y + r * 0.4f), Offset(c.x - r * 0.15f, c.y + r * 0.48f), r * 0.045f, StrokeCap.Round)
            drawLine(ink, Offset(c.x, c.y + r * 0.4f), Offset(c.x + r * 0.15f, c.y + r * 0.48f), r * 0.045f, StrokeCap.Round)
            if (species == 0 || species == 3) { // whiskers
                for (side in listOf(-1f, 1f)) for (k in -1..1) {
                    drawLine(ink.copy(alpha = 0.7f), Offset(c.x + side * r * 0.3f, c.y + r * 0.26f), Offset(c.x + side * r * 1.15f, c.y + r * (0.14f + k * 0.2f)), r * 0.03f, StrokeCap.Round)
                }
            }
            if (species == 2) drawOval(lerp(fur, Color.White, 0.5f), Offset(c.x - r * 0.3f, c.y + r * 0.08f), Size(r * 0.6f, r * 0.45f), alpha = 0.5f)
        }
    }

    // ---- symbols ----

    private fun DrawScope.drawSymbol(shape: Int, outline: Boolean, colorblind: Boolean) {
        val color = if (colorblind) ink else symbolColors[shape % symbolColors.size]
        val c = Offset(size.width / 2, size.height / 2)
        val s = minOf(size.width, size.height) * 0.32f
        val path = symbolPath(shape, c, s)
        if (outline) drawPath(path, color, style = Stroke(s * 0.26f, join = androidx.compose.ui.graphics.StrokeJoin.Round)) else drawPath(path, color)
    }

    private fun symbolPath(shape: Int, c: Offset, s: Float): Path {
        val p = Path()
        when (shape) {
            0 -> p.addOval(androidx.compose.ui.geometry.Rect(c.x - s, c.y - s, c.x + s, c.y + s))
            1 -> p.addRect(androidx.compose.ui.geometry.Rect(c.x - s * 0.9f, c.y - s * 0.9f, c.x + s * 0.9f, c.y + s * 0.9f))
            2 -> { p.moveTo(c.x, c.y - s); p.lineTo(c.x + s * 1.05f, c.y + s * 0.85f); p.lineTo(c.x - s * 1.05f, c.y + s * 0.85f); p.close() }
            3 -> { p.moveTo(c.x, c.y - s * 1.1f); p.lineTo(c.x + s * 0.85f, c.y); p.lineTo(c.x, c.y + s * 1.1f); p.lineTo(c.x - s * 0.85f, c.y); p.close() }
            4 -> {
                for (i in 0 until 10) {
                    val a = -PI / 2 + i * PI / 5
                    val rr = if (i % 2 == 0) s * 1.1f else s * 0.46f
                    val x = c.x + (cos(a) * rr).toFloat(); val y = c.y + (sin(a) * rr).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            5 -> {
                val t = s * 0.34f
                p.moveTo(c.x - t, c.y - s); p.lineTo(c.x + t, c.y - s); p.lineTo(c.x + t, c.y - t); p.lineTo(c.x + s, c.y - t); p.lineTo(c.x + s, c.y + t)
                p.lineTo(c.x + t, c.y + t); p.lineTo(c.x + t, c.y + s); p.lineTo(c.x - t, c.y + s); p.lineTo(c.x - t, c.y + t); p.lineTo(c.x - s, c.y + t)
                p.lineTo(c.x - s, c.y - t); p.lineTo(c.x - t, c.y - t); p.close()
            }
            6 -> {
                p.moveTo(c.x, c.y + s * 0.95f)
                p.cubicTo(c.x - s * 1.4f, c.y + s * 0.1f, c.x - s * 1.1f, c.y - s * 1.0f, c.x, c.y - s * 0.32f)
                p.cubicTo(c.x + s * 1.1f, c.y - s * 1.0f, c.x + s * 1.4f, c.y + s * 0.1f, c.x, c.y + s * 0.95f); p.close()
            }
            7 -> {
                for (i in 0 until 6) {
                    val a = PI / 6 + i * PI / 3
                    val x = c.x + (cos(a) * s * 1.05f).toFloat(); val y = c.y + (sin(a) * s * 1.05f).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            8 -> { // crescent
                p.moveTo(c.x + s * 0.35f, c.y - s * 1.0f)
                p.cubicTo(c.x - s * 1.5f, c.y - s * 0.6f, c.x - s * 1.5f, c.y + s * 0.6f, c.x + s * 0.35f, c.y + s * 1.0f)
                p.cubicTo(c.x - s * 0.6f, c.y + s * 0.5f, c.x - s * 0.6f, c.y - s * 0.5f, c.x + s * 0.35f, c.y - s * 1.0f); p.close()
            }
            else -> { // arrow up
                p.moveTo(c.x, c.y - s * 1.05f); p.lineTo(c.x + s * 0.95f, c.y - s * 0.05f); p.lineTo(c.x + s * 0.38f, c.y - s * 0.05f)
                p.lineTo(c.x + s * 0.38f, c.y + s * 1.0f); p.lineTo(c.x - s * 0.38f, c.y + s * 1.0f); p.lineTo(c.x - s * 0.38f, c.y - s * 0.05f)
                p.lineTo(c.x - s * 0.95f, c.y - s * 0.05f); p.close()
            }
        }
        return p
    }
}
