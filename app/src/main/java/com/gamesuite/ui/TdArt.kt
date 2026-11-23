package com.gamesuite.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.gamesuite.games.towerdefence.TdBranch
import com.gamesuite.games.towerdefence.TdEnemyKind
import com.gamesuite.games.towerdefence.TdGrid
import com.gamesuite.games.towerdefence.TdRole
import com.gamesuite.games.towerdefence.TdTerrain
import com.gamesuite.games.towerdefence.TdTerrainBuilder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Original vector art for the Tower Defence themes, drawn with Canvas primitives only (no bitmaps,
 * no third-party assets). The static terrain is baked once per (map, theme, size) into an
 * [ImageBitmap] by [bakeTerrain]; towers, enemies and effects are drawn every frame from plain
 * state. Shapes carry the information (role silhouette, archetype silhouette, dash and glyph on
 * rings); colour only decorates, and colour-blind mode swaps in a safe palette.
 */
object TdArt {
    val DASH: PathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))
    val DOTTED: PathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 9f))

    /** Bakes the terrain of [terrain] in [skin] into a bitmap of the given pixel size. */
    fun bakeTerrain(skin: TdSkin, terrain: TdTerrain, widthPx: Int, heightPx: Int): ImageBitmap {
        val bmp = ImageBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(widthPx.toFloat(), heightPx.toFloat())) {
            drawTerrain(skin, terrain)
        }
        return bmp
    }

    fun DrawScope.drawTerrain(skin: TdSkin, terrain: TdTerrain) {
        val cw = size.width / TdGrid.COLS
        val ch = size.height / TdGrid.ROWS
        drawRect(skin.ground, size = size)
        // Tile checker.
        for (ty in 0 until 14) for (tx in 0 until 24) {
            if ((tx + ty) % 2 == 0) {
                drawRect(skin.groundAlt, Offset(tx * 2 * cw, ty * 2 * ch), Size(2 * cw, 2 * ch))
            }
        }
        if (skin.id == TdThemeId.SPACE) {
            // Deck plating: thin panel lines.
            for (tx in 0..24) drawLine(skin.roadEdge.copy(alpha = 0.10f), Offset(tx * 2 * cw, 0f), Offset(tx * 2 * cw, size.height), 1f)
            for (ty in 0..14) drawLine(skin.roadEdge.copy(alpha = 0.10f), Offset(0f, ty * 2 * ch), Offset(size.width, ty * 2 * ch), 1f)
        }
        // Rough ground.
        for (y in 0 until TdGrid.ROWS) for (x in 0 until TdGrid.COLS) {
            val i = TdGrid.idx(x, y)
            if (terrain.passable[i] && terrain.cost[i] >= TdTerrainBuilder.ROUGH) {
                drawCircle(skin.rough.copy(alpha = 0.8f), cw * 0.8f, Offset((x + 0.5f) * cw, (y + 0.5f) * ch))
            }
        }
        // Road: edge pass then fill pass, round blobs so the road reads as painted, not blocky.
        for (pass in 0..1) {
            for (y in 0 until TdGrid.ROWS) for (x in 0 until TdGrid.COLS) {
                val i = TdGrid.idx(x, y)
                if (terrain.passable[i] && terrain.cost[i] <= TdTerrainBuilder.ROAD && !terrain.water[i]) {
                    val r = if (pass == 0) cw * 1.15f else cw * 0.95f
                    drawCircle(if (pass == 0) skin.roadEdge else skin.road, r, Offset((x + 0.5f) * cw, (y + 0.5f) * ch))
                }
            }
        }
        // Water and bridges.
        for (y in 0 until TdGrid.ROWS) for (x in 0 until TdGrid.COLS) {
            val i = TdGrid.idx(x, y)
            val cx = (x + 0.5f) * cw
            val cy = (y + 0.5f) * ch
            if (terrain.water[i] && !terrain.passable[i]) {
                drawRect(skin.water, Offset(x * cw - 0.5f, y * ch - 0.5f), Size(cw + 1f, ch + 1f))
                if ((x + y) % 5 == 0) drawLine(Color.White.copy(alpha = 0.25f), Offset(cx - cw * 0.5f, cy), Offset(cx + cw * 0.5f, cy), 1.5f)
            } else if (terrain.water[i] && terrain.passable[i] && !terrain.buildable[i]) {
                drawRect(skin.bridge, Offset(x * cw - 0.5f, y * ch - 0.5f), Size(cw + 1f, ch + 1f))
                if (y % 2 == 0) drawLine(skin.roadEdge.copy(alpha = 0.6f), Offset(x * cw, y * ch), Offset((x + 1) * cw, y * ch), 1.2f)
            } else if (terrain.water[i] && terrain.passable[i]) {
                drawRect(skin.water.copy(alpha = 0.35f), Offset(x * cw - 0.5f, y * ch - 0.5f), Size(cw + 1f, ch + 1f))
            } else if (!terrain.passable[i] && !isCastle(terrain, x, y)) {
                when (skin.id) {
                    TdThemeId.TOY -> {
                        val c = when ((x / 2 + y / 2) % 3) { 0 -> Color(0xFFFF5A5F); 1 -> Color(0xFFFFD23F); else -> Color(0xFF3DDC97) }
                        drawRoundRect(c, Offset(x * cw + 0.5f, y * ch + 0.5f), Size(cw - 1f, ch - 1f), CornerRadius(cw * 0.25f))
                    }
                    TdThemeId.SPACE -> {
                        drawCircle(skin.rock, cw * 0.62f, Offset(cx, cy))
                        drawCircle(skin.rock.copy(alpha = 0.5f), cw * 0.3f, Offset(cx - cw * 0.2f, cy - ch * 0.2f))
                    }
                    TdThemeId.CASTLE -> {
                        drawRoundRect(skin.rock, Offset(x * cw, y * ch), Size(cw, ch), CornerRadius(cw * 0.4f))
                        if ((x * 7 + y * 3) % 4 == 0) drawCircle(Color.White.copy(alpha = 0.18f), cw * 0.25f, Offset(cx - cw * 0.15f, cy - ch * 0.15f))
                    }
                }
            }
        }
    }

    private fun isCastle(t: TdTerrain, x: Int, y: Int): Boolean =
        x >= t.castle[0] && x <= t.castle[2] && y >= t.castle[1] && y <= t.castle[3]

    /** The castle (or station core, or block fortress) over its footprint; [health] 0..1 draws cracks. */
    fun DrawScope.drawCastle(skin: TdSkin, terrain: TdTerrain, t: Float, motion: TdMotion, health: Float) {
        val cw = size.width / TdGrid.COLS
        val ch = size.height / TdGrid.ROWS
        val x0 = terrain.castle[0] * cw
        val y0 = terrain.castle[1] * ch
        val w = (terrain.castle[2] - terrain.castle[0] + 1) * cw
        val h = (terrain.castle[3] - terrain.castle[1] + 1) * ch
        val flagTip = TdAmbient.flagRipple(t, 0f, motion, w * 0.12f)
        when (skin.id) {
            TdThemeId.CASTLE -> {
                drawRoundRect(skin.castleWall, Offset(x0, y0), Size(w, h), CornerRadius(cw * 0.3f))
                // Crenellations along the top and bottom.
                val n = 4
                for (k in 0 until n) {
                    drawRect(skin.castleWall, Offset(x0 + k * w / n + w * 0.02f, y0 - ch * 0.9f), Size(w / n * 0.6f, ch * 0.9f))
                    drawRect(skin.castleWall, Offset(x0 + k * w / n + w * 0.02f, y0 + h), Size(w / n * 0.6f, ch * 0.9f))
                }
                // Keep with blue roof in the middle.
                val kx = x0 + w * 0.2f
                drawRect(skin.castleWall.copy(alpha = 0.9f), Offset(kx, y0 + h * 0.18f), Size(w * 0.6f, h * 0.64f))
                val roof = Path().apply {
                    moveTo(kx - cw * 0.3f, y0 + h * 0.2f); lineTo(kx + w * 0.3f, y0 - ch * 1.8f); lineTo(kx + w * 0.6f + cw * 0.3f, y0 + h * 0.2f); close()
                }
                drawPath(roof, skin.castleRoof)
                // Flag.
                val fx = kx + w * 0.3f
                drawLine(Color(0xFF3A3340), Offset(fx, y0 - ch * 1.8f), Offset(fx, y0 - ch * 3.2f), 2f)
                val flag = Path().apply {
                    moveTo(fx, y0 - ch * 3.2f); lineTo(fx + cw * 1.6f + flagTip, y0 - ch * 2.9f); lineTo(fx, y0 - ch * 2.5f); close()
                }
                drawPath(flag, skin.flag)
                // Gate with portcullis.
                drawRect(Color(0xFF3A2A1E), Offset(x0 - cw * 0.5f, y0 + h * 0.35f), Size(cw * 1.2f, h * 0.3f))
                for (k in 0..2) drawLine(Color(0xFF8A8A94), Offset(x0 - cw * 0.5f + k * cw * 0.5f, y0 + h * 0.35f), Offset(x0 - cw * 0.5f + k * cw * 0.5f, y0 + h * 0.65f), 1.4f)
            }
            TdThemeId.SPACE -> {
                val c = Offset(x0 + w * 0.55f, y0 + h / 2f)
                val r = h * 0.38f
                drawRect(skin.castleWall.copy(alpha = 0.85f), Offset(x0 - w * 0.1f, c.y - ch * 0.5f), Size(w * 1.25f, ch))
                drawCircle(skin.castleWall, r, c)
                drawCircle(skin.castleRoof.copy(alpha = 0.35f), r * 1.25f, c)
                drawCircle(skin.castleRoof, r * 0.55f, c)
                drawCircle(skin.flag, r * 0.18f, c)
                drawCircle(skin.castleRoof.copy(alpha = 0.6f), r * 1.5f, c, style = Stroke(width = 2f, pathEffect = DASH))
                // Solar wings.
                drawRect(Color(0xFF3A4C9A), Offset(c.x - r * 1.2f, c.y - r * 2.1f), Size(r * 2.4f, r * 0.7f))
                drawRect(Color(0xFF3A4C9A), Offset(c.x - r * 1.2f, c.y + r * 1.4f), Size(r * 2.4f, r * 0.7f))
            }
            TdThemeId.TOY -> {
                val bw = w * 0.5f
                val bh = h / 3f
                val cols = listOf(Color(0xFFFF5A5F), Color(0xFF3A86FF), Color(0xFFFFD23F))
                for (row in 0..2) for (col in 0..1) {
                    drawRoundRect(cols[(row + col) % 3], Offset(x0 + col * bw, y0 + row * bh), Size(bw - 2f, bh - 2f), CornerRadius(cw * 0.35f))
                }
                drawRoundRect(cols[2], Offset(x0 + w * 0.15f, y0 - bh * 0.9f), Size(bw, bh * 0.9f), CornerRadius(cw * 0.35f))
                drawLine(Color(0xFF3A3340), Offset(x0 + w * 0.4f, y0 - bh * 0.9f), Offset(x0 + w * 0.4f, y0 - bh * 2.1f), 2f)
                val flag = Path().apply {
                    moveTo(x0 + w * 0.4f, y0 - bh * 2.1f); lineTo(x0 + w * 0.4f + cw * 1.5f + flagTip, y0 - bh * 1.8f); lineTo(x0 + w * 0.4f, y0 - bh * 1.5f); close()
                }
                drawPath(flag, skin.flag)
            }
        }
        // Damage: cracks at 50 percent and 25 percent; a fallen castle goes dark.
        val crack = Color(0xFF241820).copy(alpha = 0.85f)
        if (health <= 0.5f) {
            drawLine(crack, Offset(x0 + w * 0.3f, y0 + h * 0.1f), Offset(x0 + w * 0.45f, y0 + h * 0.5f), 2f)
            drawLine(crack, Offset(x0 + w * 0.45f, y0 + h * 0.5f), Offset(x0 + w * 0.35f, y0 + h * 0.8f), 2f)
        }
        if (health <= 0.25f) {
            drawLine(crack, Offset(x0 + w * 0.7f, y0 + h * 0.05f), Offset(x0 + w * 0.6f, y0 + h * 0.55f), 2.4f)
            drawLine(crack, Offset(x0 + w * 0.6f, y0 + h * 0.55f), Offset(x0 + w * 0.8f, y0 + h * 0.9f), 2.4f)
            drawRect(Color.Black.copy(alpha = 0.18f), Offset(x0, y0 - ch), Size(w, h + 2 * ch))
        }
    }

    fun roleColor(skin: TdSkin, role: TdRole, colorblind: Boolean): Color =
        if (colorblind) TdSkins.CB_ROLE.getValue(role) else skin.roleColor.getValue(role)

    fun enemyColor(skin: TdSkin, kind: TdEnemyKind, colorblind: Boolean): Color =
        if (colorblind) TdSkins.CB_ENEMY.getValue(kind) else skin.enemyColor.getValue(kind)

    /**
     * A tower silhouette: Bolt is a tall arrow-spire, Blast a wide squat mortar, Control a diamond
     * spire with a ring, Support a banner pole. [unit] is the size of one tile in px. With [base]
     * the footing and level pips are drawn too (the tray icons omit them).
     */
    fun DrawScope.drawTowerShape(
        skin: TdSkin, role: TdRole, level: Int, branch: TdBranch, centre: Offset, unit: Float,
        colorblind: Boolean, base: Boolean = true, alpha: Float = 1f
    ) {
        val col = roleColor(skin, role, colorblind).copy(alpha = alpha)
        val dark = Color(0xFF1B1E2B).copy(alpha = alpha)
        val r = unit * 0.5f
        if (base) {
            when (skin.id) {
                TdThemeId.TOY -> drawRoundRect(skin.towerBase.copy(alpha = alpha), Offset(centre.x - r, centre.y - r), Size(2 * r, 2 * r), CornerRadius(r * 0.5f))
                TdThemeId.SPACE -> {
                    drawCircle(skin.towerBase.copy(alpha = alpha), r, centre)
                    drawCircle(skin.accent.copy(alpha = 0.6f * alpha), r, centre, style = Stroke(width = 1.5f))
                }
                else -> {
                    drawRoundRect(skin.towerBase.copy(alpha = alpha), Offset(centre.x - r, centre.y - r), Size(2 * r, 2 * r), CornerRadius(r * 0.25f))
                    for (k in 0..2) drawRect(skin.towerBase.copy(alpha = alpha), Offset(centre.x - r + k * r * 0.72f, centre.y - r - r * 0.2f), Size(r * 0.55f, r * 0.25f))
                }
            }
            drawRoundRect(dark.copy(alpha = 0.5f * alpha), Offset(centre.x - r, centre.y - r), Size(2 * r, 2 * r), CornerRadius(r * 0.3f), style = Stroke(width = 1.5f))
        }
        val s = r * (0.62f + 0.06f * (level - 1))
        when (role) {
            TdRole.BOLT -> {
                val p = Path().apply { moveTo(centre.x, centre.y - s * 1.1f); lineTo(centre.x + s * 0.7f, centre.y + s * 0.8f); lineTo(centre.x - s * 0.7f, centre.y + s * 0.8f); close() }
                drawPath(p, col); drawPath(p, dark, style = Stroke(width = 1.6f))
                drawCircle(Color.White.copy(alpha = alpha), s * 0.18f, Offset(centre.x, centre.y - s * 0.1f))
            }
            TdRole.BLAST -> {
                val p = Path().apply { moveTo(centre.x - s * 1.0f, centre.y + s * 0.7f); lineTo(centre.x - s * 0.6f, centre.y - s * 0.3f); lineTo(centre.x + s * 0.6f, centre.y - s * 0.3f); lineTo(centre.x + s * 1.0f, centre.y + s * 0.7f); close() }
                drawPath(p, col); drawPath(p, dark, style = Stroke(width = 1.6f))
                drawCircle(dark, s * 0.38f, Offset(centre.x, centre.y - s * 0.55f))
                drawCircle(col, s * 0.22f, Offset(centre.x, centre.y - s * 0.55f))
            }
            TdRole.CONTROL -> {
                val p = Path().apply { moveTo(centre.x, centre.y - s * 1.15f); lineTo(centre.x + s * 0.75f, centre.y); lineTo(centre.x, centre.y + s * 1.0f); lineTo(centre.x - s * 0.75f, centre.y); close() }
                drawPath(p, col); drawPath(p, dark, style = Stroke(width = 1.6f))
                drawCircle(Color.White.copy(alpha = 0.9f * alpha), s * 1.05f, centre, style = Stroke(width = 1.6f, pathEffect = DOTTED))
            }
            TdRole.SUPPORT -> {
                drawLine(dark, Offset(centre.x - s * 0.3f, centre.y + s * 0.9f), Offset(centre.x - s * 0.3f, centre.y - s * 1.1f), 2.6f)
                val f = Path().apply { moveTo(centre.x - s * 0.3f, centre.y - s * 1.1f); lineTo(centre.x + s * 0.9f, centre.y - s * 0.7f); lineTo(centre.x - s * 0.3f, centre.y - s * 0.2f); close() }
                drawPath(f, col); drawPath(f, dark, style = Stroke(width = 1.4f))
                drawCircle(dark, s * 0.25f, Offset(centre.x - s * 0.3f, centre.y + s * 0.9f))
            }
        }
        if (base) {
            // Level pips along the bottom; a square (A) or triangle (B) badge marks the branch.
            for (k in 0 until level) drawCircle(Color.White.copy(alpha = alpha), r * 0.09f, Offset(centre.x - r * 0.6f + k * r * 0.3f, centre.y + r * 0.82f))
            if (branch != TdBranch.NONE) {
                val bx = centre.x + r * 0.8f
                val by = centre.y - r * 0.8f
                if (branch == TdBranch.A) {
                    drawRect(Color.White.copy(alpha = alpha), Offset(bx - r * 0.16f, by - r * 0.16f), Size(r * 0.32f, r * 0.32f))
                } else {
                    val tri = Path().apply { moveTo(bx, by - r * 0.2f); lineTo(bx + r * 0.2f, by + r * 0.16f); lineTo(bx - r * 0.2f, by + r * 0.16f); close() }
                    drawPath(tri, Color.White.copy(alpha = alpha))
                }
            }
        }
    }

    /** An enemy: Runner small round, Brute wide rounded block, Flyer winged with a ground shadow, Boss large and crowned. */
    fun DrawScope.drawEnemyShape(
        skin: TdSkin, kind: TdEnemyKind, centre: Offset, unit: Float, colorblind: Boolean, t: Float, motion: TdMotion
    ) {
        val col = enemyColor(skin, kind, colorblind)
        val dark = Color(0xFF14121C)
        val bob = if (motion == TdMotion.OFF) 0f else sin(t * 9f + centre.x * 0.05f) * unit * 0.03f
        val c = Offset(centre.x, centre.y + bob)
        when (kind) {
            TdEnemyKind.RUNNER -> {
                val r = unit * 0.27f
                drawLine(col.copy(alpha = 0.45f), Offset(c.x - r * 1.2f, c.y - r * 0.4f), Offset(c.x - r * 2.2f, c.y - r * 0.4f), 2f)
                drawLine(col.copy(alpha = 0.45f), Offset(c.x - r * 1.2f, c.y + r * 0.4f), Offset(c.x - r * 2.0f, c.y + r * 0.4f), 2f)
                drawCircle(col, r, c); drawCircle(dark, r, c, style = Stroke(width = 1.6f))
                drawEyes(c, r, skin)
            }
            TdEnemyKind.BRUTE -> {
                val w = unit * 0.5f
                drawRoundRect(col, Offset(c.x - w, c.y - w * 0.85f), Size(2 * w, 1.7f * w), CornerRadius(w * 0.3f))
                drawRoundRect(dark, Offset(c.x - w, c.y - w * 0.85f), Size(2 * w, 1.7f * w), CornerRadius(w * 0.3f), style = Stroke(width = 2.2f))
                drawRect(Color.White.copy(alpha = 0.35f), Offset(c.x - w * 0.8f, c.y - w * 0.55f), Size(w * 1.6f, w * 0.22f))
                drawEyes(c, w * 0.6f, skin)
            }
            TdEnemyKind.FLYER -> {
                val r = unit * 0.34f
                drawOval(Color.Black.copy(alpha = 0.22f), Offset(c.x - r, c.y + r * 1.6f), Size(2 * r, r * 0.6f))
                val flap = if (motion == TdMotion.OFF) 0.4f else 0.2f + 0.6f * TdAmbient.wave(t, 4f, centre.y)
                val wing = Path().apply {
                    moveTo(c.x, c.y); lineTo(c.x - r * 1.6f, c.y - r * flap * 2f); lineTo(c.x - r * 0.9f, c.y + r * 0.4f)
                    moveTo(c.x, c.y); lineTo(c.x + r * 1.6f, c.y - r * flap * 2f); lineTo(c.x + r * 0.9f, c.y + r * 0.4f)
                }
                drawPath(wing, col, style = Stroke(width = r * 0.7f, cap = StrokeCap.Round))
                drawCircle(col, r * 0.7f, c); drawCircle(dark, r * 0.7f, c, style = Stroke(width = 1.6f))
                drawEyes(c, r * 0.7f, skin)
            }
            TdEnemyKind.BOSS -> {
                val r = unit * 0.62f
                drawCircle(col, r, c); drawCircle(dark, r, c, style = Stroke(width = 3f))
                val crown = Path().apply {
                    moveTo(c.x - r * 0.7f, c.y - r * 0.7f); lineTo(c.x - r * 0.5f, c.y - r * 1.25f); lineTo(c.x - r * 0.15f, c.y - r * 0.85f)
                    lineTo(c.x, c.y - r * 1.35f); lineTo(c.x + r * 0.15f, c.y - r * 0.85f); lineTo(c.x + r * 0.5f, c.y - r * 1.25f); lineTo(c.x + r * 0.7f, c.y - r * 0.7f); close()
                }
                drawPath(crown, skin.accent); drawPath(crown, dark, style = Stroke(width = 1.8f))
                drawEyes(c, r, skin)
            }
        }
    }

    private fun DrawScope.drawEyes(c: Offset, r: Float, skin: TdSkin) {
        val e = r * 0.28f
        drawCircle(Color.White, e, Offset(c.x - r * 0.35f, c.y - r * 0.15f))
        drawCircle(Color.White, e, Offset(c.x + r * 0.35f, c.y - r * 0.15f))
        drawCircle(Color(0xFF14121C), e * 0.5f, Offset(c.x - r * 0.3f, c.y - r * 0.15f))
        drawCircle(Color(0xFF14121C), e * 0.5f, Offset(c.x + r * 0.4f, c.y - r * 0.15f))
    }

    /** Health bar with notches at 50 and 25 percent, so health reads by length and ticks, not by hue. */
    fun DrawScope.drawHpBar(centre: Offset, width: Float, fraction: Float, colorblind: Boolean) {
        val h = (width * 0.14f).coerceAtLeast(3f)
        val tl = Offset(centre.x - width / 2f, centre.y)
        drawRect(Color(0xCC0B120C), tl, Size(width, h))
        val fill = if (colorblind) Color(0xFFF5F0E6) else when {
            fraction > 0.5f -> Color(0xFF6FD08C); fraction > 0.25f -> Color(0xFFE8C24A); else -> Color(0xFFE8644C)
        }
        drawRect(fill, tl, Size(width * fraction.coerceIn(0f, 1f), h))
        drawRect(Color(0xCCF5F0E6), tl, Size(width, h), style = Stroke(width = 1f))
        drawLine(Color(0xCC0B120C), Offset(tl.x + width * 0.5f, tl.y), Offset(tl.x + width * 0.5f, tl.y + h), 1f)
        drawLine(Color(0xCC0B120C), Offset(tl.x + width * 0.25f, tl.y), Offset(tl.x + width * 0.25f, tl.y + h), 1f)
    }

    /** Valid or blocked footprint outline with a glyph: check + solid, or X + dashed (never colour alone). */
    fun DrawScope.drawPlacementMark(centre: Offset, unit: Float, valid: Boolean) {
        val r = unit * 0.55f
        val col = if (valid) Color(0xFF3DDC97) else Color(0xFFFF5A5F)
        drawRoundRect(col.copy(alpha = 0.18f), Offset(centre.x - r, centre.y - r), Size(2 * r, 2 * r), CornerRadius(r * 0.2f))
        drawRoundRect(
            col, Offset(centre.x - r, centre.y - r), Size(2 * r, 2 * r), CornerRadius(r * 0.2f),
            style = Stroke(width = 3f, pathEffect = if (valid) null else DASH)
        )
        val g = r * 0.45f
        val gc = Offset(centre.x + r * 0.85f, centre.y - r * 0.85f)
        drawCircle(Color(0xFF14121C), g * 1.25f, gc)
        if (valid) {
            val p = Path().apply { moveTo(gc.x - g * 0.6f, gc.y); lineTo(gc.x - g * 0.1f, gc.y + g * 0.5f); lineTo(gc.x + g * 0.65f, gc.y - g * 0.5f) }
            drawPath(p, col, style = Stroke(width = 3f, cap = StrokeCap.Round))
        } else {
            drawLine(col, Offset(gc.x - g * 0.55f, gc.y - g * 0.55f), Offset(gc.x + g * 0.55f, gc.y + g * 0.55f), 3f, StrokeCap.Round)
            drawLine(col, Offset(gc.x + g * 0.55f, gc.y - g * 0.55f), Offset(gc.x - g * 0.55f, gc.y + g * 0.55f), 3f, StrokeCap.Round)
        }
    }

    /** Range ring: [selected] = solid thick with an inner ring; others dashed. */
    fun DrawScope.drawRangeRing(centre: Offset, radius: Float, selected: Boolean, accent: Color) {
        if (selected) {
            drawCircle(accent.copy(alpha = 0.12f), radius, centre)
            drawCircle(accent, radius, centre, style = Stroke(width = 3.4f))
            drawCircle(Color(0xFF14121C).copy(alpha = 0.55f), radius - 4f, centre, style = Stroke(width = 1.4f))
        } else {
            drawCircle(Color.White.copy(alpha = 0.55f), radius, centre, style = Stroke(width = 1.8f, pathEffect = DASH))
        }
    }

    /** Chevron route preview along tile-space polylines already converted to px. */
    fun DrawScope.drawRoute(points: List<Offset>, accent: Color, unit: Float) {
        if (points.size < 2) return
        var k = 3
        while (k < points.size - 1) {
            val a = points[k - 1]
            val b = points[k + 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
            val ux = dx / len
            val uy = dy / len
            val c = points[k]
            val s = unit * 0.14f
            val p = Path().apply {
                moveTo(c.x - ux * s - uy * s, c.y - uy * s + ux * s)
                lineTo(c.x + ux * s, c.y + uy * s)
                lineTo(c.x - ux * s + uy * s, c.y - uy * s - ux * s)
            }
            drawPath(p, accent.copy(alpha = 0.8f), style = Stroke(width = 2.4f, cap = StrokeCap.Round))
            k += 6
        }
    }

    /** Status marks over an enemy: slowed (dashed ring), stunned (three dots), burning (orange dot with a flame tip). */
    fun DrawScope.drawStatus(centre: Offset, unit: Float, slowed: Boolean, stunned: Boolean, burning: Boolean) {
        if (slowed) drawCircle(Color(0xFF8AD7FF), unit * 0.45f, centre, style = Stroke(width = 2f, pathEffect = DOTTED))
        if (stunned) for (k in 0..2) {
            val a = (k * 2f * PI / 3f).toFloat()
            drawCircle(Color(0xFFFFE08A), 2.5f, Offset(centre.x + cos(a) * unit * 0.4f, centre.y - unit * 0.5f + sin(a) * unit * 0.1f))
        }
        if (burning) {
            val p = Path().apply { moveTo(centre.x, centre.y - unit * 0.85f); lineTo(centre.x + unit * 0.12f, centre.y - unit * 0.55f); lineTo(centre.x - unit * 0.12f, centre.y - unit * 0.55f); close() }
            drawPath(p, Color(0xFFFF9A3C))
        }
    }
}
