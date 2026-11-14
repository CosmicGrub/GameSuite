package com.gamesuite.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Original vector art for Toy Boxing: wind-up toy boxers (a painted tin-toy look: glossy rounded shapes with
 * a rim and a specular highlight) in a little ring with ropes, a mat and a crowd of silhouettes. Everything is
 * drawn from a handful of numbers (a pose), so the same code animates every punch. Fighters are told apart
 * by colour AND a badge shape on the trunks AND their name tag, never colour alone.
 */

/** Okabe-Ito pair: orange-red and blue stay distinct for every common kind of colour blindness. */
internal val BoxerColors = listOf(Color(0xFFD55E00), Color(0xFF0072B2))

internal class BoxerPose(
    /** Glove positions as fractions of the boxer's height: x forward (toward the rival), y up from the floor. */
    val leadX: Float, val leadY: Float, val rearX: Float, val rearY: Float,
    /** Lean of the whole body (negative = away from the rival), a squash factor, and a head tilt in radians. */
    val lean: Float, val squash: Float, val headTilt: Float,
    /** True while a hook is winding up: draw the warning flash. */
    val tell: Boolean, val down: Boolean, val dizzy: Boolean
)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
private fun ease(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3 - 2 * x) }

/**
 * The pose of a boxer from its engine action ([action]), the time since that action began ([ageMs]) and
 * whether the guard is up. [bob] (-1..1) is the idle sway; pass 0 under Reduced Motion.
 */
internal fun boxerPose(action: String, ageMs: Float, guard: Boolean, bob: Float, won: Boolean = false): BoxerPose {
    val baseLead = Offset(0.30f, 0.62f + 0.012f * bob)
    val baseRear = Offset(0.17f, 0.56f - 0.012f * bob)
    return when (action) {
        "JAB" -> {
            val p = when {
                ageMs < 100f -> ease(ageMs / 100f)
                ageMs < 200f -> 1f
                else -> 1f - ease((ageMs - 200f) / 100f)
            }
            BoxerPose(lerp(baseLead.x, 0.98f, p), lerp(baseLead.y, 0.63f, p), baseRear.x, baseRear.y, 0.06f * p, 1f, 0f, false, false, false)
        }
        "HOOK" -> {
            val x: Float; val y: Float; val lean: Float
            when {
                ageMs < 450f -> { val p = ease(ageMs / 450f); x = lerp(baseRear.x, -0.30f, p); y = lerp(baseRear.y, 0.50f, p); lean = -0.10f * p }
                ageMs < 600f -> {
                    val p = (ageMs - 450f) / 150f
                    x = lerp(-0.30f, 0.88f, ease(p)); y = lerp(0.50f, 0.66f, sin((p * PI).toFloat()).coerceIn(0f, 1f) * 0.6f + p * 0.1f); lean = lerp(-0.10f, 0.12f, p)
                }
                else -> { val p = ease((ageMs - 600f) / 250f); x = lerp(0.88f, baseRear.x, p); y = lerp(0.66f, baseRear.y, p); lean = lerp(0.12f, 0f, p) }
            }
            BoxerPose(baseLead.x, baseLead.y, x, y, lean, 1f, 0f, tell = ageMs in 120f..460f, down = false, dizzy = false)
        }
        "DODGE" -> {
            val p = when {
                ageMs < 120f -> ease(ageMs / 120f)
                ageMs < 420f -> 1f
                else -> 1f - ease((ageMs - 420f) / 230f)
            }
            BoxerPose(0.12f, 0.50f, 0.05f, 0.46f, -0.32f * p, lerp(1f, 0.86f, p), -0.15f * p, false, false, false)
        }
        "STAGGER" -> BoxerPose(0.10f, 0.34f, 0.02f, 0.30f, -0.12f, 0.95f, 0.35f, false, false, true)
        "DOWN" -> BoxerPose(0.1f, 0.1f, 0f, 0.1f, 0f, 1f, 0f, false, true, true)
        else -> if (won) BoxerPose(0.25f, 1.05f + 0.03f * bob, 0.1f, 1.0f - 0.03f * bob, 0f, 1f, 0f, false, false, false)
        else if (guard) BoxerPose(0.34f, 0.80f, 0.24f, 0.74f, -0.03f, 0.98f, 0f, false, false, false)
        else BoxerPose(baseLead.x, baseLead.y, baseRear.x, baseRear.y, 0f, 1f, 0f, false, false, false)
    }
}

internal fun DrawScope.drawRing(accent: Color, dark: Boolean, crowdPhase: Float, reduced: Boolean) {
    val w = size.width; val h = size.height
    // Back wall and spotlight.
    drawRect(Brush.verticalGradient(listOf(if (dark) Color(0xFF0E1220) else Color(0xFF26304F), if (dark) Color(0xFF1A2038) else Color(0xFF3B4770)), 0f, h * 0.7f))
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), Offset(w / 2, h * 0.2f), w * 0.5f),
        w * 0.5f, Offset(w / 2, h * 0.2f)
    )
    // Crowd: two rows of simple heads and shoulders.
    for (row in 0..1) {
        val y = h * (0.24f + row * 0.09f)
        val n = 14
        for (i in 0 until n) {
            val x = w * (i + 0.5f + 0.35f * row) / n
            val bounce = if (reduced) 0f else sin(crowdPhase * 2.2f + i * 1.7f + row) * h * 0.006f
            val tone = Color(0xFF000000).copy(alpha = 0.34f + 0.12f * row)
            drawCircle(tone, h * 0.024f, Offset(x, y + bounce))
            drawRoundRect(tone, Offset(x - h * 0.034f, y + h * 0.02f + bounce), Size(h * 0.068f, h * 0.06f), androidx.compose.ui.geometry.CornerRadius(h * 0.03f))
        }
    }
    // Mat.
    val matTop = h * 0.62f
    drawRect(Brush.verticalGradient(listOf(Color(0xFFE9E0C8), Color(0xFFC9BB94)), matTop, h), Offset(0f, matTop), Size(w, h - matTop))
    drawLine(Color.Black.copy(alpha = 0.18f), Offset(0f, matTop), Offset(w, matTop), h * 0.006f)
    // Corner posts and three ropes (red, white, blue-ish): each rope also differs in thickness so the stack reads without colour.
    val ropeColors = listOf(accent, Color(0xFFF2F2F2), Color(0xFF3F7FD6))
    val postW = w * 0.022f
    for (i in 0..2) {
        val y = h * (0.40f + i * 0.075f)
        val stroke = h * (0.014f + 0.004f * i)
        drawLine(ropeColors[i], Offset(w * 0.03f, y), Offset(w * 0.97f, y), stroke, StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.35f), Offset(w * 0.03f, y - stroke * 0.25f), Offset(w * 0.97f, y - stroke * 0.25f), stroke * 0.3f, StrokeCap.Round)
    }
    for (x in listOf(w * 0.03f, w * 0.97f)) {
        drawRoundRect(Color(0xFF8A8F9C), Offset(x - postW / 2, h * 0.34f), Size(postW, h * 0.30f), androidx.compose.ui.geometry.CornerRadius(postW / 2))
        drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(x - postW / 2, h * 0.34f), Size(postW * 0.35f, h * 0.30f), androidx.compose.ui.geometry.CornerRadius(postW / 2))
    }
}

private fun DrawScope.glove(c: Offset, r: Float, color: Color) {
    drawCircle(Color.Black.copy(alpha = 0.22f), r * 1.05f, c + Offset(r * 0.1f, r * 0.14f))
    drawCircle(Brush.radialGradient(listOf(clShade(color, 0.35f), color, clShade(color, -0.35f)), c - Offset(r * 0.3f, r * 0.35f), r * 1.4f), r, c)
    drawCircle(clShade(color, -0.55f), r, c, style = Stroke(r * 0.12f))
    drawCircle(Color.White.copy(alpha = 0.6f), r * 0.2f, c + Offset(-r * 0.38f, -r * 0.42f))
    drawLine(Color.White.copy(alpha = 0.85f), c + Offset(-r * 0.2f, r * 0.62f), c + Offset(r * 0.4f, r * 0.62f), r * 0.14f, StrokeCap.Round)
}

/**
 * Draws one boxer standing on ([cx], [floorY]) with body height [bodyH], facing right when [facing] is 1 and
 * left when -1. [badge] is the seat's trunk glyph (0 circle, 1 square). [hurtFlash] (0..1) tints the head red
 * after a hit; [shake] shifts the whole figure.
 */
internal fun DrawScope.drawBoxer(
    cx: Float, floorY: Float, bodyH: Float, facing: Int, color: Color, badge: Int,
    pose: BoxerPose, hurtFlash: Float, shake: Float, winner: Boolean, skin: Color = Color(0xFFF0C9A0)
) {
    val dir = facing.toFloat()
    val x0 = cx + shake
    // Shadow.
    drawOval(Color.Black.copy(alpha = 0.25f), Offset(x0 - bodyH * 0.32f, floorY - bodyH * 0.03f), Size(bodyH * 0.64f, bodyH * 0.07f))
    if (pose.down) {
        // Lying flat on the mat: the whole figure rotated.
        rotate(degrees = -80f * dir, pivot = Offset(x0, floorY - bodyH * 0.1f)) {
            drawBoxerUpright(x0, floorY, bodyH, 1f, color, badge, pose, hurtFlash, skin, dir)
        }
        return
    }
    val lean = pose.lean * bodyH * dir
    drawBoxerUpright(x0 + lean * 0.5f, floorY, bodyH * pose.squash, dir, color, badge, pose, hurtFlash, skin, dir, winner)
}

private fun DrawScope.drawBoxerUpright(
    x0: Float, floorY: Float, bodyH: Float, dir: Float, color: Color, badge: Int, pose: BoxerPose,
    hurtFlash: Float, skin: Color, facing: Float, winner: Boolean = false
) {
    val d = if (dir == 0f) facing else dir
    // Legs.
    val legW = bodyH * 0.11f
    for (side in listOf(-1f, 1f)) {
        drawRoundRect(clShade(skin, -0.12f), Offset(x0 + side * bodyH * 0.09f - legW / 2, floorY - bodyH * 0.34f), Size(legW, bodyH * 0.34f), androidx.compose.ui.geometry.CornerRadius(legW / 2))
        drawRoundRect(Color(0xFF2B2B33), Offset(x0 + side * bodyH * 0.09f - legW * 0.7f + d * legW * 0.4f, floorY - bodyH * 0.06f), Size(legW * 1.5f, bodyH * 0.06f), androidx.compose.ui.geometry.CornerRadius(legW / 2))
    }
    // Trunks and torso.
    val torsoTop = floorY - bodyH * 0.74f
    drawRoundRect(skin, Offset(x0 - bodyH * 0.17f, torsoTop), Size(bodyH * 0.34f, bodyH * 0.42f), androidx.compose.ui.geometry.CornerRadius(bodyH * 0.12f))
    drawRoundRect(Brush.verticalGradient(listOf(clShade(color, 0.25f), color, clShade(color, -0.3f)), floorY - bodyH * 0.42f, floorY - bodyH * 0.24f),
        Offset(x0 - bodyH * 0.19f, floorY - bodyH * 0.42f), Size(bodyH * 0.38f, bodyH * 0.2f), androidx.compose.ui.geometry.CornerRadius(bodyH * 0.07f))
    drawRoundRect(Color.White.copy(alpha = 0.9f), Offset(x0 - bodyH * 0.19f, floorY - bodyH * 0.42f), Size(bodyH * 0.38f, bodyH * 0.025f), androidx.compose.ui.geometry.CornerRadius(bodyH * 0.01f))
    // Badge shape on the trunks.
    val bc = Offset(x0, floorY - bodyH * 0.31f)
    val br = bodyH * 0.055f
    if (badge % 2 == 0) drawCircle(Color.White, br, bc) else drawRect(Color.White, bc - Offset(br, br), Size(br * 2, br * 2))
    // Head.
    val headC = Offset(x0 + d * bodyH * 0.03f, floorY - bodyH * 0.86f)
    val headR = bodyH * 0.135f
    rotate(degrees = pose.headTilt * 57.3f * d, pivot = headC + Offset(0f, headR)) {
        drawCircle(Brush.radialGradient(listOf(clShade(skin, 0.25f), skin), headC - Offset(headR * 0.3f, headR * 0.3f), headR * 1.5f), headR, headC)
        if (hurtFlash > 0f) drawCircle(Color(0xFFE53935).copy(alpha = 0.45f * hurtFlash), headR, headC)
        // Headgear band.
        drawArc(color, 180f, 180f, false, headC - Offset(headR, headR), Size(headR * 2, headR * 2), style = Stroke(headR * 0.36f))
        // Face: eyes look at the rival; dizzy = X eyes.
        val ey = headC.y + headR * 0.05f
        for (s in listOf(-0.4f, 0.4f)) {
            val ex = headC.x + d * headR * 0.25f + s * headR * 0.8f
            if (pose.dizzy) {
                val k = headR * 0.14f
                drawLine(Color(0xFF222222), Offset(ex - k, ey - k), Offset(ex + k, ey + k), headR * 0.07f, StrokeCap.Round)
                drawLine(Color(0xFF222222), Offset(ex - k, ey + k), Offset(ex + k, ey - k), headR * 0.07f, StrokeCap.Round)
            } else {
                drawCircle(Color.White, headR * 0.17f, Offset(ex, ey))
                drawCircle(Color(0xFF222222), headR * 0.09f, Offset(ex + d * headR * 0.06f, ey))
            }
        }
        drawLine(Color(0xFF7A3B2E), Offset(headC.x + d * headR * 0.1f - headR * 0.2f, headC.y + headR * 0.5f), Offset(headC.x + d * headR * 0.1f + headR * 0.2f, headC.y + headR * 0.5f), headR * 0.07f, StrokeCap.Round)
    }
    // Arms and gloves. Positions are fractions of the nominal height, mirrored by facing.
    val shoulder = Offset(x0 + d * bodyH * 0.06f, torsoTop + bodyH * 0.07f)
    fun at(px: Float, py: Float) = Offset(x0 + d * px * bodyH, floorY - py * bodyH)
    val rear = at(pose.rearX, pose.rearY)
    val lead = at(pose.leadX, pose.leadY)
    val arm = bodyH * 0.075f
    drawLine(clShade(skin, -0.1f), shoulder, rear, arm, StrokeCap.Round)
    glove(rear, bodyH * 0.105f, clShade(color, -0.1f))
    drawLine(skin, shoulder, lead, arm, StrokeCap.Round)
    glove(lead, bodyH * 0.115f, color)
    if (winner) {
        val star = Path()
        val sc = Offset(x0, floorY - bodyH * 1.18f)
        for (i in 0 until 10) {
            val a = -PI / 2 + i * PI / 5
            val rr = if (i % 2 == 0) bodyH * 0.09f else bodyH * 0.04f
            val px = sc.x + (cos(a) * rr).toFloat(); val py = sc.y + (sin(a) * rr).toFloat()
            if (i == 0) star.moveTo(px, py) else star.lineTo(px, py)
        }
        star.close()
        drawPath(star, Color(0xFFFFC83D))
    }
    // The tell: a bold warning mark over the head while a hook winds up (a shape, not just a colour).
    if (pose.tell) {
        val tc = Offset(x0, floorY - bodyH * 1.1f)
        drawCircle(Color(0xFFFFC83D), bodyH * 0.1f, tc)
        drawCircle(Color.Black, bodyH * 0.1f, tc, style = Stroke(bodyH * 0.016f))
        drawLine(Color.Black, tc + Offset(0f, -bodyH * 0.055f), tc + Offset(0f, bodyH * 0.01f), bodyH * 0.026f, StrokeCap.Round)
        drawCircle(Color.Black, bodyH * 0.015f, tc + Offset(0f, bodyH * 0.05f))
    }
    // Dizzy stars circling the head.
    if (pose.dizzy) {
        for (i in 0..2) {
            val a = i * 2.1f + x0 * 0.01f
            drawCircle(Color(0xFFFFC83D), bodyH * 0.025f, Offset(headC.x + cos(a) * headR * 1.3f, headC.y - headR * 1.15f + sin(a) * headR * 0.3f))
        }
    }
}

/** A small icon for a control pad: 0 jab (straight arrow), 1 hook (curved arrow), 2 block (shield), 3 dodge (swoosh), 4 rest (zz), 5 take (open hand). */
internal fun DrawScope.drawPadIcon(kind: Int, color: Color) {
    val w = size.width; val h = size.height
    val s = min(w, h)
    val c = Offset(w / 2, h / 2)
    val stroke = s * 0.09f
    when (kind) {
        0 -> {
            drawLine(color, Offset(c.x - s * 0.34f, c.y), Offset(c.x + s * 0.28f, c.y), stroke, StrokeCap.Round)
            drawLine(color, Offset(c.x + s * 0.28f, c.y), Offset(c.x + s * 0.12f, c.y - s * 0.16f), stroke, StrokeCap.Round)
            drawLine(color, Offset(c.x + s * 0.28f, c.y), Offset(c.x + s * 0.12f, c.y + s * 0.16f), stroke, StrokeCap.Round)
        }
        1 -> {
            val p = Path().apply {
                moveTo(c.x - s * 0.3f, c.y + s * 0.25f)
                cubicTo(c.x - s * 0.3f, c.y - s * 0.4f, c.x + s * 0.3f, c.y - s * 0.4f, c.x + s * 0.3f, c.y + s * 0.1f)
            }
            drawPath(p, color, style = Stroke(stroke, cap = StrokeCap.Round))
            drawLine(color, Offset(c.x + s * 0.3f, c.y + s * 0.1f), Offset(c.x + s * 0.12f, c.y - s * 0.02f), stroke, StrokeCap.Round)
            drawLine(color, Offset(c.x + s * 0.3f, c.y + s * 0.1f), Offset(c.x + s * 0.42f, c.y - s * 0.08f), stroke, StrokeCap.Round)
        }
        2 -> {
            val p = Path().apply {
                moveTo(c.x - s * 0.28f, c.y - s * 0.3f); lineTo(c.x + s * 0.28f, c.y - s * 0.3f)
                lineTo(c.x + s * 0.28f, c.y + s * 0.02f); quadraticTo(c.x + s * 0.28f, c.y + s * 0.3f, c.x, c.y + s * 0.38f)
                quadraticTo(c.x - s * 0.28f, c.y + s * 0.3f, c.x - s * 0.28f, c.y + s * 0.02f); close()
            }
            drawPath(p, color, style = Stroke(stroke, cap = StrokeCap.Round))
            drawLine(color, Offset(c.x, c.y - s * 0.3f), Offset(c.x, c.y + s * 0.36f), stroke * 0.6f, StrokeCap.Round)
        }
        3 -> {
            val p = Path().apply {
                moveTo(c.x + s * 0.3f, c.y - s * 0.22f)
                cubicTo(c.x - s * 0.1f, c.y - s * 0.3f, c.x - s * 0.34f, c.y + s * 0.0f, c.x - s * 0.3f, c.y + s * 0.3f)
            }
            drawPath(p, color, style = Stroke(stroke, cap = StrokeCap.Round))
            drawLine(color, Offset(c.x - s * 0.3f, c.y + s * 0.3f), Offset(c.x - s * 0.1f, c.y + s * 0.26f), stroke, StrokeCap.Round)
            drawLine(color, Offset(c.x - s * 0.3f, c.y + s * 0.3f), Offset(c.x - s * 0.32f, c.y + s * 0.08f), stroke, StrokeCap.Round)
        }
        4 -> {
            val p = Path().apply {
                moveTo(c.x - s * 0.22f, c.y - s * 0.22f); lineTo(c.x + s * 0.1f, c.y - s * 0.22f); lineTo(c.x - s * 0.22f, c.y + s * 0.1f); lineTo(c.x + s * 0.1f, c.y + s * 0.1f)
            }
            drawPath(p, color, style = Stroke(stroke, cap = StrokeCap.Round))
            drawLine(color, Offset(c.x + s * 0.18f, c.y + s * 0.12f), Offset(c.x + s * 0.34f, c.y + s * 0.12f), stroke * 0.8f, StrokeCap.Round)
        }
        else -> {
            drawCircle(color, s * 0.26f, c, style = Stroke(stroke))
            drawLine(color, Offset(c.x - s * 0.12f, c.y), Offset(c.x + s * 0.12f, c.y), stroke, StrokeCap.Round)
        }
    }
}

internal fun maxOf3(a: Float, b: Float, c: Float) = max(a, max(b, c))
