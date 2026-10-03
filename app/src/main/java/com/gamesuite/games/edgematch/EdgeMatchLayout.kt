package com.gamesuite.games.edgematch

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure layout and hit-testing math for Edge Match's boards. No Compose, no Android, no Dp: every
 * value is a plain Float in whatever unit the caller picks (px or dp, as long as it is the same
 * throughout one call), so the shape matrix below is covered by a plain JVM test instead of a device.
 *
 * Why this exists: the HEX board used to lay each hexagon out as a clickable 2R x 2R Box on a
 * 1.5R x sqrt(3)R pitch, so neighbouring bounding squares overlapped and the later-composed sibling
 * received the tap, which rotated the WRONG tile for roughly the bottom sixth of every interior
 * hex and a thin strip on its right. [hexIndexAt] replaces that with one pixel -> axial rounding
 * against the whole board, so a tap always resolves to exactly the hexagon drawn under the finger.
 *
 * Hex layout (pointy-top, rhombus grid, `index = r * size + q`): tile (q, r) is centred at
 * `x = R*sqrt(3)*(q + r/2) + R*sqrt(3)/2`, `y = 1.5*R*r + R`, with R the centre-to-vertex radius.
 */
object EdgeMatchLayout {
    /** The width / radius ratio of a pointy-top hexagon (flat-to-flat width is `SQRT3 * R`). */
    val SQRT3: Float = sqrt(3f)

    /** Bounding width of a [size] x [size] hex rhombus, in units of the hex radius. */
    fun hexWidthFactor(size: Int): Float = SQRT3 * (1.5f * (size - 1) + 1f)

    /** Bounding height of a [size] x [size] hex rhombus, in units of the hex radius. */
    fun hexHeightFactor(size: Int): Float = 1.5f * (size - 1) + 2f

    /** X of hex (q, r)'s centre, measured from the board's own left edge. */
    fun hexCenterX(q: Int, r: Int, radius: Float): Float = radius * SQRT3 * (q + r / 2f) + radius * SQRT3 / 2f

    /** Y of hex row r's centre, measured from the board's own top edge. */
    fun hexCenterY(r: Int, radius: Float): Float = radius * 1.5f * r + radius

    /**
     * The flat tile index (`r * size + q`) of the hexagon under ([x], [y]) on a board whose
     * top-left is (0, 0), or null when the point falls on no tile (outside the rhombus, or the
     * empty triangles at its slanted ends). Cube-rounds the fractional axial coordinate, so every
     * point of the plane belongs to exactly one hexagon: the same cell the player sees.
     */
    fun hexIndexAt(x: Float, y: Float, radius: Float, size: Int): Int? {
        if (radius <= 0f || size <= 0) return null
        val rFrac = (y - radius) / (1.5f * radius)
        val qFrac = (x - radius * SQRT3 / 2f) / (radius * SQRT3) - rFrac / 2f
        // Cube coordinates: (cx, cy, cz) = (q, -q - r, r).
        val cx = qFrac
        val cz = rFrac
        val cy = -cx - cz
        val rx = cx.roundToInt()
        val ry = cy.roundToInt()
        val rz = cz.roundToInt()
        val dx = abs(rx - cx)
        val dy = abs(ry - cy)
        val dz = abs(rz - cz)
        // Re-derive whichever coordinate was rounded furthest so the three still sum to zero.
        val q: Int
        val r: Int
        when {
            dx > dy && dx > dz -> { q = -ry - rz; r = rz }
            dy > dz -> { q = rx; r = rz }
            else -> { q = rx; r = -rx - ry }
        }
        return if (q in 0 until size && r in 0 until size) r * size + q else null
    }

    /**
     * How many of tile [index]'s sides touch another tile (the sides that can match or mismatch;
     * border-facing sides never count). For a SQUARE board that is 2 at a corner, 3 along an edge
     * and 4 inside; for a HEX board 2 to 6 by the same axial neighbour offsets the engine uses.
     */
    fun interiorNeighborCount(index: Int, size: Int, geometry: EdgeMatchGeometry): Int = when (geometry) {
        EdgeMatchGeometry.SQUARE -> {
            val row = index / size
            val col = index % size
            (if (row > 0) 1 else 0) + (if (row < size - 1) 1 else 0) +
                (if (col > 0) 1 else 0) + (if (col < size - 1) 1 else 0)
        }
        EdgeMatchGeometry.HEX -> {
            val r = index / size
            val q = index % size
            var count = 0
            for (d in 0 until 6) {
                val nq = q + EdgeMatchGame.HEX_DELTA_Q[d]
                val nr = r + EdgeMatchGame.HEX_DELTA_R[d]
                if (nq in 0 until size && nr in 0 until size) count++
            }
            count
        }
    }
}
