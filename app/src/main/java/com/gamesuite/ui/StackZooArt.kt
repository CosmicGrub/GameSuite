package com.gamesuite.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.gamesuite.games.stackzoo.ZooShape
import com.gamesuite.games.stackzoo.ZooShapes

/*
 * Original flat vector art of Stack Zoo: every animal is drawn straight from the same convex parts the physics uses, so
 * what you see is exactly what balances. Thick dark outline, one body colour per animal, a round eye. No bitmaps.
 */

private val Ink = Color(0xFF3B2A1E)

/** Twenty body colours (one per animal index), chosen to differ in lightness as well as hue so they never rely on hue alone. */
internal val ZooBodyColors: List<Color> = listOf(
    0xFF5BAA6E, 0xFF9AA5B1, 0xFFB7A6C9, 0xFFE8C766, 0xFF7DB36B, 0xFFE59A62, 0xFF3F5F8F, 0xFFD9C3A5, 0xFFE0773C, 0xFF9C6B45,
    0xFFF2D13D, 0xFF7ED38A, 0xFF8D6E9C, 0xFFB8845A, 0xFF6FA86F, 0xFF5C86B8, 0xFFE2B36A, 0xFF8A8A8A, 0xFFC9D86B, 0xFFF2A7B8
).map { Color(it) }

/** The silhouette parts of animal [index] as screen paths: `vertex -> world -> screen` is done by [toScreen]. */
private fun DrawScope.partPath(shape: ZooShape, part: Int, toScreen: (Double, Double) -> Offset): Path {
    val path = Path()
    val n = shape.vertCount[part]
    for (k in 0 until n) {
        val i = part * 8 + k
        val p = toScreen(shape.vx[i], shape.vy[i])
        if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    return path
}

/**
 * Draws animal [animalIndex] with its centre of mass at [com] (pixels), turned by [angle] radians (counter-clockwise on
 * screen) and scaled [pxPerUnit]. [alpha] fades a falling animal; [outline] widens the ink line.
 */
internal fun DrawScope.drawZooAnimal(animalIndex: Int, com: Offset, angle: Double, pxPerUnit: Float, alpha: Float = 1f, tint: Color? = null) {
    val shape = ZooShapes.animals[animalIndex.coerceIn(0, ZooShapes.animals.size - 1)]
    val c = StrictMath.cos(angle).toFloat()
    val s = StrictMath.sin(angle).toFloat()
    // body-frame (centre of mass origin) -> screen: rotate counter-clockwise, flip y
    val toScreen = { vx: Double, vy: Double ->
        val x = vx.toFloat()
        val y = vy.toFloat()
        Offset(com.x + (x * c - y * s) * pxPerUnit, com.y - (x * s + y * c) * pxPerUnit)
    }
    val fill = (tint ?: ZooBodyColors[animalIndex.coerceIn(0, ZooBodyColors.size - 1)]).copy(alpha = alpha)
    val line = Ink.copy(alpha = alpha)
    val width = (pxPerUnit * 0.07f).coerceAtLeast(2f)
    for (p in 0 until shape.partCount) {
        val path = partPath(shape, p, toScreen)
        drawPath(path, fill)
        drawPath(path, line, style = Stroke(width, join = StrokeJoin.Round))
    }
    // an eye on the highest vertex, pulled in toward the middle
    var ey = -1e9
    var ex = 0.0
    var ez = 0.0
    for (p in 0 until shape.partCount) for (k in 0 until shape.vertCount[p]) {
        val i = p * 8 + k
        val up = shape.vx[i] * s + shape.vy[i] * c
        if (up > ey) { ey = up; ex = shape.vx[i]; ez = shape.vy[i] }
    }
    val eye = toScreen(ex * 0.8, ez * 0.8 - 0.12)
    drawCircle(Color.White.copy(alpha = alpha), pxPerUnit * 0.17f, eye)
    drawCircle(line, pxPerUnit * 0.08f, eye)
}

/** A small icon of animal [animalIndex] filling the box ([topLeft], [size]) upright, fitted by its own bounding box. */
internal fun DrawScope.drawZooAnimalFitted(animalIndex: Int, topLeft: Offset, boxW: Float, boxH: Float, alpha: Float = 1f) {
    val shape = ZooShapes.animals[animalIndex.coerceIn(0, ZooShapes.animals.size - 1)]
    val w = (shape.maxX - shape.minX).toFloat()
    val h = (shape.maxY - shape.minY).toFloat()
    val k = minOf(boxW / (w + 0.2f), boxH / (h + 0.2f))
    val cx = topLeft.x + boxW / 2f - ((shape.minX + shape.maxX) / 2f).toFloat() * k
    val cy = topLeft.y + boxH / 2f + ((shape.minY + shape.maxY) / 2f).toFloat() * k
    // the shape frame has its origin at the centre of mass, so the box centre is offset from it
    drawZooAnimal(animalIndex, Offset(cx, cy), 0.0, k, alpha)
}

/** The rocking log: a plank turned [angle] radians about its pivot, on a round stand. Drawn in world pixels around [pivot]. */
internal fun DrawScope.drawZooLog(pivot: Offset, angle: Double, pxPerUnit: Float, wood: Color, woodDark: Color, alarm: Boolean) {
    val c = StrictMath.cos(angle).toFloat()
    val s = StrictMath.sin(angle).toFloat()
    val hw = 3.0f
    val hh = 0.2f
    fun pt(x: Float, y: Float) = Offset(pivot.x + (x * c - y * s) * pxPerUnit, pivot.y - (x * s + y * c) * pxPerUnit)
    // the stand: a half-round log under the plank
    drawCircle(woodDark, pxPerUnit * 0.8f, Offset(pivot.x, pivot.y + pxPerUnit * 0.75f))
    drawCircle(Ink.copy(alpha = 0.6f), pxPerUnit * 0.8f, Offset(pivot.x, pivot.y + pxPerUnit * 0.75f), style = Stroke(pxPerUnit * 0.06f))
    val plank = Path().apply {
        val a = pt(-hw, hh); val b = pt(hw, hh); val d = pt(hw, -hh); val e = pt(-hw, -hh)
        moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(d.x, d.y); lineTo(e.x, e.y); close()
    }
    drawPath(plank, wood)
    drawPath(plank, Ink, style = Stroke(pxPerUnit * 0.07f, join = StrokeJoin.Round))
    // wobble marker: a triangle on the pivot (shape as well as colour)
    if (alarm) {
        val t = Path().apply {
            moveTo(pivot.x, pivot.y + pxPerUnit * 0.1f)
            lineTo(pivot.x - pxPerUnit * 0.35f, pivot.y + pxPerUnit * 0.75f)
            lineTo(pivot.x + pxPerUnit * 0.35f, pivot.y + pxPerUnit * 0.75f)
            close()
        }
        drawPath(t, Color(0xFFE5483B))
        drawPath(t, Ink, style = Stroke(pxPerUnit * 0.05f))
    }
}

/** Linear blend helper for tints. */
internal fun zooMix(a: Color, b: Color, t: Float): Color = lerp(a, b, t)
