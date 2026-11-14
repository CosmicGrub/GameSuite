package com.gamesuite.games.minigolf

import kotlin.math.hypot

data class GolfPt(val x: Double, val y: Double)

/** An axis-aligned zone: sand, water or a ramp. */
data class GolfRect(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    fun contains(px: Double, py: Double) = px >= x0 && px <= x1 && py >= y0 && py <= y1
}

data class GolfBumper(val x: Double, val y: Double, val r: Double)

/** A ramp or conveyor: while the ball is inside [area] it is pushed by ([ax], [ay]) units/s per second. */
data class GolfRamp(val area: GolfRect, val ax: Double, val ay: Double)

/**
 * One hole. All coordinates are in a 100 x 60 field (x right, y down). [outline] is the closed playing
 * area (the ball stays inside it), each of [obstacles] is a solid island inside it, [bumpers] kick the ball
 * away, [sand] drags it, [water] swallows it (a penalty stroke, back to where it was hit from) and [ramps]
 * push it along. The ball is 2.4 units across and the cup 4.4.
 */
class GolfHole(
    val number: Int,
    val par: Int,
    val outline: List<GolfPt>,
    val obstacles: List<List<GolfPt>> = emptyList(),
    val bumpers: List<GolfBumper> = emptyList(),
    val sand: List<GolfRect> = emptyList(),
    val water: List<GolfRect> = emptyList(),
    val ramps: List<GolfRamp> = emptyList(),
    val tee: GolfPt,
    val cup: GolfPt
) {
    /** Every wall as a segment (x0, y0, x1, y1). */
    val walls: List<DoubleArray> = buildList {
        fun ring(p: List<GolfPt>) {
            for (i in p.indices) {
                val a = p[i]; val b = p[(i + 1) % p.size]
                add(doubleArrayOf(a.x, a.y, b.x, b.y))
            }
        }
        ring(outline); obstacles.forEach { ring(it) }
    }

    fun inWater(x: Double, y: Double) = water.any { it.contains(x, y) }
    fun inSand(x: Double, y: Double) = sand.any { it.contains(x, y) }

    /** Whether (x, y) is inside the playing area and not inside an obstacle. */
    fun isPlayable(x: Double, y: Double): Boolean {
        if (!insidePoly(outline, x, y)) return false
        return obstacles.none { insidePoly(it, x, y) }
    }

    companion object {
        fun insidePoly(poly: List<GolfPt>, x: Double, y: Double): Boolean {
            var inside = false
            var j = poly.size - 1
            for (i in poly.indices) {
                val a = poly[i]; val b = poly[j]
                if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
                j = i
            }
            return inside
        }
    }
}

private fun rect(x0: Double, y0: Double, x1: Double, y1: Double) =
    listOf(GolfPt(x0, y0), GolfPt(x1, y0), GolfPt(x1, y1), GolfPt(x0, y1))

/** The nine holes. Original layouts, drawn and tuned for this game. */
object GolfCourse {
    const val FIELD_W = 100.0
    const val FIELD_H = 60.0

    val holes: List<GolfHole> = listOf(
        // 1. Straightaway: a plain fairway to learn the stroke.
        GolfHole(
            number = 1, par = 2,
            outline = rect(8.0, 20.0, 92.0, 40.0),
            tee = GolfPt(16.0, 30.0), cup = GolfPt(82.0, 30.0)
        ),
        // 2. Dogleg: along the top, then down the right side.
        GolfHole(
            number = 2, par = 3,
            outline = listOf(GolfPt(8.0, 10.0), GolfPt(92.0, 10.0), GolfPt(92.0, 52.0), GolfPt(66.0, 52.0), GolfPt(66.0, 26.0), GolfPt(8.0, 26.0)),
            tee = GolfPt(16.0, 18.0), cup = GolfPt(79.0, 44.0)
        ),
        // 3. Sand Trap: a bunker strip right across the fairway.
        GolfHole(
            number = 3, par = 2,
            outline = rect(8.0, 14.0, 92.0, 46.0),
            sand = listOf(GolfRect(42.0, 14.0, 54.0, 46.0)),
            tee = GolfPt(16.0, 30.0), cup = GolfPt(82.0, 30.0)
        ),
        // 4. Pond: two lakes leave a narrow bridge in the middle.
        GolfHole(
            number = 4, par = 3,
            outline = rect(8.0, 10.0, 92.0, 50.0),
            water = listOf(GolfRect(38.0, 10.0, 60.0, 24.0), GolfRect(38.0, 36.0, 60.0, 50.0)),
            tee = GolfPt(16.0, 30.0), cup = GolfPt(82.0, 30.0)
        ),
        // 5. Pinball: round bumpers between the tee and the cup.
        GolfHole(
            number = 5, par = 3,
            outline = rect(8.0, 8.0, 92.0, 52.0),
            bumpers = listOf(GolfBumper(38.0, 20.0, 4.5), GolfBumper(38.0, 40.0, 4.5), GolfBumper(56.0, 30.0, 5.0), GolfBumper(70.0, 18.0, 4.0), GolfBumper(70.0, 42.0, 4.0)),
            tee = GolfPt(16.0, 30.0), cup = GolfPt(84.0, 30.0)
        ),
        // 6. Zigzag: two walls force an S-shaped route.
        GolfHole(
            number = 6, par = 4,
            outline = rect(8.0, 8.0, 92.0, 52.0),
            obstacles = listOf(rect(34.0, 8.0, 40.0, 38.0), rect(60.0, 22.0, 66.0, 52.0)),
            tee = GolfPt(16.0, 30.0), cup = GolfPt(82.0, 30.0)
        ),
        // 7. Conveyors: two belts push the ball up, then down.
        GolfHole(
            number = 7, par = 3,
            outline = rect(8.0, 12.0, 92.0, 48.0),
            ramps = listOf(GolfRamp(GolfRect(30.0, 12.0, 46.0, 48.0), 0.0, -70.0), GolfRamp(GolfRect(56.0, 12.0, 72.0, 48.0), 0.0, 70.0)),
            tee = GolfPt(16.0, 38.0), cup = GolfPt(84.0, 38.0)
        ),
        // 8. Moat: the cup sits on an island with one narrow bridge.
        GolfHole(
            number = 8, par = 3,
            outline = rect(8.0, 8.0, 92.0, 52.0),
            water = listOf(
                GolfRect(60.0, 14.0, 92.0, 22.0), GolfRect(60.0, 38.0, 92.0, 46.0),
                GolfRect(60.0, 22.0, 68.0, 27.0), GolfRect(60.0, 33.0, 68.0, 38.0)
            ),
            tee = GolfPt(16.0, 30.0), cup = GolfPt(82.0, 30.0)
        ),
        // 9. Grand Finale: a bent fairway with sand, a bumper and a belt.
        GolfHole(
            number = 9, par = 4,
            outline = listOf(
                GolfPt(8.0, 8.0), GolfPt(70.0, 8.0), GolfPt(70.0, 30.0), GolfPt(92.0, 30.0), GolfPt(92.0, 54.0),
                GolfPt(30.0, 54.0), GolfPt(30.0, 34.0), GolfPt(8.0, 34.0)
            ),
            bumpers = listOf(GolfBumper(48.0, 20.0, 4.5)),
            sand = listOf(GolfRect(8.0, 26.0, 20.0, 34.0)),
            ramps = listOf(GolfRamp(GolfRect(60.0, 38.0, 80.0, 54.0), 55.0, 0.0)),
            water = listOf(GolfRect(34.0, 42.0, 46.0, 54.0)),
            tee = GolfPt(14.0, 14.0), cup = GolfPt(82.0, 46.0)
        )
    )

    fun hole(index: Int): GolfHole = holes[index]

    /** Distance between two points (used for tee/cup sanity checks). */
    fun dist(a: GolfPt, b: GolfPt) = hypot(a.x - b.x, a.y - b.y)
}
