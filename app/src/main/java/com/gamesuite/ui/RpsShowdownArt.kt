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
import com.gamesuite.games.partypack.RpsMove

/*
 * Original vector art for the five moves of Rock-Paper-Showdown, drawn on a Canvas: a chunky toy-like style with a soft
 * body gradient, a darker outline and a highlight. Every move is also named in text wherever it is shown.
 */

private val Ink = Color(0xFF2B2A33)

private fun body(base: Color, top: Float, bottom: Float, c: Offset, h: Float) =
    Brush.verticalGradient(listOf(clShade(base, top), base, clShade(base, bottom)), c.y - h, c.y + h)

/** Draws [move] centred at [c], fitting a square of [s] px. */
internal fun DrawScope.drawRpsMove(move: RpsMove, c: Offset, s: Float) {
    val u = s / 100f // 100 units across
    fun p(x: Float, y: Float) = Offset(c.x + x * u, c.y + y * u)
    val lw = 3.4f * u
    when (move) {
        RpsMove.ROCK -> {
            val path = Path().apply {
                moveTo(p(-38f, 10f).x, p(-38f, 10f).y)
                cubicTo(p(-46f, -14f).x, p(-46f, -14f).y, p(-24f, -40f).x, p(-24f, -40f).y, p(2f, -38f).x, p(2f, -38f).y)
                cubicTo(p(30f, -42f).x, p(30f, -42f).y, p(48f, -18f).x, p(48f, -18f).y, p(40f, 14f).x, p(40f, 14f).y)
                cubicTo(p(36f, 36f).x, p(36f, 36f).y, p(10f, 42f).x, p(10f, 42f).y, p(-12f, 40f).x, p(-12f, 40f).y)
                cubicTo(p(-32f, 38f).x, p(-32f, 38f).y, p(-36f, 26f).x, p(-36f, 26f).y, p(-38f, 10f).x, p(-38f, 10f).y)
                close()
            }
            drawPath(path, body(Color(0xFF9AA1AD), 0.35f, -0.3f, c, 42f * u))
            drawPath(path, Ink, style = Stroke(lw))
            drawLine(Color(0x55FFFFFF), p(-22f, -20f), p(-6f, -28f), lw, StrokeCap.Round)
            drawLine(Color(0x44000000), p(-6f, 8f), p(14f, 18f), lw * 0.8f, StrokeCap.Round)
            drawLine(Color(0x44000000), p(14f, 18f), p(10f, 30f), lw * 0.8f, StrokeCap.Round)
        }
        RpsMove.PAPER -> {
            val path = Path().apply {
                moveTo(p(-30f, -40f).x, p(-30f, -40f).y); lineTo(p(14f, -40f).x, p(14f, -40f).y); lineTo(p(34f, -20f).x, p(34f, -20f).y)
                lineTo(p(34f, 40f).x, p(34f, 40f).y); lineTo(p(-30f, 40f).x, p(-30f, 40f).y); close()
            }
            drawPath(path, Brush.verticalGradient(listOf(Color.White, Color(0xFFE3E7EF)), c.y - 40 * u, c.y + 40 * u))
            drawPath(path, Ink, style = Stroke(lw))
            val fold = Path().apply { moveTo(p(14f, -40f).x, p(14f, -40f).y); lineTo(p(14f, -20f).x, p(14f, -20f).y); lineTo(p(34f, -20f).x, p(34f, -20f).y); close() }
            drawPath(fold, Color(0xFFC9D2E3)); drawPath(fold, Ink, style = Stroke(lw * 0.8f))
            for (i in 0..3) drawLine(Color(0xFF8C9BB8), p(-20f, -8f + i * 14f), p(24f - (i % 2) * 10f, -8f + i * 14f), lw * 0.8f, StrokeCap.Round)
        }
        RpsMove.SCISSORS -> {
            for (sign in listOf(-1f, 1f)) {
                rotate(sign * 18f, c) {
                    val blade = Path().apply {
                        moveTo(p(-3f * sign, 2f).x, p(-3f * sign, 2f).y); lineTo(p(-9f * sign + 6f * sign, -42f).x, p(-9f * sign + 6f * sign, -42f).y)
                        lineTo(p(6f * sign, -2f).x, p(6f * sign, -2f).y); close()
                    }
                    drawPath(blade, Brush.verticalGradient(listOf(Color(0xFFF2F4F8), Color(0xFF9AA4B5)), c.y - 42 * u, c.y)); drawPath(blade, Ink, style = Stroke(lw * 0.8f))
                    drawCircle(Color(0xFFD7263D), 13f * u, p(-14f * sign, 30f)); drawCircle(Ink, 13f * u, p(-14f * sign, 30f), style = Stroke(lw))
                    drawCircle(Color(0xFFFFE3E6), 6f * u, p(-14f * sign, 30f))
                }
            }
            drawCircle(Ink, 4.5f * u, p(0f, 4f))
        }
        RpsMove.LIZARD -> {
            val green = Color(0xFF45B36B)
            // tail, legs, body, head
            val tail = Path().apply {
                moveTo(p(0f, 14f).x, p(0f, 14f).y)
                cubicTo(p(-6f, 34f).x, p(-6f, 34f).y, p(14f, 40f).x, p(14f, 40f).y, p(30f, 30f).x, p(30f, 30f).y)
            }
            drawPath(tail, Ink, style = Stroke(14f * u, cap = StrokeCap.Round)); drawPath(tail, green, style = Stroke(9f * u, cap = StrokeCap.Round))
            for ((x, y, dx, dy) in listOf(listOf(-8f, -8f, -22f, -12f), listOf(8f, -8f, 22f, -12f), listOf(-8f, 8f, -22f, 16f), listOf(8f, 8f, 22f, 16f))) {
                drawLine(Ink, p(x, y), p(dx, dy), 9f * u, StrokeCap.Round); drawLine(green, p(x, y), p(dx, dy), 5f * u, StrokeCap.Round)
            }
            drawOval(Brush.verticalGradient(listOf(clShade(green, 0.3f), green, clShade(green, -0.3f)), c.y - 30 * u, c.y + 20 * u), p(-13f, -18f), Size(26f * u, 40f * u))
            drawOval(Ink, p(-13f, -18f), Size(26f * u, 40f * u), style = Stroke(lw))
            drawCircle(green, 12f * u, p(0f, -30f)); drawCircle(Ink, 12f * u, p(0f, -30f), style = Stroke(lw))
            drawCircle(Color.White, 3.4f * u, p(-5f, -32f)); drawCircle(Color.White, 3.4f * u, p(5f, -32f))
            drawCircle(Ink, 1.6f * u, p(-5f, -32f)); drawCircle(Ink, 1.6f * u, p(5f, -32f))
        }
        RpsMove.SPOCK -> {
            val skin = Color(0xFFF2C79B)
            val sleeve = Color(0xFF2F6FDE)
            // sleeve, palm, fingers in the V split, thumb
            drawRoundRect(sleeve, p(-22f, 22f), Size(44f * u, 22f * u), CornerRadius(6f * u)); drawRoundRect(Ink, p(-22f, 22f), Size(44f * u, 22f * u), CornerRadius(6f * u), style = Stroke(lw))
            for ((x, ang, len) in listOf(Triple(-19f, -14f, 38f), Triple(-6f, -5f, 44f), Triple(7f, 5f, 44f), Triple(20f, 14f, 36f))) {
                rotate(ang, p(x, 10f)) {
                    drawRoundRect(skin, p(x - 6f, 10f - len), Size(12f * u, len * u), CornerRadius(6f * u))
                    drawRoundRect(Ink, p(x - 6f, 10f - len), Size(12f * u, len * u), CornerRadius(6f * u), style = Stroke(lw * 0.85f))
                }
            }
            drawRoundRect(skin, p(-24f, -2f), Size(48f * u, 30f * u), CornerRadius(12f * u)); drawRoundRect(Ink, p(-24f, -2f), Size(48f * u, 30f * u), CornerRadius(12f * u), style = Stroke(lw))
            rotate(-52f, p(-22f, 14f)) {
                drawRoundRect(skin, p(-28f, 4f), Size(12f * u, 28f * u), CornerRadius(6f * u)); drawRoundRect(Ink, p(-28f, 4f), Size(12f * u, 28f * u), CornerRadius(6f * u), style = Stroke(lw * 0.85f))
            }
        }
    }
}

/** A small lock, for a pick that is locked in (the move behind it is never drawn). */
internal fun DrawScope.drawRpsLock(c: Offset, s: Float, color: Color) {
    val u = s / 100f
    val shackle = Path().apply {
        moveTo(c.x - 18 * u, c.y - 4 * u); lineTo(c.x - 18 * u, c.y - 20 * u)
        cubicTo(c.x - 18 * u, c.y - 46 * u, c.x + 18 * u, c.y - 46 * u, c.x + 18 * u, c.y - 20 * u); lineTo(c.x + 18 * u, c.y - 4 * u)
    }
    drawPath(shackle, color, style = Stroke(9f * u, cap = StrokeCap.Round))
    drawRoundRect(color, Offset(c.x - 30 * u, c.y - 6 * u), Size(60f * u, 46f * u), CornerRadius(10f * u))
    drawCircle(Color(0xFF1B1B22), 6f * u, Offset(c.x, c.y + 14 * u))
    drawRect(Color(0xFF1B1B22), Offset(c.x - 2.4f * u, c.y + 14 * u), Size(4.8f * u, 14f * u))
}
