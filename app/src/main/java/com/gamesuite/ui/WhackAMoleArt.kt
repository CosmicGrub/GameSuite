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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import com.gamesuite.games.whackamole.Critter
import com.gamesuite.games.whackamole.HoleView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Original vector art of Whack-a-Mole Race: a dirt mound with a dark hole, and three critters drawn only from
 * circles, paths and gradients: Moxie the mole (round brown head, pink nose), Goldie the gopher (golden, a star on
 * her cap, big white teeth) and Prickle the urchin (a spiky crown, angry brows). The three are told apart by SHAPE
 * (round / star-capped / spiky) and by their written names, never by colour alone.
 */

private val Dirt = Color(0xFF8B5E3C)
private val DirtLight = Color(0xFFB07C52)
private val HoleDark = Color(0xFF1E140C)

/** Draws the hole in the cell of side [cell] centred at [c] with whatever [view] has up in it. [cursor] marks the one-switch scan position. */
internal fun DrawScope.wmDrawHole(c: Offset, cell: Float, view: HoleView?, cursor: Boolean) {
    val holeW = cell * 0.78f
    val holeH = cell * 0.30f
    val lipY = c.y + cell * 0.22f
    // Ground mound behind and the dark hole.
    drawOval(Brush.verticalGradient(listOf(DirtLight, Dirt), startY = lipY - holeH, endY = lipY + holeH), Offset(c.x - holeW * 0.62f, lipY - holeH * 0.62f), Size(holeW * 1.24f, holeH * 1.5f))
    drawOval(HoleDark, Offset(c.x - holeW / 2f, lipY - holeH / 2f), Size(holeW, holeH))
    if (view != null && view.pop > 0f) {
        val rise = (1f - view.pop) * cell * 0.62f
        clipRect(left = c.x - cell / 2f, top = c.y - cell / 2f, right = c.x + cell / 2f, bottom = lipY + holeH * 0.02f) {
            val head = Offset(c.x, lipY - cell * 0.2f + rise)
            if (view.hitAgeMs >= 0) {
                scale(1.12f, 0.68f, Offset(head.x, lipY)) { wmDrawCritter(view.critter, head, cell, dazed = true) }
                val a = (view.hitAgeMs / 120f).coerceIn(0f, 1f)
                rdDrawSymbol(4, Color.White, Offset(head.x - cell * 0.28f, head.y - cell * 0.3f), cell * 0.07f * a, alpha = 0.9f)
                rdDrawSymbol(4, Color(0xFFF0E442), Offset(head.x + cell * 0.3f, head.y - cell * 0.22f), cell * 0.06f * a, alpha = 0.9f)
            } else {
                wmDrawCritter(view.critter, head, cell, dazed = false)
            }
        }
    }
    // The front lip of the mound, drawn over the critter so it really comes out of the hole.
    val lip = Path().apply {
        addArc(androidx.compose.ui.geometry.Rect(c.x - holeW / 2f, lipY - holeH / 2f, c.x + holeW / 2f, lipY + holeH / 2f), 0f, 180f)
    }
    drawPath(lip, DirtLight, style = Stroke(cell * 0.07f, cap = StrokeCap.Round))
    if (cursor) {
        val r = cell * 0.47f
        val tl = Offset(c.x - r, c.y - r)
        drawRoundRect(Color.White, tl, Size(r * 2, r * 2), CornerRadius(cell * 0.14f), style = Stroke(cell * 0.045f))
        drawRoundRect(Color(0xFF101010), tl + Offset(cell * 0.035f, cell * 0.035f), Size(r * 2 - cell * 0.07f, r * 2 - cell * 0.07f), CornerRadius(cell * 0.12f), style = Stroke(cell * 0.02f))
    }
}

private fun DrawScope.eye(c: Offset, r: Float, dazed: Boolean, angry: Boolean = false) {
    if (dazed) {
        drawLine(Color(0xFF241810), Offset(c.x - r * 0.8f, c.y - r * 0.8f), Offset(c.x + r * 0.8f, c.y + r * 0.8f), r * 0.35f, StrokeCap.Round)
        drawLine(Color(0xFF241810), Offset(c.x + r * 0.8f, c.y - r * 0.8f), Offset(c.x - r * 0.8f, c.y + r * 0.8f), r * 0.35f, StrokeCap.Round)
    } else {
        drawCircle(Color.White, r, c)
        drawCircle(Color(0xFF241810), r * 0.55f, c + Offset(0f, r * 0.1f))
        drawCircle(Color.White, r * 0.18f, c + Offset(-r * 0.15f, -r * 0.1f))
    }
    if (angry) drawLine(Color(0xFF14101F), Offset(c.x - r * 1.2f, c.y - r * 1.25f), Offset(c.x + r * 1.1f, c.y - r * 0.55f), r * 0.4f, StrokeCap.Round)
}

private fun DrawScope.wmDrawCritter(critter: Critter, head: Offset, cell: Float, dazed: Boolean) {
    val r = cell * 0.26f
    when (critter) {
        Critter.MOLE -> {
            val fur = Color(0xFF7B5236)
            drawCircle(fur, r * 0.4f, Offset(head.x - r * 0.85f, head.y - r * 0.8f)); drawCircle(Color(0xFFE8A0A8), r * 0.2f, Offset(head.x - r * 0.85f, head.y - r * 0.8f))
            drawCircle(fur, r * 0.4f, Offset(head.x + r * 0.85f, head.y - r * 0.8f)); drawCircle(Color(0xFFE8A0A8), r * 0.2f, Offset(head.x + r * 0.85f, head.y - r * 0.8f))
            drawCircle(Brush.radialGradient(listOf(rdShade(fur, 0.3f), fur, rdShade(fur, -0.3f)), center = Offset(head.x - r * 0.3f, head.y - r * 0.4f), radius = r * 1.8f), r, head)
            drawCircle(rdShade(fur, -0.55f), r, head, style = Stroke(r * 0.09f))
            drawOval(Color(0xFFC9A27E), Offset(head.x - r * 0.55f, head.y + r * 0.05f), Size(r * 1.1f, r * 0.8f))
            drawCircle(Color(0xFFE88AA0), r * 0.2f, Offset(head.x, head.y + r * 0.2f))
            eye(Offset(head.x - r * 0.42f, head.y - r * 0.2f), r * 0.2f, dazed)
            eye(Offset(head.x + r * 0.42f, head.y - r * 0.2f), r * 0.2f, dazed)
            for (s in listOf(-1f, 1f)) {
                drawLine(Color(0x88241810), Offset(head.x + s * r * 0.35f, head.y + r * 0.4f), Offset(head.x + s * r * 0.95f, head.y + r * 0.32f), r * 0.05f)
                drawLine(Color(0x88241810), Offset(head.x + s * r * 0.35f, head.y + r * 0.5f), Offset(head.x + s * r * 0.95f, head.y + r * 0.58f), r * 0.05f)
            }
        }
        Critter.GOLD -> {
            val fur = Color(0xFFE8B923)
            drawCircle(fur, r * 0.38f, Offset(head.x - r * 0.8f, head.y - r * 0.75f)); drawCircle(fur, r * 0.38f, Offset(head.x + r * 0.8f, head.y - r * 0.75f))
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF2A8), fur, Color(0xFFB88A00)), center = Offset(head.x - r * 0.3f, head.y - r * 0.4f), radius = r * 1.8f), r, head)
            drawCircle(Color(0xFF7A5A00), r, head, style = Stroke(r * 0.09f))
            drawOval(Color(0xFFFFF0B8), Offset(head.x - r * 0.55f, head.y + r * 0.05f), Size(r * 1.1f, r * 0.75f))
            drawCircle(Color(0xFF5A3A12), r * 0.17f, Offset(head.x, head.y + r * 0.18f))
            drawRoundRect(Color.White, Offset(head.x - r * 0.2f, head.y + r * 0.38f), Size(r * 0.4f, r * 0.3f), CornerRadius(r * 0.06f))
            drawLine(Color(0xFF9A9A9A), Offset(head.x, head.y + r * 0.38f), Offset(head.x, head.y + r * 0.68f), r * 0.03f)
            eye(Offset(head.x - r * 0.42f, head.y - r * 0.22f), r * 0.2f, dazed)
            eye(Offset(head.x + r * 0.42f, head.y - r * 0.22f), r * 0.2f, dazed)
            // The star on her cap: the bonus is the one with a star on top.
            rdDrawSymbol(4, Color(0xFFFFFFFF), Offset(head.x, head.y - r * 1.15f), r * 0.42f)
        }
        Critter.DECOY -> {
            val body = Color(0xFF4A3F6B)
            for (i in 0 until 12) {
                val a = i * 2 * PI / 12
                val base1 = Offset(head.x + (cos(a - 0.2) * r * 0.9f).toFloat(), head.y + (sin(a - 0.2) * r * 0.9f).toFloat())
                val base2 = Offset(head.x + (cos(a + 0.2) * r * 0.9f).toFloat(), head.y + (sin(a + 0.2) * r * 0.9f).toFloat())
                val tip = Offset(head.x + (cos(a) * r * 1.5f).toFloat(), head.y + (sin(a) * r * 1.5f).toFloat())
                drawPath(Path().apply { moveTo(base1.x, base1.y); lineTo(tip.x, tip.y); lineTo(base2.x, base2.y); close() }, Color(0xFF2E2745))
                drawCircle(Color(0xFFE6E0FF), r * 0.05f, tip)
            }
            drawCircle(Brush.radialGradient(listOf(Color(0xFF7A6CA8), body, Color(0xFF2E2745)), center = Offset(head.x - r * 0.3f, head.y - r * 0.4f), radius = r * 1.8f), r, head)
            drawCircle(Color(0xFF15102A), r, head, style = Stroke(r * 0.09f))
            eye(Offset(head.x - r * 0.38f, head.y - r * 0.12f), r * 0.2f, dazed, angry = !dazed)
            eye(Offset(head.x + r * 0.38f, head.y - r * 0.12f), r * 0.2f, dazed)
            if (!dazed) drawLine(Color(0xFF14101F), Offset(head.x + r * 0.15f, head.y - r * 0.55f), Offset(head.x + r * 0.62f, head.y - r * 0.4f), r * 0.1f, StrokeCap.Round)
            val zig = Path().apply {
                moveTo(head.x - r * 0.45f, head.y + r * 0.55f)
                lineTo(head.x - r * 0.22f, head.y + r * 0.4f); lineTo(head.x, head.y + r * 0.58f); lineTo(head.x + r * 0.22f, head.y + r * 0.4f); lineTo(head.x + r * 0.45f, head.y + r * 0.55f)
            }
            drawPath(zig, Color(0xFFE6E0FF), style = Stroke(r * 0.08f, cap = StrokeCap.Round))
        }
    }
}
