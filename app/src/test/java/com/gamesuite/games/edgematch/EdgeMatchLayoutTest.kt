package com.gamesuite.games.edgematch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pins [EdgeMatchLayout]'s hex hit-testing. The bug it replaces: each hexagon used to be a
 * clickable 2R x 2R square on a 1.5R x sqrt(3)R pitch, so neighbouring squares overlapped and the
 * later-composed sibling got the tap, rotating the WRONG tile for about the bottom sixth of every
 * interior hex and a thin strip on its right.
 */
class EdgeMatchLayoutTest {

    private val radius = 20f

    private fun indexOf(q: Int, r: Int, size: Int) = r * size + q

    @Test
    fun `every hex centre resolves to its own tile, for every board size`() {
        for (size in EdgeMatchGame.MIN_SIZE..EdgeMatchGame.MAX_SIZE) {
            for (r in 0 until size) for (q in 0 until size) {
                val x = EdgeMatchLayout.hexCenterX(q, r, radius)
                val y = EdgeMatchLayout.hexCenterY(r, radius)
                assertEquals("size=$size q=$q r=$r", indexOf(q, r, size), EdgeMatchLayout.hexIndexAt(x, y, radius, size))
            }
        }
    }

    @Test
    fun `any point inside a hexagon's inscribed circle resolves to that hexagon`() {
        val size = 6
        val inscribed = 0.8f * radius // the apothem is 0.866R, so 0.8R is safely inside the hexagon
        for (r in 0 until size) for (q in 0 until size) {
            val cx = EdgeMatchLayout.hexCenterX(q, r, radius)
            val cy = EdgeMatchLayout.hexCenterY(r, radius)
            for (degrees in 0 until 360 step 15) {
                val a = Math.toRadians(degrees.toDouble())
                val x = cx + inscribed * cos(a).toFloat()
                val y = cy + inscribed * sin(a).toFloat()
                assertEquals(
                    "q=$q r=$r angle=$degrees",
                    indexOf(q, r, size),
                    EdgeMatchLayout.hexIndexAt(x, y, radius, size)
                )
            }
        }
    }

    /** The two exact regions the old overlapping-Box layout got wrong, on an interior tile. */
    @Test
    fun `the bottom vertex region and the east strip belong to the tile under the finger, not a neighbour`() {
        val size = 5
        val q = 2
        val r = 2
        val cx = EdgeMatchLayout.hexCenterX(q, r, radius)
        val cy = EdgeMatchLayout.hexCenterY(r, radius)
        val own = indexOf(q, r, size)

        // Near the bottom vertex (R straight down from the centre): inside this hexagon.
        assertEquals(own, EdgeMatchLayout.hexIndexAt(cx, cy + 0.95f * radius, radius, size))
        // Just inside the east flat side (apothem = 0.866R): still this hexagon.
        assertEquals(own, EdgeMatchLayout.hexIndexAt(cx + 0.85f * radius, cy, radius, size))
        // Just past it: the east neighbour (q + 1, same row).
        assertEquals(indexOf(q + 1, r, size), EdgeMatchLayout.hexIndexAt(cx + 0.89f * radius, cy, radius, size))
        // A little past the bottom vertex (and 1 right of the centre line) it is the south-east
        // neighbour, axial (q, r + 1), that owns the point, never `own`.
        assertEquals(indexOf(q, r + 1, size), EdgeMatchLayout.hexIndexAt(cx + 1f, cy + 1.05f * radius, radius, size))
    }

    @Test
    fun `points outside the rhombus resolve to no tile`() {
        val size = 5
        assertNull(EdgeMatchLayout.hexIndexAt(-1f, -1f, radius, size))
        assertNull(EdgeMatchLayout.hexIndexAt(-50f, 40f, radius, size))
        val w = EdgeMatchLayout.hexWidthFactor(size) * radius
        val h = EdgeMatchLayout.hexHeightFactor(size) * radius
        assertNull(EdgeMatchLayout.hexIndexAt(w + 30f, h / 2f, radius, size))
        assertNull(EdgeMatchLayout.hexIndexAt(w / 2f, h + 30f, radius, size))
        // The empty triangles at the rhombus's slanted ends (top-right and bottom-left of the box).
        assertNull(EdgeMatchLayout.hexIndexAt(w - 1f, 1f, radius, size))
        assertNull(EdgeMatchLayout.hexIndexAt(1f, h - 1f, radius, size))
    }

    @Test
    fun `degenerate input never crashes or returns a tile`() {
        assertNull(EdgeMatchLayout.hexIndexAt(10f, 10f, 0f, 5))
        assertNull(EdgeMatchLayout.hexIndexAt(10f, 10f, -5f, 5))
        assertNull(EdgeMatchLayout.hexIndexAt(10f, 10f, radius, 0))
    }

    /**
     * Brute force: sampled points across (and around) the board must resolve to the tile whose
     * centre is nearest, or to null when the nearest lattice site is off the board. This is the
     * property that makes the tap land on exactly the hexagon the player sees.
     */
    @Test
    fun `hit testing always picks the nearest hex centre`() {
        for (size in listOf(2, 3, 5, 8)) {
            val w = EdgeMatchLayout.hexWidthFactor(size) * radius
            val h = EdgeMatchLayout.hexHeightFactor(size) * radius
            var x = -radius
            while (x <= w + radius) {
                var y = -radius
                while (y <= h + radius) {
                    var best = Float.MAX_VALUE
                    var second = Float.MAX_VALUE
                    var bestQ = 0
                    var bestR = 0
                    for (r in -2..size + 1) for (q in -2..size + 1) {
                        val d = hypot(
                            (x - EdgeMatchLayout.hexCenterX(q, r, radius)).toDouble(),
                            (y - EdgeMatchLayout.hexCenterY(r, radius)).toDouble()
                        ).toFloat()
                        if (d < best) {
                            second = best
                            best = d
                            bestQ = q
                            bestR = r
                        } else if (d < second) {
                            second = d
                        }
                    }
                    // Skip points that sit (almost) exactly on a boundary: float rounding may legitimately go either way there.
                    if (second - best > 0.01f) {
                        val expected = if (bestQ in 0 until size && bestR in 0 until size) indexOf(bestQ, bestR, size) else null
                        assertEquals("size=$size x=$x y=$y", expected, EdgeMatchLayout.hexIndexAt(x, y, radius, size))
                    }
                    y += 3.7f
                }
                x += 3.7f
            }
        }
    }

    @Test
    fun `square interior neighbour counts are 2 at corners, 3 along edges, 4 inside, and sum to twice the seam count`() {
        val size = 4
        val counts = (0 until size * size).map { EdgeMatchLayout.interiorNeighborCount(it, size, EdgeMatchGeometry.SQUARE) }
        assertEquals(2, counts[0])
        assertEquals(2, counts[size - 1])
        assertEquals(2, counts[size * (size - 1)])
        assertEquals(2, counts[size * size - 1])
        assertEquals(3, counts[1])
        assertEquals(3, counts[size])
        assertEquals(4, counts[size + 1])
        for (n in EdgeMatchGame.MIN_SIZE..EdgeMatchGame.MAX_SIZE) {
            val sum = (0 until n * n).sumOf { EdgeMatchLayout.interiorNeighborCount(it, n, EdgeMatchGeometry.SQUARE) }
            assertEquals("n=$n: every seam is counted from both sides", 2 * (2 * n * (n - 1)), sum)
        }
    }

    @Test
    fun `hex interior neighbour counts match the hand-built 2x2 board and sum to twice the seam count`() {
        // Same 2x2 rhombus the engine test builds by hand: corners touch 2 tiles, the other two touch 3.
        val counts2 = (0 until 4).map { EdgeMatchLayout.interiorNeighborCount(it, 2, EdgeMatchGeometry.HEX) }
        assertEquals(listOf(2, 3, 3, 2), counts2)
        for (n in EdgeMatchGame.MIN_SIZE..EdgeMatchGame.MAX_SIZE) {
            val sum = (0 until n * n).sumOf { EdgeMatchLayout.interiorNeighborCount(it, n, EdgeMatchGeometry.HEX) }
            assertEquals("n=$n: a rhombus has (n-1)(3n-1) seams, each counted from both sides", 2 * (n - 1) * (3 * n - 1), sum)
        }
        // An interior hex touches all six neighbours.
        assertEquals(6, EdgeMatchLayout.interiorNeighborCount(2 * 5 + 2, 5, EdgeMatchGeometry.HEX))
    }

    @Test
    fun `board extents are consistent with the tile centres`() {
        for (size in EdgeMatchGame.MIN_SIZE..EdgeMatchGame.MAX_SIZE) {
            val width = EdgeMatchLayout.hexWidthFactor(size) * radius
            val height = EdgeMatchLayout.hexHeightFactor(size) * radius
            // The right-most centre is the last tile of the last row; its right-most vertex-to-vertex
            // extent (half a flat-to-flat width past the centre) is exactly the board width.
            val lastX = EdgeMatchLayout.hexCenterX(size - 1, size - 1, radius)
            assertEquals("size=$size width", width, lastX + radius * EdgeMatchLayout.SQRT3 / 2f, 0.01f)
            val lastY = EdgeMatchLayout.hexCenterY(size - 1, radius)
            assertEquals("size=$size height", height, lastY + radius, 0.01f)
            assertNotNull(EdgeMatchLayout.hexIndexAt(lastX, lastY, radius, size))
        }
    }
}
