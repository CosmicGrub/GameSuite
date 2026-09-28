package com.gamesuite.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The UNO table's seat placement is pure math ([computeTableLayout]), so it can be checked across
 * screen shapes and player counts no single device can reproduce -- in particular the up-to-ten-
 * player tables that no menu entry starts locally. Every shape here is a real table size measured on
 * (or derived from) a target device, or a deliberately awkward one.
 */
class UnoTableLayoutTest {

    private data class Shape(val name: String, val w: Float, val h: Float)

    private val shapes = listOf(
        Shape("Tab S9 FE portrait", 807f, 1004f),
        Shape("Fold 5 inner (stacked)", 674f, 510f),
        Shape("Fold 5 cover", 328f, 700f),
        Shape("phone portrait", 344f, 620f),
        Shape("small phone", 320f, 520f),
        Shape("half of an unfolded Fold (book posture)", 415f, 600f),
        Shape("short and wide", 780f, 300f),
        // The band between "phone" and "tablet" -- where the rows/arc switch (480dp) and the felt's own
        // aspect flips from tall to wide, and where nothing has been measured on a device.
        Shape("narrow tablet strip", 500f, 600f),
        Shape("mid-width tall", 540f, 650f),
        Shape("square-ish", 600f, 600f),
        Shape("Fold cover, landscape-ish", 631f, 520f),
        Shape("Fold inner, tall", 674f, 700f),
        Shape("tablet landscape", 900f, 400f),
        Shape("desktop window", 1200f, 800f),
        // Narrow AND short: too tight for rows, wide enough that the arc must dodge a big pile cluster.
        Shape("phone, short (keyboard up)", 340f, 440f),
        Shape("phone, squat", 344f, 480f),
        Shape("wide phone, short", 480f, 430f)
    )

    /** Card-size setting extremes (0.75 and 1.45 are the ends of the device-independent range). */
    private val userScales = listOf(0.75f, 1.03f, 1.45f)

    private class Plate(val l: Float, val t: Float, val r: Float, val b: Float) {
        val area get() = (r - l) * (b - t)
        fun overlapArea(o: Plate): Float {
            val w = minOf(r, o.r) - maxOf(l, o.l)
            val h = minOf(b, o.b) - maxOf(t, o.t)
            return if (w > 0f && h > 0f) w * h else 0f
        }
    }

    private fun plates(layout: TableLayout): List<Plate> {
        val hw = layout.seatW / 2f
        val hh = layout.seatH / 2f
        return layout.seats.map { Plate(it.cx - hw, it.cy - hh, it.cx + hw, it.cy + hh) }
    }

    /** Runs [check] over every shape x player count and fails once, listing every violation found. */
    private fun forEveryCase(check: (Shape, Int, TableLayout, MutableList<String>) -> Unit) {
        val problems = mutableListOf<String>()
        for (shape in shapes) for (n in 1..9) for (us in userScales) {
            check(shape.copy(name = "${shape.name} @${us}x"), n, computeTableLayout(shape.w, shape.h, n, us), problems)
        }
        assertTrue("${problems.size} violation(s):\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `there is one seat per opponent`() = forEveryCase { shape, n, layout, problems ->
        if (layout.seats.size != n) problems += "${shape.name} n=$n has ${layout.seats.size} seats"
    }

    @Test
    fun `every seat plate stays inside the table`() = forEveryCase { shape, n, layout, problems ->
        plates(layout).forEachIndexed { i, p ->
            if (!(p.l >= -1f && p.t >= -1f && p.r <= shape.w + 1f && p.b <= shape.h + 1f)) {
                problems += "${shape.name} n=$n seat $i plate (${p.l},${p.t})-(${p.r},${p.b}) leaves ${shape.w}x${shape.h}"
            }
        }
    }

    @Test
    fun `every seat plate sits on the felt, clear of the rim`() = forEveryCase { shape, n, layout, problems ->
        val rx = (layout.feltRight - layout.feltLeft) / 2f
        val ry = (layout.feltBottom - layout.feltTop) / 2f
        val cx = shape.w / 2f
        val cy = (layout.feltTop + layout.feltBottom) / 2f
        // 8dp inside the felt's own edge: the rim is ~12dp thick, and the placement itself works to 14dp.
        val fx = rx - 8f
        val fy = ry - 8f
        plates(layout).forEachIndexed { i, p ->
            for ((x, y) in listOf(p.l to p.t, p.r to p.t, p.l to p.b, p.r to p.b)) {
                val ex = (x - cx) / fx
                val ey = (y - cy) / fy
                if (ex * ex + ey * ey > 1f) problems += "${shape.name} n=$n seat $i corner ($x,$y) is off the felt (e=${ex * ex + ey * ey})"
            }
        }
    }

    @Test
    fun `seats do not pile on top of each other`() = forEveryCase { shape, n, layout, problems ->
        val ps = plates(layout)
        for (i in ps.indices) for (j in i + 1 until ps.size) {
            val frac = ps[i].overlapArea(ps[j]) / minOf(ps[i].area, ps[j].area)
            if (frac > 0.12f) problems += "${shape.name} n=$n seats $i and $j overlap by ${(frac * 100).toInt()}%"
        }
    }

    @Test
    fun `seats stay out of the pile cluster`() = forEveryCase { shape, n, layout, problems ->
        val cluster = Plate(
            layout.clusterCenterX - layout.clusterHalfW,
            layout.clusterTop,
            layout.clusterCenterX + layout.clusterHalfW,
            layout.clusterBottom
        )
        plates(layout).forEachIndexed { i, p ->
            val frac = p.overlapArea(cluster) / p.area
            if (frac > 0.10f) problems += "${shape.name} n=$n seat $i overlaps the piles by ${(frac * 100).toInt()}%"
        }
    }

    @Test
    fun `narrow tables use rows and wide ones use the arc`() {
        assertTrue(computeTableLayout(344f, 620f, 3, 1f).usedRows)
        assertTrue(!computeTableLayout(807f, 1004f, 3, 1f).usedRows)
    }
}
