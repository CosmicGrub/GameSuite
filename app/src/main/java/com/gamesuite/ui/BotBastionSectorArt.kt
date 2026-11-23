package com.gamesuite.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** How much the ambient layer moves: Reduced Motion freezes it, Calm drifts at half speed. */
internal enum class BbAmbientMotion(val speed: Double) { FROZEN(0.0), CALM(0.5), STANDARD(1.0) }

/** The ambient clock: game time scaled by the motion level, so a frozen layer draws the same frame forever. */
internal fun bbAmbientPhase(timeSeconds: Double, motion: BbAmbientMotion): Double = timeSeconds * motion.speed

/** One drifting speck of an ambient layer. Positions are fractions of the arena. */
internal class BbMote(val x: Float, val y: Float, val size: Float, val speed: Float, val phase: Float)

internal fun bbMotes(sector: Int, count: Int = 26): List<BbMote> {
    val r = Random(900 + sector)
    return List(count) { BbMote(r.nextFloat(), r.nextFloat(), 0.5f + r.nextFloat() * 1.6f, 0.2f + r.nextFloat() * 0.8f, r.nextFloat() * 6.28f) }
}

/** A fraction in 0..1 that wraps: where a mote that started at [start] is after [t] seconds at [rate] screens per second. */
internal fun bbWrap(start: Float, rate: Double, t: Double): Float {
    val v = (start + rate * t) % 1.0
    return (if (v < 0) v + 1.0 else v).toFloat()
}

/**
 * Original vector backdrops and ambient layers for the four sectors. The backdrop is static; the ambient layer is a few
 * dozen dots and strokes, so it adds almost nothing to the frame.
 *
 * 1 Cradle Reach: soft blue gas and a banded ring-world. 2 Glass Belt: translucent shards and glints.
 * 3 Ember Deep: a red-hot horizon and rising embers. 4 Hush Verge: near-black violet with slow ribbons of light.
 */
internal object BbSectors {
    class Look(val base: Color, val far: Color, val accent: Color, val star: Color)

    fun look(sector: Int): Look = when (sector) {
        1 -> Look(Color(0xFF0B1230), Color(0xFF1B2D6B), Color(0xFF6FA8FF), Color(0xCCFFFFFF))
        2 -> Look(Color(0xFF071A24), Color(0xFF0F3B4C), Color(0xFF8FF0E8), Color(0xCCD8FFFB))
        3 -> Look(Color(0xFF1A0A0A), Color(0xFF4A1408), Color(0xFFFF8A3D), Color(0xAAFFD9B0))
        else -> Look(Color(0xFF0A0614), Color(0xFF241046), Color(0xFFB892FF), Color(0xFFFFFFFF))
    }
}

internal fun DrawScope.drawBbSectorBackdrop(sector: Int, stars: List<Triple<Float, Float, Float>>) {
    val l = BbSectors.look(sector)
    val w = size.width
    val h = size.height
    drawRect(l.base)
    when (sector) {
        1 -> {
            drawCircle(l.far, radius = h * 0.75f, center = Offset(w * 0.2f, h * 0.9f))
            drawCircle(l.far.copy(alpha = 0.6f), radius = h * 0.5f, center = Offset(w * 0.85f, h * 0.1f))
            // a banded ring-world in the corner
            val c = Offset(w * 0.86f, h * 0.2f)
            drawCircle(Color(0xFF2C4A9A), radius = h * 0.13f, center = c)
            drawOval(l.accent.copy(alpha = 0.55f), Offset(c.x - h * 0.24f, c.y - h * 0.035f), Size(h * 0.48f, h * 0.07f), style = Stroke(width = 2f))
        }
        2 -> {
            drawCircle(l.far, radius = h * 0.65f, center = Offset(w * 0.8f, h * 0.9f))
            for (i in 0 until 5) {
                val x = w * (0.1f + i * 0.2f)
                val y = h * (0.15f + (i % 3) * 0.3f)
                val p = Path()
                p.moveTo(x, y - h * 0.09f); p.lineTo(x + h * 0.05f, y); p.lineTo(x, y + h * 0.09f); p.lineTo(x - h * 0.05f, y); p.close()
                drawPath(p, l.accent.copy(alpha = 0.1f))
                drawPath(p, l.accent.copy(alpha = 0.3f), style = Stroke(width = 1.5f))
            }
        }
        3 -> {
            drawRect(Brush.verticalGradient(listOf(l.far.copy(alpha = 0f), l.far)), Offset(0f, h * 0.55f), Size(w, h * 0.45f))
            drawCircle(l.accent.copy(alpha = 0.12f), radius = h * 0.55f, center = Offset(w * 0.5f, h * 1.05f))
            drawCircle(l.accent.copy(alpha = 0.18f), radius = h * 0.3f, center = Offset(w * 0.5f, h * 1.05f))
        }
        else -> {
            drawCircle(l.far, radius = h * 0.8f, center = Offset(w * 0.1f, h * 0.2f))
            drawCircle(l.far.copy(alpha = 0.5f), radius = h * 0.6f, center = Offset(w * 0.9f, h * 0.9f))
        }
    }
    for ((x, y, r) in stars) drawCircle(l.star, radius = r * (if (sector == 4) 0.8f else 1f), center = Offset(x * w, y * h))
}

/** The moving layer. [phase] is [bbAmbientPhase] output; with a frozen phase the same frame is drawn every time. */
internal fun DrawScope.drawBbSectorAmbient(sector: Int, motes: List<BbMote>, phase: Double) {
    val l = BbSectors.look(sector)
    val w = size.width
    val h = size.height
    when (sector) {
        1 -> for (m in motes) { // slow gas dust
            val x = bbWrap(m.x, 0.004 * m.speed, phase) * w
            drawCircle(l.accent.copy(alpha = 0.10f + 0.08f * m.speed), radius = m.size * 3f, center = Offset(x, m.y * h))
        }
        2 -> for (m in motes) { // glints that wink in place
            val a = (0.5 + 0.5 * sin(phase * 0.8 * m.speed + m.phase)).toFloat()
            val c = Offset(m.x * w, m.y * h)
            val r = m.size * 2.2f * a
            drawLine(l.accent.copy(alpha = 0.55f * a), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5f, StrokeCap.Round)
            drawLine(l.accent.copy(alpha = 0.55f * a), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5f, StrokeCap.Round)
        }
        3 -> for (m in motes) { // embers rising, wrapping at the top
            val y = 1f - bbWrap(1f - m.y, 0.02 * m.speed, phase)
            val x = (m.x + 0.012f * sin(phase * 0.5 * m.speed + m.phase).toFloat()).coerceIn(0f, 1f) * w
            drawCircle(l.accent.copy(alpha = (0.25f + 0.5f * (1f - y)).coerceIn(0f, 1f)), radius = m.size, center = Offset(x, y * h))
        }
        else -> { // two slow ribbons of light and a few twinkles
            for (k in 0 until 2) {
                val p = Path()
                for (i in 0..24) {
                    val t = i / 24f
                    val x = t * w
                    val y = h * (0.3f + 0.35f * k) + h * 0.06f * sin(t * 2f * PI.toFloat() + phase * (0.25 + 0.1 * k) + k).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                drawPath(p, l.accent.copy(alpha = 0.10f), style = Stroke(width = h * 0.05f, cap = StrokeCap.Round))
                drawPath(p, l.accent.copy(alpha = 0.18f), style = Stroke(width = 1.5f, cap = StrokeCap.Round))
            }
            for (m in motes.take(10)) {
                val a = (0.3 + 0.4 * sin(phase * 0.3 * m.speed + m.phase)).toFloat().coerceIn(0f, 1f)
                drawCircle(Color.White.copy(alpha = a), radius = m.size * 0.7f, center = Offset(m.x * w, m.y * h))
            }
        }
    }
}
