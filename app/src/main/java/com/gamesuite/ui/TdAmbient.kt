package com.gamesuite.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The living-world layer (docs/design/tower-defence/LIVING-WORLD.md). A pure, seeded plan of ambient
 * elements plus pure position functions of time (so the loops are reproducible in JVM tests), and
 * thin DrawScope functions that paint them into the SAME Canvas as the board, driven by the frame
 * clock: nothing here recomposes anything.
 *
 * Levels: [TdMotion.CALM] and [TdMotion.STANDARD] only (Lively waits on a measured frame budget on
 * the real devices). [TdMotion.OFF] is Reduced Motion: the same scenery is drawn frozen at time 0.
 * Ambient never affects gameplay, is never red or amber near the route, and is not interactive.
 * Budget targets (design): Calm at most 0.4 ms, Standard at most 1.0 ms. They are NOT measured here
 * (no device runs were allowed); [TdAmbient.drawCalls] gives a hard ceiling on draw commands instead.
 */
enum class TdMotion { OFF, CALM, STANDARD }

enum class TdAmbientType { CLOUD, FLAG, GRASS, BIRDS, TORCH, STAR, COMET, BEACON, BALL, MOTE, GLOW }

/** One ambient element. Coordinates are fractions of the area it lives in (0..1). */
data class TdAmbientElement(val type: TdAmbientType, val x: Float, val y: Float, val speed: Float, val phase: Float, val size: Float)

object TdAmbient {
    /** Hard ceiling on elements (each is one to a few draw commands) for any theme and level. */
    const val MAX_ELEMENTS = 80

    private class Lcg(seed: Long) {
        private var s = seed * 6364136223846793005L + 1442695040888963407L
        fun next(): Float {
            s = s * 6364136223846793005L + 1442695040888963407L
            return ((s ushr 40) and 0xFFFFFF).toInt() / 16777216f
        }
    }

    /** Counts per type for [theme] at [motion]; Calm roughly halves them and drops the busy ones. */
    private fun counts(theme: TdAmbientKind, motion: TdMotion): Map<TdAmbientType, Int> {
        val calm = motion == TdMotion.CALM
        return when (theme) {
            TdAmbientKind.MEADOW -> mapOf(
                TdAmbientType.CLOUD to if (calm) 2 else 4,
                TdAmbientType.FLAG to 3,
                TdAmbientType.GRASS to if (calm) 6 else 12,
                TdAmbientType.BIRDS to if (calm) 0 else 1,
                TdAmbientType.TORCH to 2
            )
            TdAmbientKind.STARFIELD -> mapOf(
                TdAmbientType.STAR to if (calm) 20 else 40,
                TdAmbientType.COMET to if (calm) 0 else 1,
                TdAmbientType.BEACON to 4
            )
            TdAmbientKind.NURSERY -> mapOf(
                TdAmbientType.BALL to if (calm) 0 else 1,
                TdAmbientType.MOTE to if (calm) 6 else 12,
                TdAmbientType.GLOW to 1
            )
        }
    }

    /** The element list for a theme and motion level, deterministic for a seed. */
    fun plan(theme: TdAmbientKind, motion: TdMotion, seed: Long = 1L): List<TdAmbientElement> {
        val rnd = Lcg(seed)
        val out = ArrayList<TdAmbientElement>()
        for ((type, n) in counts(theme, motion)) {
            repeat(n) { i ->
                val x = rnd.next()
                val y = rnd.next()
                val speed = 0.5f + rnd.next()
                val phase = rnd.next() * (2f * PI.toFloat())
                val e = when (type) {
                    TdAmbientType.CLOUD -> TdAmbientElement(type, x, 0.05f + 0.35f * y, 0.003f + 0.004f * speed, phase, 0.6f + 0.6f * rnd.next())
                    TdAmbientType.FLAG -> TdAmbientElement(type, 0f, 0f, 1.2f, phase, 1f)
                    TdAmbientType.GRASS -> TdAmbientElement(type, x, y, 0.5f, phase, 0.5f + 0.5f * rnd.next())
                    TdAmbientType.BIRDS -> TdAmbientElement(type, 0f, 0.1f + 0.15f * y, 1f, phase * 6f, 1f)
                    TdAmbientType.TORCH -> TdAmbientElement(type, i * 1f, 0f, 4f, phase, 1f)
                    TdAmbientType.STAR -> TdAmbientElement(type, x, y, 0.6f + speed, phase, 0.4f + 0.8f * rnd.next())
                    TdAmbientType.COMET -> TdAmbientElement(type, 0f, 0.1f + 0.3f * y, 1f, phase * 5f, 1f)
                    TdAmbientType.BEACON -> TdAmbientElement(type, x, y, 0.5f, phase, 1f)
                    TdAmbientType.BALL -> TdAmbientElement(type, 0f, 0.7f, 1f, phase * 3f, 1f)
                    TdAmbientType.MOTE -> TdAmbientElement(type, x, y, 0.01f + 0.02f * speed, phase, 0.5f + rnd.next())
                    TdAmbientType.GLOW -> TdAmbientElement(type, 0.06f, 0.9f, 0.4f, phase, 1f)
                }
                out.add(e)
            }
        }
        return out
    }

    /** Sine in 0..1. */
    fun wave(t: Float, hz: Float, phase: Float): Float = 0.5f + 0.5f * sin(2f * PI.toFloat() * hz * t + phase)

    /** Clouds drift left to right and wrap. */
    fun cloudX(e: TdAmbientElement, t: Float): Float {
        val v = (e.x + t * e.speed) % 1.4f
        return v - 0.2f
    }

    /** A crossing that happens every [period] seconds and lasts [duration]; null while idle, else 0..1 progress. */
    fun crossing(t: Float, period: Float, duration: Float, phase: Float): Float? {
        val u = (t + phase) % period
        return if (u < duration) u / duration else null
    }

    /** Torch flicker brightness 0.75..1 from two incommensurate sines (no randomness). */
    fun flicker(t: Float, phase: Float): Float = 0.75f + 0.125f * (sin(t * 23f + phase) + sin(t * 37f + phase * 1.7f)) * 0.5f + 0.125f

    /** Draw command ceiling for a plan (every element is at most 3 draw calls). */
    fun drawCalls(plan: List<TdAmbientElement>): Int = plan.size * 3 + 8

    // ---- drawing -------------------------------------------------------------------------------

    /**
     * The screen backdrop: sky gradient, far and mid silhouettes with gentle parallax, and the sky
     * ambient (clouds, birds, stars, comet, nebula). [t] is ambient time in seconds (0 when frozen).
     */
    fun DrawScope.drawBackdrop(skin: TdSkin, plan: List<TdAmbientElement>, t: Float, motion: TdMotion) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(skin.skyTop, skin.skyBottom)), size = size)
        val drift = if (motion == TdMotion.STANDARD) t else 0f
        when (skin.ambient) {
            TdAmbientKind.STARFIELD -> {
                // Breathing nebula.
                val breathe = 0.10f + 0.06f * wave(t, 1f / 20f, 0f)
                drawCircle(Color(0xFFFF4FD8).copy(alpha = breathe), radius = w * 0.35f, center = Offset(w * 0.2f, h * 0.3f))
                drawCircle(Color(0xFF4FE3FF).copy(alpha = breathe * 0.8f), radius = w * 0.3f, center = Offset(w * 0.8f, h * 0.6f))
            }
            else -> {}
        }
        // Far layer 1.5 px/s, mid layer 4 px/s (Standard only; static in Calm and when frozen).
        val farShift = (drift * 1.5f) % w
        val midShift = (drift * 4f) % w
        drawHills(skin.far, h * 0.62f, h * 0.10f, w, farShift)
        drawHills(skin.mid, h * 0.74f, h * 0.08f, w, midShift)
        for (e in plan) {
            when (e.type) {
                TdAmbientType.CLOUD -> {
                    val cx = cloudX(e, if (motion == TdMotion.STANDARD) t else 0f) * w
                    val cy = e.y * h
                    val r = h * 0.035f * e.size
                    val c = Color.White.copy(alpha = 0.75f)
                    drawCircle(c, r, Offset(cx, cy))
                    drawCircle(c, r * 0.8f, Offset(cx + r * 1.1f, cy + r * 0.15f))
                    drawCircle(c, r * 0.7f, Offset(cx - r * 1.0f, cy + r * 0.25f))
                }
                TdAmbientType.STAR -> {
                    val a = 0.35f + 0.65f * (if (motion == TdMotion.OFF) 0.7f else wave(t, 0.4f * e.speed, e.phase))
                    drawCircle(Color.White.copy(alpha = a), radius = 1.2f + 1.4f * e.size, center = Offset(e.x * w, e.y * h * 0.8f))
                }
                TdAmbientType.COMET -> {
                    val p = crossing(t, 30f, 3f, e.phase)
                    if (p != null && motion == TdMotion.STANDARD) {
                        val x = w * (1.1f - 1.3f * p)
                        val y = h * (e.y + 0.25f * p)
                        drawLine(Color.White.copy(alpha = 0.8f), Offset(x, y), Offset(x + w * 0.08f, y - h * 0.04f), strokeWidth = 2.5f)
                        drawCircle(Color.White, 3f, Offset(x, y))
                    }
                }
                TdAmbientType.BIRDS -> {
                    val p = crossing(t, 40f, 9f, e.phase)
                    if (p != null && motion == TdMotion.STANDARD) {
                        val x0 = w * (-0.1f + 1.2f * p)
                        for (b in 0 until 5) {
                            val bx = x0 - b * w * 0.025f
                            val by = h * e.y + (b % 2) * h * 0.012f
                            val flap = 0.4f + 0.6f * wave(t, 3f, b.toFloat())
                            val path = Path().apply {
                                moveTo(bx - 6f, by - 4f * flap); lineTo(bx, by); lineTo(bx + 6f, by - 4f * flap)
                            }
                            drawPath(path, Color(0xFF2A3350), style = Stroke(width = 2f))
                        }
                    }
                }
                else -> {}
            }
        }
    }

    private fun DrawScope.drawHills(color: Color, baseY: Float, amp: Float, w: Float, shift: Float) {
        val path = Path()
        path.moveTo(0f, size.height)
        var x = -shift - w
        path.lineTo(x, size.height)
        while (x <= w + w * 0.25f) {
            val y = baseY + amp * sin((x + shift) / w * 2f * PI.toFloat() * 1.3f)
            path.lineTo(x, y)
            x += w / 24f
        }
        path.lineTo(w * 1.3f, size.height)
        path.close()
        drawPath(path, color)
    }

    /**
     * Foreground ambience over the board: grass tufts that sway, torches that flicker beside the
     * castle, beacons, motes, the bouncing ball and the night-light glow. [board] is the board's
     * rectangle in the Canvas; elements stay in the upper third or on non-route decoration by
     * being low-alpha and small, so they never read as units.
     */
    fun DrawScope.drawForeground(skin: TdSkin, plan: List<TdAmbientElement>, t: Float, motion: TdMotion, board: Size, castleY: Float) {
        val frozen = motion == TdMotion.OFF
        for (e in plan) {
            when (e.type) {
                TdAmbientType.GRASS -> {
                    val sway = if (frozen) 0f else (wave(t, 0.5f, e.phase) - 0.5f) * 5f * e.size
                    val bx = e.x * board.width
                    val by = e.y * board.height
                    val col = skin.rough.copy(alpha = 0.55f)
                    for (k in -1..1) drawLine(col, Offset(bx + k * 3f, by), Offset(bx + k * 4f + sway, by - 9f * e.size), strokeWidth = 1.6f)
                }
                TdAmbientType.TORCH -> {
                    val f = if (frozen || motion == TdMotion.CALM) 1f else flicker(t, e.phase)
                    val tx = board.width * 0.935f
                    val ty = castleY + (if (e.x < 1f) -board.height * 0.1f else board.height * 0.12f)
                    drawCircle(Color(0xFFFFB347).copy(alpha = 0.35f * f), board.height * 0.035f, Offset(tx, ty))
                    drawCircle(Color(0xFFFFE08A).copy(alpha = 0.9f * f), board.height * 0.012f, Offset(tx, ty))
                }
                TdAmbientType.BEACON -> {
                    val a = if (frozen) 0.7f else 0.3f + 0.7f * wave(t, 0.5f, e.phase)
                    drawCircle(Color(0xFF4FE3FF).copy(alpha = a), 3.2f, Offset(e.x * board.width, e.y * board.height))
                }
                TdAmbientType.MOTE -> {
                    val x = ((e.x + (if (frozen) 0f else t * e.speed)) % 1f) * board.width
                    val y = (e.y + 0.03f * sin(t * 0.7f + e.phase)) * board.height
                    drawCircle(Color.White.copy(alpha = 0.35f), 1.5f + 1.5f * e.size, Offset(x, y))
                }
                TdAmbientType.BALL -> {
                    val p = crossing(t, 15f, 6f, e.phase)
                    if (p != null && !frozen) {
                        val x = board.width * (1.05f - 1.1f * p)
                        val y = board.height * 0.97f - abs(sin(p * 3f * PI.toFloat())) * board.height * 0.18f
                        drawCircle(Color(0xFFFF5A5F).copy(alpha = 0.55f), board.height * 0.03f, Offset(x, y))
                    }
                }
                TdAmbientType.GLOW -> {
                    val a = if (frozen) 0.18f else 0.10f + 0.14f * wave(t, 0.4f, 0f)
                    drawCircle(Color(0xFFFFE08A).copy(alpha = a), board.height * 0.12f, Offset(board.width * e.x, board.height * e.y))
                }
                else -> {}
            }
        }
    }

    /** Flag ripple for the castle: horizontal offset of the tip in px, 0 when frozen. */
    fun flagRipple(t: Float, phase: Float, motion: TdMotion, amp: Float): Float =
        if (motion == TdMotion.OFF) 0f else (wave(t, 1.2f, phase) - 0.5f) * amp
}
