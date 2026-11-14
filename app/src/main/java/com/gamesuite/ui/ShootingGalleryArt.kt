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
import com.gamesuite.games.shootinggallery.GalleryTarget
import com.gamesuite.games.shootinggallery.GalleryWorld
import com.gamesuite.games.shootinggallery.TargetKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Original vector art for Shooting Gallery: a carnival booth with a striped valance and a twinkling
 * bulb string, a painted night backdrop, brass rails for the targets to ride on and a plank counter.
 * Targets are tin cut-outs: a plump duck silhouette, a bullseye ring, a golden star and a spiky black
 * "decoy" mine with a cross on it. Each kind has its own SHAPE (duck, rings, star, spikes), so nothing
 * relies on colour alone. Range coordinates are 0..1000 by 0..600; [sc] is pixels per range unit.
 */

internal fun DrawScope.drawGalleryBooth(sc: Float, seconds: Float, reduced: Boolean, dark: Boolean) {
    val w = GalleryWorld.W * sc
    val h = GalleryWorld.H * sc
    drawRect(
        Brush.verticalGradient(
            if (dark) listOf(Color(0xFF0E1230), Color(0xFF3B2A5A)) else listOf(Color(0xFF3B4C8F), Color(0xFFE59B6D))
        ),
        size = Size(w, h)
    )
    // painted hills
    for ((k, col) in listOf(Color(0x55102040), Color(0x77102040)).withIndex()) {
        val p = Path().apply {
            moveTo(0f, h)
            var x = 0f
            while (x <= w + 10f) { lineTo(x, h * (0.66f + 0.05f * k) + 22f * sc * sin(x / w * (6f + 3f * k) + k * 1.7f)); x += 10f }
            lineTo(w, h); close()
        }
        drawPath(p, col)
    }
    // stars
    for (i in 0 until 14) {
        val x = ((i * 137) % 1000) * sc
        val y = (30 + (i * 71) % 190) * sc
        val tw = if (reduced) 0.7f else 0.5f + 0.5f * sin(seconds * 1.7f + i)
        drawCircle(Color.White.copy(alpha = 0.55f * tw), 2.6f * sc + 1f, Offset(x, y))
    }
    // lane rails
    for (laneY in listOf(150f, 300f, 450f)) {
        val y = (laneY + 46f) * sc
        drawLine(Color(0xFF3A2F26), Offset(0f, y + 3f * sc), Offset(w, y + 3f * sc), 7f * sc)
        drawLine(Color(0xFFD9B26A), Offset(0f, y), Offset(w, y), 5f * sc, StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.5f), Offset(0f, y - 1.5f * sc), Offset(w, y - 1.5f * sc), 1.4f * sc)
    }
    // plank counter
    val counterTop = 536f * sc
    drawRect(Brush.verticalGradient(listOf(Color(0xFF8A5A33), Color(0xFF4A2D18)), startY = counterTop, endY = h), Offset(0f, counterTop), Size(w, h - counterTop))
    for (i in 1 until 8) drawLine(Color(0x55000000), Offset(i * w / 8f, counterTop), Offset(i * w / 8f, h), 2f)
    drawLine(Color(0xFFD9A441), Offset(0f, counterTop), Offset(w, counterTop), 6f * sc)
    // striped valance with scallops
    val stripes = 14
    val sw = w / stripes
    for (i in 0 until stripes) {
        val c = if (i % 2 == 0) Color(0xFFC62F3A) else Color(0xFFF6EBD0)
        val x = i * sw
        val p = Path().apply {
            moveTo(x, 0f); lineTo(x + sw, 0f); lineTo(x + sw, 52f * sc)
            quadraticBezierTo(x + sw / 2f, 82f * sc, x, 52f * sc); close()
        }
        drawPath(p, c)
        drawPath(p, Color(0x33000000), style = Stroke(1.2f))
    }
    // bulb string
    for (i in 0 until 12) {
        val x = (i + 0.5f) * w / 12f
        val y = 62f * sc + 7f * sc * sin(i * 0.9f)
        val on = reduced || ((seconds * 2.2f + i * 0.5f).toInt() % 2 == 0)
        drawCircle(Color(0xFFFFE08A).copy(alpha = if (on) 0.35f else 0.12f), 11f * sc, Offset(x, y))
        drawCircle(if (on) Color(0xFFFFF1B8) else Color(0xFFB59A55), 5f * sc, Offset(x, y))
    }
}

private fun starPath(c: Offset, r: Float): Path {
    val p = Path()
    for (i in 0 until 10) {
        val a = -PI / 2 + i * PI / 5
        val rr = if (i % 2 == 0) r else r * 0.45f
        val x = c.x + (cos(a) * rr).toFloat(); val y = c.y + (sin(a) * rr).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

/**
 * One target at time [t]. [hitAgeMs] is -1 while it is still standing, otherwise how long ago it was
 * shot (it tips forward and fades out). [highlight] pulses a ring around it (the hint).
 */
internal fun DrawScope.drawGalleryTarget(
    sc: Float, tg: GalleryTarget, t: Int, hitAgeMs: Int, colorblind: Boolean, seconds: Float, highlight: Boolean, reduced: Boolean
) {
    val x = tg.xAt(t).toFloat() * sc
    var y = tg.yAt(t).toFloat() * sc
    val u = (tg.rx / 36.0).toFloat() * sc
    var alpha = 1f
    var tilt = 0f
    if (hitAgeMs >= 0) {
        val k = (hitAgeMs / 450f).coerceIn(0f, 1f)
        alpha = 1f - k
        if (!reduced) { y += k * k * 70f * sc; tilt = k * 70f * tg.dir }
    }
    val rail = (tg.y0.toFloat() + 46f) * sc
    val dirF = tg.dir.toFloat()
    rotate(tilt, Offset(x, y)) {
        // the stand: a rod down to the rail
        if (hitAgeMs < 0) {
            drawLine(Color(0xFF6C6C70), Offset(x, y), Offset(x, rail), 3f * sc)
            drawRoundRect(Color(0xFFB5B5BA), Offset(x - 12f * sc, rail - 3f * sc), Size(24f * sc, 8f * sc), CornerRadius(3f * sc))
        }
        when (tg.kind) {
            TargetKind.DUCK -> {
                val body = if (colorblind) Color(0xFFF0A202) else Color(0xFFF2B233)
                val dark = Color(0xFF7A4B00)
                // tail
                val tail = Path().apply {
                    moveTo(x - dirF * 30f * u, y - 2f * u); lineTo(x - dirF * 44f * u, y - 16f * u); lineTo(x - dirF * 24f * u, y - 10f * u); close()
                }
                drawPath(tail, body.copy(alpha = alpha)); drawPath(tail, dark.copy(alpha = alpha), style = Stroke(2.2f * u))
                drawOval(body.copy(alpha = alpha), Offset(x - 34f * u, y - 20f * u), Size(68f * u, 44f * u))
                drawOval(dark.copy(alpha = alpha), Offset(x - 34f * u, y - 20f * u), Size(68f * u, 44f * u), style = Stroke(2.6f * u))
                // head and beak
                val hc = Offset(x + dirF * 24f * u, y - 24f * u)
                drawCircle(body.copy(alpha = alpha), 15f * u, hc)
                drawCircle(dark.copy(alpha = alpha), 15f * u, hc, style = Stroke(2.6f * u))
                val beak = Path().apply {
                    moveTo(hc.x + dirF * 13f * u, hc.y - 2f * u); lineTo(hc.x + dirF * 31f * u, hc.y + 3f * u); lineTo(hc.x + dirF * 12f * u, hc.y + 8f * u); close()
                }
                drawPath(beak, Color(0xFFE4572E).copy(alpha = alpha)); drawPath(beak, dark.copy(alpha = alpha), style = Stroke(2f * u))
                drawCircle(Color(0xFF1B1B1B).copy(alpha = alpha), 2.6f * u, Offset(hc.x + dirF * 5f * u, hc.y - 4f * u))
                // wing
                drawArc(dark.copy(alpha = 0.8f * alpha), if (dirF > 0) 200f else -20f, 140f, false, Offset(x - 20f * u, y - 8f * u), Size(34f * u, 24f * u), style = Stroke(3f * u, cap = StrokeCap.Round))
                // little bow-tie so a small duck still reads as a duck
                drawCircle(Color.White.copy(alpha = 0.45f * alpha), 3f * u, Offset(x - dirF * 12f * u, y - 10f * u))
            }
            TargetKind.RING -> {
                val r = tg.rx.toFloat() * sc
                val outer = if (colorblind) Color(0xFF0072B2) else Color(0xFF2E6FD1)
                val mid = Color(0xFFFDFBF2)
                val core = if (colorblind) Color(0xFFD55E00) else Color(0xFFD7263D)
                drawCircle(Color(0x44000000).copy(alpha = 0.3f * alpha), r, Offset(x + 3f * sc, y + 4f * sc))
                drawCircle(outer.copy(alpha = alpha), r, Offset(x, y))
                drawCircle(mid.copy(alpha = alpha), r * 0.6f, Offset(x, y))
                drawCircle(core.copy(alpha = alpha), r * 0.25f, Offset(x, y))
                drawCircle(Color(0xFF111111).copy(alpha = alpha), r, Offset(x, y), style = Stroke(2.6f * sc))
                drawCircle(Color(0xFF111111).copy(alpha = 0.8f * alpha), r * 0.6f, Offset(x, y), style = Stroke(2f * sc))
                drawCircle(Color(0xFF111111).copy(alpha = 0.8f * alpha), r * 0.25f, Offset(x, y), style = Stroke(2f * sc))
            }
            TargetKind.STAR -> {
                val r = tg.rx.toFloat() * sc * 1.25f
                val pulse = if (reduced) 1f else 1f + 0.08f * sin(seconds * 7f)
                drawCircle(Color(0xFFFFE066).copy(alpha = 0.35f * alpha), r * 1.5f * pulse, Offset(x, y))
                val p = starPath(Offset(x, y), r * pulse)
                drawPath(p, Color(0xFFFFC107).copy(alpha = alpha))
                drawPath(p, Color(0xFF8A5A00).copy(alpha = alpha), style = Stroke(2.8f * sc))
                drawCircle(Color.White.copy(alpha = 0.6f * alpha), r * 0.14f, Offset(x - r * 0.18f, y - r * 0.22f))
            }
            else -> { // decoy: a spiky black mine with a cross on it
                val r = tg.rx.toFloat() * sc * 0.8f
                for (i in 0 until 8) {
                    val a = i * PI / 4
                    val tip = Offset(x + (cos(a) * r * 1.55f).toFloat(), y + (sin(a) * r * 1.55f).toFloat())
                    val l = Offset(x + (cos(a - 0.28) * r).toFloat(), y + (sin(a - 0.28) * r).toFloat())
                    val rr = Offset(x + (cos(a + 0.28) * r).toFloat(), y + (sin(a + 0.28) * r).toFloat())
                    val sp = Path().apply { moveTo(l.x, l.y); lineTo(tip.x, tip.y); lineTo(rr.x, rr.y); close() }
                    drawPath(sp, Color(0xFF24202A).copy(alpha = alpha))
                }
                drawCircle(Brush.radialGradient(listOf(Color(0xFF5A5466), Color(0xFF1C1822)), Offset(x - r * 0.3f, y - r * 0.3f), r * 1.4f), r, Offset(x, y), alpha = alpha)
                val c = Color(0xFFFFF3E0).copy(alpha = alpha)
                drawLine(c, Offset(x - r * 0.5f, y - r * 0.5f), Offset(x + r * 0.5f, y + r * 0.5f), 4.5f * sc, StrokeCap.Round)
                drawLine(c, Offset(x + r * 0.5f, y - r * 0.5f), Offset(x - r * 0.5f, y + r * 0.5f), 4.5f * sc, StrokeCap.Round)
                // fuse spark
                val spark = Offset(x + r * 0.6f, y - r * 1.55f)
                drawLine(Color(0xFF8D6E63).copy(alpha = alpha), Offset(x + r * 0.2f, y - r), spark, 2.5f * sc)
                if (hitAgeMs < 0 && !reduced && (seconds * 6f).toInt() % 2 == 0) drawCircle(Color(0xFFFFB300), 4f * sc, spark)
            }
        }
    }
    if (highlight && hitAgeMs < 0) {
        val pulse = if (reduced) 1f else 1f + 0.12f * sin(seconds * 8f)
        val r = (tg.rx.toFloat() + 14f) * sc * pulse
        drawCircle(Color(0xFFFFD21F), r, Offset(x, y), style = Stroke(4f * sc))
        drawCircle(Color(0xFF111111), r + 3f * sc, Offset(x, y), style = Stroke(1.6f * sc))
    }
}

/** A bullet hole (or a miss mark) that fades after a moment. */
internal fun DrawScope.drawGalleryMark(sc: Float, x: Int, y: Int, ageMs: Int, hit: Boolean) {
    val a = (1f - ageMs / 1400f).coerceIn(0f, 1f)
    if (a <= 0f) return
    val c = Offset(x * sc, y * sc)
    drawCircle(Color(0xFF111111).copy(alpha = 0.55f * a), 5.5f * sc, c)
    drawCircle(Color.White.copy(alpha = 0.8f * a), 5.5f * sc, c, style = Stroke(1.6f * sc))
    if (ageMs < 160) drawCircle(Color(0xFFFFF3B0).copy(alpha = 0.8f * (1f - ageMs / 160f)), (10f + ageMs / 10f) * sc, c)
    if (hit && ageMs < 300) drawCircle(Color.White.copy(alpha = 0.7f * (1f - ageMs / 300f)), (12f + ageMs / 6f) * sc, c, style = Stroke(2.4f * sc))
}

/** A row of bullets: [filled] standing, the rest spent; [reloadFraction] 0..1 shows the magazine refilling. */
internal fun DrawScope.drawAmmoRow(total: Int, filled: Int, reloadFraction: Float, color: Color) {
    val slot = size.width / total
    val r = minOf(slot * 0.28f, size.height * 0.5f)
    for (i in 0 until total) {
        val cx = slot * (i + 0.5f)
        val cy = size.height / 2
        val on = if (reloadFraction >= 0f) i < (reloadFraction * total).toInt() else i < filled
        val body = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(cx - r * 0.7f, cy - r * 1.35f, cx + r * 0.7f, cy + r * 1.1f, CornerRadius(r * 0.5f)))
        }
        if (on) {
            drawPath(body, color); drawRect(Color(0xFFD9A441), Offset(cx - r * 0.7f, cy + r * 0.45f), Size(r * 1.4f, r * 0.65f))
        } else drawPath(body, color.copy(alpha = 0.25f), style = Stroke(r * 0.25f))
    }
}
