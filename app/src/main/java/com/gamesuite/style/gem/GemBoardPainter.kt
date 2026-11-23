package com.gamesuite.style.gem

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.gamesuite.games.gemcascade.Cell
import com.gamesuite.games.gemcascade.GemKinds
import com.gamesuite.games.gemcascade.GemShape
import com.gamesuite.games.gemcascade.PieceType
import com.gamesuite.games.gemcascade.Pos
import com.gamesuite.games.gemcascade.SpecialKind
import com.gamesuite.style.ArtTier
import com.gamesuite.ui.drawGemCage
import com.gamesuite.ui.drawGemSpecial
import com.gamesuite.ui.drawGemTimer
import com.gamesuite.ui.drawIce
import com.gamesuite.ui.drawPiece
import com.gamesuite.ui.gemColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The single draw routine for Gem Cascade's board, in three looks. The board plane is mapped to the screen by
 * [GemFrame.camera] (identity in Flat, a gentle tilt in 2.5D, a deeper tilt in 3D; zero tilt under Reduced Motion).
 * Each part picks its own tier, so a board can be 3D while the particles are 2.5D (mixing is allowed per surface).
 * Flat reproduces the original 2D baseline exactly.
 */
internal object GemBoardPainter {

    fun paint(scope: DrawScope, f: GemFrame) = with(scope) {
        val b = f.state.board
        if (b.rows != f.rows || b.cols != f.cols) return
        f.budget.reset()
        val s = f.cellPx
        drawBoardBack(f)
        // ice floor
        for (r in 0 until b.rows) for (c in 0 until b.cols) {
            val layers = b.iceAt(Pos(r, c))
            if (layers <= 0) continue
            if (f.style.boardTier == ArtTier.FLAT) drawIce(Offset(c * s, r * s), s, layers, f.paints)
            else {
                cellPath(f, f.paints.path, c.toFloat(), r.toFloat(), 1f, 1f, 0.06f)
                drawPath(f.paints.path, Color(0xFF9FDCFF).copy(alpha = 0.30f + 0.12f * layers))
                f.budget.force()
            }
        }
        // pieces, back (top) to front so nearer pieces overlap farther ones
        val tier = f.style.pieceTier
        for (r in 0 until b.rows) for (c in 0 until b.cols) {
            val p = Pos(r, c)
            val cell = b[p]
            if (cell.type == PieceType.EMPTY) continue
            val o = f.state.offsets[p]
            val pr = f.camera.project((c + 0.5f + (o?.x ?: 0f) * (1f - f.t)) * s, (r + 0.5f + (o?.y ?: 0f) * (1f - f.t)) * s)
            val centre = Offset(pr.x, pr.y)
            val se = s * pr.scale
            var scale = 1f
            var alpha = 1f
            if (p in f.state.fading) { alpha = 1f - f.t; scale = if (f.reduced) 1f else 1f - 0.4f * f.t }
            if (p in f.state.pop) scale = 1f + 0.35f * sin(PI.toFloat() * f.t)
            when (tier) {
                ArtTier.FLAT -> drawPiece(cell, centre, se, scale, alpha, f.paints, f.text)
                ArtTier.SOFT_25D -> drawSoftPiece(f, cell, centre, se, scale, alpha, (c + 0.5f) / b.cols - 0.5f)
                ArtTier.FULL_3D -> if (!drawSpritePiece(f, cell, centre, se, scale, alpha)) {
                    f.onSpriteMissing()
                    drawSoftPiece(f, cell, centre, se, scale, alpha, (c + 0.5f) / b.cols - 0.5f)
                }
            }
        }
        drawOverlays(f)
        drawBursts(f)
        drawFloatingText(f)
    }

    // ------------------------------------------------------------------------------------------------ board back
    private fun DrawScope.drawBoardBack(f: GemFrame) {
        val s = f.cellPx
        val w = f.cols * s
        val h = f.rows * s
        val pal = f.palette
        when (f.style.boardTier) {
            ArtTier.FLAT -> {
                drawRoundRect(pal.bgBoard, Offset.Zero, Size(size.width, size.height), CornerRadius(s * 0.15f))
                for (r in 0 until f.rows) for (c in 0 until f.cols) {
                    drawRect(if ((r + c) % 2 == 0) pal.tileA else pal.tileB, Offset(c * s, r * s), Size(s, s))
                }
            }
            ArtTier.SOFT_25D, ArtTier.FULL_3D -> {
                val deep = f.style.boardTier == ArtTier.FULL_3D
                val path = f.paints.path
                // soft ground shadow under the slab
                val slab = s * (if (deep) 0.30f else 0.14f)
                plate(f, path, 0f, 0f, w, h, 0.06f * s, slab)
                drawPath(path, Color.Black.copy(alpha = if (deep) 0.34f else 0.22f))
                // slab front face (thickness), then the top plate
                val bl = f.camera.project(0f, h); val br = f.camera.project(w, h)
                val sc = (bl.scale + br.scale) / 2f
                path.rewind()
                path.moveTo(bl.x, bl.y); path.lineTo(br.x, br.y)
                path.lineTo(br.x, br.y + slab * sc); path.lineTo(bl.x, bl.y + slab * sc); path.close()
                drawPath(path, lerp(pal.bgBoard, Color.Black, if (pal.dark) 0.55f else 0.35f))
                plate(f, path, 0f, 0f, w, h, 0f, 0f)
                drawPath(path, pal.bgBoard)
                f.budget.force(4)
                val gap = if (deep) 0.045f else 0.025f
                for (r in 0 until f.rows) for (c in 0 until f.cols) {
                    cellPath(f, path, c.toFloat(), r.toFloat(), 1f, 1f, gap)
                    drawPath(path, if ((r + c) % 2 == 0) pal.tileA else pal.tileB)
                    f.budget.force()
                }
                // cascade multiplier glow: a coloured rim around the plate that grows with the chain
                val lvl = f.state.chainLevel
                if (lvl >= 2 && !f.reduced) {
                    plate(f, path, 0f, 0f, w, h, 0f, 0f)
                    drawPath(path, pal.accent.copy(alpha = (0.14f * lvl.coerceAtMost(5))), style = Stroke(s * (0.05f + 0.02f * lvl.coerceAtMost(5))))
                    f.budget.force()
                }
            }
        }
    }

    /** The board rectangle (inset by [inset], pushed down by [dy] screen px) as a projected polygon. */
    private fun plate(f: GemFrame, path: Path, x0: Float, y0: Float, x1: Float, y1: Float, inset: Float, dy: Float) {
        val a = f.camera.project(x0 - inset, y0 - inset); val b = f.camera.project(x1 + inset, y0 - inset)
        val c = f.camera.project(x1 + inset, y1 + inset); val d = f.camera.project(x0 - inset, y1 + inset)
        path.rewind()
        path.moveTo(a.x, a.y + dy); path.lineTo(b.x, b.y + dy); path.lineTo(c.x, c.y + dy); path.lineTo(d.x, d.y + dy); path.close()
    }

    /** One cell-aligned quad (cell units, inset in cells) as a projected polygon. */
    private fun cellPath(f: GemFrame, path: Path, col: Float, row: Float, w: Float, h: Float, inset: Float) {
        val s = f.cellPx
        val a = f.camera.project((col + inset) * s, (row + inset) * s)
        val b = f.camera.project((col + w - inset) * s, (row + inset) * s)
        val c = f.camera.project((col + w - inset) * s, (row + h - inset) * s)
        val d = f.camera.project((col + inset) * s, (row + h - inset) * s)
        path.rewind()
        path.moveTo(a.x, a.y); path.lineTo(b.x, b.y); path.lineTo(c.x, c.y); path.lineTo(d.x, d.y); path.close()
    }

    // ------------------------------------------------------------------------------------------------ overlays
    private fun DrawScope.drawOverlays(f: GemFrame) {
        val s = f.cellPx
        val st = f.state
        val flat = f.camera.isIdentity
        val accent = f.palette.accent
        val fg = f.palette.fg
        val tv = f.t
        fun frame(p: Pos, inset: Float, color: Color, strokeW: Float, fill: Boolean, w: Float = 1f) {
            if (flat) {
                val tl = Offset(p.c * s + s * inset, p.r * s + s * inset)
                val sz = Size(s * w - 2 * s * inset, s * w - 2 * s * inset)
                if (fill) drawRoundRect(color, tl, sz, CornerRadius(s * 0.2f))
                else drawRoundRect(color, tl, sz, CornerRadius(s * 0.2f), style = Stroke(s * strokeW))
            } else {
                cellPath(f, f.paints.path, p.c.toFloat(), p.r.toFloat(), w, w, inset)
                if (fill) drawPath(f.paints.path, color) else drawPath(f.paints.path, color, style = Stroke(s * strokeW))
            }
        }
        for (p in st.glow) frame(p, 0.06f, Color.White.copy(alpha = 0.45f), 0f, true)
        for (p in st.rings) {
            val pr = f.camera.project((p.c + 0.5f) * s, (p.r + 0.5f) * s)
            drawCircle(accent.copy(alpha = 0.55f * (1f - tv)), s * pr.scale * (0.3f + 0.4f * tv), Offset(pr.x, pr.y), style = Stroke(s * pr.scale * 0.08f))
        }
        for (p in st.hint) frame(p, 0.05f, accent, 0.08f, false)
        st.selected?.let { p -> frame(p, 0.04f, fg, 0.07f, false) }
        st.scan?.let { p -> frame(p, 0.03f, accent, 0.1f, false, if (st.scanBlock) 2f else 1f) }
    }

    // ------------------------------------------------------------------------------------------------ bursts
    private fun DrawScope.drawBursts(f: GemFrame) {
        val s = f.cellPx
        for (bu in f.state.bursts) {
            val t = ((f.nowMs - bu.startMs).toFloat() / bu.lifeMs).coerceIn(0f, 1f)
            if (t >= 1f) continue
            val pr = f.camera.project((bu.col + 0.5f) * s, (bu.row + 0.5f) * s)
            val base = Color(bu.argb)
            for (i in 0 until bu.count) {
                if (!f.budget.tryUse()) return
                val ps = GemParticles.state(bu.seed, i, t, bu.reach)
                val x = pr.x + ps.dx * s * pr.scale
                val y = pr.y + ps.dy * s * pr.scale
                val sz = ps.size * s * pr.scale
                val col = base.copy(alpha = ps.alpha)
                when (bu.shape) {
                    ParticleShape.SQUARE -> drawRect(col, Offset(x - sz, y - sz), Size(sz * 2f, sz * 2f))
                    ParticleShape.BLOB -> drawCircle(col, sz * 1.15f, Offset(x, y))
                    ParticleShape.SHARD -> {
                        val a = Math.toRadians(ps.rotation.toDouble())
                        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
                        val p = f.paints.path2
                        p.rewind()
                        p.moveTo(x + ca * sz * 1.6f, y + sa * sz * 1.6f)
                        p.lineTo(x - sa * sz, y + ca * sz)
                        p.lineTo(x + sa * sz, y - ca * sz)
                        p.close()
                        drawPath(p, col)
                    }
                }
            }
        }
    }

    private fun DrawScope.drawFloatingText(f: GemFrame) {
        val fa = if (f.floatT >= 1f) 0f else (1f - f.floatT)
        if (fa <= 0f) return
        val s = f.cellPx
        val st = f.state
        val fv = f.floatT
        st.scoreText?.let {
            val pr = f.camera.project(st.textAt.x * s, (st.textAt.y - 0.5f * fv) * s)
            val lay = f.text.measure(it, TextStyle(color = f.palette.fg.copy(alpha = fa), fontSize = (s * pr.scale * 0.34f).toSp(), fontWeight = FontWeight.Bold))
            drawText(lay, topLeft = Offset(pr.x - lay.size.width / 2f, pr.y))
        }
        st.chainText?.let {
            val pr = f.camera.project(f.cols * s / 2f, f.rows * s * 0.4f - 0.3f * fv * s)
            val lay = f.text.measure(it, TextStyle(color = f.palette.accent.copy(alpha = fa), fontSize = (s * pr.scale * 0.5f).toSp(), fontWeight = FontWeight.ExtraBold))
            drawText(lay, topLeft = Offset(pr.x - lay.size.width / 2f, pr.y))
        }
    }

    // ------------------------------------------------------------------------------------------------ 2.5D pieces
    private fun mix(a: Color, b: Color, t: Float) = lerp(a, b, t)

    /** Layered gem: contact shadow, dark rim body, lit body, bevel facet, parallax-lit specular. 6 to 9 commands, no allocations. */
    private fun DrawScope.drawSoftPiece(f: GemFrame, cell: Cell, c: Offset, s: Float, scale: Float, alpha: Float, lightX: Float) {
        if (alpha <= 0f || scale <= 0f) return
        val r = s * 0.37f * scale
        if (f.budget.tryUse()) {
            drawOval(Color.Black.copy(alpha = 0.26f * alpha), Offset(c.x - r * 0.85f, c.y + r * 0.62f), Size(r * 1.7f, r * 0.55f))
        }
        if (cell.type != PieceType.GEM) {
            drawPiece(cell, c, s, scale, alpha, f.paints, f.text)
            return
        }
        val base = if (cell.special == SpecialKind.PRISM) Color.White else gemColor(cell.kind)
        val shape = GemKinds.shapes[cell.kind.coerceIn(0, GemKinds.COUNT - 1)]
        val p = f.paints.path2
        gemShapePath(p, shape, c.x, c.y, r)
        drawPath(p, mix(base, Color.Black, 0.34f).copy(alpha = alpha))
        gemShapePath(p, shape, c.x - r * 0.03f, c.y - r * 0.05f, r * 0.9f)
        drawPath(p, base.copy(alpha = alpha))
        f.budget.force(2)
        if (f.budget.tryUse(2)) {
            gemShapePath(p, shape, c.x - r * 0.12f, c.y - r * 0.17f, r * 0.55f)
            drawPath(p, mix(base, Color.White, 0.38f).copy(alpha = 0.85f * alpha))
            // specular slides a little with the gem's position across the board (light fixed top-left)
            val sx = c.x - r * 0.34f - lightX * r * 0.35f
            drawOval(Color.White.copy(alpha = 0.7f * alpha), Offset(sx - r * 0.17f, c.y - r * 0.5f), Size(r * 0.34f, r * 0.19f))
        }
        if (f.budget.tryUse()) {
            gemShapePath(p, shape, c.x, c.y, r)
            drawPath(p, f.paints.ink.copy(alpha = 0.55f * alpha), style = Stroke(s * 0.03f))
        }
        drawGemSpecial(cell, c, r, alpha, f.paints, s)
        drawGemCage(cell, c, r, alpha, f.paints, s)
        drawGemTimer(cell, c, r, alpha, f.paints, f.text, s)
    }

    // ------------------------------------------------------------------------------------------------ 3D pieces
    private fun spriteName(cell: Cell): String? = when (cell.type) {
        PieceType.GEM -> if (cell.special == SpecialKind.PRISM) "prism" else "gem" + cell.kind.coerceIn(0, GemKinds.COUNT - 1)
        PieceType.STONE -> "stone" + cell.hp.coerceIn(1, 3)
        PieceType.SPIDER -> "spider"
        PieceType.BUTTERFLY -> "butterfly"
        PieceType.TREASURE -> "treasure"
        PieceType.EMPTY -> null
    }

    private fun overlayName(cell: Cell): String? = when (cell.special) {
        SpecialKind.FLARE_ROW -> "flare_row"; SpecialKind.FLARE_COL -> "flare_col"
        SpecialKind.BURST -> "burst"; SpecialKind.NOVA -> "nova"
        else -> null
    }

    /** Draws baked sprites; false when the atlas or a sprite is missing (the caller demotes and draws 2.5D instead: never a blank cell). */
    private fun DrawScope.drawSpritePiece(f: GemFrame, cell: Cell, c: Offset, s: Float, scale: Float, alpha: Float): Boolean {
        if (alpha <= 0f || scale <= 0f) return true
        val atlas = f.atlas ?: return false
        val name = spriteName(cell) ?: return true
        val body = atlas.manifest[name] ?: return false
        if (f.budget.tryUse()) {
            drawOval(Color.Black.copy(alpha = 0.28f * alpha), Offset(c.x - s * 0.3f * scale, c.y + s * 0.2f * scale), Size(s * 0.6f * scale, s * 0.2f * scale))
        }
        blit(atlas, body, c, s * scale, alpha)
        if (cell.type == PieceType.GEM) {
            overlayName(cell)?.let { n -> atlas.manifest[n]?.let { blit(atlas, it, c, s * scale, alpha) } }
            if (cell.locked) atlas.manifest["cage"]?.let { blit(atlas, it, c, s * scale, alpha) }
            drawGemTimer(cell, c, s * 0.37f * scale, alpha, f.paints, f.text, s)
        }
        f.budget.force(2)
        return true
    }

    private fun DrawScope.blit(atlas: GemAtlas, r: com.gamesuite.style.SpriteRect, centre: Offset, size: Float, alpha: Float) {
        val d = size.toInt().coerceAtLeast(1)
        drawImage(
            atlas.image,
            srcOffset = IntOffset(r.x, r.y), srcSize = IntSize(r.w, r.h),
            dstOffset = IntOffset((centre.x - d / 2f).toInt(), (centre.y - d / 2f - size * 0.04f).toInt()), dstSize = IntSize(d, d),
            alpha = alpha, filterQuality = FilterQuality.Medium
        )
    }

    // ------------------------------------------------------------------------------------------------ shapes
    /** Builds the outline of a gem shape centred at ([cx], [cy]) with flat-look radius [r] (same proportions as the flat art). */
    fun gemShapePath(path: Path, shape: GemShape, cx: Float, cy: Float, r: Float) {
        path.rewind()
        when (shape) {
            GemShape.CIRCLE -> path.addOval(androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r))
            GemShape.SQUARE -> path.addRoundRect(androidx.compose.ui.geometry.RoundRect(cx - r * 0.9f, cy - r * 0.9f, cx + r * 0.9f, cy + r * 0.9f, CornerRadius(r * 0.25f)))
            GemShape.TRIANGLE -> polygon(path, cx, cy, r * 1.15f, 3, -90.0)
            GemShape.DIAMOND -> polygon(path, cx, cy, r * 1.15f, 4, -90.0)
            GemShape.STAR -> {
                val n = 10
                for (i in 0 until n) {
                    val rad = if (i % 2 == 0) r * 1.2f else r * 1.2f * 0.48f
                    val a = Math.toRadians(-90.0 + i * 360.0 / n)
                    val x = cx + rad * cos(a).toFloat(); val y = cy + rad * sin(a).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            GemShape.HEXAGON -> polygon(path, cx, cy, r * 1.05f, 6, 0.0)
            GemShape.CROSS -> {
                val t = r * 0.31f
                val pts = floatArrayOf(-t, -r, t, -r, t, -t, r, -t, r, t, t, t, t, r, -t, r, -t, t, -r, t, -r, -t, -t, -t)
                for (i in 0 until 12) {
                    val x = cx + pts[i * 2]; val y = cy + pts[i * 2 + 1]
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
        }
    }

    private fun polygon(path: Path, cx: Float, cy: Float, r: Float, n: Int, startDeg: Double) {
        for (i in 0 until n) {
            val a = Math.toRadians(startDeg + i * 360.0 / n)
            val x = cx + r * cos(a).toFloat(); val y = cy + r * sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }
}
