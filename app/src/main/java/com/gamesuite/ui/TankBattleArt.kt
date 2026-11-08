package com.gamesuite.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.gamesuite.games.tankbattle.Tank
import com.gamesuite.games.tankbattle.TankWorld
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Original vector art for Tank Battle: a dusk sky with drifting cloud banks, layered hills, a
 * destructible meadow-and-clay ground, enamel-painted toy tanks (seat colour plus a glyph on the
 * turret, so identity never rests on colour alone), shells, fireballs and smoke. Pure drawing code:
 * the screen decides what to show and when.
 */

internal class TankArtPalette(
    val skyTop: Color, val skyBottom: Color, val sun: Color, val farHills: Color, val nearHills: Color,
    val grass: Color, val soil: Color, val deep: Color, val ink: Color
)

internal fun tankPalette(dark: Boolean) = if (!dark) TankArtPalette(
    skyTop = Color(0xFF8EC5E8), skyBottom = Color(0xFFFBE3C2), sun = Color(0xFFFFE9A8),
    farHills = Color(0xFFA9C7B5), nearHills = Color(0xFF86AF8E),
    grass = Color(0xFF5E9B3F), soil = Color(0xFFB07A49), deep = Color(0xFF6E4A2B), ink = Color(0xFF1C1B1F)
) else TankArtPalette(
    skyTop = Color(0xFF111A3A), skyBottom = Color(0xFF5B3A6B), sun = Color(0xFFE8E6FF),
    farHills = Color(0xFF2B3A55), nearHills = Color(0xFF23402F),
    grass = Color(0xFF3F7A33), soil = Color(0xFF7D5535), deep = Color(0xFF3F2A1A), ink = Color(0xFFEDE7F6)
)

/** World to pixels for a canvas that shows the whole battlefield. */
internal class TankView(val w: Float, val h: Float) {
    val sx = w / TankWorld.W
    val sy = h / TankWorld.H
    fun px(x: Double) = (x * sx).toFloat()
    fun py(y: Double) = (h - y * sy).toFloat()
    fun pt(x: Double, y: Double) = Offset(px(x), py(y))
}

/**
 * Static layers of the battlefield that were rebuilt on every animation frame: the hill ridges and the terrain
 * fill / strata / turf paths. They depend only on the canvas size and the terrain, so they are rebuilt only when
 * one of those changes (a shell crater, a resize). One instance per screen, `remember`ed; used only from the draw pass.
 */
internal class TankArtCache {
    private var ridgeW = -1f
    private var ridgeH = -1f
    private var farRidge = Path()
    private var nearRidge = Path()

    private var terrainKey: List<Int>? = null
    private var terrainW = -1f
    private var terrainH = -1f
    private var terrainSoil = Color.Unspecified
    private var terrainDeep = Color.Unspecified
    var fill = Path(); private set
    var strata: List<Path> = emptyList(); private set
    var top = Path(); private set
    var soilBrush: Brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)); private set

    fun ridges(w: Float, h: Float): Pair<Path, Path> {
        if (w != ridgeW || h != ridgeH) {
            ridgeW = w; ridgeH = h
            farRidge = ridgePath(w, h, h * 0.52f, h * 0.05f, 9f, 0.6f)
            nearRidge = ridgePath(w, h, h * 0.62f, h * 0.045f, 13f, 2.1f)
        }
        return farRidge to nearRidge
    }

    private fun ridgePath(w: Float, h: Float, baseY: Float, amp: Float, freq: Float, phase: Float) = Path().apply {
        moveTo(0f, h)
        var x = 0f
        while (x <= w + 6f) { lineTo(x, baseY + amp * sin(x / w * freq + phase)); x += 6f }
        lineTo(w, h); close()
    }

    /** Rebuilds the terrain paths when the terrain, the size or the palette changed since the last call. */
    fun terrain(v: TankView, terrain: List<Int>, pal: TankArtPalette) {
        val k = terrainKey
        if (k != null && (k === terrain || k == terrain) && v.w == terrainW && v.h == terrainH && pal.soil == terrainSoil && pal.deep == terrainDeep) return
        terrainKey = terrain; terrainW = v.w; terrainH = v.h; terrainSoil = pal.soil; terrainDeep = pal.deep
        fill = Path().apply {
            moveTo(0f, v.h)
            for (x in terrain.indices) lineTo(v.px(x + 0.5), v.py(terrain[x].toDouble()))
            lineTo(v.w, v.h); close()
        }
        soilBrush = Brush.verticalGradient(listOf(pal.soil, pal.deep), startY = v.h * 0.3f, endY = v.h)
        strata = listOf(14.0, 34.0, 60.0).map { drop ->
            Path().also { p ->
                for (x in terrain.indices step 2) {
                    val y = v.py((terrain[x] - drop).coerceAtLeast(0.0))
                    if (x == 0) p.moveTo(v.px(x + 0.5), y) else p.lineTo(v.px(x + 0.5), y)
                }
            }
        }
        top = Path().also { p ->
            for (x in terrain.indices) {
                val o = v.pt(x + 0.5, terrain[x].toDouble())
                if (x == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y)
            }
        }
    }
}

internal fun DrawScope.drawTankSky(pal: TankArtPalette, windDrift: Float, seconds: Float, reduced: Boolean, cache: TankArtCache = TankArtCache()) {
    drawRect(Brush.verticalGradient(listOf(pal.skyTop, pal.skyBottom)), size = size)
    // sun / moon
    drawCircle(pal.sun.copy(alpha = 0.35f), size.minDimension * 0.17f, Offset(size.width * 0.82f, size.height * 0.2f))
    drawCircle(pal.sun, size.minDimension * 0.095f, Offset(size.width * 0.82f, size.height * 0.2f))
    // cloud banks drift with the wind
    val drift = if (reduced) 0f else seconds * windDrift * 5f
    for (i in 0 until 4) {
        val base = (i * 0.31f + 0.07f)
        val x = (((base * size.width + drift * (0.6f + 0.2f * i)) % (size.width * 1.3f)) + size.width * 1.3f) % (size.width * 1.3f) - size.width * 0.15f
        val y = size.height * (0.12f + 0.09f * (i % 3))
        val r = size.height * (0.04f + 0.012f * (i % 2))
        val c = Color.White.copy(alpha = 0.55f)
        drawCircle(c, r, Offset(x, y)); drawCircle(c, r * 1.2f, Offset(x + r, y - r * 0.3f)); drawCircle(c, r, Offset(x + r * 2.1f, y))
    }
    // two ridges of far hills (cached: they depend only on the canvas size)
    val (far, near) = cache.ridges(size.width, size.height)
    drawPath(far, pal.farHills)
    drawPath(near, pal.nearHills)
}

internal fun DrawScope.drawTankTerrain(v: TankView, terrain: List<Int>, pal: TankArtPalette, cache: TankArtCache = TankArtCache()) {
    cache.terrain(v, terrain, pal)
    drawPath(cache.fill, cache.soilBrush)
    // strata lines follow the surface
    for ((k, p) in cache.strata.withIndex()) {
        drawPath(p, pal.deep.copy(alpha = 0.35f - 0.07f * k), style = Stroke(v.sy * 1.4f))
    }
    // pebbles
    for (i in 0 until 46) {
        val x = (i * 37 + 11) % terrain.size
        val depth = 8.0 + (i * 53 % 40)
        val y = (terrain[x] - depth)
        if (y > 2) drawCircle(pal.deep.copy(alpha = 0.55f), v.sx * (0.9f + (i % 3) * 0.5f), v.pt(x + 0.5, y))
    }
    // turf along the surface
    val top = cache.top
    drawPath(top, pal.grass, style = Stroke(v.sy * 3.6f, cap = StrokeCap.Round))
    drawPath(top, Color.White.copy(alpha = 0.18f), style = Stroke(v.sy * 1.0f, cap = StrokeCap.Round))
}

/**
 * An enamel toy tank. [seat] chooses the glyph set into the turret; [color] is the seat colour;
 * [angle] is the barrel in degrees (0 right, 90 up); [hp] 0 means a burnt-out wreck; [seconds] drives smoke.
 */
internal fun DrawScope.drawTank(
    v: TankView, tank: Tank, seat: Int, terrain: List<Int>, color: Color, ink: Color,
    barrelAngle: Float, hpShown: Int, bounce: Float, seconds: Float, reduced: Boolean
) {
    val cx = tank.x.toDouble()
    val ground = terrain[tank.x].toDouble()
    val u = v.sx
    val base = v.pt(cx, ground)
    val dead = hpShown <= 0
    val body = if (dead) Color(0xFF4A4642) else color
    val dark = clShade(body, -0.45f)
    val light = clShade(body, 0.35f)
    // contact shadow
    drawOval(Color(0x55000000), Offset(base.x - 9f * u, base.y - 1.2f * u), Size(18f * u, 3f * u))
    val lift = -bounce * 3f * u
    // tracks
    val trackTop = base.y - 5.2f * u + lift
    drawRoundRect(Color(0xFF26231F), Offset(base.x - 8f * u, trackTop), Size(16f * u, 5.2f * u), androidx.compose.ui.geometry.CornerRadius(2.6f * u))
    for (i in 0 until 4) {
        val wx = base.x - 5.6f * u + i * 3.7f * u
        drawCircle(Color(0xFF4F4A44), 1.55f * u, Offset(wx, trackTop + 2.6f * u))
        drawCircle(Color(0xFF8B857C), 0.55f * u, Offset(wx, trackTop + 2.6f * u))
    }
    // hull
    val hull = Path().apply {
        moveTo(base.x - 7f * u, trackTop + 1.2f * u)
        lineTo(base.x - 5.2f * u, trackTop - 3.4f * u)
        lineTo(base.x + 5.2f * u, trackTop - 3.4f * u)
        lineTo(base.x + 7f * u, trackTop + 1.2f * u)
        close()
    }
    drawPath(hull, Brush.verticalGradient(listOf(light, body, dark), startY = trackTop - 3.4f * u, endY = trackTop + 1.2f * u))
    drawPath(hull, dark, style = Stroke(0.7f * u))
    // barrel (drawn behind the turret)
    val a = (barrelAngle * PI / 180.0)
    val pivot = Offset(base.x, trackTop - 3.4f * u)
    val dirX = cos(a).toFloat(); val dirY = -sin(a).toFloat()
    val tip = Offset(pivot.x + dirX * 9f * u, pivot.y + dirY * 9f * u)
    drawLine(Color(0xFF1F1C19), pivot, tip, 3.1f * u, StrokeCap.Round)
    drawLine(dark, pivot, tip, 2.2f * u, StrokeCap.Round)
    drawLine(light.copy(alpha = 0.7f), Offset(pivot.x + dirY * 0.6f * u, pivot.y - dirX * 0.6f * u), Offset(tip.x + dirY * 0.6f * u, tip.y - dirX * 0.6f * u), 0.6f * u, StrokeCap.Round)
    drawCircle(Color(0xFF1F1C19), 1.5f * u, tip)
    // turret dome with the seat glyph
    drawCircle(Brush.radialGradient(listOf(light, body, dark), Offset(pivot.x - 1.2f * u, pivot.y - 1.6f * u), 5.4f * u), 3.9f * u, pivot)
    drawCircle(dark, 3.9f * u, pivot, style = Stroke(0.6f * u))
    drawSeatGlyph(seat, Offset(pivot.x, pivot.y - 0.1f * u), 2.1f * u)
    if (dead) {
        // smoke column
        run {
            for (k in 0 until 4) {
                val ph = ((seconds * 0.5f + k * 0.25f) % 1f)
                val sxp = base.x + sin((seconds + k) * 1.7f) * 1.2f * u
                val syp = pivot.y - ph * 22f * u
                drawCircle(Color(0xFF6B6661).copy(alpha = (1f - ph) * 0.55f), (2f + ph * 3f) * u, Offset(sxp, if (reduced) pivot.y - (k + 1) * 4f * u else syp))
            }
        }
    }
}

/** Hit points above a tank: a bar, the number and a short pip row so it reads without colour. */
internal fun DrawScope.drawTankHp(v: TankView, tank: Tank, terrain: List<Int>, hpShown: Int, color: Color, ink: Color, isTurn: Boolean, seconds: Float, reduced: Boolean) {
    if (hpShown <= 0) return
    val u = v.sx
    val c = v.pt(tank.x.toDouble(), terrain[tank.x] + 19.0)
    val w = 17f * u; val h = 2.4f * u
    drawRoundRect(Color(0xAA000000), Offset(c.x - w / 2 - 0.5f * u, c.y - h / 2 - 0.5f * u), Size(w + u, h + u), androidx.compose.ui.geometry.CornerRadius(h))
    val frac = (hpShown / TankWorld.START_HP.toFloat()).coerceIn(0f, 1f)
    val bar = if (frac > 0.5f) Color(0xFF8BD17C) else if (frac > 0.25f) Color(0xFFF2C14E) else Color(0xFFF2776B)
    drawRoundRect(bar, Offset(c.x - w / 2, c.y - h / 2), Size(w * frac, h), androidx.compose.ui.geometry.CornerRadius(h))
    if (isTurn) {
        val bob = if (reduced) 0f else sin(seconds * 5f) * 1.2f * u
        val a = Offset(c.x, c.y - 5.5f * u + bob)
        val p = Path().apply {
            moveTo(a.x, a.y); lineTo(a.x - 2.6f * u, a.y - 3.8f * u); lineTo(a.x + 2.6f * u, a.y - 3.8f * u); close()
        }
        drawPath(p, Color.White); drawPath(p, Color(0xFF111111), style = Stroke(0.8f * u))
        drawCircle(color, 1.0f * u, Offset(a.x, a.y - 2.2f * u))
    }
}

internal fun DrawScope.drawShell(v: TankView, at: Offset, previous: Offset, big: Boolean) {
    val u = v.sx
    val r = (if (big) 2.6f else 1.9f) * u
    drawLine(Color(0x66FFE9A8), previous, at, r * 1.3f, StrokeCap.Round)
    drawCircle(Color(0x55FFB74D), r * 2.1f, at)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF3C4), Color(0xFF3A3530)), at, r), r, at)
    drawCircle(Color(0xFF111111), r, at, style = Stroke(0.5f * u))
}

internal fun DrawScope.drawExplosion(v: TankView, centre: Offset, radiusWorld: Float, p: Float) {
    if (p >= 1f) return
    val r = radiusWorld * v.sx
    val a = 1f - p
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFFFFFFFF).copy(alpha = a), Color(0xFFFFD166).copy(alpha = a), Color(0xFFEF6C00).copy(alpha = a * 0.8f), Color(0x00B71C1C)),
            centre, (r * (0.5f + 0.9f * p)).coerceAtLeast(1f)
        ),
        r * (0.5f + 0.9f * p), centre
    )
    drawCircle(Color.White.copy(alpha = a * 0.8f), r * (0.4f + 1.5f * p), centre, style = Stroke(2.5f + 3f * a))
}

internal fun DrawScope.drawBouncePuff(v: TankView, centre: Offset, p: Float) {
    if (p >= 1f) return
    drawCircle(Color(0xFFD7C4A0).copy(alpha = 0.7f * (1f - p)), v.sx * (2.5f + 6f * p), centre)
    drawCircle(Color(0xFFD7C4A0).copy(alpha = 0.8f * (1f - p)), v.sx * (4f + 5f * p), centre, style = Stroke(1.5f))
}

/** Dotted preview of the first part of the shot (the shell's path for the first moments). */
internal fun DrawScope.drawAimPreview(v: TankView, pts: List<Offset>, color: Color) {
    pts.forEachIndexed { i, o ->
        drawCircle(Color(0xFF111111).copy(alpha = 0.6f), 1.5f * v.sx + 1.6f, o)
        drawCircle(color.copy(alpha = 1f - i * (0.75f / pts.size.coerceAtLeast(1))), 1.5f * v.sx, o)
    }
}

/** Shell icon for the weapon chips: the shape differs per shell (dot, big dot, zig-zag) as well as the label. */
internal fun DrawScope.drawShellIcon(kind: String, color: Color) {
    val c = Offset(size.width / 2, size.height / 2)
    val r = size.minDimension * 0.5f
    when (kind) {
        "big" -> { drawCircle(color, r * 0.78f, c); drawCircle(Color.White.copy(alpha = 0.55f), r * 0.2f, Offset(c.x - r * 0.25f, c.y - r * 0.28f)) }
        "bouncer" -> {
            val p = Path().apply {
                moveTo(c.x - r * 0.9f, c.y + r * 0.55f); lineTo(c.x - r * 0.3f, c.y - r * 0.5f)
                lineTo(c.x + r * 0.2f, c.y + r * 0.4f); lineTo(c.x + r * 0.9f, c.y - r * 0.6f)
            }
            drawPath(p, color, style = Stroke(r * 0.3f, cap = StrokeCap.Round))
            drawCircle(color, r * 0.22f, Offset(c.x + r * 0.9f, c.y - r * 0.6f))
        }
        else -> drawCircle(color, r * 0.4f, c)
    }
}
