package com.gamesuite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * A real little 3D engine for one die, drawn on a Compose Canvas (no image files): a unit cube is
 * rotated by a 3x3 matrix, each visible face is lit by one lamp and projected with perspective, and
 * the pips are circles laid out on the face plane, so a tumbling die shows genuine foreshortening.
 * A settled die has the rolled number facing the viewer with the top and right faces peeking out.
 * Opposite faces add to seven (1/6, 2/5, 3/4) and the die is right-handed like a standard one: with the 1 facing
 * the viewer, the 2 is on the left and the 3 below, so 1-2-3 run counter-clockwise round their shared corner.
 */

private typealias Mat = FloatArray // row-major 3x3

private fun identity(): Mat = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

private fun mul(a: Mat, b: Mat): Mat {
    val r = FloatArray(9)
    for (i in 0 until 3) for (j in 0 until 3) {
        var s = 0f
        for (k in 0 until 3) s += a[i * 3 + k] * b[k * 3 + j]
        r[i * 3 + j] = s
    }
    return r
}

private fun rotX(a: Float): Mat { val c = cos(a); val s = sin(a); return floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c) }
private fun rotY(a: Float): Mat { val c = cos(a); val s = sin(a); return floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c) }

/** Rodrigues rotation about the unit axis (x, y, z). */
private fun rotAxis(x: Float, y: Float, z: Float, a: Float): Mat {
    val c = cos(a); val s = sin(a); val t = 1f - c
    return floatArrayOf(
        t * x * x + c, t * x * y - s * z, t * x * z + s * y,
        t * x * y + s * z, t * y * y + c, t * y * z - s * x,
        t * x * z - s * y, t * y * z + s * x, t * z * z + c
    )
}

private class Face(val value: Int, val n: FloatArray, val u: FloatArray, val v: FloatArray)

private val faces = listOf(
    Face(1, floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f)),
    Face(6, floatArrayOf(0f, 0f, -1f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f)),
    Face(5, floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 1f, 0f)),
    Face(2, floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 1f, 0f)),
    Face(3, floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f)),
    Face(4, floatArrayOf(0f, -1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f))
)

/** Outward normal of the cube face carrying [value] (x right, y DOWN the screen, z towards the viewer); exposed for the geometry test. */
internal fun dieFaceNormal(value: Int): FloatArray = faces.first { it.value == value }.n.copyOf()

/** The rotation that turns the face showing [value] towards the viewer (+z). */
private fun faceToFront(value: Int): Mat = when (value) {
    6 -> rotY(PI.toFloat())
    2 -> rotY((PI / 2).toFloat())
    5 -> rotY((-PI / 2).toFloat())
    3 -> rotX((PI / 2).toFloat())
    4 -> rotX((-PI / 2).toFloat())
    else -> identity()
}

private val tiltView: Mat by lazy { mul(rotX(-0.5f), rotY(-0.55f)) }

private fun pipCells(value: Int): List<Pair<Int, Int>> = when (value) {
    1 -> listOf(0 to 0)
    2 -> listOf(-1 to -1, 1 to 1)
    3 -> listOf(-1 to -1, 0 to 0, 1 to 1)
    4 -> listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)
    5 -> listOf(-1 to -1, 1 to -1, 0 to 0, -1 to 1, 1 to 1)
    else -> listOf(-1 to -1, -1 to 0, -1 to 1, 1 to -1, 1 to 0, 1 to 1)
}

/**
 * Draws one die centred at [center] in this scope. [spin] is 0 for a settled die and up to 1 for a
 * fresh throw: the cube is turned about [axisSeed]'s axis by that fraction of several full turns.
 * [body] / [pip] colour the die; [bust] tints a 1 that cost the turn.
 */
internal fun DrawScope.drawDie3D(
    center: Offset, extent: Float, value: Int, spin: Float, axisSeed: Int, body: Color, pip: Color, lift: Float = 0f, bust: Boolean = false
) {
    val v = value.coerceIn(1, 6)
    // fixed random axis per die and throw
    val ax0 = sin(axisSeed * 12.9898f) * 0.5f + 0.6f
    val ay0 = cos(axisSeed * 78.233f) * 0.5f + 0.1f
    val az0 = sin(axisSeed * 37.719f) * 0.5f
    val len = sqrt(ax0 * ax0 + ay0 * ay0 + az0 * az0).coerceAtLeast(0.001f)
    val turns = 2f + (abs(axisSeed) % 2)
    val spinM = rotAxis(ax0 / len, ay0 / len, az0 / len, spin * turns * 2f * PI.toFloat())
    val m = mul(tiltView, mul(spinM, faceToFront(v)))

    val c = Offset(center.x, center.y - lift)
    // contact shadow, shrinking and fading as the die is thrown up
    val shadowK = 1f - (lift / (extent * 1.6f)).coerceIn(0f, 0.6f)
    drawOval(
        Color(0x44000000).copy(alpha = 0.3f * shadowK),
        topLeft = Offset(center.x - extent * 1.15f * shadowK, center.y + extent * 1.0f),
        size = androidx.compose.ui.geometry.Size(extent * 2.3f * shadowK, extent * 0.55f * shadowK)
    )

    val dist = 5.5f
    fun project(x: Float, y: Float, z: Float): Offset {
        val rx = m[0] * x + m[1] * y + m[2] * z
        val ry = m[3] * x + m[4] * y + m[5] * z
        val rz = m[6] * x + m[7] * y + m[8] * z
        val k = dist / (dist - rz)
        return Offset(c.x + rx * extent * k, c.y + ry * extent * k)
    }
    val lampX = -0.35f; val lampY = -0.65f; val lampZ = 0.68f

    // paint the faces from back to front
    val order = faces.sortedBy { f -> m[6] * f.n[0] + m[7] * f.n[1] + m[8] * f.n[2] }
    for (f in order) {
        val nz = m[6] * f.n[0] + m[7] * f.n[1] + m[8] * f.n[2]
        if (nz <= 0.02f) continue
        val nx = m[0] * f.n[0] + m[1] * f.n[1] + m[2] * f.n[2]
        val ny = m[3] * f.n[0] + m[4] * f.n[1] + m[5] * f.n[2]
        val lit = (nx * lampX + ny * lampY + nz * lampZ).coerceIn(0f, 1f)
        val shade = 0.58f + 0.42f * lit
        val faceColor = Color(
            (body.red * shade).coerceIn(0f, 1f), (body.green * shade).coerceIn(0f, 1f), (body.blue * shade).coerceIn(0f, 1f), 1f
        )
        val inset = 0.8f
        val corners = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f).map { (su, sv) ->
            project(
                f.n[0] + inset * (su * f.u[0] + sv * f.v[0]),
                f.n[1] + inset * (su * f.u[1] + sv * f.v[1]),
                f.n[2] + inset * (su * f.u[2] + sv * f.v[2])
            )
        }
        val path = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            for (i in 1 until 4) lineTo(corners[i].x, corners[i].y)
            close()
        }
        val round = extent * 0.2f
        drawPath(path, Color(0x66000000), style = Stroke(round * 1.25f, join = StrokeJoin.Round))
        drawPath(path, faceColor, style = Stroke(round, join = StrokeJoin.Round))
        drawPath(path, faceColor)
        // pips on the face plane
        val bustFace = bust && f.value == 1 && f === order.last()
        for ((a, b) in pipCells(f.value)) {
            val pc = floatArrayOf(
                f.n[0] + 0.5f * (a * f.u[0] + b * f.v[0]),
                f.n[1] + 0.5f * (a * f.u[1] + b * f.v[1]),
                f.n[2] + 0.5f * (a * f.u[2] + b * f.v[2])
            )
            val r = if (f.value == 1) 0.27f else 0.16f
            val ring = Path()
            val steps = 14
            for (s in 0 until steps) {
                val ang = 2.0 * PI * s / steps
                val du = (cos(ang) * r).toFloat(); val dv = (sin(ang) * r).toFloat()
                val p = project(
                    pc[0] + du * f.u[0] + dv * f.v[0] + f.n[0] * 0.01f,
                    pc[1] + du * f.u[1] + dv * f.v[1] + f.n[1] * 0.01f,
                    pc[2] + du * f.u[2] + dv * f.v[2] + f.n[2] * 0.01f
                )
                if (s == 0) ring.moveTo(p.x, p.y) else ring.lineTo(p.x, p.y)
            }
            ring.close()
            val pipColor = if (bustFace) Color(0xFFD8281C) else if (f.value == 1) Color(0xFFC42B1F) else pip
            drawPath(ring, pipColor.copy(alpha = 0.55f + 0.45f * shade))
        }
    }
}

/** A die as its own composable: [spin] 0 settled, ->1 at the start of a throw; [hop] raises it off the table. */
@Composable
internal fun Die3DView(
    value: Int, spin: Float, axisSeed: Int, size: Dp, body: Color, pip: Color, description: String,
    hop: Float = 0f, bust: Boolean = false, modifier: Modifier = Modifier
) {
    Canvas(modifier.size(size).semantics { contentDescription = description }) {
        val extent = this.size.minDimension * 0.27f
        drawDie3D(
            Offset(this.size.width / 2f, this.size.height / 2f + extent * 0.2f), extent, value, spin, axisSeed, body, pip,
            lift = hop * extent * 1.8f, bust = bust
        )
    }
}
