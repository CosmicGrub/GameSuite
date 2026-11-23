package com.gamesuite.games.blackjack

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.Rank
import com.gamesuite.games.cards.Suit
import com.gamesuite.settings.LocalGameSpeed
import com.gamesuite.settings.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Original card, chip and shoe art for the card-room games (Blackjack, War), drawn entirely in Compose:
 * cream-stock faces with vector suit pips (so no font has to carry a suit symbol), standard pip layouts,
 * an engraved-lattice back and clay-style chips. Suits differ by SHAPE as well as colour; with the
 * colour-blind setting on, the red suits also carry a diagonal hatch.
 */

private val PAPER = Color(0xFFFFFBF2)
private val INK = Color(0xFF1D1B20)
private val CRIMSON = Color(0xFFC62828)
private val EDGE = Color(0xFF8A8372)
private val BACK_BASE = Color(0xFF14506B)
private val BACK_LINE = Color(0xFF4FA3C7)
private val BACK_GOLD = Color(0xFFE8C36A)

/** Card height for a given width. */
fun cardHeightFor(width: Dp): Dp = width * 1.4f

private fun suitColor(suit: Suit) = if (suit.isRed) CRIMSON else INK

// ---- pip shapes (unit square placed at x,y with side s) ------------------------------------------

private fun suitPath(suit: Suit, x: Float, y: Float, s: Float): Path {
    fun px(v: Float) = x + v * s
    fun py(v: Float) = y + v * s
    return Path().apply {
        when (suit) {
            Suit.HEARTS -> {
                moveTo(px(.5f), py(.93f))
                cubicTo(px(.04f), py(.58f), px(-.02f), py(.28f), px(.16f), py(.14f))
                cubicTo(px(.32f), py(.01f), px(.48f), py(.12f), px(.5f), py(.28f))
                cubicTo(px(.52f), py(.12f), px(.68f), py(.01f), px(.84f), py(.14f))
                cubicTo(px(1.02f), py(.28f), px(.96f), py(.58f), px(.5f), py(.93f))
                close()
            }
            Suit.DIAMONDS -> {
                moveTo(px(.5f), py(.03f))
                lineTo(px(.9f), py(.5f))
                lineTo(px(.5f), py(.97f))
                lineTo(px(.1f), py(.5f))
                close()
            }
            Suit.SPADES -> {
                moveTo(px(.5f), py(.04f))
                cubicTo(px(.98f), py(.38f), px(1.02f), py(.68f), px(.82f), py(.79f))
                cubicTo(px(.68f), py(.87f), px(.55f), py(.8f), px(.53f), py(.68f))
                lineTo(px(.61f), py(.96f))
                lineTo(px(.39f), py(.96f))
                lineTo(px(.47f), py(.68f))
                cubicTo(px(.45f), py(.8f), px(.32f), py(.87f), px(.18f), py(.79f))
                cubicTo(px(-.02f), py(.68f), px(.02f), py(.38f), px(.5f), py(.04f))
                close()
            }
            Suit.CLUBS -> {
                fun circle(cx: Float, cy: Float, r: Float) =
                    addOval(androidx.compose.ui.geometry.Rect(px(cx - r), py(cy - r), px(cx + r), py(cy + r)))
                circle(.5f, .27f, .23f)
                circle(.27f, .57f, .23f)
                circle(.73f, .57f, .23f)
                moveTo(px(.5f), py(.45f))
                lineTo(px(.36f), py(.96f))
                lineTo(px(.64f), py(.96f))
                close()
            }
        }
    }
}

private fun DrawScope.drawPip(suit: Suit, center: Offset, size: Float, flipped: Boolean, colorblind: Boolean) {
    val path = suitPath(suit, center.x - size / 2, center.y - size / 2, size)
    val color = suitColor(suit)
    val draw = {
        drawPath(path, color)
        if (colorblind && suit.isRed) {
            clipPath(path) {
                var d = -size
                while (d < size * 2) {
                    drawLine(
                        PAPER.copy(alpha = 0.75f),
                        Offset(center.x - size + d, center.y + size / 2),
                        Offset(center.x + d, center.y - size / 2),
                        strokeWidth = size * 0.07f
                    )
                    d += size * 0.26f
                }
            }
        }
    }
    if (flipped) rotate(180f, center) { draw() } else draw()
}

// ---- pip layouts ---------------------------------------------------------------------------------

private const val L = 0.3f
private const val C = 0.5f
private const val R = 0.7f

private fun pipLayout(rank: Rank): List<Pair<Float, Float>> = when (rank) {
    Rank.TWO -> listOf(C to .17f, C to .83f)
    Rank.THREE -> listOf(C to .17f, C to .5f, C to .83f)
    Rank.FOUR -> listOf(L to .17f, R to .17f, L to .83f, R to .83f)
    Rank.FIVE -> listOf(L to .17f, R to .17f, C to .5f, L to .83f, R to .83f)
    Rank.SIX -> listOf(L to .17f, R to .17f, L to .5f, R to .5f, L to .83f, R to .83f)
    Rank.SEVEN -> listOf(L to .17f, R to .17f, C to .33f, L to .5f, R to .5f, L to .83f, R to .83f)
    Rank.EIGHT -> listOf(L to .17f, R to .17f, C to .33f, L to .5f, R to .5f, C to .67f, L to .83f, R to .83f)
    Rank.NINE -> listOf(L to .17f, R to .17f, L to .39f, R to .39f, C to .5f, L to .61f, R to .61f, L to .83f, R to .83f)
    Rank.TEN -> listOf(L to .17f, R to .17f, C to .28f, L to .39f, R to .39f, L to .61f, R to .61f, C to .72f, L to .83f, R to .83f)
    else -> emptyList()
}

@Composable
private fun fixedSp(dpValue: Float): TextUnit = with(LocalDensity.current) { dpValue.dp.toSp() }

/** The printed face of [card] at [width] (height is [cardHeightFor]). Drawn flat: no animation, no semantics. */
@Composable
fun CasinoCardFace(card: Card, width: Dp, colorblind: Boolean, modifier: Modifier = Modifier) {
    val height = cardHeightFor(width)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(width * 0.1f)
    val color = suitColor(card.suit)
    Box(
        modifier
            .size(width, height)
            .shadow(3.dp, shape)
            .background(PAPER, shape)
            .border(1.dp, EDGE.copy(alpha = 0.55f), shape)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val pip = w * 0.19f
            // corner marks: top-left upright, bottom-right half a turn
            val cornerPip = Offset(w * 0.17f, w * 0.52f)
            drawPip(card.suit, cornerPip, pip, flipped = false, colorblind = colorblind)
            drawPip(card.suit, Offset(w - cornerPip.x, h - cornerPip.y), pip, flipped = true, colorblind = colorblind)
            val centerBox = Offset(w * 0.5f, h * 0.5f)
            when {
                card.rank == Rank.ACE -> drawPip(card.suit, centerBox, w * 0.5f, flipped = false, colorblind = colorblind)
                card.rank.value >= 11 -> {
                    val frameW = w * 0.56f
                    val frameH = h * 0.62f
                    val tl = Offset(centerBox.x - frameW / 2, centerBox.y - frameH / 2)
                    drawRoundRect(color.copy(alpha = 0.08f), tl, Size(frameW, frameH), CornerRadius(w * 0.05f))
                    drawRoundRect(color.copy(alpha = 0.55f), tl, Size(frameW, frameH), CornerRadius(w * 0.05f), style = Stroke(w * 0.018f))
                    drawPip(card.suit, Offset(centerBox.x, centerBox.y - frameH * 0.34f), w * 0.2f, false, colorblind)
                    drawPip(card.suit, Offset(centerBox.x, centerBox.y + frameH * 0.34f), w * 0.2f, true, colorblind)
                    drawCourtEmblem(card.rank, centerBox, w, color)
                }
                else -> pipLayout(card.rank).forEach { (cx, cy) ->
                    // the middle column and the upper half stand upright; the lower half hangs upside down
                    val x = w * (0.25f + cx * 0.5f)
                    val y = h * cy
                    drawPip(card.suit, Offset(x, y), w * 0.18f, flipped = cy > 0.5f, colorblind = colorblind)
                }
            }
        }
        // rank index text, corner marks
        val indexSize = fixedSp(width.value * if (card.rank == Rank.TEN) 0.19f else 0.27f)
        Text(
            card.rank.label, color = color, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, fontSize = indexSize,
            modifier = Modifier.align(Alignment.TopStart).offset(x = width * 0.06f, y = height * 0.02f)
        )
        Text(
            card.rank.label, color = color, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, fontSize = indexSize,
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = -width * 0.06f, y = -height * 0.02f)
                .graphicsLayer { rotationZ = 180f }
        )
        if (card.rank.value >= 11) {
            Text(
                card.rank.label, color = color, fontWeight = FontWeight.Black, fontFamily = FontFamily.Serif,
                fontSize = fixedSp(width.value * 0.42f), modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

private fun DrawScope.drawCourtEmblem(rank: Rank, center: Offset, w: Float, color: Color) {
    val y = center.y - w * 0.36f
    when (rank) {
        Rank.KING -> Path().apply { // a three-point crown
            moveTo(center.x - w * 0.1f, y + w * 0.06f)
            lineTo(center.x - w * 0.1f, y - w * 0.04f)
            lineTo(center.x - w * 0.05f, y + w * 0.01f)
            lineTo(center.x, y - w * 0.06f)
            lineTo(center.x + w * 0.05f, y + w * 0.01f)
            lineTo(center.x + w * 0.1f, y - w * 0.04f)
            lineTo(center.x + w * 0.1f, y + w * 0.06f)
            close()
        }.let { drawPath(it, color.copy(alpha = 0.8f)) }
        Rank.QUEEN -> { // a circlet with a gem
            drawArc(color.copy(alpha = 0.8f), 200f, 140f, false, Offset(center.x - w * 0.1f, y - w * 0.03f), Size(w * 0.2f, w * 0.12f), style = Stroke(w * 0.02f))
            drawCircle(color.copy(alpha = 0.9f), w * 0.022f, Offset(center.x, y - w * 0.025f))
        }
        else -> { // the jack's feather
            for (i in 0..2) {
                val a = (-60f + i * 30f) * PI.toFloat() / 180f
                drawLine(color.copy(alpha = 0.75f), Offset(center.x, y + w * 0.06f), Offset(center.x + sin(a) * w * 0.12f, y + w * 0.06f - cos(a) * w * 0.12f), strokeWidth = w * 0.02f)
            }
        }
    }
}

/** The back of a card: engraved lattice, a gold rosette and a thin border. */
@Composable
fun CasinoCardBack(width: Dp, modifier: Modifier = Modifier) {
    val height = cardHeightFor(width)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(width * 0.1f)
    Box(modifier.size(width, height).shadow(3.dp, shape).background(PAPER, shape).border(1.dp, EDGE.copy(alpha = 0.55f), shape)) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = size.width * 0.07f
            val tl = Offset(pad, pad)
            val inner = Size(size.width - 2 * pad, size.height - 2 * pad)
            val r = CornerRadius(size.width * 0.06f)
            drawRoundRect(BACK_BASE, tl, inner, r)
            clipPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(tl.x, tl.y, tl.x + inner.width, tl.y + inner.height, r)) }) {
                val step = size.width * 0.16f
                var d = -size.height
                while (d < size.width + size.height) {
                    drawLine(BACK_LINE.copy(alpha = 0.55f), Offset(d, 0f), Offset(d + size.height, size.height), strokeWidth = size.width * 0.012f)
                    drawLine(BACK_LINE.copy(alpha = 0.55f), Offset(d + size.height, 0f), Offset(d, size.height), strokeWidth = size.width * 0.012f)
                    d += step
                }
            }
            drawRoundRect(BACK_GOLD, tl, inner, r, style = Stroke(size.width * 0.02f))
            val c = Offset(size.width / 2, size.height / 2)
            val rr = size.width * 0.2f
            drawCircle(BACK_BASE, rr * 1.15f, c)
            drawCircle(BACK_GOLD, rr * 1.15f, c, style = Stroke(size.width * 0.02f))
            val diamond = Path().apply {
                moveTo(c.x, c.y - rr); lineTo(c.x + rr * 0.7f, c.y); lineTo(c.x, c.y + rr); lineTo(c.x - rr * 0.7f, c.y); close()
            }
            drawPath(diamond, BACK_GOLD)
            drawCircle(BACK_BASE, rr * 0.22f, c)
        }
    }
}

/**
 * A card that turns over: shows the face when [faceUp] and [card] is a real card, otherwise the back. The
 * turn is a Y-axis flip (instant under Reduced Motion). [description] is its TalkBack label, set by the
 * caller so a hidden card never announces what it is.
 */
@Composable
fun CasinoCard(
    card: Card?, faceUp: Boolean, width: Dp, description: String, colorblind: Boolean, modifier: Modifier = Modifier
) {
    val reduced = LocalReducedMotion.current
    val speed = LocalGameSpeed.current
    val real = card != null && card.id >= 0 && faceUp
    val rotation by animateFloatAsState(
        targetValue = if (real) 0f else 180f,
        animationSpec = tween(if (reduced) 0 else speed.scale(420L).toInt(), easing = FastOutSlowInEasing),
        label = "casino-card-flip"
    )
    val density = LocalDensity.current.density
    Box(
        modifier
            .size(width, cardHeightFor(width))
            .graphicsLayer { rotationY = rotation; cameraDistance = 14f * density }
            .clearAndSetSemantics { contentDescription = description }
    ) {
        if (rotation <= 90f && card != null && card.id >= 0) {
            CasinoCardFace(card, width, colorblind)
        } else {
            CasinoCardBack(width, Modifier.graphicsLayer { rotationY = 180f })
        }
    }
}

/**
 * Slides [content] in from the shoe: from [from] (the shoe's centre in root coordinates) to where it
 * lands, after [delayMs]. Does nothing special (and shows at once) when [animate] is false or Reduced Motion is on.
 */
@Composable
fun DealIn(
    from: Offset?, delayMs: Long, animate: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit
) {
    val reduced = LocalReducedMotion.current
    val speed = LocalGameSpeed.current
    val active = remember { animate && !reduced }
    val progress = remember { Animatable(if (active) 0f else 1f) }
    var delta by remember { mutableStateOf<Offset?>(null) }
    var started by remember { mutableStateOf(!active) }
    var center by remember { mutableStateOf<Offset?>(null) }
    val fromState = rememberUpdatedState(from)
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        // Wait until the card and the shoe both know where they are; if the shoe never reports, just show the card.
        val both = withTimeoutOrNull(500L) {
            snapshotFlow { val c = center; val f = fromState.value; if (c != null && f != null) c to f else null }
                .filterNotNull().first()
        }
        if (both == null) { started = true; progress.snapTo(1f); return@LaunchedEffect }
        delta = both.second - both.first
        kotlinx.coroutines.delay(speed.scale(delayMs))
        started = true
        progress.animateTo(1f, tween(speed.scale(380L).toInt().coerceAtLeast(60), easing = FastOutSlowInEasing))
    }
    Box(
        modifier
            .onGloballyPositioned { c ->
                if (center == null) center = c.positionInRoot() + Offset(c.size.width / 2f, c.size.height / 2f)
            }
            .graphicsLayer {
                if (!started) {
                    alpha = 0f
                } else {
                    val p = progress.value
                    val d = delta
                    if (d != null && p < 1f) {
                        translationX = d.x * (1f - p)
                        translationY = d.y * (1f - p)
                        rotationZ = (1f - p) * 14f
                    }
                }
            },
        content = content
    )
}

/** The dealing shoe: a stack of card backs with the number of cards left. Reports its centre for [DealIn]. */
@Composable
fun CasinoShoe(left: Int, width: Dp, description: String, onCenter: (Offset) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    Box(
        modifier
            .size(width + 10.dp, cardHeightFor(width) + 8.dp)
            .onGloballyPositioned { onCenter(it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)) }
            .clearAndSetSemantics { contentDescription = description }
    ) {
        val layers = (left / 30).coerceIn(1, 4)
        for (i in layers - 1 downTo 0) {
            CasinoCardBack(width, Modifier.offset { IntOffset((i * 2.5f * density).roundToInt(), (i * 2f * density).roundToInt()) })
        }
    }
}

// ---- chips ---------------------------------------------------------------------------------------

/** Colour of a chip denomination. Values also print on the chip, so colour is never the only cue. */
fun chipColor(value: Int): Color = when {
    value >= 500 -> Color(0xFF6A3FA0)
    value >= 100 -> Color(0xFF22252B)
    value >= 50 -> Color(0xFFD9822B)
    else -> Color(0xFF2468B4)
}

@Composable
fun ChipView(value: Int, size: Dp, modifier: Modifier = Modifier, description: String? = null) {
    val base = chipColor(value)
    val label = if (value >= 1000) "${value / 1000}K" else "$value"
    Box(
        modifier.size(size).let { if (description != null) it.clearAndSetSemantics { contentDescription = description } else it },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            val c = Offset(this.size.width / 2, this.size.height / 2)
            drawCircle(Color(0x44000000), r, c + Offset(0f, r * 0.08f))
            drawCircle(base, r, c)
            // edge notches
            val notches = 8
            for (i in 0 until notches) {
                val a = (i * 360f / notches)
                rotate(a, c) {
                    drawRoundRect(
                        Color.White.copy(alpha = 0.92f),
                        Offset(c.x - r * 0.13f, c.y - r * 0.99f), Size(r * 0.26f, r * 0.2f), CornerRadius(r * 0.04f)
                    )
                }
            }
            drawCircle(Color.White.copy(alpha = 0.85f), r * 0.68f, c, style = Stroke(r * 0.045f))
            drawCircle(base.copy(alpha = 0.9f), r * 0.64f, c)
            drawCircle(Color.White.copy(alpha = 0.18f), r * 0.9f, c - Offset(r * 0.12f, r * 0.14f), style = Stroke(r * 0.12f))
        }
        Text(
            label, color = Color.White, fontWeight = FontWeight.Black,
            fontSize = fixedSp(size.value * if (label.length >= 3) 0.25f else 0.3f)
        )
    }
}

/** A pile of chips worth [amount], greedy by denomination (at most [maxChips] drawn). */
@Composable
fun ChipPile(amount: Int, chipSize: Dp, modifier: Modifier = Modifier, maxChips: Int = 6) {
    val chips = remember(amount) {
        val out = ArrayList<Int>()
        var rest = amount
        for (v in BjRules.CHIP_VALUES.sortedDescending()) while (rest >= v && out.size < 40) { out += v; rest -= v }
        out.takeLast(maxChips)
    }
    val lift = chipSize * 0.14f
    Box(modifier.size(chipSize, chipSize + lift * (chips.size.coerceAtLeast(1) - 1))) {
        chips.forEachIndexed { i, v ->
            ChipView(v, chipSize, Modifier.align(Alignment.BottomCenter).offset(y = -lift * i))
        }
    }
}
