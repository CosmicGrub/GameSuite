package com.gamesuite.style

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A projected point: screen [x], [y] and the local [scale] (1 = same size as the flat board; <1 = farther away). */
data class Projected(val x: Float, val y: Float, val scale: Float)

/**
 * Tilts a flat board plane (planeW x planeH, y down) back by [tiltDegrees] and looks at it with a perspective camera,
 * fitted into a [boxW] x [boxH] box. Pure maths. [project] maps a plane point to the screen, [unproject] is its exact
 * inverse (used to hit-test pointers on the tilted board). With tilt 0 the mapping is the identity when the box equals the plane.
 *
 * Top of the board (small y) is farther from the camera: depth = d - y * sin(tilt), screen = (x, y * cos(tilt)) * d / depth.
 */
class BoardCamera(
    val planeW: Float,
    val planeH: Float,
    val tiltDegrees: Float,
    val boxW: Float,
    val boxH: Float,
    perspective: Float = 2.2f
) {
    private val c = cos(Math.toRadians(tiltDegrees.toDouble())).toFloat()
    private val s = sin(Math.toRadians(tiltDegrees.toDouble())).toFloat()
    private val d = perspective * max(planeW, planeH)
    private val fit: Float
    private val tx: Float
    private val ty: Float

    init {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (cx in floatArrayOf(0f, planeW)) for (cy in floatArrayOf(0f, planeH)) {
            val x = cx - planeW / 2f; val y = cy - planeH / 2f
            val k = d / (d - y * s)
            val rx = x * k; val ry = y * c * k
            minX = min(minX, rx); maxX = max(maxX, rx); minY = min(minY, ry); maxY = max(maxY, ry)
        }
        fit = min(boxW / (maxX - minX), boxH / (maxY - minY))
        tx = boxW / 2f - fit * (minX + maxX) / 2f
        ty = boxH / 2f - fit * (minY + maxY) / 2f
    }

    val isIdentity: Boolean get() = abs(tiltDegrees) < 1e-6f && abs(fit - 1f) < 1e-4f

    fun project(u: Float, v: Float): Projected {
        val x = u - planeW / 2f; val y = v - planeH / 2f
        val k = d / (d - y * s)
        return Projected(fit * x * k + tx, fit * y * c * k + ty, fit * k)
    }

    /** The plane point under screen point ([sx], [sy]); the inverse of [project] (exact up to float rounding). */
    fun unproject(sx: Float, sy: Float): Pair<Float, Float> {
        val rx = (sx - tx) / fit
        val ry = (sy - ty) / fit
        val y = ry * d / (c * d + ry * s)
        val k = d / (d - y * s)
        return (rx / k + planeW / 2f) to (y + planeH / 2f)
    }
}

/** Classic 2:1-style isometric projection of a grid (for Tower Defence and other diorama boards). */
class IsoProjection(val tileW: Float, val tileH: Float, val originX: Float, val originY: Float, val heightUnit: Float = tileH) {
    fun project(gx: Float, gy: Float, gz: Float = 0f): Projected =
        Projected(originX + (gx - gy) * tileW / 2f, originY + (gx + gy) * tileH / 2f - gz * heightUnit, 1f)

    /** The grid position on the plane at height [gz] under a screen point (the picking inverse). */
    fun unproject(sx: Float, sy: Float, gz: Float = 0f): Pair<Float, Float> {
        val a = (sx - originX) / (tileW / 2f)
        val b = (sy - originY + gz * heightUnit) / (tileH / 2f)
        return ((a + b) / 2f) to ((b - a) / 2f)
    }

    /** Painter's-algorithm key: draw smaller first. */
    fun depthKey(gx: Float, gy: Float, gz: Float = 0f): Float = gx + gy + gz * 0.001f
}
