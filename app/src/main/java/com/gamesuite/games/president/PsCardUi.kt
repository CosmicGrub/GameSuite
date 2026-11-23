package com.gamesuite.games.president

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.rotate as rotateScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamesuite.games.cards.Rank
import com.gamesuite.games.cards.Suit
import kotlinx.coroutines.delay

/*
 * The card table kit shared by President and Sevens: original vector card faces (suit shapes drawn as
 * paths, never a font glyph, so they read the same on every device), a card back, a fanned hand that wraps
 * into rows when it is long, the felt, and the seat plates. Everything is drawn here; no image assets.
 */

/** Cards are 5:7. */
const val PS_CARD_ASPECT = 1.4f

internal val PaperTop = Color(0xFFFFFBF1)
internal val PaperBottom = Color(0xFFF1E8D2)
internal val PaperEdge = Color(0xFFB9AC8C)

/**
 * The ink colour of a suit. The default pairs red with near-black; colour-blind mode swaps in a vermilion
 * and a deep blue that differ in lightness as well as hue. The four suit SHAPES differ either way.
 */
fun psSuitColor(suit: Suit, colorblind: Boolean): Color = when {
    colorblind -> if (suit.isRed) Color(0xFFD84315) else Color(0xFF0B2F5B)
    else -> if (suit.isRed) Color(0xFFC62828) else Color(0xFF1B2227)
}

/** Draws [suit]'s shape centred on [c], fitting a box of about 2*[r]. */
internal fun DrawScope.drawSuit(suit: Suit, c: Offset, r: Float, color: Color) {
    when (suit) {
        Suit.HEARTS -> drawPath(heartPath(c, r), color)
        Suit.DIAMONDS -> {
            val p = Path().apply {
                moveTo(c.x, c.y - r * 1.08f)
                lineTo(c.x + r * 0.78f, c.y)
                lineTo(c.x, c.y + r * 1.08f)
                lineTo(c.x - r * 0.78f, c.y)
                close()
            }
            drawPath(p, color)
        }
        Suit.SPADES -> {
            val up = Path().apply {
                moveTo(c.x, c.y - r * 1.0f)
                cubicTo(c.x + r * 0.2f, c.y - r * 0.6f, c.x + r * 1.15f, c.y - r * 0.15f, c.x + r * 1.0f, c.y + r * 0.35f)
                cubicTo(c.x + r * 0.9f, c.y + r * 0.85f, c.x + r * 0.25f, c.y + r * 0.85f, c.x + r * 0.1f, c.y + r * 0.45f)
                lineTo(c.x + r * 0.32f, c.y + r * 1.05f)
                lineTo(c.x - r * 0.32f, c.y + r * 1.05f)
                lineTo(c.x - r * 0.1f, c.y + r * 0.45f)
                cubicTo(c.x - r * 0.25f, c.y + r * 0.85f, c.x - r * 0.9f, c.y + r * 0.85f, c.x - r * 1.0f, c.y + r * 0.35f)
                cubicTo(c.x - r * 1.15f, c.y - r * 0.15f, c.x - r * 0.2f, c.y - r * 0.6f, c.x, c.y - r * 1.0f)
                close()
            }
            drawPath(up, color)
        }
        Suit.CLUBS -> {
            val lobe = r * 0.46f
            drawCircle(color, lobe, Offset(c.x, c.y - r * 0.5f))
            drawCircle(color, lobe, Offset(c.x - r * 0.52f, c.y + r * 0.22f))
            drawCircle(color, lobe, Offset(c.x + r * 0.52f, c.y + r * 0.22f))
            val stem = Path().apply {
                moveTo(c.x - r * 0.1f, c.y + r * 0.1f)
                lineTo(c.x + r * 0.1f, c.y + r * 0.1f)
                lineTo(c.x + r * 0.34f, c.y + r * 1.05f)
                lineTo(c.x - r * 0.34f, c.y + r * 1.05f)
                close()
            }
            drawPath(stem, color)
        }
    }
}

private fun heartPath(c: Offset, r: Float) = Path().apply {
    moveTo(c.x, c.y + r * 1.0f)
    cubicTo(c.x - r * 0.4f, c.y + r * 0.7f, c.x - r * 1.25f, c.y + r * 0.1f, c.x - r * 1.05f, c.y - r * 0.45f)
    cubicTo(c.x - r * 0.9f, c.y - r * 0.95f, c.x - r * 0.2f, c.y - r * 0.95f, c.x, c.y - r * 0.35f)
    cubicTo(c.x + r * 0.2f, c.y - r * 0.95f, c.x + r * 0.9f, c.y - r * 0.95f, c.x + r * 1.05f, c.y - r * 0.45f)
    cubicTo(c.x + r * 1.25f, c.y + r * 0.1f, c.x + r * 0.4f, c.y + r * 0.7f, c.x, c.y + r * 1.0f)
    close()
}

/** Pip positions for 2..10 in a 3-column grid, as (x, y) in 0..1 of the pip area. */
private val PIPS: Map<Int, List<Pair<Float, Float>>> = run {
    val l = 0.0f; val m = 0.5f; val r = 1.0f
    mapOf(
        2 to listOf(m to 0f, m to 1f),
        3 to listOf(m to 0f, m to 0.5f, m to 1f),
        4 to listOf(l to 0f, r to 0f, l to 1f, r to 1f),
        5 to listOf(l to 0f, r to 0f, m to 0.5f, l to 1f, r to 1f),
        6 to listOf(l to 0f, r to 0f, l to 0.5f, r to 0.5f, l to 1f, r to 1f),
        7 to listOf(l to 0f, r to 0f, m to 0.25f, l to 0.5f, r to 0.5f, l to 1f, r to 1f),
        8 to listOf(l to 0f, r to 0f, m to 0.25f, l to 0.5f, r to 0.5f, m to 0.75f, l to 1f, r to 1f),
        9 to listOf(l to 0f, r to 0f, l to 0.333f, r to 0.333f, m to 0.5f, l to 0.667f, r to 0.667f, l to 1f, r to 1f),
        10 to listOf(l to 0f, r to 0f, m to 0.167f, l to 0.333f, r to 0.333f, l to 0.667f, r to 0.667f, m to 0.833f, l to 1f, r to 1f)
    )
}

@Composable
internal fun fixedSp(dpValue: Float): TextUnit = with(LocalDensity.current) { dpValue.dp.toSp() }

/** A rank as printed on a card ("A", "10", "K"). */
fun psRankText(rank: Rank): String = rank.label

/**
 * One card: a face (when [cardId] is a real id) or the back (a [PS_HIDDEN_CARD] / negative id). [width] sets
 * the size (the height is 1.4x); at narrow widths only the corner index and one pip are drawn. [ring] draws
 * a coloured outline (selection, hint), [dimmed] veils a card that cannot be played.
 */
@Composable
fun PsCardFace(
    cardId: Int,
    width: Dp,
    modifier: Modifier = Modifier,
    colorblind: Boolean = false,
    dimmed: Boolean = false,
    ring: Color? = null
) {
    val height = width * PS_CARD_ASPECT
    val shape = RoundedCornerShape(width * 0.09f)
    Box(
        modifier
            .size(width, height)
            .shadow(if (width < 28.dp) 1.dp else 3.dp, shape)
            .clip(shape)
    ) {
        if (cardId < 0) PsCardBack() else PsCardFront(cardId, width, colorblind)
        if (dimmed) Box(Modifier.fillMaxSize().background(Color(0x66202020)))
        if (ring != null) Box(Modifier.fillMaxSize().border(3.dp, ring, shape))
    }
}

@Composable
private fun PsCardBack() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(Brush.linearGradient(listOf(Color(0xFF0F3B52), Color(0xFF1E5A73)), Offset.Zero, Offset(w, h)))
        val inset = w * 0.07f
        drawRoundRect(Color(0xFFF2E4BE), Offset(inset, inset), Size(w - 2 * inset, h - 2 * inset), CornerRadius(w * 0.06f), style = Stroke(w * 0.022f))
        // diamond lattice
        val step = w * 0.2f
        var x = -h
        while (x < w + h) {
            drawLine(Color(0x33F2E4BE), Offset(x, 0f), Offset(x + h, h), w * 0.012f)
            drawLine(Color(0x33F2E4BE), Offset(x + h, 0f), Offset(x, h), w * 0.012f)
            x += step
        }
        // medallion
        val c = Offset(w / 2f, h / 2f)
        val r = w * 0.27f
        val gem = Path().apply {
            moveTo(c.x, c.y - r * 1.25f); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r * 1.25f); lineTo(c.x - r, c.y); close()
        }
        drawPath(gem, Color(0xFF0F3B52))
        drawPath(gem, Color(0xFFE0B04A), style = Stroke(w * 0.035f))
        drawCircle(Color(0xFFE0B04A), r * 0.28f, c)
    }
}

@Composable
private fun PsCardFront(cardId: Int, width: Dp, colorblind: Boolean) {
    val card = PsDeck.card(cardId)
    val ink = psSuitColor(card.suit, colorblind)
    val mini = width < 30.dp
    val small = width < 46.dp
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(PaperTop, PaperBottom)))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRoundRect(PaperEdge.copy(alpha = 0.55f), Offset(w * 0.04f, w * 0.04f), Size(w * 0.92f, h - w * 0.08f), CornerRadius(w * 0.06f), style = Stroke(w * 0.014f))
            if (mini) {
                drawSuit(card.suit, Offset(w / 2f, h * 0.68f), w * 0.3f, ink)
            } else {
                drawPips(card.rank, card.suit, ink, small, colorblind)
            }
        }
        // corner index: rank over a small pip, mirrored at the opposite corner
        val label = psRankText(card.rank)
        val corner: @Composable () -> Unit = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    label, color = ink, fontWeight = FontWeight.Black, maxLines = 1,
                    fontSize = fixedSp(width.value * (if (label.length > 1) 0.2f else 0.25f).let { if (mini) it * 1.05f else it }),
                    lineHeight = fixedSp(width.value * 0.26f)
                )
                if (!mini) Canvas(Modifier.size(width * 0.17f)) { drawSuit(card.suit, Offset(size.width / 2f, size.height / 2f), size.minDimension * 0.46f, ink) }
            }
        }
        Box(Modifier.align(Alignment.TopStart).padding(start = width * 0.06f, top = width * 0.04f)) { corner() }
        if (!mini) Box(Modifier.align(Alignment.BottomEnd).padding(end = width * 0.06f, bottom = width * 0.04f).rotate(180f)) { corner() }
    }
}

private fun DrawScope.drawPips(rank: Rank, suit: Suit, ink: Color, small: Boolean, colorblind: Boolean) {
    val w = size.width
    val h = size.height
    val left = w * 0.27f
    val right = w * 0.73f
    val top = h * 0.2f
    val bottom = h * 0.8f
    val value = if (rank == Rank.ACE) 1 else rank.value
    when {
        value == 1 -> {
            drawSuit(suit, Offset(w / 2f, h / 2f), w * 0.3f, ink)
            drawCircle(ink.copy(alpha = 0.35f), w * 0.4f, Offset(w / 2f, h / 2f), style = Stroke(w * 0.012f))
        }
        value >= 11 -> {
            // court cards: a framed panel with the letter, a small crest, and the suit above and below it
            val f = Offset(w * 0.2f, h * 0.17f)
            val fs = Size(w * 0.6f, h * 0.66f)
            drawRoundRect(ink.copy(alpha = 0.08f), f, fs, CornerRadius(w * 0.05f))
            drawRoundRect(ink.copy(alpha = 0.6f), f, fs, CornerRadius(w * 0.05f), style = Stroke(w * 0.016f))
            drawRoundRect(ink.copy(alpha = 0.3f), f + Offset(w * 0.03f, w * 0.03f), Size(fs.width - w * 0.06f, fs.height - w * 0.06f), CornerRadius(w * 0.03f), style = Stroke(w * 0.01f))
            drawCrest(value, Offset(w / 2f, h * 0.31f), w * 0.15f, ink)
            drawSuit(suit, Offset(w / 2f, h * 0.7f), w * 0.15f, ink)
        }
        else -> {
            val pips = PIPS[value] ?: return
            val r = w * (if (small) 0.12f else 0.115f)
            for ((px, py) in pips) {
                val cx = left + (right - left) * px
                val cy = top + (bottom - top) * py
                val flip = py > 0.5f
                if (flip && !small) {
                    // the lower half is printed upside down, as on a real card (the shapes are symmetric enough that it only matters for spades and clubs)
                    rotateScope(180f, Offset(cx, cy)) { drawSuit(suit, Offset(cx, cy), r, ink) }
                } else {
                    drawSuit(suit, Offset(cx, cy), r, ink)
                }
            }
        }
    }
}

/** A tiny emblem above the court letter: jack = plume, queen = tiara, king = crown. */
private fun DrawScope.drawCrest(value: Int, c: Offset, r: Float, ink: Color) {
    when (value) {
        13 -> {
            val p = Path().apply {
                moveTo(c.x - r, c.y + r * 0.6f); lineTo(c.x - r, c.y - r * 0.3f); lineTo(c.x - r * 0.45f, c.y + r * 0.15f)
                lineTo(c.x, c.y - r * 0.7f); lineTo(c.x + r * 0.45f, c.y + r * 0.15f); lineTo(c.x + r, c.y - r * 0.3f)
                lineTo(c.x + r, c.y + r * 0.6f); close()
            }
            drawPath(p, ink)
        }
        12 -> {
            drawArc(ink, 200f, 140f, false, Offset(c.x - r, c.y - r * 0.3f), Size(r * 2, r * 1.6f), style = Stroke(r * 0.28f))
            for (i in -1..1) drawCircle(ink, r * 0.2f, Offset(c.x + i * r * 0.62f, c.y - r * 0.2f - (if (i == 0) r * 0.25f else 0f)))
        }
        else -> {
            val p = Path().apply {
                moveTo(c.x - r * 0.7f, c.y + r * 0.7f)
                cubicTo(c.x - r * 0.9f, c.y - r * 0.2f, c.x + r * 0.1f, c.y - r * 0.9f, c.x + r * 0.8f, c.y - r * 0.7f)
                cubicTo(c.x + r * 0.6f, c.y + r * 0.1f, c.x - r * 0.1f, c.y + r * 0.5f, c.x - r * 0.7f, c.y + r * 0.7f)
                close()
            }
            drawPath(p, ink)
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Hand
// ------------------------------------------------------------------------------------------------

/**
 * A hand laid out as overlapping cards that wrap into rows when there are too many to fit the width with
 * a usable slice of each card showing. A tapped card rides up. [descriptionOf] gives each card its
 * TalkBack label. [dealKey] replays the staggered deal-in when it changes.
 */
@Composable
fun PsHand(
    cards: List<Int>,
    selected: Set<Int>,
    playable: Set<Int>?,
    hint: Set<Int>,
    enabled: Boolean,
    colorblind: Boolean,
    reduced: Boolean,
    dealKey: Any?,
    cardWidth: Dp,
    descriptionOf: (Int) -> String,
    selectedLabel: String,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
    gameSpeedScale: (Long) -> Long = { it },
    minStep: Dp = 44.dp
) {
    BoxWithConstraints(modifier) {
        val n = cards.size
        val cardW = cardWidth
        val cardH = cardW * PS_CARD_ASPECT
        val avail = maxWidth
        val maxPerRow = (((avail - cardW) / minStep).toInt() + 1).coerceAtLeast(1)
        val rows = if (n == 0) 1 else (n + maxPerRow - 1) / maxPerRow
        val perRow = if (n == 0) 1 else (n + rows - 1) / rows
        val step = if (perRow <= 1) 0.dp else minOf(maxOf(cardW * 0.62f, minStep), (avail - cardW) / (perRow - 1))
        val rowStep = cardH * 0.6f
        val lift = 16.dp
        val totalH = cardH + rowStep * (rows - 1) + lift
        Box(Modifier.width(avail).height(totalH)) {
            cards.forEachIndexed { index, id ->
                val row = index / perRow
                val col = index % perRow
                val inRow = if (row == rows - 1) n - row * perRow else perRow
                val rowWidth = cardW + step * (inRow - 1).coerceAtLeast(0)
                val startX = (avail - rowWidth) / 2
                val isSelected = id in selected
                val liftTarget = if (isSelected) 0.dp else lift
                val liftSpec: androidx.compose.animation.core.FiniteAnimationSpec<Dp> =
                    if (reduced) snap() else spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)
                val y by animateDpAsState(liftTarget, liftSpec, label = "ps-lift")
                val deal = remember(dealKey, id) { Animatable(if (reduced || dealKey == null) 1f else 0f) }
                LaunchedEffect(dealKey, id) {
                    if (!reduced && dealKey != null && deal.value < 1f) {
                        delay(gameSpeedScale((index * 38L).coerceAtMost(640L)))
                        deal.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow))
                    }
                }
                val isHint = id in hint
                val ring = when {
                    isSelected -> Color(0xFFFFD54A)
                    isHint -> Color(0xFF7CE0C3)
                    else -> null
                }
                val desc = descriptionOf(id) + if (isSelected) " $selectedLabel" else ""
                PsCardFace(
                    cardId = id, width = cardW,
                    colorblind = colorblind,
                    dimmed = playable != null && id !in playable && enabled,
                    ring = ring,
                    modifier = Modifier
                        .offset(x = startX + step * col, y = y + rowStep * row)
                        .graphicsLayer {
                            val a = deal.value
                            scaleX = 0.7f + 0.3f * a; scaleY = 0.7f + 0.3f * a; alpha = a.coerceIn(0f, 1f)
                            translationY = (1f - a) * -60f
                        }
                        .semantics { contentDescription = desc; this.selected = isSelected }
                        .clickable(role = Role.Button, onClickLabel = desc) { onTap(id) }
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Felt and seats
// ------------------------------------------------------------------------------------------------

/** The table top: radial felt with a stitched rim. [tint] colours it (President deep teal, Sevens green). */
@Composable
fun PsFelt(tint: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.radialGradient(listOf(tint, Color.Black.copy(alpha = 0.55f).compositeOver(tint))))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 9.dp.toPx()
            drawRoundRect(
                Color.White.copy(alpha = 0.22f), Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset),
                CornerRadius(22.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 9f)))
            )
        }
        content()
    }
}

val PsSeatHues = listOf(
    Color(0xFFE57373), Color(0xFF64B5F6), Color(0xFF81C784), Color(0xFFFFB74D), Color(0xFFBA68C8), Color(0xFF4DB6AC)
)

/**
 * An opponent (or any seat) as a small plate: avatar with the player's initial, name, how many cards are
 * left, a role chip, and a speech bubble for a pass. The seat on turn glows. Everything is also one
 * TalkBack node described by [description].
 */
@Composable
fun PsSeatPlate(
    name: String,
    seatIndex: Int,
    cardCount: Int,
    isTurn: Boolean,
    reduced: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    roleLabel: String? = null,
    placeLabel: String? = null,
    bubble: String? = null,
    textColor: Color = Color.White,
    plateColor: Color = Color(0x33000000),
    accentText: Color = Color(0xFFFFE082)
) {
    val hue = PsSeatHues[seatIndex % PsSeatHues.size]
    val pulse = if (isTurn && !reduced) rememberInfiniteTransition(label = "ps-turn").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "ps-turn-a"
    ).value else if (isTurn) 0.8f else 0f
    Box(modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(plateColor)
                .border(if (isTurn) 2.5.dp else 1.dp, if (isTurn) Color(0xFFFFE082).copy(alpha = 0.4f + 0.6f * pulse) else textColor.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(hue), contentAlignment = Alignment.Center) {
                    Text(name.trim().take(1).uppercase(), color = Color(0xFF1B1B1B), fontWeight = FontWeight.Black, fontSize = 15.sp)
                }
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        name, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                        fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.widthIn(max = 78.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PsCardFace(-1, 12.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("$cardCount", color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
            if (roleLabel != null || placeLabel != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    listOfNotNull(placeLabel, roleLabel).joinToString(" · "), color = accentText, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 120.dp)
                )
            }
        }
        if (bubble != null) {
            Text(
                bubble, color = Color(0xFF1B1B1B), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-8).dp)
                    .background(Color(0xFFFFF3C4), RoundedCornerShape(10.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
    }
}
