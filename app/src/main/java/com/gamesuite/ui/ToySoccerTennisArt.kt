package com.gamesuite.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Shared drawing for Toy Soccer and Toy Tennis. All original vector art: toy figures with a moulded
 * plastic base, and balls with a soft highlight. Teams are NEVER told apart by colour alone: team 0 is
 * orange with stripes and a triangle crest, team 1 is blue with dots and a diamond crest (an Okabe-Ito
 * colour-blind-safe pair).
 */

internal val StTeamA = Color(0xFFE69F00)
internal val StTeamB = Color(0xFF0072B2)

internal fun stTeamColor(team: Int, dark: Boolean): Color =
    if (team == 0) StTeamA else if (dark) Color(0xFF3C9BD6) else StTeamB

/** "m:ss" for a number of simulation ticks at [stepsPerSecond]. */
internal fun stClock(ticks: Int, stepsPerSecond: Int): String {
    val total = ((ticks + stepsPerSecond - 1) / stepsPerSecond).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * A toy figure seen from above: a contact shadow, a glossy base in the team colour with the team
 * pattern (stripes for team 0, dots for team 1), a dark centre with the team crest, a white ring for a
 * keeper and a pulsing ring while the figure is [ready] to be flicked ([pulse] 0..1).
 */
internal fun DrawScope.drawStFigure(
    c: Offset, r: Float, team: Int, dark: Boolean, keeper: Boolean, ready: Boolean, pulse: Float, outline: Boolean = false
) {
    val base = stTeamColor(team, dark)
    drawCircle(Color(0x55000000), r, Offset(c.x + r * 0.10f, c.y + r * 0.18f))
    drawCircle(clShade(base, -0.5f), r, c)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(clShade(base, 0.4f), base, clShade(base, -0.28f)),
            center = Offset(c.x - r * 0.3f, c.y - r * 0.35f), radius = r * 1.6f
        ),
        radius = r * 0.9f, center = c
    )
    val dk = clShade(base, -0.42f)
    if (team == 0) {
        for (k in -2..2) {
            val dy = k * r * 0.3f
            val half = sqrt((r * 0.86f * r * 0.86f - dy * dy).coerceAtLeast(0f))
            drawLine(dk, Offset(c.x - half, c.y + dy), Offset(c.x + half, c.y + dy), strokeWidth = r * 0.07f)
        }
    } else {
        for (k in 0 until 10) {
            val a = k * 2.0 * PI / 10
            drawCircle(dk, r * 0.065f, Offset(c.x + (cos(a) * r * 0.74f).toFloat(), c.y + (sin(a) * r * 0.74f).toFloat()))
        }
    }
    drawCircle(clShade(base, -0.55f), r * 0.5f, c)
    drawSeatGlyph(if (team == 0) 2 else 3, c, r * 0.3f)
    if (keeper) drawCircle(Color.White, r * 0.97f, c, style = Stroke(r * 0.07f))
    if (outline) drawCircle(Color.Black, r * 1.02f, c, style = Stroke(r * 0.07f))
    if (ready) {
        drawCircle(Color.White.copy(alpha = 0.5f + 0.4f * pulse), r * (1.12f + 0.07f * pulse), c, style = Stroke(r * 0.09f))
    }
}

/** A ball: white with a moulded highlight and dark panels that turn with [spin] (radians). */
internal fun DrawScope.drawStBall(c: Offset, r: Float, spin: Float) {
    if (r < 0.5f) return
    drawCircle(Color(0x44000000), r, Offset(c.x + r * 0.15f, c.y + r * 0.35f))
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.White, Color(0xFFEDEDED), Color(0xFFB4B4B4)),
            center = Offset(c.x - r * 0.3f, c.y - r * 0.35f), radius = r * 1.5f
        ),
        radius = r, center = c
    )
    for (k in 0 until 5) {
        val a = spin + k * 2.0 * PI / 5
        drawCircle(Color(0xFF2B2B2B), r * 0.2f, Offset(c.x + (cos(a) * r * 0.58f).toFloat(), c.y + (sin(a) * r * 0.58f).toFloat()))
    }
    drawCircle(Color(0xFF2B2B2B), r * 0.24f, c)
    drawCircle(Color(0xFF5A5A5A), r, c, style = Stroke(r * 0.08f))
    drawCircle(Color.White.copy(alpha = 0.6f), r * 0.16f, Offset(c.x - r * 0.4f, c.y - r * 0.45f))
}
