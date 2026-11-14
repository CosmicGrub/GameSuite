package com.gamesuite.rewards

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform

/*
 * 24 original stickers drawn in code. Every picture is drawn on a 100 x 100 grid (scaled to the canvas): a white
 * rounded backing with a soft drop shadow, a slight tilt, and flat rounded shapes on top. No bitmaps.
 */

private val Ink = Color(0xFF2B1B4A)
private val White = Color(0xFFFFFFFF)
private val Cheek = Color(0xFFFF9AA2)

/** The sticker [sticker] at [tilt] degrees (a small positive or negative number gives the hand-placed look). */
@Composable
fun StickerArt(sticker: Sticker, modifier: Modifier = Modifier, tilt: Float = defaultTilt(sticker)) {
    Canvas(modifier) {
        val s = size.minDimension / 100f
        scale(s, s, pivot = Offset.Zero) {
            rotate(tilt, pivot = Offset(50f, 50f)) {
                // drop shadow
                drawRoundRect(Color(0x33000000), Offset(5f, 8f), Size(90f, 90f), CornerRadius(26f))
                // sticker backing
                drawRoundRect(White, Offset(3f, 3f), Size(90f, 90f), CornerRadius(26f))
                drawRoundRect(sticker.set.tint(), Offset(9f, 9f), Size(78f, 78f), CornerRadius(20f))
                withTransform({ translate(6f, 6f) }) { drawArt(sticker.id) }
            }
        }
    }
}

/** A small, stable per-sticker tilt between -6 and +6 degrees. */
fun defaultTilt(sticker: Sticker): Float = ((sticker.id.hashCode() and 0x7fffffff) % 13 - 6).toFloat()

private fun StickerSet.tint(): Color = when (this) {
    StickerSet.ANIMALS -> Color(0xFFFFE9BD)
    StickerSet.SPACE -> Color(0xFFD9DBFF)
    StickerSet.FOOD -> Color(0xFFFFD9D2)
}

private fun DrawScope.dot(c: Color, x: Float, y: Float, r: Float) = drawCircle(c, r, Offset(x, y))
private fun DrawScope.oval(c: Color, x: Float, y: Float, w: Float, h: Float) = drawOval(c, Offset(x, y), Size(w, h))
private fun DrawScope.pill(c: Color, x: Float, y: Float, w: Float, h: Float, r: Float = 6f) =
    drawRoundRect(c, Offset(x, y), Size(w, h), CornerRadius(r))
private fun DrawScope.line(c: Color, x1: Float, y1: Float, x2: Float, y2: Float, w: Float = 3f) =
    drawLine(c, Offset(x1, y1), Offset(x2, y2), w, StrokeCap.Round)
private fun DrawScope.poly(c: Color, vararg p: Float) {
    val path = Path().apply {
        moveTo(p[0], p[1])
        var i = 2
        while (i < p.size) { lineTo(p[i], p[i + 1]); i += 2 }
        close()
    }
    drawPath(path, c)
}
private fun DrawScope.eyes(x1: Float, x2: Float, y: Float, r: Float = 3f) {
    dot(Ink, x1, y, r); dot(Ink, x2, y, r)
}
private fun DrawScope.smile(cx: Float, y: Float, w: Float = 14f) =
    drawArc(Ink, 20f, 140f, false, Offset(cx - w / 2, y - w / 3), Size(w, w * 0.7f), style = Stroke(2.5f, cap = StrokeCap.Round))
private fun DrawScope.star(c: Color, cx: Float, cy: Float, r: Float) {
    val path = Path()
    for (i in 0 until 10) {
        val a = Math.toRadians((-90 + i * 36).toDouble())
        val rad = if (i % 2 == 0) r else r * 0.45f
        val x = cx + (rad * Math.cos(a)).toFloat()
        val y = cy + (rad * Math.sin(a)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, c)
}

/** Draws the picture for sticker [id] inside a 75 x 75 area starting at the origin. */
private fun DrawScope.drawArt(id: String) {
    when (id) {
        // ---- Animals ----
        "animals_cat" -> {
            poly(Color(0xFFF5A04A), 14f, 30f, 20f, 8f, 34f, 22f); poly(Color(0xFFF5A04A), 61f, 30f, 55f, 8f, 41f, 22f)
            dot(Color(0xFFF5A04A), 37.5f, 42f, 26f); eyes(28f, 47f, 38f); poly(Cheek, 34f, 45f, 41f, 45f, 37.5f, 50f)
            line(Ink, 8f, 44f, 22f, 47f, 2f); line(Ink, 67f, 44f, 53f, 47f, 2f)
        }
        "animals_dog" -> {
            oval(Color(0xFF8A5A3C), 6f, 14f, 18f, 34f); oval(Color(0xFF8A5A3C), 51f, 14f, 18f, 34f)
            dot(Color(0xFFD9A066), 37.5f, 42f, 25f); oval(Color(0xFFFFF1DC), 25f, 44f, 25f, 18f)
            eyes(29f, 46f, 36f); dot(Ink, 37.5f, 48f, 4f); smile(37.5f, 57f, 10f)
        }
        "animals_rabbit" -> {
            oval(Color(0xFFEDEDED), 22f, 0f, 12f, 38f); oval(Color(0xFFEDEDED), 41f, 0f, 12f, 38f)
            oval(Cheek, 25f, 8f, 6f, 24f); oval(Cheek, 44f, 8f, 6f, 24f)
            dot(Color(0xFFEDEDED), 37.5f, 48f, 24f); eyes(29f, 46f, 44f); dot(Cheek, 37.5f, 51f, 3.5f); smile(37.5f, 58f, 9f)
        }
        "animals_frog" -> {
            dot(Color(0xFF5BBF5B), 37.5f, 44f, 27f); dot(Color(0xFF5BBF5B), 22f, 22f, 11f); dot(Color(0xFF5BBF5B), 53f, 22f, 11f)
            dot(White, 22f, 22f, 7f); dot(White, 53f, 22f, 7f); eyes(22f, 53f, 22f, 3.5f); smile(37.5f, 52f, 26f)
            dot(Cheek, 20f, 50f, 4f); dot(Cheek, 55f, 50f, 4f)
        }
        "animals_owl" -> {
            oval(Color(0xFF9A6B45), 14f, 10f, 47f, 58f); poly(Color(0xFF9A6B45), 16f, 20f, 18f, 6f, 30f, 14f)
            poly(Color(0xFF9A6B45), 59f, 20f, 57f, 6f, 45f, 14f)
            dot(White, 28f, 32f, 10f); dot(White, 47f, 32f, 10f); eyes(28f, 47f, 32f, 4.5f)
            poly(Color(0xFFFFB547), 32f, 40f, 43f, 40f, 37.5f, 50f); oval(Color(0xFFE6C9A0), 24f, 50f, 27f, 18f)
        }
        "animals_fish" -> {
            poly(Color(0xFFFF8A3D), 52f, 38f, 70f, 22f, 70f, 54f); oval(Color(0xFFFF8A3D), 8f, 20f, 52f, 36f)
            dot(White, 22f, 34f, 6f); dot(Ink, 22f, 34f, 3f); line(White, 38f, 24f, 38f, 52f, 3f); line(White, 46f, 26f, 46f, 50f, 3f)
            smile(14f, 46f, 8f)
        }
        "animals_bear" -> {
            dot(Color(0xFF8A5A3C), 16f, 16f, 11f); dot(Color(0xFF8A5A3C), 59f, 16f, 11f)
            dot(Color(0xFFA9714B), 37.5f, 40f, 27f); oval(Color(0xFFE6C9A0), 26f, 42f, 23f, 18f)
            eyes(28f, 47f, 34f); dot(Ink, 37.5f, 47f, 4f); smile(37.5f, 55f, 9f)
        }
        "animals_penguin" -> {
            oval(Color(0xFF2E3A59), 15f, 6f, 45f, 62f); oval(White, 24f, 26f, 27f, 38f)
            dot(White, 29f, 22f, 6f); dot(White, 46f, 22f, 6f); eyes(29f, 46f, 22f, 2.8f)
            poly(Color(0xFFFFB547), 32f, 28f, 43f, 28f, 37.5f, 36f)
            oval(Color(0xFFFFB547), 20f, 66f, 14f, 6f); oval(Color(0xFFFFB547), 41f, 66f, 14f, 6f)
        }
        // ---- Space ----
        "space_rocket" -> {
            poly(Color(0xFFE5484D), 20f, 58f, 8f, 70f, 28f, 62f); poly(Color(0xFFE5484D), 55f, 58f, 67f, 70f, 47f, 62f)
            oval(Color(0xFFEDEDED), 24f, 6f, 27f, 62f); oval(Color(0xFFE5484D), 24f, 6f, 27f, 24f)
            dot(Color(0xFF6CB4FF), 37.5f, 38f, 8f); dot(White, 37.5f, 38f, 4f)
            poly(Color(0xFFFFB547), 30f, 68f, 45f, 68f, 37.5f, 82f)
        }
        "space_planet" -> {
            dot(Color(0xFF8B7CFF), 37.5f, 38f, 24f); oval(Color(0xFF6A5BE0), 22f, 28f, 14f, 10f); dot(Color(0xFF6A5BE0), 46f, 50f, 5f)
            drawOval(Color(0xFFFFB547), Offset(2f, 30f), Size(71f, 16f), style = Stroke(5f))
        }
        "space_star" -> {
            star(Color(0xFFFFC93C), 37.5f, 40f, 34f); eyes(30f, 45f, 40f); smile(37.5f, 48f, 10f)
            dot(Cheek, 25f, 47f, 3f); dot(Cheek, 50f, 47f, 3f)
        }
        "space_moon" -> {
            dot(Color(0xFFFFE08A), 37.5f, 38f, 28f); dot(Color(0xFFF2C65A), 28f, 28f, 6f); dot(Color(0xFFF2C65A), 46f, 46f, 8f)
            dot(Color(0xFFF2C65A), 30f, 50f, 4f); eyes(30f, 46f, 36f, 2.5f); smile(38f, 42f, 10f)
        }
        "space_comet" -> {
            poly(Color(0xFF6CB4FF), 52f, 36f, 6f, 8f, 18f, 30f); poly(Color(0xFF9BD0FF), 52f, 42f, 4f, 30f, 20f, 44f)
            poly(Color(0xFFCFE8FF), 50f, 48f, 8f, 54f, 26f, 56f)
            dot(Color(0xFFFFB547), 54f, 44f, 15f); dot(Color(0xFFFFD27A), 50f, 40f, 5f)
        }
        "space_alien" -> {
            line(Color(0xFF3FA66A), 26f, 14f, 22f, 4f); line(Color(0xFF3FA66A), 49f, 14f, 53f, 4f)
            dot(Color(0xFF3FA66A), 22f, 4f, 3f); dot(Color(0xFF3FA66A), 53f, 4f, 3f)
            oval(Color(0xFF5BD18A), 12f, 12f, 51f, 56f); oval(Ink, 22f, 30f, 14f, 20f); oval(Ink, 39f, 30f, 14f, 20f)
            dot(White, 28f, 36f, 3f); dot(White, 45f, 36f, 3f); smile(37.5f, 56f, 12f)
        }
        "space_helmet" -> {
            dot(Color(0xFFEDEDED), 37.5f, 40f, 30f); pill(Color(0xFF2E3A59), 18f, 26f, 39f, 28f, 14f)
            oval(Color(0x556CB4FF), 22f, 29f, 14f, 8f); dot(Color(0xFFE5484D), 20f, 64f, 4f); dot(Color(0xFF6CB4FF), 30f, 66f, 4f)
            pill(Color(0xFFD0D3DC), 22f, 62f, 31f, 10f, 5f)
        }
        "space_ufo" -> {
            dot(Color(0x996CB4FF), 37.5f, 30f, 17f); oval(Color(0xFF8A8FA8), 6f, 36f, 63f, 22f)
            dot(Color(0xFFFFB547), 20f, 47f, 3.5f); dot(Color(0xFFFF8A80), 37.5f, 50f, 3.5f); dot(Color(0xFF9BD0FF), 55f, 47f, 3.5f)
            poly(Color(0x66FFE08A), 28f, 58f, 47f, 58f, 56f, 74f, 19f, 74f)
        }
        // ---- Food ----
        "food_apple" -> {
            dot(Color(0xFFE5484D), 28f, 42f, 22f); dot(Color(0xFFE5484D), 47f, 42f, 22f); oval(Color(0xFFE5484D), 12f, 28f, 51f, 44f)
            line(Color(0xFF6B4423), 37.5f, 22f, 40f, 8f, 4f); oval(Color(0xFF5BBF5B), 41f, 8f, 18f, 10f)
            oval(Color(0x66FFFFFF), 19f, 34f, 8f, 14f)
        }
        "food_banana" -> {
            val p = Path().apply {
                moveTo(10f, 20f); cubicTo(14f, 62f, 50f, 78f, 70f, 40f); cubicTo(56f, 56f, 28f, 52f, 22f, 16f); close()
            }
            drawPath(p, Color(0xFFFFD84A)); line(Color(0xFF6B4423), 10f, 20f, 14f, 10f, 5f)
            drawPath(Path().apply { moveTo(16f, 26f); cubicTo(26f, 56f, 46f, 62f, 62f, 46f) }, Color(0xFFE0B52E), style = Stroke(2f))
        }
        "food_cupcake" -> {
            poly(Color(0xFFE6B07A), 14f, 40f, 61f, 40f, 54f, 70f, 21f, 70f)
            line(Color(0xFFC98E5A), 28f, 42f, 30f, 68f, 2f); line(Color(0xFFC98E5A), 46f, 42f, 44f, 68f, 2f)
            dot(Color(0xFFFF8FB8), 37.5f, 32f, 22f); dot(Color(0xFFFF8FB8), 24f, 38f, 12f); dot(Color(0xFFFF8FB8), 51f, 38f, 12f)
            dot(Color(0xFFE5484D), 37.5f, 9f, 6f)
        }
        "food_pizza" -> {
            poly(Color(0xFFFFC857), 10f, 14f, 66f, 14f, 37.5f, 72f)
            pill(Color(0xFFE0A93B), 8f, 10f, 60f, 10f, 5f)
            dot(Color(0xFFE5484D), 30f, 32f, 6f); dot(Color(0xFFE5484D), 46f, 30f, 6f); dot(Color(0xFFE5484D), 38f, 48f, 6f)
            dot(Color(0xFF5BBF5B), 24f, 24f, 2.5f); dot(Color(0xFF5BBF5B), 52f, 42f, 2.5f)
        }
        "food_icecream" -> {
            poly(Color(0xFFE6B07A), 22f, 40f, 53f, 40f, 37.5f, 76f)
            line(Color(0xFFC98E5A), 28f, 46f, 44f, 62f, 2f); line(Color(0xFFC98E5A), 47f, 46f, 31f, 62f, 2f)
            dot(Color(0xFFFF8FB8), 37.5f, 28f, 20f); dot(Color(0xFFB9E4FF), 24f, 38f, 10f); dot(Color(0xFFB9E4FF), 51f, 38f, 10f)
            dot(Color(0xFFE5484D), 37.5f, 6f, 5f)
        }
        "food_strawberry" -> {
            poly(Color(0xFFE5484D), 8f, 24f, 67f, 24f, 37.5f, 74f); dot(Color(0xFFE5484D), 24f, 28f, 17f); dot(Color(0xFFE5484D), 51f, 28f, 17f)
            poly(Color(0xFF5BBF5B), 37.5f, 22f, 22f, 10f, 30f, 22f); poly(Color(0xFF5BBF5B), 37.5f, 22f, 53f, 10f, 45f, 22f)
            poly(Color(0xFF5BBF5B), 37.5f, 22f, 30f, 6f, 45f, 6f)
            dot(Color(0xFFFFE08A), 26f, 36f, 2f); dot(Color(0xFFFFE08A), 46f, 38f, 2f); dot(Color(0xFFFFE08A), 36f, 50f, 2f); dot(Color(0xFFFFE08A), 37f, 36f, 2f)
        }
        "food_cookie" -> {
            dot(Color(0xFFD9A066), 37.5f, 38f, 29f)
            dot(Color(0xFF5B3A29), 27f, 28f, 4.5f); dot(Color(0xFF5B3A29), 46f, 26f, 4f); dot(Color(0xFF5B3A29), 34f, 44f, 4.5f)
            dot(Color(0xFF5B3A29), 52f, 46f, 4f); dot(Color(0xFF5B3A29), 40f, 58f, 3.5f); dot(Color(0xFF5B3A29), 22f, 48f, 3.5f)
        }
        "food_carrot" -> {
            poly(Color(0xFFFF8A3D), 28f, 22f, 48f, 22f, 37.5f, 76f)
            line(Color(0xFFE06E1F), 32f, 34f, 40f, 34f, 2f); line(Color(0xFFE06E1F), 34f, 48f, 41f, 48f, 2f)
            oval(Color(0xFF5BBF5B), 18f, 2f, 12f, 26f); oval(Color(0xFF5BBF5B), 32f, 0f, 12f, 28f); oval(Color(0xFF5BBF5B), 46f, 2f, 12f, 26f)
        }
        else -> star(Color(0xFFFFC93C), 37.5f, 40f, 30f)
    }
}

/** A dashed rounded outline (for locked slots), drawn on a 100 grid like the stickers. */
@Composable
fun LockedSlotOutline(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 100f
        scale(s, s, pivot = Offset.Zero) {
            drawRoundRect(
                color, Offset(8f, 8f), Size(80f, 80f), CornerRadius(22f),
                style = Stroke(3f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            )
        }
    }
}
