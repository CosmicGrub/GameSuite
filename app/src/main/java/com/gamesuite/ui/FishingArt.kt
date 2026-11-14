package com.gamesuite.ui

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
import com.gamesuite.games.fishing.FishSpecies
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * Original vector art for Fishing: a painted picture-book pond seen from above. Fish are drawn from
 * a few numbers (body proportions, a colour, a pattern), so one function paints all fifteen kinds
 * and animates a wiggle. Kinds are told apart by shape and pattern as well as colour.
 */

internal class FishingPalette(
    val shallow: Color, val mid: Color, val deep: Color, val bank: Color, val bg: Color, val text: Color, val card: Color,
    val accent: Color, val gold: Color, val wood: Color, val good: Color, val warn: Color, val bad: Color
)

internal fun fishingPalette(dark: Boolean) = if (!dark) FishingPalette(
    shallow = Color(0xFF7CC8D8), mid = Color(0xFF4FA3C7), deep = Color(0xFF2F76A8), bank = Color(0xFF7FB069), bg = Color(0xFFEFF6F4),
    text = Color(0xFF14262E), card = Color(0xFFDCEBEA), accent = Color(0xFF0F6E86), gold = Color(0xFFE0A100), wood = Color(0xFF8A6238),
    good = Color(0xFF2E9B5B), warn = Color(0xFFE8A000), bad = Color(0xFFD64541)
) else FishingPalette(
    shallow = Color(0xFF2F7F95), mid = Color(0xFF225F83), deep = Color(0xFF173F63), bank = Color(0xFF3F6B3A), bg = Color(0xFF0F1A1F),
    text = Color(0xFFE3F1F3), card = Color(0xFF1C2D33), accent = Color(0xFF55C7E0), gold = Color(0xFFFFC83D), wood = Color(0xFF9B7447),
    good = Color(0xFF4FD08A), warn = Color(0xFFFFC233), bad = Color(0xFFFF6B66)
)

private val fishHues = listOf(
    Color(0xFFB8C4CC), Color(0xFF4F8F4F), Color(0xFF7A9A3A), Color(0xFFC9B36A), Color(0xFF8E9AA6), Color(0xFFA4793E), Color(0xFFD08BA0),
    Color(0xFF5E7A52), Color(0xFF6B5B4B), Color(0xFF6E8F5A), Color(0xFF3D4A3A), Color(0xFF7A4A2A), Color(0xFF6C7A8A), Color(0xFFE8782A), Color(0xFFC9D8F0)
)

internal fun fishColor(sp: FishSpecies): Color = fishHues[sp.hue % fishHues.size]

/**
 * A fish centred at [c], [len] pixels long, nose toward screen angle [angle] (radians), body wiggling by
 * [wiggle] -1..1. [sp] picks the proportions ([FishSpecies.shape]), colour and pattern.
 */
internal fun DrawScope.drawFish(c: Offset, len: Float, sp: FishSpecies, angle: Double, wiggle: Float, alpha: Float = 1f) {
    val base = fishColor(sp)
    val shape = sp.shape
    if (shape == 6) { drawBoot(c, len, angle, alpha); return }
    val bodyH = len * when (shape) { 0 -> 0.17f; 1 -> 0.46f; 2 -> 0.30f; 3 -> 0.34f; 4 -> 0.20f; else -> 0.10f }
    val deg = Math.toDegrees(angle).toFloat()
    rotate(deg, c) {
        val tailW = len * 0.2f
        val wob = wiggle * bodyH * 0.35f
        // tail
        val tail = Path().apply {
            moveTo(c.x - len * 0.38f, c.y + wob * 0.5f)
            lineTo(c.x - len * 0.5f - tailW * 0.3f, c.y - bodyH * 0.65f + wob)
            lineTo(c.x - len * 0.46f, c.y + wob * 0.5f)
            lineTo(c.x - len * 0.5f - tailW * 0.3f, c.y + bodyH * 0.65f + wob)
            close()
        }
        drawPath(tail, clShade(base, -0.2f), alpha = alpha)
        // dorsal and belly fins
        val fin = Path().apply {
            moveTo(c.x - len * 0.1f, c.y - bodyH * 0.45f); lineTo(c.x - len * 0.22f, c.y - bodyH * 0.95f); lineTo(c.x - len * 0.28f, c.y - bodyH * 0.4f); close()
        }
        drawPath(fin, clShade(base, -0.3f), alpha = alpha)
        // body
        val body = Path().apply {
            moveTo(c.x + len * 0.5f, c.y)
            cubicTo(c.x + len * 0.3f, c.y - bodyH * 0.75f, c.x - len * 0.2f, c.y - bodyH * 0.65f + wob * 0.4f, c.x - len * 0.4f, c.y + wob * 0.5f)
            cubicTo(c.x - len * 0.2f, c.y + bodyH * 0.65f + wob * 0.4f, c.x + len * 0.3f, c.y + bodyH * 0.75f, c.x + len * 0.5f, c.y)
            close()
        }
        drawPath(body, Brush.verticalGradient(listOf(clShade(base, 0.25f), base, clShade(base, 0.45f)), c.y - bodyH, c.y + bodyH), alpha = alpha)
        drawPath(body, clShade(base, -0.5f).copy(alpha = 0.7f * alpha), style = Stroke(len * 0.012f + 1f))
        val mark = clShade(base, -0.45f).copy(alpha = 0.55f * alpha)
        when (sp.pattern) {
            1 -> for (i in 0 until 4) drawLine(mark, Offset(c.x + len * (0.2f - i * 0.12f), c.y - bodyH * 0.55f), Offset(c.x + len * (0.2f - i * 0.12f), c.y + bodyH * 0.55f), strokeWidth = len * 0.03f)
            2 -> for (i in 0 until 5) drawCircle(mark, len * 0.025f, Offset(c.x + len * (0.28f - i * 0.1f), c.y + (if (i % 2 == 0) -1 else 1) * bodyH * 0.22f))
            3 -> { drawCircle(Color.White.copy(alpha = 0.55f * alpha), len * 0.06f, Offset(c.x - len * 0.05f, c.y - bodyH * 0.2f)); drawCircle(Color.White.copy(alpha = 0.55f * alpha), len * 0.05f, Offset(c.x + len * 0.15f, c.y + bodyH * 0.15f)) }
        }
        // eye
        drawCircle(Color.White.copy(alpha = alpha), len * 0.04f + 1f, Offset(c.x + len * 0.34f, c.y - bodyH * 0.12f))
        drawCircle(Color(0xFF101010).copy(alpha = alpha), len * 0.022f + 0.8f, Offset(c.x + len * 0.35f, c.y - bodyH * 0.12f))
    }
}

private fun DrawScope.drawBoot(c: Offset, len: Float, angle: Double, alpha: Float) {
    rotate(Math.toDegrees(angle).toFloat(), c) {
        val leather = Color(0xFF6B4A2B).copy(alpha = alpha)
        val h = len * 0.42f
        val p = Path().apply {
            moveTo(c.x - len * 0.30f, c.y - h * 0.5f); lineTo(c.x - len * 0.30f, c.y + h * 0.1f)
            lineTo(c.x + len * 0.10f, c.y + h * 0.1f); lineTo(c.x + len * 0.12f, c.y + h * 0.5f)
            lineTo(c.x + len * 0.5f, c.y + h * 0.5f); lineTo(c.x + len * 0.5f, c.y + h * 0.15f)
            lineTo(c.x + len * 0.2f, c.y - h * 0.1f); lineTo(c.x + len * 0.05f, c.y - h * 0.5f); close()
        }
        drawPath(p, leather)
        drawPath(p, Color(0xFF2F1F10).copy(alpha = alpha), style = Stroke(len * 0.02f + 1f))
        drawRect(Color(0xFF9A6A3A).copy(alpha = alpha), Offset(c.x - len * 0.30f, c.y - h * 0.5f), Size(len * 0.35f, h * 0.18f))
        for (i in 0 until 3) drawLine(Color.White.copy(alpha = 0.7f * alpha), Offset(c.x + len * (0.0f + i * 0.04f), c.y - h * 0.02f - i * h * 0.09f), Offset(c.x + len * (0.06f + i * 0.04f), c.y - h * 0.02f - i * h * 0.09f), strokeWidth = len * 0.015f + 1f)
    }
}

/** Reeds, rocks or lily pads on a water cell. [cover]: 0 open, 1 reeds, 2 rocks, 3 lilies. */
internal fun DrawScope.drawCover(cover: Int, c: Offset, r: Float, sway: Float) {
    when (cover) {
        1 -> for (i in -2..2) {
            val x = c.x + i * r * 0.22f
            val h = r * (0.7f + 0.12f * (i % 2))
            drawLine(Color(0xFF3F7A3A), Offset(x, c.y + r * 0.4f), Offset(x + sway * r * 0.1f * (1 + abs(i)), c.y + r * 0.4f - h), strokeWidth = r * 0.07f, cap = StrokeCap.Round)
            drawOval(Color(0xFF7A5A32), Offset(x - r * 0.05f + sway * r * 0.1f * (1 + abs(i)), c.y + r * 0.4f - h - r * 0.12f), Size(r * 0.1f, r * 0.24f))
        }
        2 -> {
            drawOval(Color(0xFF7D868C), Offset(c.x - r * 0.45f, c.y - r * 0.15f), Size(r * 0.6f, r * 0.42f))
            drawOval(Color(0xFFA2ABB0), Offset(c.x - r * 0.12f, c.y - r * 0.32f), Size(r * 0.5f, r * 0.38f))
            drawOval(Color(0xFF616A70), Offset(c.x - r * 0.05f, c.y + r * 0.1f), Size(r * 0.42f, r * 0.28f))
        }
        3 -> {
            for ((dx, dy) in listOf(-0.3f to -0.1f, 0.25f to 0.2f, 0.1f to -0.3f)) {
                val o = Offset(c.x + dx * r, c.y + dy * r)
                drawCircle(Color(0xFF4F9A4A), r * 0.24f, o)
                drawLine(Color(0xFF2F6A2F), o, Offset(o.x + r * 0.24f, o.y - r * 0.05f), strokeWidth = r * 0.06f)
            }
            drawCircle(Color(0xFFF4A6C8), r * 0.09f, Offset(c.x + r * 0.1f, c.y - r * 0.3f))
        }
    }
}

/** A fishing float: red cap, white body. [dip] 0..1 pulls it under. */
internal fun DrawScope.drawBobber(c: Offset, r: Float, dip: Float) {
    val y = c.y + r * 0.9f * dip
    drawCircle(Color(0x33FFFFFF), r * 1.8f, Offset(c.x, c.y + r * 0.4f), style = Stroke(r * 0.12f))
    drawCircle(Color.White, r, Offset(c.x, y))
    drawArc(Color(0xFFD64541), 180f, 180f, true, Offset(c.x - r, y - r), Size(r * 2, r * 2))
    drawCircle(Color(0x66000000), r, Offset(c.x, y), style = Stroke(r * 0.12f))
    drawLine(Color(0xFF222222), Offset(c.x, y - r), Offset(c.x, y - r * 1.8f), strokeWidth = r * 0.14f)
}

/** The dock and angler at the bottom of the scene; returns where the rod tip is. */
internal fun DrawScope.drawDock(c: Offset, unit: Float, pal: FishingPalette, seatColor: Color, lean: Float): Offset {
    drawRoundRect(pal.wood, Offset(c.x - unit * 70f, c.y - unit * 12f), Size(unit * 140f, unit * 50f), CornerRadius(unit * 6f))
    for (i in 0 until 6) drawLine(clShade(pal.wood, -0.3f), Offset(c.x - unit * 70f + i * unit * 28f, c.y - unit * 12f), Offset(c.x - unit * 70f + i * unit * 28f, c.y + unit * 38f), strokeWidth = unit * 2f)
    // angler seen from above: shoulders, hat
    drawOval(clShade(seatColor, -0.1f), Offset(c.x - unit * 20f, c.y - unit * 22f), Size(unit * 40f, unit * 22f))
    drawCircle(Color(0xFFF0C9A0), unit * 10f, Offset(c.x, c.y - unit * 12f))
    drawCircle(Color(0xFFE8D6A0), unit * 15f, Offset(c.x, c.y - unit * 14f), style = Stroke(unit * 4f))
    val tip = Offset(c.x + unit * 12f + lean * unit * 6f, c.y - unit * 70f)
    drawLine(Color(0xFF3A2A18), Offset(c.x + unit * 8f, c.y - unit * 14f), tip, strokeWidth = unit * 3.2f, cap = StrokeCap.Round)
    return tip
}

/** A water ripple that grows and fades with [t] 0..1. */
internal fun DrawScope.drawRipple(c: Offset, r: Float, t: Float, color: Color = Color.White) {
    drawCircle(color.copy(alpha = 0.6f * (1f - t)), r * (0.5f + t), c, style = Stroke(r * 0.1f))
}

internal fun angleTo(from: Offset, to: Offset): Double = Math.atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())

internal fun wiggleOf(t: Float, speed: Float = 6f): Float = sin(t * speed * 2f * PI.toFloat())

internal fun cosf(x: Float) = cos(x)
