package com.gamesuite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gamesuite.games.hanafuda.HanaCard
import com.gamesuite.games.hanafuda.HanaKind
import com.gamesuite.games.hanafuda.HanaRibbon
import com.gamesuite.games.hanafuda.HanaSpecial
import com.gamesuite.games.hanafuda.HanafudaCards
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Original vector art for the 48 flower cards, drawn in Compose. Nothing here copies a commercial deck:
 * each month is a simple flower motif; the Bright and Animal cards add a named picture on top of it; the
 * Ribbon cards add a ribbon strip whose marks (waves, a diamond, bars) tell red-poetry, plain red and
 * blue apart without colour; the Plain cards are the flower alone. The class is stated three ways: a
 * border colour, a badge SHAPE in the corner (star, paw, ribbon, dot) and a text label on the card.
 */

private val Cream = Color(0xFFF7F0DD)

private fun monthPanel(month: Int): Pair<Color, Color> = when (month) {
    0 -> Color(0xFFB8D8BE) to Color(0xFF7FB18F)
    1 -> Color(0xFFF9D6DE) to Color(0xFFEFA9BC)
    2 -> Color(0xFFFFE3E8) to Color(0xFFF7B5C6)
    3 -> Color(0xFFE2D8F5) to Color(0xFFB9A4E4)
    4 -> Color(0xFFD3DDF5) to Color(0xFF9DB4E6)
    5 -> Color(0xFFFAC1CF) to Color(0xFFE88DA8)
    6 -> Color(0xFFEBD3E3) to Color(0xFFCB9DBA)
    7 -> Color(0xFF39408F) to Color(0xFF1D2159)
    8 -> Color(0xFFFFF0B0) to Color(0xFFF2CF5D)
    9 -> Color(0xFFFAD2AE) to Color(0xFFEFA06B)
    10 -> Color(0xFFD7EBE6) to Color(0xFF9CCDC3)
    else -> Color(0xFFE9DDF4) to Color(0xFFBBA5D6)
}

private fun kindBorder(kind: HanaKind): Color = when (kind) {
    HanaKind.BRIGHT -> Color(0xFFD9A400)
    HanaKind.ANIMAL -> Color(0xFF1F8A84)
    HanaKind.RIBBON -> Color(0xFFB02A3A)
    HanaKind.PLAIN -> Color(0xFF8C7C66)
}

private val RibbonRed = Color(0xFFD0343C)
private val RibbonBlue = Color(0xFF2F63B5)

/** One card face. [kindLabel] is the localized class word ("Bright"); shown only when the card is big enough to read it. */
@Composable
fun HanafudaCardFace(cardId: Int, width: Dp, height: Dp, kindLabel: String, modifier: Modifier = Modifier) {
    val card = HanafudaCards.card(cardId)
    val border = kindBorder(card.kind)
    val showLabel = width.value >= 40f
    Box(modifier.size(width, height)) {
        Canvas(Modifier.size(width, height)) { drawCardFace(card, border, showLabel) }
        if (showLabel) {
            Text(
                kindLabel.uppercase(),
                color = border,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                fontSize = with(LocalDensity.current) { (width.value * 0.125f).dp.toSp() },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = (height.value * 0.025f).dp)
            )
        }
    }
}

/** The face-down side: a deep indigo with a pale chrysanthemum-like rosette. */
@Composable
fun HanafudaCardBack(width: Dp, height: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width, height)) {
        val r = CornerRadius(size.width * 0.07f)
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF7A1F2B), Color(0xFF4A0F1A))), cornerRadius = r)
        drawRoundRect(Color(0xFFF0D9A8), Offset(size.width * 0.07f, size.width * 0.07f), Size(size.width * 0.86f, size.height - size.width * 0.14f), CornerRadius(size.width * 0.04f), style = Stroke(size.width * 0.025f))
        val c = Offset(size.width / 2f, size.height / 2f)
        val rad = size.width * 0.3f
        for (k in 0 until 12) {
            rotate(k * 30f, c) {
                drawOval(Color(0xFFF0D9A8).copy(alpha = 0.85f), Offset(c.x - rad * 0.12f, c.y - rad), Size(rad * 0.24f, rad * 0.8f), style = Stroke(size.width * 0.018f))
            }
        }
        drawCircle(Color(0xFFF0D9A8), rad * 0.18f, c)
        drawRoundRect(Color.Black.copy(alpha = 0.25f), cornerRadius = r, style = Stroke(1.2f))
    }
}

// ---- drawing helpers --------------------------------------------------------------------------------

private class Pen(val d: DrawScope, val w: Float, val h: Float) {
    fun x(v: Float) = v * w
    fun y(v: Float) = v * h
    fun o(px: Float, py: Float) = Offset(px * w, py * h)
    fun circle(color: Color, cx: Float, cy: Float, r: Float) = d.drawCircle(color, r * w, o(cx, cy))
    fun ring(color: Color, cx: Float, cy: Float, r: Float, sw: Float) = d.drawCircle(color, r * w, o(cx, cy), style = Stroke(sw * w))
    fun oval(color: Color, cx: Float, cy: Float, rx: Float, ry: Float) =
        d.drawOval(color, Offset((cx - rx) * w, (cy - ry) * h), Size(rx * 2 * w, ry * 2 * h))
    fun line(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, sw: Float) =
        d.drawLine(color, o(x1, y1), o(x2, y2), sw * w, StrokeCap.Round)
    fun rect(color: Color, x1: Float, y1: Float, x2: Float, y2: Float) =
        d.drawRect(color, o(x1, y1), Size((x2 - x1) * w, (y2 - y1) * h))
    fun poly(color: Color, vararg pts: Float) {
        val p = Path()
        for (i in pts.indices step 2) if (i == 0) p.moveTo(x(pts[i]), y(pts[i + 1])) else p.lineTo(x(pts[i]), y(pts[i + 1]))
        p.close()
        d.drawPath(p, color)
    }
    fun curve(color: Color, sw: Float, x1: Float, y1: Float, cx: Float, cy: Float, x2: Float, y2: Float) {
        val p = Path()
        p.moveTo(x(x1), y(y1))
        p.quadraticBezierTo(x(cx), y(cy), x(x2), y(y2))
        d.drawPath(p, color, style = Stroke(sw * w, cap = StrokeCap.Round))
    }
    fun petals(color: Color, cx: Float, cy: Float, n: Int, len: Float, wid: Float, start: Float = -90f) {
        for (k in 0 until n) {
            val a = (start + k * 360f / n) * PI.toFloat() / 180f
            val px = cx + cos(a) * len * 0.55f
            val py = cy + sin(a) * len * 0.55f * (w / h)
            d.rotate(start + k * 360f / n + 90f, o(px, py)) {
                drawOval(color, Offset((px - wid / 2) * w, (py - len * 0.5f * w / h) * h), Size(wid * w, len * w))
            }
        }
    }
}

private fun DrawScope.drawCardFace(card: HanaCard, border: Color, labelled: Boolean) {
    val pen = Pen(this, size.width, size.height)
    val r = size.width * 0.07f
    drawRoundRect(Cream, cornerRadius = CornerRadius(r))
    // Panel with the month's colours, leaving a band at the bottom for the class label.
    val inset = size.width * 0.07f
    val bandH = if (labelled) size.height * 0.17f else size.height * 0.06f
    val panelTop = inset
    val panelH = size.height - inset - bandH
    val (c1, c2) = monthPanel(card.month)
    drawRoundRect(Brush.verticalGradient(listOf(c1, c2), startY = panelTop, endY = panelTop + panelH), Offset(inset, panelTop), Size(size.width - inset * 2, panelH), CornerRadius(r * 0.5f))

    // Everything inside the panel is drawn in a nested coordinate space so the art scales with the panel.
    val sub = Pen(this, size.width, size.height)
    drawMonth(sub, card.month)
    when {
        card.kind == HanaKind.RIBBON -> drawRibbon(sub, card.ribbon)
        card.special != HanaSpecial.NONE -> drawSpecial(sub, card.special)
    }
    // Border by class; Bright cards get a second, inner gold line and a halo.
    val bw = if (card.kind == HanaKind.BRIGHT) size.width * 0.045f else size.width * 0.03f
    drawRoundRect(border, cornerRadius = CornerRadius(r), style = Stroke(bw))
    if (card.kind == HanaKind.BRIGHT) {
        drawRoundRect(Color(0xFFFFE9A6), Offset(bw * 1.4f, bw * 1.4f), Size(size.width - bw * 2.8f, size.height - bw * 2.8f), CornerRadius(r * 0.8f), style = Stroke(size.width * 0.012f))
    }
    drawBadge(pen, card)
}

/** The class badge: a star (bright), a paw (animal), a bow (ribbon) or a dot (plain) on a pale disc. */
private fun DrawScope.drawBadge(pen: Pen, card: HanaCard) {
    val cx = 0.2f
    val cyy = 0.135f
    pen.circle(Color.White.copy(alpha = 0.92f), cx, cyy, 0.115f)
    pen.ring(kindBorder(card.kind), cx, cyy, 0.115f, 0.016f)
    when (card.kind) {
        HanaKind.BRIGHT -> {
            for (k in 0 until 8) {
                val a = k * 45f * PI.toFloat() / 180f
                pen.line(Color(0xFFD9A400), cx, cyy, cx + cos(a) * 0.085f, cyy + sin(a) * 0.085f * pen.w / pen.h, 0.03f)
            }
            pen.circle(Color(0xFFD9A400), cx, cyy, 0.035f)
        }
        HanaKind.ANIMAL -> {
            val ink = Color(0xFF1F8A84)
            pen.oval(ink, cx, cyy + 0.02f * pen.w / pen.h, 0.045f, 0.03f)
            pen.circle(ink, cx - 0.055f, cyy - 0.02f * pen.w / pen.h, 0.02f)
            pen.circle(ink, cx - 0.02f, cyy - 0.05f * pen.w / pen.h, 0.02f)
            pen.circle(ink, cx + 0.02f, cyy - 0.05f * pen.w / pen.h, 0.02f)
            pen.circle(ink, cx + 0.055f, cyy - 0.02f * pen.w / pen.h, 0.02f)
        }
        HanaKind.RIBBON -> {
            val ink = Color(0xFFB02A3A)
            pen.poly(ink, cx - 0.07f, cyy - 0.04f * pen.w / pen.h, cx, cyy, cx - 0.07f, cyy + 0.04f * pen.w / pen.h)
            pen.poly(ink, cx + 0.07f, cyy - 0.04f * pen.w / pen.h, cx, cyy, cx + 0.07f, cyy + 0.04f * pen.w / pen.h)
            pen.circle(ink, cx, cyy, 0.025f)
        }
        HanaKind.PLAIN -> pen.circle(Color(0xFF8C7C66), cx, cyy, 0.03f)
    }
}

// ---- the twelve months ------------------------------------------------------------------------------

private fun drawMonth(p: Pen, month: Int) {
    val a = p.w / p.h // width/height, to keep round things round
    when (month) {
        0 -> { // pine: trunk and three tiers of needles
            p.rect(Color(0xFF6B4A2E), 0.47f, 0.5f, 0.55f, 0.72f)
            val g = Color(0xFF2E6B45)
            p.poly(g, 0.5f, 0.2f, 0.26f, 0.4f, 0.74f, 0.4f)
            p.poly(Color(0xFF357A50), 0.5f, 0.3f, 0.22f, 0.54f, 0.78f, 0.54f)
            p.poly(Color(0xFF2E6B45), 0.5f, 0.42f, 0.18f, 0.68f, 0.82f, 0.68f)
        }
        1 -> { // plum: a bare branch with five-petal blossoms
            p.curve(Color(0xFF6B4A2E), 0.035f, 0.1f, 0.7f, 0.45f, 0.62f, 0.9f, 0.28f)
            for ((bx, by) in listOf(0.3f to 0.62f, 0.55f to 0.5f, 0.78f to 0.36f)) {
                p.petals(Color(0xFFE5628A), bx, by, 5, 0.13f, 0.1f)
                p.circle(Color(0xFFFFD35A), bx, by, 0.025f)
            }
        }
        2 -> { // cherry: clusters of notched pink blossoms
            for ((bx, by) in listOf(0.3f to 0.34f, 0.62f to 0.3f, 0.45f to 0.55f, 0.74f to 0.58f)) {
                p.petals(Color(0xFFFFFFFF).copy(alpha = 0.9f), bx, by, 5, 0.17f, 0.12f)
                p.petals(Color(0xFFF08BA6), bx, by, 5, 0.12f, 0.08f)
                p.circle(Color(0xFFB03060), bx, by, 0.02f)
            }
        }
        3 -> { // wisteria: hanging clusters of violet blossoms
            p.line(Color(0xFF6B4A2E), 0.1f, 0.18f, 0.9f, 0.18f, 0.03f)
            for ((i, bx) in listOf(0.27f, 0.5f, 0.73f).withIndex()) {
                var r = 0.065f
                var yy = 0.24f
                repeat(6) {
                    p.circle(Color(0xFF8F6FD0), bx + (if (it % 2 == 0) -0.02f else 0.02f), yy, r)
                    yy += 0.085f + i * 0.004f
                    r *= 0.88f
                }
            }
        }
        4 -> { // iris: blades and a three-petal flower over water
            p.poly(Color(0xFF2F8A52), 0.3f, 0.7f, 0.36f, 0.2f, 0.42f, 0.7f)
            p.poly(Color(0xFF3E9C60), 0.6f, 0.7f, 0.68f, 0.24f, 0.72f, 0.7f)
            p.oval(Color(0xFF5B3FB3), 0.5f, 0.36f, 0.075f, 0.13f * a)
            p.oval(Color(0xFF7A5CD0), 0.38f, 0.46f, 0.075f, 0.1f * a)
            p.oval(Color(0xFF7A5CD0), 0.62f, 0.46f, 0.075f, 0.1f * a)
            p.line(Color(0xFFFFE066), 0.5f, 0.34f, 0.5f, 0.52f, 0.015f)
            p.curve(Color(0xFF6C8FD8), 0.02f, 0.1f, 0.72f, 0.3f, 0.66f, 0.5f, 0.72f)
            p.curve(Color(0xFF6C8FD8), 0.02f, 0.5f, 0.72f, 0.7f, 0.78f, 0.9f, 0.72f)
        }
        5 -> { // peony: layered round petals and leaves
            p.poly(Color(0xFF2F8A52), 0.18f, 0.62f, 0.3f, 0.5f, 0.38f, 0.68f)
            p.poly(Color(0xFF2F8A52), 0.82f, 0.62f, 0.7f, 0.5f, 0.62f, 0.68f)
            p.circle(Color(0xFFC21E56), 0.5f, 0.44f, 0.27f)
            p.circle(Color(0xFFE0457A), 0.5f, 0.44f, 0.2f)
            p.circle(Color(0xFFF58FB0), 0.5f, 0.44f, 0.13f)
            p.circle(Color(0xFFFFD35A), 0.5f, 0.44f, 0.04f)
        }
        6 -> { // bush clover: arching stems dotted with small blossoms
            for ((i, sx) in listOf(0.2f, 0.45f, 0.7f).withIndex()) {
                p.curve(Color(0xFF6E8B3D), 0.02f, sx, 0.75f, sx + 0.1f, 0.2f + i * 0.06f, sx + 0.3f, 0.3f + i * 0.04f)
                for (t in 1..5) {
                    val tt = t / 6f
                    val bx = (1 - tt) * (1 - tt) * sx + 2 * (1 - tt) * tt * (sx + 0.1f) + tt * tt * (sx + 0.3f)
                    val by = (1 - tt) * (1 - tt) * 0.75f + 2 * (1 - tt) * tt * (0.2f + i * 0.06f) + tt * tt * (0.3f + i * 0.04f)
                    p.circle(Color(0xFFC0407A), bx, by, 0.028f)
                }
            }
        }
        7 -> { // pampas: grass plumes under a night sky
            p.circle(Color(0xFFF2E6A8), 0.72f, 0.26f, 0.15f)
            p.oval(Color(0xFF14163A), 0.5f, 0.82f, 0.62f, 0.2f * a)
            for (sx in listOf(0.22f, 0.4f, 0.58f)) {
                p.curve(Color(0xFFD8C99A), 0.04f, sx, 0.78f, sx + 0.04f, 0.42f, sx + 0.14f, 0.28f)
                for (t in 1..4) p.line(Color(0xFFEFE3BE), sx + 0.03f * t, 0.5f - 0.05f * t, sx + 0.03f * t + 0.07f, 0.47f - 0.05f * t, 0.016f)
            }
        }
        8 -> { // chrysanthemum: a many-petalled flower
            p.petals(Color(0xFFF0A81E), 0.5f, 0.44f, 16, 0.36f, 0.075f)
            p.petals(Color(0xFFFFE27A), 0.5f, 0.44f, 12, 0.26f, 0.08f, start = -75f)
            p.circle(Color(0xFFB87812), 0.5f, 0.44f, 0.05f)
        }
        9 -> { // maple: three palmate leaves
            for ((lx, ly, sc) in listOf(Triple(0.3f, 0.35f, 1f), Triple(0.68f, 0.3f, 0.85f), Triple(0.5f, 0.6f, 1.1f))) mapleLeaf(p, lx, ly, 0.17f * sc, Color(0xFFC8321F))
        }
        10 -> { // willow: long drooping strands
            p.line(Color(0xFF6B4A2E), 0.12f, 0.16f, 0.88f, 0.16f, 0.03f)
            for (i in 0 until 6) {
                val sx = 0.18f + i * 0.13f
                p.curve(Color(0xFF3F9A6E), 0.022f, sx, 0.16f, sx + 0.07f, 0.45f, sx - 0.02f, 0.74f)
                p.line(Color(0xFF6BBF93), sx + 0.03f, 0.4f, sx + 0.07f, 0.44f, 0.014f)
                p.line(Color(0xFF6BBF93), sx + 0.01f, 0.58f, sx + 0.05f, 0.62f, 0.014f)
            }
        }
        else -> { // paulownia: a broad leaf and upright flower spikes
            p.poly(Color(0xFF2F8A52), 0.5f, 0.78f, 0.2f, 0.5f, 0.34f, 0.42f, 0.5f, 0.52f, 0.66f, 0.42f, 0.8f, 0.5f)
            for (sx in listOf(0.34f, 0.5f, 0.66f)) {
                var yy = 0.42f
                repeat(4) {
                    p.oval(Color(0xFF9A6FCF), sx, yy, 0.05f, 0.04f * a)
                    yy -= 0.07f
                }
            }
        }
    }
}

private fun mapleLeaf(p: Pen, cx: Float, cy: Float, r: Float, color: Color) {
    val a = p.w / p.h
    val pts = ArrayList<Float>()
    for (k in 0 until 10) {
        val ang = (-90f + k * 36f) * PI.toFloat() / 180f
        val rr = if (k % 2 == 0) r else r * 0.45f
        pts += cx + cos(ang) * rr
        pts += cy + sin(ang) * rr * a
    }
    p.poly(color, *pts.toFloatArray())
    p.line(Color(0xFF7A1A10), cx, cy, cx, cy + r * a * 1.1f, 0.012f)
}

// ---- named pictures ---------------------------------------------------------------------------------

private fun drawSpecial(p: Pen, sp: HanaSpecial) {
    val a = p.w / p.h
    when (sp) {
        HanaSpecial.CRANE -> {
            p.circle(Color(0xFFD7263D), 0.7f, 0.3f, 0.11f)
            p.oval(Color.White, 0.42f, 0.55f, 0.16f, 0.1f * a)
            p.curve(Color.White, 0.045f, 0.52f, 0.5f, 0.6f, 0.38f, 0.56f, 0.28f)
            p.circle(Color.White, 0.56f, 0.27f, 0.045f)
            p.circle(Color(0xFFD7263D), 0.56f, 0.24f, 0.015f)
            p.line(Color(0xFF222222), 0.6f, 0.28f, 0.68f, 0.3f, 0.012f)
            p.poly(Color(0xFF222222), 0.3f, 0.5f, 0.12f, 0.46f, 0.26f, 0.58f)
            p.line(Color(0xFF222222), 0.42f, 0.62f, 0.42f, 0.74f, 0.012f)
        }
        HanaSpecial.CURTAIN -> {
            for (i in 0 until 8) p.rect(if (i % 2 == 0) Color(0xFFD7263D) else Color.White, 0.12f + i * 0.095f, 0.14f, 0.12f + (i + 1) * 0.095f, 0.46f)
            for (i in 0 until 8) p.circle(if (i % 2 == 0) Color(0xFFD7263D) else Color.White, 0.16f + i * 0.095f, 0.46f, 0.047f)
            p.line(Color(0xFF3A2E22), 0.1f, 0.13f, 0.9f, 0.13f, 0.02f)
        }
        HanaSpecial.MOON -> {
            p.circle(Color(0xFFFFC94D).copy(alpha = 0.35f), 0.5f, 0.38f, 0.3f)
            p.circle(Color(0xFFFFE08A), 0.5f, 0.38f, 0.22f)
            p.circle(Color(0xFFFFD35A), 0.5f, 0.38f, 0.17f)
        }
        HanaSpecial.RAIN_MAN -> {
            p.oval(Color(0xFFD7263D), 0.5f, 0.3f, 0.26f, 0.13f * a)
            p.line(Color(0xFF3A2E22), 0.5f, 0.3f, 0.5f, 0.56f, 0.014f)
            p.circle(Color(0xFF3A2E22), 0.5f, 0.6f, 0.035f)
            p.line(Color(0xFF3A2E22), 0.5f, 0.64f, 0.5f, 0.74f, 0.022f)
            for (i in 0 until 5) p.line(Color(0xFF3A6EA5), 0.14f + i * 0.17f, 0.12f, 0.1f + i * 0.17f, 0.2f, 0.012f)
        }
        HanaSpecial.PHOENIX -> {
            p.oval(Color(0xFFC9A227), 0.42f, 0.42f, 0.12f, 0.1f * a)
            p.circle(Color(0xFFC9A227), 0.55f, 0.32f, 0.055f)
            p.poly(Color(0xFFD7263D), 0.58f, 0.27f, 0.64f, 0.24f, 0.6f, 0.32f)
            for ((i, c) in listOf(Color(0xFFD7263D), Color(0xFF2F63B5), Color(0xFF2F8A52), Color(0xFFC9A227)).withIndex()) {
                p.curve(c, 0.03f, 0.33f, 0.46f, 0.18f, 0.62f + i * 0.03f, 0.28f - i * 0.04f, 0.76f)
            }
        }
        HanaSpecial.BUSH_WARBLER, HanaSpecial.CUCKOO, HanaSpecial.SWALLOW -> {
            val body = when (sp) {
                HanaSpecial.BUSH_WARBLER -> Color(0xFF8A9A3B)
                HanaSpecial.CUCKOO -> Color(0xFF7C8794)
                else -> Color(0xFF1F3A7A)
            }
            if (sp == HanaSpecial.CUCKOO) p.circle(Color(0xFFFFF3C0), 0.74f, 0.24f, 0.07f)
            p.oval(body, 0.46f, 0.5f, 0.17f, 0.09f * a)
            p.circle(body, 0.62f, 0.44f, 0.065f)
            p.poly(Color(0xFFE59B22), 0.67f, 0.43f, 0.76f, 0.46f, 0.67f, 0.48f)
            p.circle(Color.White, 0.64f, 0.42f, 0.014f)
            if (sp == HanaSpecial.SWALLOW) {
                p.poly(body, 0.3f, 0.5f, 0.12f, 0.42f, 0.2f, 0.52f)
                p.poly(body, 0.3f, 0.52f, 0.12f, 0.62f, 0.22f, 0.5f)
            } else {
                p.poly(body, 0.3f, 0.5f, 0.12f, 0.6f, 0.3f, 0.56f)
            }
        }
        HanaSpecial.BRIDGE -> {
            for (i in 0 until 7) p.poly(Color(0xFF8B5A2B), 0.12f + i * 0.11f, 0.54f, 0.2f + i * 0.11f, 0.54f, 0.2f + i * 0.11f, 0.6f, 0.12f + i * 0.11f, 0.6f)
            p.curve(Color(0xFF6B4A2E), 0.025f, 0.1f, 0.54f, 0.5f, 0.28f, 0.9f, 0.54f)
            for (i in 1..4) p.line(Color(0xFF6B4A2E), 0.1f + i * 0.16f, 0.58f, 0.1f + i * 0.16f, 0.72f, 0.02f)
        }
        HanaSpecial.BUTTERFLIES -> {
            for ((bx, by, sc) in listOf(Triple(0.35f, 0.55f, 1f), Triple(0.68f, 0.3f, 0.75f))) {
                p.oval(Color(0xFF3A6EA5), bx - 0.07f * sc, by - 0.05f * sc * a, 0.075f * sc, 0.07f * sc * a)
                p.oval(Color(0xFF3A6EA5), bx + 0.07f * sc, by - 0.05f * sc * a, 0.075f * sc, 0.07f * sc * a)
                p.oval(Color(0xFFF2A33A), bx - 0.05f * sc, by + 0.05f * sc * a, 0.055f * sc, 0.05f * sc * a)
                p.oval(Color(0xFFF2A33A), bx + 0.05f * sc, by + 0.05f * sc * a, 0.055f * sc, 0.05f * sc * a)
                p.line(Color(0xFF222222), bx, by - 0.08f * sc * a, bx, by + 0.09f * sc * a, 0.014f)
            }
        }
        HanaSpecial.BOAR -> {
            p.oval(Color(0xFF6B4226), 0.46f, 0.52f, 0.22f, 0.12f * a)
            p.circle(Color(0xFF6B4226), 0.7f, 0.5f, 0.09f)
            p.poly(Color.White, 0.76f, 0.52f, 0.84f, 0.5f, 0.78f, 0.58f)
            p.poly(Color(0xFF3F2514), 0.64f, 0.4f, 0.68f, 0.34f, 0.7f, 0.42f)
            p.circle(Color.White, 0.72f, 0.47f, 0.014f)
            for (lx in listOf(0.32f, 0.4f, 0.54f, 0.6f)) p.line(Color(0xFF3F2514), lx, 0.62f, lx, 0.74f, 0.022f)
        }
        HanaSpecial.GEESE -> {
            for ((gx, gy) in listOf(0.3f to 0.3f, 0.55f to 0.2f, 0.7f to 0.42f)) {
                p.curve(Color(0xFFEFE3BE), 0.026f, gx - 0.1f, gy + 0.03f, gx, gy - 0.05f, gx, gy)
                p.curve(Color(0xFFEFE3BE), 0.026f, gx + 0.1f, gy + 0.03f, gx, gy - 0.05f, gx, gy)
            }
        }
        HanaSpecial.SAKE_CUP -> {
            p.poly(Color(0xFFD7263D), 0.2f, 0.4f, 0.8f, 0.4f, 0.7f, 0.62f, 0.3f, 0.62f)
            p.rect(Color(0xFFD7263D), 0.4f, 0.62f, 0.6f, 0.68f)
            p.rect(Color(0xFFC9A227), 0.2f, 0.38f, 0.8f, 0.42f)
            p.rect(Color(0xFFC9A227), 0.34f, 0.68f, 0.66f, 0.71f)
            p.oval(Color(0xFFFFE8A8), 0.5f, 0.44f, 0.18f, 0.025f * a)
        }
        HanaSpecial.DEER -> {
            p.oval(Color(0xFFA9692F), 0.44f, 0.58f, 0.18f, 0.09f * a)
            p.line(Color(0xFFA9692F), 0.58f, 0.52f, 0.66f, 0.38f, 0.045f)
            p.circle(Color(0xFFA9692F), 0.68f, 0.36f, 0.055f)
            p.line(Color(0xFF6B3E15), 0.67f, 0.3f, 0.6f, 0.16f, 0.014f)
            p.line(Color(0xFF6B3E15), 0.67f, 0.3f, 0.76f, 0.16f, 0.014f)
            p.line(Color(0xFF6B3E15), 0.64f, 0.23f, 0.56f, 0.22f, 0.012f)
            p.line(Color(0xFF6B3E15), 0.72f, 0.23f, 0.8f, 0.22f, 0.012f)
            for (lx in listOf(0.32f, 0.38f, 0.5f, 0.56f)) p.line(Color(0xFF6B3E15), lx, 0.64f, lx, 0.76f, 0.02f)
            p.circle(Color.White, 0.36f, 0.58f, 0.012f)
        }
        else -> Unit
    }
}

private fun drawRibbon(p: Pen, ribbon: HanaRibbon) {
    val color = if (ribbon == HanaRibbon.BLUE) RibbonBlue else RibbonRed
    val l = 0.36f
    val r = 0.64f
    // A tanzaku strip with a swallow-tail cut at the bottom.
    p.poly(color, l, 0.14f, r, 0.14f, r, 0.74f, 0.5f, 0.66f, l, 0.74f)
    p.rect(Color.White.copy(alpha = 0.25f), l, 0.14f, l + 0.03f, 0.7f)
    when (ribbon) {
        HanaRibbon.RED_POETRY -> for (i in 0 until 3) {
            val y = 0.24f + i * 0.14f
            p.curve(Color.White, 0.016f, l + 0.05f, y, 0.5f, y - 0.05f, r - 0.05f, y)
        }
        HanaRibbon.RED -> {
            p.poly(Color.White, 0.5f, 0.32f, 0.56f, 0.4f, 0.5f, 0.48f, 0.44f, 0.4f)
        }
        HanaRibbon.BLUE -> for (i in 0 until 3) {
            val y = 0.26f + i * 0.13f
            p.line(Color.White, l + 0.05f, y, r - 0.05f, y, 0.02f)
        }
        HanaRibbon.NONE -> Unit
    }
}
