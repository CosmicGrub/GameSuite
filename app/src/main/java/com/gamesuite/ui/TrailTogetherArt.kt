package com.gamesuite.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.gamesuite.games.trailtogether.TrailColour
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Everything Trail Together draws, all in code (no bitmaps). Colour never carries meaning alone: every stone colour
 * has a fixed shape drawn on the stone, the card and the pickers; every pal has a seat badge of colour AND shape.
 */
internal class TrailTheme(
    val bg: Color,
    val surface: Color,
    val ink: Color,
    val soft: Color,
    val accent: Color,
    val onAccent: Color,
    val path: Color,
    val fogBack: Color,
    val fogVeil: Color,
    val glow: Color
)

internal fun trailTheme(dark: Boolean): TrailTheme = if (dark) {
    TrailTheme(
        bg = Color(0xFF2A241B), surface = Color(0xFF3A3226), ink = Color(0xFFF6EEDD), soft = Color(0xFFB9AE98),
        accent = Color(0xFF8FD6A0), onAccent = Color(0xFF10301A), path = Color(0xFF5A4C38),
        fogBack = Color(0xFF8A94A1), fogVeil = Color(0xFFC9D0DA), glow = Color(0xFFFFD866)
    )
} else {
    TrailTheme(
        bg = Color(0xFFFFF6E5), surface = Color(0xFFFFFDF7), ink = Color(0xFF3B2F22), soft = Color(0xFF6E5F4D),
        accent = Color(0xFF2E6B3F), onAccent = Color(0xFFFFFFFF), path = Color(0xFFE6D3AE),
        fogBack = Color(0xFFB7BFC9), fogVeil = Color(0xFFDDE2E9), glow = Color(0xFFE9A800)
    )
}

/** The stone colour tokens of the design (section 2.2). */
internal fun TrailColour.tint(): Color = when (this) {
    TrailColour.BERRY -> Color(0xFFC8324B)
    TrailColour.SUN -> Color(0xFFE9A800)
    TrailColour.LEAF -> Color(0xFF3E8E41)
    TrailColour.SKY -> Color(0xFF2D7DD2)
    TrailColour.PLUM -> Color(0xFF7B3FA0)
    TrailColour.PEACH -> Color(0xFFE9772E)
}

/** The pal badges: Nutmeg orange square, Pip blue circle, Clover green triangle, Burr purple star (identity only). */
internal val TrailPalColours = listOf(Color(0xFFD9661F), Color(0xFF2468B8), Color(0xFF2E8B3E), Color(0xFF7B3FA0))
internal val TrailPalShapes = listOf("square", "circle", "triangle", "star")

/** Reusable paths so drawing allocates nothing per frame. */
internal class TrailScratch(val a: Path = Path(), val b: Path = Path())

private fun starPoint(cx: Float, cy: Float, r: Float, i: Int, n: Int): Offset {
    val a = -PI / 2 + i * 2 * PI / n
    return Offset(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
}

/** Fills [p] with the shape of radius about [r] centred on ([cx], [cy]). */
internal fun trailShapePath(shape: String, cx: Float, cy: Float, r: Float, p: Path) {
    p.rewind()
    when (shape) {
        "triangle" -> {
            p.moveTo(cx, cy - r)
            p.lineTo(cx + r * 0.98f, cy + r * 0.78f)
            p.lineTo(cx - r * 0.98f, cy + r * 0.78f)
            p.close()
        }
        "square" -> p.addRoundRect(RoundRect(cx - r * 0.84f, cy - r * 0.84f, cx + r * 0.84f, cy + r * 0.84f, CornerRadius(r * 0.22f)))
        "diamond" -> {
            p.moveTo(cx, cy - r * 1.08f)
            p.lineTo(cx + r * 0.88f, cy)
            p.lineTo(cx, cy + r * 1.08f)
            p.lineTo(cx - r * 0.88f, cy)
            p.close()
        }
        "star" -> {
            for (i in 0 until 10) {
                val o = starPoint(cx, cy, if (i % 2 == 0) r * 1.1f else r * 0.48f, i, 10)
                if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y)
            }
            p.close()
        }
        "hexagon" -> {
            for (i in 0 until 6) {
                val a = PI / 6 + i * PI / 3
                val x = cx + r * 1.02f * cos(a).toFloat()
                val y = cy + r * 1.02f * sin(a).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
        }
        else -> p.addOval(Rect(cx - r, cy - r, cx + r, cy + r))
    }
}

internal fun DrawScope.drawTrailShape(
    shape: String, centre: Offset, r: Float, fill: Color, outline: Color, outlinePx: Float, scratch: Path, alpha: Float = 1f
) {
    trailShapePath(shape, centre.x, centre.y, r, scratch)
    drawPath(scratch, fill.copy(alpha = fill.alpha * alpha))
    drawPath(scratch, outline.copy(alpha = outline.alpha * alpha), style = Stroke(outlinePx, join = StrokeJoin.Round))
}

/** A lit stone: a soft disc of the stone colour with its shape on it, so shape and colour agree. */
internal fun DrawScope.drawStone(colour: TrailColour, centre: Offset, r: Float, theme: TrailTheme, density: Float, scratch: Path, scale: Float = 1f) {
    val rr = r * scale
    drawCircle(Color.Black.copy(alpha = 0.14f), rr * 1.05f, Offset(centre.x, centre.y + rr * 0.12f))
    drawCircle(theme.surface, rr)
    drawCircle(theme.ink.copy(alpha = 0.55f), rr, style = Stroke(1.5f * density))
    drawTrailShape(colour.shape, centre, rr * 0.72f, colour.tint(), theme.ink, 1.6f * density, scratch)
}

/** A spot that is not lit yet: a dotted outline. */
internal fun DrawScope.drawDimStone(centre: Offset, r: Float, theme: TrailTheme, density: Float) {
    drawCircle(
        theme.soft.copy(alpha = 0.7f), r * 0.9f, centre,
        style = Stroke(2f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * density, 5f * density)))
    )
}

/** The glowing ring that marks the spot the next stone will light. [phase] 0..1. */
internal fun DrawScope.drawFrontierGlow(centre: Offset, r: Float, theme: TrailTheme, density: Float, phase: Float) {
    drawCircle(theme.glow.copy(alpha = 0.25f + 0.35f * phase), r * (1.05f + 0.15f * phase), centre)
    drawCircle(theme.glow, r * 0.95f, centre, style = Stroke(3f * density))
}

/** The burrow: a round door in a green hill, with a lit window once the trail is lit through. */
internal fun DrawScope.drawBurrow(centre: Offset, r: Float, open: Boolean, theme: TrailTheme, density: Float, scratch: Path) {
    val hill = Color(0xFF4F9A58)
    drawCircle(hill, r * 1.25f, Offset(centre.x, centre.y + r * 0.2f))
    drawCircle(theme.ink.copy(alpha = 0.5f), r * 1.25f, Offset(centre.x, centre.y + r * 0.2f), style = Stroke(1.5f * density))
    val door = if (open) Color(0xFFFFD866) else Color(0xFF6B4A2B)
    drawCircle(door, r * 0.78f, centre)
    drawCircle(theme.ink, r * 0.78f, centre, style = Stroke(2f * density))
    if (open) {
        // open door: a bright window with a cross frame
        drawLine(theme.ink, Offset(centre.x, centre.y - r * 0.78f), Offset(centre.x, centre.y + r * 0.78f), 1.5f * density)
        drawLine(theme.ink, Offset(centre.x - r * 0.78f, centre.y), Offset(centre.x + r * 0.78f, centre.y), 1.5f * density)
        drawCircle(Color(0xFFFFF3B0).copy(alpha = 0.55f), r * 1.5f, centre)
    } else {
        // closed door: a small round knob
        drawCircle(Color(0xFFE9C46A), r * 0.12f, Offset(centre.x + r * 0.32f, centre.y))
    }
    scratch.rewind()
}

/**
 * A hedgehog pal facing right: brown body, a ragged ridge of spines, a pale snout and a seat badge in the owner's
 * colour AND shape. [size] is the body width.
 */
internal fun DrawScope.drawHedgehog(centre: Offset, size: Float, ownerColour: Color, ownerShape: String, theme: TrailTheme, density: Float, scratch: Path, badgeScratch: Path, alpha: Float = 1f) {
    val w = size
    val h = size * 0.74f
    val bodyColour = Color(0xFF8B5E3C).copy(alpha = alpha)
    val spine = Color(0xFF4E3524).copy(alpha = alpha)
    // spines: a zigzag over the back
    val p = scratch
    p.rewind()
    val left = centre.x - w * 0.5f
    val top = centre.y - h * 0.5f
    p.moveTo(left, centre.y + h * 0.1f)
    val teeth = 7
    for (i in 0..teeth) {
        val x = left + w * 0.78f * i / teeth
        val up = top + h * 0.02f
        p.lineTo(x, if (i % 2 == 0) up else up + h * 0.3f)
    }
    p.lineTo(left + w * 0.78f, centre.y + h * 0.35f)
    p.lineTo(left + w * 0.05f, centre.y + h * 0.35f)
    p.close()
    drawPath(p, spine)
    // body
    drawOval(bodyColour, Offset(left + w * 0.06f, top + h * 0.3f), Size(w * 0.84f, h * 0.7f))
    // snout
    drawOval(Color(0xFFF1D6AE).copy(alpha = alpha), Offset(left + w * 0.66f, top + h * 0.42f), Size(w * 0.34f, h * 0.42f))
    drawCircle(Color(0xFF2B1B10).copy(alpha = alpha), w * 0.045f, Offset(left + w * 0.985f, top + h * 0.6f))
    drawCircle(Color(0xFF2B1B10).copy(alpha = alpha), w * 0.04f, Offset(left + w * 0.78f, top + h * 0.5f))
    // feet
    drawCircle(spine, w * 0.07f, Offset(left + w * 0.28f, top + h * 1.0f))
    drawCircle(spine, w * 0.07f, Offset(left + w * 0.62f, top + h * 1.0f))
    drawOval(theme.ink.copy(alpha = 0.55f * alpha), Offset(left + w * 0.06f, top + h * 0.3f), Size(w * 0.84f, h * 0.7f), style = Stroke(1.2f * density))
    // owner badge
    val b = Offset(left + w * 0.3f, top + h * 0.55f)
    drawCircle(Color.White.copy(alpha = alpha), w * 0.2f, b)
    drawTrailShape(ownerShape, b, w * 0.14f, ownerColour, theme.ink, 1.2f * density, badgeScratch, alpha)
}

/** Soft scalloped fog rising from the bottom of the board. [level] 0..1 is how far the fog has come; [drift] 0..1 bobs it. */
internal fun DrawScope.drawFogBank(level: Float, drift: Float, theme: TrailTheme, front: Boolean) {
    val w = size.width
    val h = size.height
    val rise = h * (0.05f + 0.55f * level.coerceIn(0f, 1f))
    val top = h - rise
    val puff = (w / 7f).coerceAtLeast(8f)
    val colour = if (front) theme.fogVeil.copy(alpha = 0.16f) else theme.fogBack.copy(alpha = 0.88f)
    drawRect(colour, Offset(0f, top + puff * 0.3f), Size(w, h - top))
    var x = -puff * 0.3f
    var i = 0
    while (x < w + puff) {
        val bob = sin((drift * 2.0 * PI) + i * 1.3).toFloat() * puff * 0.08f
        drawCircle(colour, puff * (0.62f + 0.12f * ((i * 7) % 3)), Offset(x, top + bob))
        x += puff * 0.9f
        i++
    }
}

/** A drifting cloud icon for the fog track, [r] is its radius. */
internal fun DrawScope.drawCloud(centre: Offset, r: Float, fill: Color, outline: Color, density: Float) {
    drawCircle(fill, r * 0.55f, Offset(centre.x - r * 0.55f, centre.y + r * 0.1f))
    drawCircle(fill, r * 0.7f, Offset(centre.x, centre.y - r * 0.15f))
    drawCircle(fill, r * 0.5f, Offset(centre.x + r * 0.6f, centre.y + r * 0.12f))
    drawRect(fill, Offset(centre.x - r * 0.55f, centre.y + r * 0.1f), Size(r * 1.15f, r * 0.52f))
    drawCircle(outline.copy(alpha = 0.35f), r * 0.7f, Offset(centre.x, centre.y - r * 0.15f), style = Stroke(1f * density))
}

/** The hop icon: a curved arrow jumping from the left to the right. */
internal fun DrawScope.drawHopArrow(box: Rect, colour: Color, density: Float, scratch: Path) {
    scratch.rewind()
    scratch.moveTo(box.left, box.bottom)
    scratch.quadraticBezierTo(box.center.x, box.top - box.height * 0.9f, box.right, box.bottom)
    drawPath(scratch, colour, style = Stroke(2.6f * density, cap = StrokeCap.Round))
    val a = Offset(box.right, box.bottom)
    val s = box.width * 0.18f
    scratch.rewind()
    scratch.moveTo(a.x, a.y)
    scratch.lineTo(a.x - s * 1.2f, a.y - s * 0.2f)
    scratch.lineTo(a.x - s * 0.3f, a.y - s * 1.1f)
    scratch.close()
    drawPath(scratch, colour)
}

/** A cosy lantern: handle, body with a warm glow, base. */
internal fun DrawScope.drawLantern(centre: Offset, size: Float, theme: TrailTheme, density: Float) {
    val w = size * 0.62f
    val h = size * 0.8f
    val left = centre.x - w / 2
    val top = centre.y - h / 2 + size * 0.06f
    drawCircle(Color(0xFFFFE08A).copy(alpha = 0.35f), size * 0.62f, centre)
    drawArc(theme.ink, 180f, 180f, false, Offset(centre.x - w * 0.28f, top - h * 0.3f), Size(w * 0.56f, h * 0.5f), style = Stroke(2f * density, cap = StrokeCap.Round))
    drawRoundRect(Color(0xFFFFC93C), Offset(left, top), Size(w, h), CornerRadius(w * 0.3f))
    drawRoundRect(theme.ink, Offset(left, top), Size(w, h), CornerRadius(w * 0.3f), style = Stroke(2f * density))
    drawLine(theme.ink.copy(alpha = 0.7f), Offset(centre.x, top), Offset(centre.x, top + h), 1.5f * density)
    drawRect(theme.ink, Offset(left - w * 0.06f, top - h * 0.07f), Size(w * 1.12f, h * 0.1f))
    drawRect(theme.ink, Offset(left - w * 0.06f, top + h * 0.96f), Size(w * 1.12f, h * 0.1f))
}

/** A plus badge (the stone card's "light a new stone"). */
internal fun DrawScope.drawPlus(centre: Offset, r: Float, fill: Color, mark: Color, density: Float) {
    drawCircle(fill, r, centre)
    drawLine(mark, Offset(centre.x - r * 0.5f, centre.y), Offset(centre.x + r * 0.5f, centre.y), 2.4f * density, StrokeCap.Round)
    drawLine(mark, Offset(centre.x, centre.y - r * 0.5f), Offset(centre.x, centre.y + r * 0.5f), 2.4f * density, StrokeCap.Round)
}

/** The swap icon: two curved arrows chasing each other. */
internal fun DrawScope.drawSwapIcon(centre: Offset, r: Float, colour: Color, density: Float, scratch: Path) {
    val stroke = Stroke(2.6f * density, cap = StrokeCap.Round)
    drawArc(colour, 200f, 140f, false, Offset(centre.x - r, centre.y - r), Size(r * 2, r * 2), style = stroke)
    drawArc(colour, 20f, 140f, false, Offset(centre.x - r, centre.y - r), Size(r * 2, r * 2), style = stroke)
    val tip1 = Offset(centre.x + r * cos(Math.toRadians(340.0)).toFloat(), centre.y + r * sin(Math.toRadians(340.0)).toFloat())
    val tip2 = Offset(centre.x + r * cos(Math.toRadians(160.0)).toFloat(), centre.y + r * sin(Math.toRadians(160.0)).toFloat())
    for ((tip, dir) in listOf(tip1 to 1f, tip2 to -1f)) {
        scratch.rewind()
        scratch.moveTo(tip.x, tip.y)
        scratch.lineTo(tip.x - dir * r * 0.5f, tip.y - r * 0.05f)
        scratch.lineTo(tip.x - dir * r * 0.05f, tip.y + dir * r * 0.5f)
        scratch.close()
        drawPath(scratch, colour)
    }
}

/** The help icon: a little spark of light. */
internal fun DrawScope.drawSparkIcon(centre: Offset, r: Float, colour: Color, scratch: Path) {
    scratch.rewind()
    for (i in 0 until 8) {
        val o = starPoint(centre.x, centre.y, if (i % 2 == 0) r else r * 0.34f, i, 8)
        if (i == 0) scratch.moveTo(o.x, o.y) else scratch.lineTo(o.x, o.y)
    }
    scratch.close()
    drawPath(scratch, colour)
}

/** The serpentine board of section 2.5: spot i is in row i / columns; even rows run left to right, odd rows right to left; row 0 is at the bottom. */
internal object TrailGeometry {
    fun rows(spotCount: Int, columns: Int): Int = (spotCount + columns - 1) / columns

    /** Centre of spot [index] inside a board of [rows] rows of square cells of [cell] px. */
    fun centre(index: Int, columns: Int, rows: Int, cell: Float): Offset {
        val row = index / columns
        val c = index % columns
        val col = if (row % 2 == 0) c else columns - 1 - c
        return Offset((col + 0.5f) * cell, (rows - row - 0.5f) * cell)
    }
}
