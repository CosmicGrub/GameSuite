package com.gamesuite.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.gamesuite.games.botbastion.BbArena
import com.gamesuite.games.botbastion.BbBot
import com.gamesuite.games.botbastion.BbEnemy
import com.gamesuite.games.botbastion.BbEngine
import com.gamesuite.games.botbastion.BotStats
import com.gamesuite.games.botbastion.BotType
import com.gamesuite.games.botbastion.EnemyType
import com.gamesuite.games.botbastion.MapDef
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** One short visual effect, in game time. Written by the frame callback, read by the draw lambda. */
internal class BbFx(
    val kind: Kind, val x: Float, val y: Float, val x2: Float, val y2: Float, val born: Double, val life: Double, val tint: Color
) {
    enum class Kind { SHOT, BLAST, FREEZE, KILL, CORE_HIT, NOVA }
}

/** Original vector art for the arena. Everything is plain paths and circles so it scales to any screen. */
internal object BbPalette {
    val space = Color(0xFF0B1026)
    val spaceFar = Color(0xFF151C3F)
    val lane = Color(0xFF26356B)
    val laneEdge = Color(0xFF4A66C4)
    val voidFill = Color(0xFF05060F)
    val node = Color(0xFF8FA6D8)
    val nodeSel = Color(0xFFFFE066)
    val core = Color(0xFF4FD1C5)
    val shield = Color(0xFF9BE7FF)
    val danger = Color(0xFFFF5D73)
    val hpBack = Color(0x88000000)
    val players = listOf(Color(0xFFFFFFFF), Color(0xFFFFD166), Color(0xFF06D6A0), Color(0xFFEF476F))

    fun bot(t: BotType): Color = when (t) {
        BotType.PICKET -> Color(0xFF4CC9F0)
        BotType.LOBBER -> Color(0xFFF4A261)
        BotType.HAZE -> Color(0xFFB5E48C)
        BotType.SKIFF -> Color(0xFFF72585)
        BotType.TENDER -> Color(0xFF9D8CFF)
    }

    fun enemy(t: EnemyType): Color = when (t) {
        EnemyType.DRIFTER -> Color(0xFFB08968)
        EnemyType.DARTER -> Color(0xFFE9C46A)
        EnemyType.PLATER -> Color(0xFF9AA5B1)
        EnemyType.SPLITTER -> Color(0xFFD66BA0)
        EnemyType.COURIER -> Color(0xFF7FD86B)
        EnemyType.BEHEMOTH -> Color(0xFFE76F51)
        EnemyType.BIT -> Color(0xFFE9A6C8)
    }
}

private fun polygon(c: Offset, r: Float, sides: Int, rot: Float = -PI.toFloat() / 2): Path {
    val p = Path()
    for (i in 0 until sides) {
        val a = rot + i * 2 * PI.toFloat() / sides
        val x = c.x + r * cos(a)
        val y = c.y + r * sin(a)
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

/** The star field: fixed positions, so it never costs more than a loop of dots. */
internal fun bbStars(): List<Triple<Float, Float, Float>> {
    val r = Random(42)
    return List(70) { Triple(r.nextFloat(), r.nextFloat(), 0.4f + r.nextFloat() * 1.3f) }
}

internal fun DrawScope.drawBbBackdrop(stars: List<Triple<Float, Float, Float>>) {
    drawRect(BbPalette.space)
    drawCircle(BbPalette.spaceFar, radius = size.height * 0.7f, center = Offset(size.width * 0.2f, size.height * 0.85f))
    for ((x, y, r) in stars) drawCircle(Color(0x99FFFFFF), radius = r, center = Offset(x * size.width, y * size.height))
}

internal fun DrawScope.drawBbMap(map: MapDef, scale: Float) {
    for (v in map.voids) {
        drawOval(BbPalette.voidFill, Offset((v.cx - v.rx).toFloat() * scale, (v.cy - v.ry).toFloat() * scale),
            Size((v.rx * 2).toFloat() * scale, (v.ry * 2).toFloat() * scale))
        drawOval(BbPalette.laneEdge.copy(alpha = 0.5f), Offset((v.cx - v.rx).toFloat() * scale, (v.cy - v.ry).toFloat() * scale),
            Size((v.rx * 2).toFloat() * scale, (v.ry * 2).toFloat() * scale), style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
    }
    for (lane in map.lanes) {
        val path = Path()
        lane.points.forEachIndexed { i, p ->
            val x = p.x.toFloat() * scale
            val y = p.y.toFloat() * scale
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, BbPalette.laneEdge, style = Stroke(width = 0.72f * scale, cap = StrokeCap.Round))
        drawPath(path, BbPalette.lane, style = Stroke(width = 0.6f * scale, cap = StrokeCap.Round))
        // the entry is marked with a ring and a chevron so it never relies on colour alone
        val e = lane.points.first()
        val ex = (e.x.toFloat()).coerceIn(0.35f, BbArena.W.toFloat() - 0.35f) * scale
        val ey = (e.y.toFloat()).coerceIn(0.35f, BbArena.H.toFloat() - 0.35f) * scale
        drawCircle(BbPalette.danger, radius = 0.32f * scale, center = Offset(ex, ey), style = Stroke(width = 3f))
        drawPath(polygon(Offset(ex, ey), 0.16f * scale, 3), BbPalette.danger)
    }
}

internal fun DrawScope.drawBbCore(engine: BbEngine, scale: Float, now: Double, hitFlash: Float) {
    val c = Offset(engine.map.core.x.toFloat() * scale, engine.map.core.y.toFloat() * scale)
    val frac = engine.coreHp / engine.maxCore.toFloat()
    val body = if (frac > 0.5f) BbPalette.core else if (frac > 0.25f) Color(0xFFFFC857) else BbPalette.danger
    if (engine.shield > 0) drawCircle(BbPalette.shield.copy(alpha = 0.55f), radius = 0.95f * scale, center = c, style = Stroke(width = 3f + (engine.shield / 40f).coerceAtMost(4f)))
    drawCircle(body.copy(alpha = 0.25f), radius = 0.8f * scale, center = c)
    drawPath(polygon(c, 0.62f * scale, 6, 0f), body)
    drawPath(polygon(c, 0.34f * scale, 6, 0f), BbPalette.space)
    drawCircle(body, radius = 0.14f * scale, center = c)
    if (hitFlash > 0f) drawCircle(BbPalette.danger.copy(alpha = hitFlash * 0.6f), radius = 1.1f * scale, center = c)
}

internal fun DrawScope.drawBbNode(p: Offset, scale: Float, selected: Boolean, occupied: Boolean) {
    if (occupied) return
    drawCircle(if (selected) BbPalette.nodeSel else BbPalette.node, radius = 0.34f * scale, center = p,
        style = Stroke(width = if (selected) 4f else 2f, pathEffect = if (selected) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
    drawCircle((if (selected) BbPalette.nodeSel else BbPalette.node).copy(alpha = 0.25f), radius = 0.1f * scale, center = p)
}

internal fun DrawScope.drawBbBot(b: BbBot, scale: Float, selected: Boolean, time: Double) {
    val c = Offset(b.x.toFloat() * scale, b.y.toFloat() * scale)
    val col = BbPalette.bot(b.type)
    val r = 0.4f * scale
    // each bot has its own silhouette: circle, square, triangle, diamond, hexagon
    when (b.type) {
        BotType.PICKET -> { drawCircle(col, r, c); drawCircle(BbPalette.space, r * 0.45f, c) }
        BotType.LOBBER -> { drawRect(col, Offset(c.x - r, c.y - r), Size(r * 2, r * 2)); drawRect(BbPalette.space, Offset(c.x - r * 0.4f, c.y - r * 0.4f), Size(r * 0.8f, r * 0.8f)) }
        BotType.HAZE -> { drawPath(polygon(c, r * 1.15f, 3), col); drawCircle(BbPalette.space, r * 0.3f, c.copy(y = c.y + r * 0.2f)) }
        BotType.SKIFF -> { drawPath(polygon(c, r * 1.1f, 4), col); drawCircle(BbPalette.space, r * 0.3f, c) }
        BotType.TENDER -> { drawPath(polygon(c, r * 1.1f, 6, 0f), col); drawLine(BbPalette.space, Offset(c.x - r * 0.5f, c.y), Offset(c.x + r * 0.5f, c.y), 3f); drawLine(BbPalette.space, Offset(c.x, c.y - r * 0.5f), Offset(c.x, c.y + r * 0.5f), 3f) }
    }
    // the owner's colour ring, and level pips
    drawCircle(BbPalette.players[b.owner.coerceIn(0, 3)], radius = r * 1.25f, center = c, style = Stroke(width = 2f))
    for (i in 0 until b.level) drawCircle(Color.White, radius = 2.2f, center = Offset(c.x - (b.level - 1) * 4f + i * 8f, c.y + r * 1.7f))
    if (selected) {
        val range = BotStats.rangeU(b.type, b.level).toFloat() * scale
        val dashed = b.type == BotType.LOBBER || b.type == BotType.HAZE // solid versus dashed rings: not colour alone
        drawCircle(col.copy(alpha = 0.9f), radius = range, center = c, style = Stroke(width = 2.5f, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null))
        if (b.type == BotType.LOBBER) drawCircle(col.copy(alpha = 0.5f), radius = (BotStats.BLIND_U * scale).toFloat(), center = c, style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))))
    }
}

internal fun DrawScope.drawBbEnemy(e: BbEnemy, scale: Float) {
    val c = Offset(e.x.toFloat() * scale, e.y.toFloat() * scale)
    val col = BbPalette.enemy(e.type)
    when (e.type) {
        EnemyType.DRIFTER -> drawCircle(col, 0.26f * scale, c)
        EnemyType.DARTER -> drawPath(polygon(c, 0.26f * scale, 3, 0f), col)
        EnemyType.PLATER -> { drawPath(polygon(c, 0.36f * scale, 6, 0f), col); drawPath(polygon(c, 0.2f * scale, 6, 0f), BbPalette.space.copy(alpha = 0.6f)) }
        EnemyType.SPLITTER -> drawRect(col, Offset(c.x - 0.25f * scale, c.y - 0.25f * scale), Size(0.5f * scale, 0.5f * scale))
        EnemyType.COURIER -> { drawPath(polygon(c, 0.34f * scale, 4), col); drawCircle(Color.White, 0.1f * scale, c) }
        EnemyType.BEHEMOTH -> { drawPath(polygon(c, 0.8f * scale, 8), col); drawPath(polygon(c, 0.5f * scale, 8), BbPalette.space.copy(alpha = 0.55f)); drawCircle(Color.White, 0.12f * scale, c) }
        EnemyType.BIT -> drawCircle(col, 0.13f * scale, c)
    }
    if (e.freeze > 0) drawCircle(Color(0xFFBFE9FF), radius = 0.42f * scale, center = c, style = Stroke(width = 2.5f))
    else if (e.slow > 0) drawCircle(Color(0xFF9D8CFF).copy(alpha = 0.7f), radius = 0.4f * scale, center = c, style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))))
    if (e.hp < e.maxHp) {
        val w = if (e.type == EnemyType.BEHEMOTH) 1.6f * scale else 0.7f * scale
        val top = c.y - (if (e.type == EnemyType.BEHEMOTH) 1.0f else 0.5f) * scale
        drawRect(BbPalette.hpBack, Offset(c.x - w / 2, top), Size(w, 4f))
        drawRect(if (e.hp / e.maxHp > 0.4) Color(0xFF7BE495) else BbPalette.danger, Offset(c.x - w / 2, top), Size(w * (e.hp / e.maxHp).toFloat().coerceIn(0f, 1f), 4f))
    }
}

internal fun DrawScope.drawBbFx(list: List<BbFx>, now: Double, scale: Float, reduced: Boolean) {
    for (f in list) {
        val age = ((now - f.born) / f.life).toFloat()
        if (age < 0f || age > 1f) continue
        val a = 1f - age
        when (f.kind) {
            BbFx.Kind.SHOT -> drawLine(f.tint.copy(alpha = a), Offset(f.x * scale, f.y * scale), Offset(f.x2 * scale, f.y2 * scale), if (reduced) 2f else 3f)
            BbFx.Kind.BLAST -> drawCircle(f.tint.copy(alpha = a * 0.7f), radius = (if (reduced) f.x2 else f.x2 * (0.4f + 0.6f * age)) * scale, center = Offset(f.x * scale, f.y * scale), style = Stroke(width = 3f))
            BbFx.Kind.FREEZE -> drawCircle(Color(0xFFBFE9FF).copy(alpha = a), radius = 0.6f * scale, center = Offset(f.x * scale, f.y * scale), style = Stroke(width = 2f))
            BbFx.Kind.KILL -> if (!reduced) {
                for (i in 0 until 6) {
                    val ang = i * PI.toFloat() / 3
                    val d = (0.2f + 0.5f * age) * scale
                    drawCircle(f.tint.copy(alpha = a), radius = 2.5f, center = Offset(f.x * scale + cos(ang) * d, f.y * scale + sin(ang) * d))
                }
            }
            BbFx.Kind.CORE_HIT -> drawCircle(BbPalette.danger.copy(alpha = a * 0.5f), radius = 1.3f * scale, center = Offset(f.x * scale, f.y * scale))
            BbFx.Kind.NOVA -> drawRect(Color.White.copy(alpha = a * (if (reduced) 0.25f else 0.5f)), Offset.Zero, size)
        }
    }
}
