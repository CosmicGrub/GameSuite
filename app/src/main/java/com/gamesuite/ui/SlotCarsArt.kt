package com.gamesuite.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.gamesuite.games.slotcars.SlotCarsRules
import com.gamesuite.games.slotcars.SlotTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Original vector art for Slot Cars: a painted-toy racing set seen from above. Felt-green baize, a
 * grey road with a slot down each lane, red and white kerbs, amber chevron signs before every bend
 * and little glossy cars. Cars are told apart by colour AND a roof pattern AND a number, never by
 * colour alone (Okabe-Ito colours, which stay distinct for every common kind of colour blindness).
 */

internal val SlotCarColors = listOf(Color(0xFFD55E00), Color(0xFF0072B2), Color(0xFFE8D22A), Color(0xFF009E73))

/** Roof patterns: 0 centre stripe, 1 dots, 2 checks, 3 twin stripes. */
internal fun slotCarPatternName(seat: Int) = seat % 4

internal class SlotCarsPalette(
    val baize: Color, val baizeStripe: Color, val road: Color, val roadEdge: Color, val slot: Color,
    val kerbA: Color, val kerbB: Color, val sign: Color, val text: Color, val card: Color, val bg: Color, val gold: Color, val accent: Color
)

internal fun slotCarsPalette(dark: Boolean) = if (!dark) SlotCarsPalette(
    baize = Color(0xFF4F9A5A), baizeStripe = Color(0xFF478F52), road = Color(0xFF4A4F57), roadEdge = Color(0xFF2E3238), slot = Color(0xFF15181C),
    kerbA = Color(0xFFD64541), kerbB = Color(0xFFF5F5F0), sign = Color(0xFFFFB400), text = Color(0xFF1B2A1E), card = Color(0xFFDDE8D9),
    bg = Color(0xFFF1F5EE), gold = Color(0xFFE0A100), accent = Color(0xFF1E7B34)
) else SlotCarsPalette(
    baize = Color(0xFF2A5E33), baizeStripe = Color(0xFF265730), road = Color(0xFF3A3F47), roadEdge = Color(0xFF1B1E22), slot = Color(0xFF0B0D0F),
    kerbA = Color(0xFFC0443F), kerbB = Color(0xFFDADAD2), sign = Color(0xFFFFC233), text = Color(0xFFE6F0E3), card = Color(0xFF222B22),
    bg = Color(0xFF111611), gold = Color(0xFFFFC83D), accent = Color(0xFF5BD477)
)

/** Maps track units to pixels, turning the track a quarter when the canvas is taller than wide. */
internal class SlotXf(val w: Float, val h: Float, val track: SlotTrack) {
    val rotated = h > w * 1.12f
    private val pad = 70.0
    private val bw = track.maxX - track.minX + 2 * pad
    private val bh = track.maxY - track.minY + 2 * pad
    private val cx = (track.minX + track.maxX) / 2
    private val cy = (track.minY + track.maxY) / 2
    val scale: Float = if (!rotated) min(w / bw.toFloat(), h / bh.toFloat()) else min(w / bh.toFloat(), h / bw.toFloat())

    fun pt(x: Double, y: Double): Offset {
        val ux = (x - cx).toFloat() * scale; val uy = (y - cy).toFloat() * scale
        return if (!rotated) Offset(w / 2 + ux, h / 2 + uy) else Offset(w / 2 - uy, h / 2 + ux)
    }

    fun len(u: Double): Float = (u * scale).toFloat()
    fun angle(a: Double): Double = if (rotated) a + PI / 2 else a
}

internal fun slotRoadPath(xf: SlotXf, track: SlotTrack, lane: Int = -1, lanes: Int = 1): Path {
    val p = Path()
    var s = 0.0
    var first = true
    while (s < track.length) {
        val o = if (lane < 0) xf.pt(track.x(s), track.y(s)) else xf.pt(track.laneX(s, lane, lanes), track.laneY(s, lane, lanes))
        if (first) { p.moveTo(o.x, o.y); first = false } else p.lineTo(o.x, o.y)
        s += 7.0
    }
    p.close()
    return p
}

internal fun DrawScope.drawSlotBaize(pal: SlotCarsPalette) {
    drawRect(pal.baize)
    val step = size.minDimension / 9f
    var x = 0f
    var k = 0
    while (x < size.width) { if (k % 2 == 0) drawRect(pal.baizeStripe, Offset(x, 0f), Size(step, size.height)); x += step; k++ }
}

/** Road, kerbs, slots, start line and the amber chevron signs before each bend. */
internal fun DrawScope.drawSlotTrack(xf: SlotXf, track: SlotTrack, lanes: Int, pal: SlotCarsPalette, roadPath: Path, lanePaths: List<Path>) {
    val width = SlotCarsRules.LANE_WIDTH * 4 + 8.0
    drawPath(roadPath, Color(0x33000000), style = Stroke(xf.len(width + 14), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(roadPath, pal.kerbB, style = Stroke(xf.len(width + 10), cap = StrokeCap.Butt, join = StrokeJoin.Round))
    drawPath(
        roadPath, pal.kerbA,
        style = Stroke(xf.len(width + 10), cap = StrokeCap.Butt, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(xf.len(16.0), xf.len(16.0))))
    )
    drawPath(roadPath, pal.roadEdge, style = Stroke(xf.len(width + 2), join = StrokeJoin.Round))
    drawPath(roadPath, pal.road, style = Stroke(xf.len(width - 2), join = StrokeJoin.Round))
    for (lp in lanePaths) drawPath(lp, pal.slot, style = Stroke(xf.len(2.6), join = StrokeJoin.Round))
    // start / finish chequered line across the road at s = 0
    val c = xf.pt(track.x(0.0), track.y(0.0))
    val ang = xf.angle(track.headingAt(0.0)).toFloat()
    rotate(Math.toDegrees(ang.toDouble()).toFloat(), c) {
        val cell = xf.len(width / 6.0)
        for (r in 0 until 6) for (q in 0 until 2) {
            drawRect(if ((r + q) % 2 == 0) Color.White else Color(0xFF111111), Offset(c.x - cell + q * cell, c.y - 3 * cell + r * cell), Size(cell, cell))
        }
    }
    // amber chevron signs a braking distance before each bend, on the outside of the road
    for (corner in track.corners) {
        val s = corner.startS - 70.0
        val sx = track.x(s); val sy = track.y(s); val h = track.headingAt(s)
        val ox = sx - sin(h) * (width / 2 + 24); val oy = sy + cos(h) * (width / 2 + 24)
        val o = xf.pt(ox, oy)
        val r = xf.len(14.0)
        val tri = Path().apply { moveTo(o.x, o.y - r); lineTo(o.x + r * 0.95f, o.y + r * 0.8f); lineTo(o.x - r * 0.95f, o.y + r * 0.8f); close() }
        drawPath(tri, Color(0x55000000), style = Stroke(r * 0.45f))
        drawPath(tri, pal.sign)
        drawLine(Color(0xFF3A2A00), Offset(o.x, o.y - r * 0.3f), Offset(o.x, o.y + r * 0.25f), strokeWidth = r * 0.28f, cap = StrokeCap.Round)
        drawCircle(Color(0xFF3A2A00), r * 0.13f, Offset(o.x, o.y + r * 0.55f))
    }
}

/**
 * A little glossy car at [c] pointing along screen angle [angle] (radians). [slip] 0..1 turns the car
 * out of line and throws sparks, [blink] makes it flash while it is being put back, [number] is the
 * roof numeral drawn by the caller as a pattern of dots, [spin] spins it when it flies off.
 */
internal fun DrawScope.drawSlotCar(
    c: Offset, angle: Double, unit: Float, seat: Int, color: Color, throttle: Boolean, slip: Float, blink: Boolean, spin: Float = 0f, alpha: Float = 1f
) {
    val L = unit * 24f; val W = unit * 12f
    val deg = Math.toDegrees(angle).toFloat() + spin + slip * 9f * (if (seat % 2 == 0) 1f else -1f)
    val a = if (blink) 0.45f * alpha else alpha
    rotate(deg, c) {
        drawRoundRect(Color(0x55000000), Offset(c.x - L / 2 + unit, c.y - W / 2 + unit * 1.5f), Size(L, W), CornerRadius(W * 0.45f), alpha = a)
        if (throttle) {
            drawCircle(Color(0xFFFFE08A).copy(alpha = 0.55f * a), W * 0.4f, Offset(c.x - L / 2 - W * 0.15f, c.y))
        }
        drawRoundRect(
            Brush.verticalGradient(listOf(clShade(color, 0.35f), color, clShade(color, -0.35f)), c.y - W / 2, c.y + W / 2),
            Offset(c.x - L / 2, c.y - W / 2), Size(L, W), CornerRadius(W * 0.45f), alpha = a
        )
        drawRoundRect(clShade(color, -0.55f).copy(alpha = 0.8f * a), Offset(c.x - L / 2, c.y - W / 2), Size(L, W), CornerRadius(W * 0.45f), style = Stroke(unit * 1.1f))
        // windscreen
        drawRoundRect(Color(0xCC1A2330).copy(alpha = 0.8f * a), Offset(c.x + L * 0.04f, c.y - W * 0.32f), Size(L * 0.2f, W * 0.64f), CornerRadius(W * 0.12f))
        // roof pattern (the colour-blind-safe identity)
        val pat = Color.White.copy(alpha = 0.9f * a)
        when (slotCarPatternName(seat)) {
            0 -> drawRect(pat, Offset(c.x - L * 0.40f, c.y - W * 0.09f), Size(L * 0.5f, W * 0.18f))
            1 -> for (i in 0 until 3) drawCircle(pat, W * 0.1f, Offset(c.x - L * 0.3f + i * L * 0.15f, c.y))
            2 -> for (i in 0 until 3) for (j in 0 until 2) if ((i + j) % 2 == 0) drawRect(pat, Offset(c.x - L * 0.38f + i * L * 0.15f, c.y - W * 0.2f + j * W * 0.2f), Size(L * 0.15f, W * 0.2f))
            else -> { drawRect(pat, Offset(c.x - L * 0.40f, c.y - W * 0.2f), Size(L * 0.5f, W * 0.1f)); drawRect(pat, Offset(c.x - L * 0.40f, c.y + W * 0.1f), Size(L * 0.5f, W * 0.1f)) }
        }
        // number pips on the bonnet: one to four
        for (i in 0..seat) drawCircle(Color(0xFF111111).copy(alpha = a), W * 0.07f, Offset(c.x + L * 0.33f, c.y - W * 0.2f + i * W * 0.13f))
        // headlights
        drawCircle(Color(0xFFFFF6C8).copy(alpha = a), W * 0.09f, Offset(c.x + L * 0.47f, c.y - W * 0.3f))
        drawCircle(Color(0xFFFFF6C8).copy(alpha = a), W * 0.09f, Offset(c.x + L * 0.47f, c.y + W * 0.3f))
    }
    if (slip > 0.5f) {
        val n = 4
        for (i in 0 until n) {
            val ang = angle + PI + (i - 1.5) * 0.45
            val d = unit * (14f + 6f * i)
            drawCircle(Color(0xFFFFD27A).copy(alpha = (0.9f - 0.15f * i) * slip), unit * (2.4f - 0.3f * i), Offset(c.x + (cos(ang) * d).toFloat(), c.y + (sin(ang) * d).toFloat()))
        }
    }
}

/** Five start lights: [lit] red lights then green on GO. */
internal fun DrawScope.drawStartLights(center: Offset, unit: Float, lit: Int, go: Boolean) {
    val gap = unit * 26f
    drawRoundRect(Color(0xCC101418), Offset(center.x - gap * 1.9f, center.y - unit * 14f), Size(gap * 3.8f, unit * 28f), CornerRadius(unit * 10f))
    for (i in 0 until 3) {
        val on = if (go) true else i < lit
        val col = if (go) Color(0xFF3DDC84) else Color(0xFFFF3B30)
        drawCircle(if (on) col else Color(0xFF3A3F47), unit * 9f, Offset(center.x + (i - 1) * gap, center.y))
        if (on) drawCircle(col.copy(alpha = 0.35f), unit * 14f, Offset(center.x + (i - 1) * gap, center.y))
    }
}

/** A grip meter: a bar that fills with how close the car is to leaving the slot, with a danger band and tick marks. */
internal fun DrawScope.drawGripBar(use: Float, pal: SlotCarsPalette) {
    val u = use.coerceIn(0f, 1.2f)
    drawRoundRect(pal.card, Offset.Zero, size, CornerRadius(size.height / 2))
    val full = size.width * (u / 1.2f)
    val col = when { u > 0.95f -> Color(0xFFD64541); u > 0.8f -> pal.sign; else -> pal.accent }
    drawRoundRect(col, Offset.Zero, Size(max(full, 0f), size.height), CornerRadius(size.height / 2))
    // hatching of the danger band (shape, not colour alone)
    val dz = size.width * (0.8f / 1.2f)
    var x = dz
    while (x < size.width) { drawLine(Color(0x99000000), Offset(x, 0f), Offset(x + size.height * 0.5f, size.height), strokeWidth = 2f); x += size.height * 0.6f }
    drawLine(pal.text, Offset(dz, 0f), Offset(dz, size.height), strokeWidth = 3f)
}
