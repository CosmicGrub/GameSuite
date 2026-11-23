package com.gamesuite.ui

import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.gamesuite.games.towerdefence.TdBoardMath
import com.gamesuite.games.towerdefence.TdBranch
import com.gamesuite.games.towerdefence.TdEnemyKind
import com.gamesuite.games.towerdefence.TdGrid
import com.gamesuite.games.towerdefence.TdPlaceResult
import com.gamesuite.games.towerdefence.TdRole
import com.gamesuite.games.towerdefence.TdRules
import com.gamesuite.games.towerdefence.TdScan
import com.gamesuite.games.towerdefence.TowerDefenceGame
import com.gamesuite.ui.effects.BurstParticle

/** A tower being placed or moved, with the verdict of the placement rules for its current cell. */
data class TdGhost(val role: TdRole, val cx: Int, val cy: Int, val result: TdPlaceResult, val moving: Boolean) {
    val valid: Boolean get() = result is TdPlaceResult.Ok
}

/** Route polylines are recomputed only when the towers change (the signature), never per frame. */
internal class TdRouteCache {
    var signature: Int = Int.MIN_VALUE
    var points: List<List<Offset>> = emptyList()
}

/** Everything the board's single Canvas needs. State objects are read ONLY inside the draw lambda. */
internal class TdBoardCtx(
    val game: TowerDefenceGame,
    val skin: TdSkin,
    val colorblind: Boolean,
    val motion: TdMotion,
    val terrain: ImageBitmap?,
    val plan: List<TdAmbientElement>,
    val ambient: State<Float>,
    val ghost: State<TdGhost?>,
    val selectedId: State<Int?>,
    val armed: State<TdRole?>,
    val showRoute: State<Boolean>,
    val scan: State<TdScan?>,
    val particles: State<List<BurstParticle>>,
    val routeCache: TdRouteCache,
    val kidFirst: Boolean,
    /** Keyboard / D-pad placement cursor (anchor cell); null unless the board has input focus. */
    val cursor: State<Pair<Int, Int>?>? = null
)

private fun towerSignature(s: TowerDefenceGame.TowerDefenceState): Int {
    var h = s.towers.size
    for (t in s.towers) h = h * 31 + t.cx * 64 + t.cy
    return h
}

/** Draws the open-field board: terrain bitmap, ambience, castle, route, towers, enemies, effects, ghost, scan band. */
internal fun DrawScope.drawOpenBoard(c: TdBoardCtx) {
    val s = c.game.state.value
    val terrain = s.level.terrain ?: return
    val w = size.width
    val unit = w / TdGrid.TILES_X
    val t = c.ambient.value.let { if (c.motion == TdMotion.CALM) kotlin.math.floor(it * 30f) / 30f else it }
    c.terrain?.let { drawImage(it) }
    val castleMid = (terrain.castle[1] + terrain.castle[3] + 1) / 2f * (size.height / TdGrid.ROWS)
    with(TdAmbient) { drawForeground(c.skin, c.plan, if (c.motion == TdMotion.OFF) 0f else t, c.motion, size, castleMid) }
    with(TdArt) {
        drawCastle(c.skin, terrain, t, c.motion, s.lives.toFloat() / s.castleMax.coerceAtLeast(1))

        val armed = c.armed.value
        val ghost = c.ghost.value
        val selected = c.selectedId.value
        val placing = armed != null || ghost != null

        // Build-phase dots on tiles where a tower fits.
        if (placing) {
            val dots = ArrayList<Offset>(200)
            var ty = 0
            while (ty < 14) {
                var tx = 0
                while (tx < 24) {
                    val fp = TdGrid.footprint(tx * 2, ty * 2)
                    if (fp != null && fp.all { terrain.buildable[it] }) dots.add(Offset((tx + 0.5f) * unit, (ty + 0.5f) * unit))
                    tx++
                }
                ty++
            }
            drawPoints(dots, PointMode.Points, Color.White.copy(alpha = 0.35f), strokeWidth = unit * 0.12f, cap = StrokeCap.Round)
        }

        // Route preview while placing and between waves (when the player wants it).
        if ((placing || !s.waveInProgress) && c.showRoute.value) {
            val sig = towerSignature(s)
            if (sig != c.routeCache.signature) {
                c.routeCache.points = c.game.routePreview()
                c.routeCache.signature = sig
            }
            for (route in c.routeCache.points) {
                drawRoute(route.map { Offset(it.x * unit, it.y * unit) }, c.skin.accent, unit)
            }
        }

        // Range rings: the selected tower solid, the others dashed while placing.
        for (tw in s.towers) {
            val centre = TdGrid.towerCentre(tw.cx, tw.cy)
            val st = TdRules.stats(tw.role, tw.upgradeLevel, tw.branch)
            val range = if (tw.role == TdRole.SUPPORT) st.auraRange else st.range
            val isSel = tw.id == selected
            if (isSel || placing) drawRangeRing(Offset(centre.x * unit, centre.y * unit), range * unit, isSel, c.skin.accent)
        }

        // Towers.
        for (tw in s.towers) {
            val centre = TdGrid.towerCentre(tw.cx, tw.cy)
            val p = Offset(centre.x * unit, centre.y * unit)
            drawTowerShape(c.skin, tw.role, tw.upgradeLevel, tw.branch, p, unit, c.colorblind)
            if (tw.id == selected) {
                drawCircle(c.skin.accent, unit * 0.62f, p, style = Stroke(width = 3f))
            }
        }

        // Ground fire left by burning shells: a ring plus a flame mark, so it reads by shape as well as colour.
        for (fp in s.firePatches) {
            val p = Offset(fp.pos.x * unit, fp.pos.y * unit)
            val r = fp.radius * unit
            val fade = (fp.remaining / 1.5f).coerceIn(0.35f, 1f)
            val flick = if (c.motion == TdMotion.OFF) 1f else 0.85f + 0.15f * kotlin.math.sin(t * 9f + fp.id)
            drawCircle(Color(0xFFFF8A2B).copy(alpha = 0.28f * fade), r, p)
            drawCircle(Color(0xFFFF8A2B).copy(alpha = 0.8f * fade), r, p, style = Stroke(width = 2f, pathEffect = DASH))
            val flame = androidx.compose.ui.graphics.Path().apply {
                moveTo(p.x, p.y - r * 0.5f * flick); lineTo(p.x + r * 0.22f, p.y + r * 0.2f); lineTo(p.x - r * 0.22f, p.y + r * 0.2f); close()
            }
            drawPath(flame, Color(0xFFFFD166).copy(alpha = 0.9f * fade))
        }

        // Enemies, far ones first so near ones overlap them.
        for (e in s.enemies.sortedBy { it.pos.y }) {
            val p = Offset(e.pos.x * unit, e.pos.y * unit)
            drawEnemyShape(c.skin, e.kind, p, unit, c.colorblind, if (c.motion == TdMotion.OFF) 0f else t, c.motion)
            val onFire = s.firePatches.isNotEmpty() && e.kind != TdEnemyKind.FLYER &&
                s.firePatches.any { fp -> val dx = fp.pos.x - e.pos.x; val dy = fp.pos.y - e.pos.y; dx * dx + dy * dy <= fp.radius * fp.radius }
            drawStatus(p, unit, e.slowRemaining > 0f, e.stunRemaining > 0f, onFire)
            val frac = (e.hp / e.maxHp).coerceIn(0f, 1f)
            if (frac < 1f || e.kind == TdEnemyKind.BOSS) {
                val above = if (e.kind == TdEnemyKind.BOSS) 0.95f else 0.55f
                drawHpBar(Offset(p.x, p.y - unit * above), unit * (if (e.kind == TdEnemyKind.BOSS) 1.4f else 0.8f), frac, c.colorblind)
            }
        }

        // Projectiles.
        for (pr in s.projectiles) {
            val p = Offset(pr.position.x * unit, pr.position.y * unit)
            when (pr.role) {
                TdRole.BLAST -> drawCircle(Color(0xFF14121C), unit * 0.14f, p)
                TdRole.CONTROL -> drawCircle(c.skin.projectile, unit * 0.12f, p, style = Stroke(width = 2f))
                else -> drawCircle(c.skin.projectile, unit * 0.09f, p)
            }
        }

        // Kill bursts (particle positions are fractions of the board).
        for (pt in c.particles.value) {
            drawCircle(pt.color.copy(alpha = pt.lifeFraction), unit * 0.08f * pt.lifeFraction.coerceAtLeast(0.35f), Offset(pt.pos.x * size.width, pt.pos.y * size.height))
        }

        // Ghost.
        if (ghost != null) {
            val centre = TdGrid.towerCentre(ghost.cx, ghost.cy)
            val p = Offset(centre.x * unit, centre.y * unit)
            val st = TdRules.stats(ghost.role, 1, TdBranch.NONE)
            drawRangeRing(p, (if (ghost.role == TdRole.SUPPORT) st.auraRange else st.range) * unit, false, c.skin.accent)
            drawTowerShape(c.skin, ghost.role, 1, TdBranch.NONE, p, unit, c.colorblind, alpha = 0.7f)
            drawPlacementMark(p, unit, ghost.valid)
        }

        // Keyboard / D-pad cursor: a bold square over the 2 x 2 footprint, with corner ticks (shape, not hue).
        val cur = c.cursor?.value
        if (cur != null) {
            val cell = size.width / TdGrid.COLS
            val tl = Offset(cur.first * cell, cur.second * cell)
            drawRect(Color.White, tl, Size(cell * 2f, cell * 2f), style = Stroke(width = 4f))
            drawRect(Color(0xFF14121C), tl, Size(cell * 2f, cell * 2f), style = Stroke(width = 1.5f))
        }

        // One-switch scan band over the 14 x 8 grid.
        val scan = c.scan.value
        if (scan != null) {
            val cwid = size.width / TdBoardMath.GRID_COLS
            val chei = size.height / TdBoardMath.GRID_ROWS
            val band = c.skin.accent.copy(alpha = 0.35f)
            when (scan.phase) {
                TdScan.Phase.COLUMN -> drawRect(band, Offset(scan.index * cwid, 0f), Size(cwid, size.height))
                TdScan.Phase.ROW -> {
                    drawRect(band.copy(alpha = 0.15f), Offset(scan.col * cwid, 0f), Size(cwid, size.height))
                    drawRect(band, Offset(scan.col * cwid, scan.index * chei), Size(cwid, chei))
                }
                TdScan.Phase.ROLE -> drawRect(band, Offset(scan.col * cwid, scan.row * chei), Size(cwid, chei))
            }
        }
    }
}

/** Classic fixed-lane board: ground, road polyline, tower spots, Bolt towers, runner-shaped enemies. */
internal fun DrawScope.drawClassicBoard(c: TdBoardCtx, selectedZone: State<Int?>) {
    val s = c.game.state.value
    val w = size.width
    val h = size.height
    fun px(p: Offset) = Offset(p.x * w, p.y * h)
    val skin = c.skin
    drawRect(skin.ground, size = size)
    val path = androidx.compose.ui.graphics.Path().apply {
        val first = px(s.level.path.first())
        moveTo(first.x, first.y)
        for (i in 1 until s.level.path.size) { val p = px(s.level.path[i]); lineTo(p.x, p.y) }
    }
    val roadW = w * 0.03f
    drawPath(path, skin.roadEdge, style = Stroke(width = roadW + 6f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawPath(path, skin.road, style = Stroke(width = roadW, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    val unit = w * 0.09f
    val sel = selectedZone.value
    with(TdArt) {
        for ((i, z) in s.level.towerZones.withIndex()) {
            val tower = s.towers.firstOrNull { it.zoneIndex == i }
            val p = px(z)
            if (tower == null) {
                drawCircle(Color.White.copy(alpha = 0.55f), unit * 0.5f, p, style = Stroke(width = if (sel == i) 4f else 2f, pathEffect = if (sel == i) null else DASH))
                drawLine(Color.White.copy(alpha = 0.7f), Offset(p.x - unit * 0.18f, p.y), Offset(p.x + unit * 0.18f, p.y), 2f)
                drawLine(Color.White.copy(alpha = 0.7f), Offset(p.x, p.y - unit * 0.18f), Offset(p.x, p.y + unit * 0.18f), 2f)
            } else {
                drawRangeRing(p, TowerDefenceGame.towerRange(tower.upgradeLevel) * w, sel == i, skin.accent)
                drawTowerShape(skin, TdRole.BOLT, tower.upgradeLevel, TdBranch.NONE, p, unit, c.colorblind)
                if (sel == i) drawCircle(skin.accent, unit * 0.62f, p, style = Stroke(width = 3f))
            }
        }
        for (e in s.enemies) {
            val pos = TowerDefenceGame.positionAlongPath(s.level.path, e.distanceTraveled) ?: continue
            val p = px(pos)
            drawEnemyShape(skin, TdEnemyKind.RUNNER, p, unit * 0.9f, c.colorblind, 0f, TdMotion.OFF)
            drawHpBar(Offset(p.x, p.y - unit * 0.5f), unit * 0.8f, (e.hp / e.maxHp).coerceIn(0f, 1f), c.colorblind)
        }
        for (pr in s.projectiles) drawCircle(skin.projectile, w * 0.009f, px(pr.position))
        for (pt in c.particles.value) {
            drawCircle(pt.color.copy(alpha = pt.lifeFraction), w * 0.008f * pt.lifeFraction.coerceAtLeast(0.35f), px(pt.pos))
        }
    }
}
