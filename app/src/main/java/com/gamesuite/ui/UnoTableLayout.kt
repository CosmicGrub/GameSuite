package com.gamesuite.ui

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Design size of an opponent seat's plate at seat scale 1.0, in dp (avatar, name, count chip and fan). */
internal const val SEAT_W_UNIT = 108f
internal const val SEAT_H_UNIT = 132f

/** The compact plate (no fan -- the count chip already says how many cards) used on crowded narrow tables. */
internal const val SEAT_W_COMPACT = 84f
internal const val SEAT_H_COMPACT = 96f

/**
 * A compact plate carries the player's name, and text does not shrink below ~9sp the way the plate does,
 * so on a crowded phone table the plates got too narrow for their own names (Fold cover screen, nine
 * opponents: "Player 2Player 3Player 4..." running together). They never get narrower than this (dp).
 */
internal const val COMPACT_MIN_PLATE_W = 58f

private fun plateWidth(wUnit: Float, scl: Float): Float =
    if (wUnit == SEAT_W_COMPACT) maxOf(wUnit * scl, COMPACT_MIN_PLATE_W) else wUnit * scl

/** Center of one opponent seat's plate, in dp from the table's top-left. */
internal data class TableSeatPos(val cx: Float, val cy: Float)

/**
 * Everything about where things go on the UNO table, computed from nothing but the table's own
 * size (dp) -- no Compose, no device. Kept pure so the seat placement (arcs on wide tables, rows on
 * narrow ones, the pile cluster in the middle) can be unit tested across screen shapes and player
 * counts that no device in hand can reproduce (UNO allows ten players).
 */
internal data class TableLayout(
    val discardW: Float,
    val discardH: Float,
    val drawW: Float,
    val seatScale: Float,
    /** Plate size at [seatScale], in dp. */
    val seatW: Float,
    val seatH: Float,
    /** True when seats are drawn without their card fan (crowded narrow tables). */
    val compact: Boolean,
    val seats: List<TableSeatPos>,
    val feltLeft: Float,
    val feltTop: Float,
    val feltRight: Float,
    val feltBottom: Float,
    /** The pile cluster sits this fraction of the table's height below the felt's center. */
    val clusterYFrac: Float,
    /** True when opponents were packed into rows (narrow tables) rather than seated along an arc. */
    val usedRows: Boolean,
    val clusterHalfW: Float,
    val clusterTop: Float,
    val clusterBottom: Float,
    val clusterCenterX: Float
)

/**
 * @param userScale the Card size setting as a device-independent multiplier around 1.0
 *   (see cardPreferenceScale in UnoScreen.kt).
 */
internal fun computeTableLayout(tableW: Float, tableH: Float, seatCount: Int, userScale: Float): TableLayout {
    // Narrow tables (a phone, the Fold's cover screen) get proportionally bigger piles: with fixed
    // proportions the cards came out too small to read there. A crowded narrow table needs the
    // vertical room for its seat rows more than it needs big piles, so they ease off past four
    // opponents.
    val narrow = tableW < 480f
    val crowdedNarrow = narrow && seatCount > 4
    val pileFactor = when {
        crowdedNarrow -> 0.19f
        narrow -> 0.25f
        else -> 0.21f
    }
    // On a table that is wide enough for an arc, the pile cluster may take at most about half its width:
    // the biggest Card size setting otherwise leaves the opponents no room on either side of it.
    val discardW = (min(tableW, tableH * 0.8f) * pileFactor * userScale).coerceIn(52f, 150f)
        .let { if (narrow) it else min(it, tableW * 0.26f) }
    val discardH = discardW * (104f / 72f)
    val drawW = discardW * 0.8f
    // The far seat and the pile cluster stack vertically, so on a short table the seats scale down
    // until a seat, the piles (with their status line and buttons), and your own seat all fit top to
    // bottom.
    val verticalFit = (tableH - discardH - 190f) / 228f
    val baseSeatScale = minOf(tableW / 430f, tableH / 570f, verticalFit).coerceIn(0.62f, 1.35f)

    val feltLeft = 8f
    val feltRight = tableW - 8f
    val feltTop = 14f
    val feltBottom = tableH - 6f
    val rx = (feltRight - feltLeft) / 2f
    val ry = (feltBottom - feltTop) / 2f
    val cx = tableW / 2f
    val cy = (feltTop + feltBottom) / 2f

    // The pile cluster's footprint (piles, status pill, an opponent's turn tag, buttons), so seats
    // can be kept out of it.
    val clusterHalfW = (drawW + discardW * 0.14f + discardW) / 2f
    val clusterTotalH = discardH + 130f
    val clusterYFrac = if (narrow) 0.08f else 0.025f
    val clusterCenterY = cy + tableH * clusterYFrac
    val clusterTop = clusterCenterY - clusterTotalH / 2f
    val clusterBottom = clusterCenterY + clusterTotalH / 2f

    // 240 degrees of rim (side seats land near mid-height instead of high above the piles),
    // centered on the far side of the table; the near side is left for the local player's own seat.
    val seatSpanDeg = 240f
    // Capped so a small table doesn't strand its couple of seats low on the rim with a blank band
    // of felt above them.
    val seatSpacingDeg = if (seatCount > 0) min(seatSpanDeg / seatCount, 70f) else 0f

    // Narrow tables can't seat opponents around an arc without stacking them on each other or on the
    // piles, so there they are packed into rows across the top of the felt: each row is filled with
    // as many seats as the felt's width at that height allows, rows stack downward, and everything
    // must end above the pile cluster. Full seats first; if that doesn't fit at a readable size, the
    // compact plate (no fan) is tried.
    fun packSeatRows(scl: Float, wUnit: Float, hUnit: Float): List<TableSeatPos>? {
        val w = plateWidth(wUnit, scl)
        val h = hUnit * scl
        val gap = 4f
        val fxRow = rx - 12f
        val fyRow = ry - 12f
        val out = ArrayList<TableSeatPos>()
        var remaining = seatCount
        var rowTop = feltTop + 10f
        while (remaining > 0) {
            var placed = false
            for (k in min(remaining, 8) downTo 1) {
                val need = k * w + (k - 1) * gap
                var top = rowTop
                while (top + h <= clusterTop - 8f) {
                    val dy = (cy - top) / fyRow
                    val half = if (dy >= 1f) 0f else if (top < cy) fxRow * sqrt(1f - dy * dy) else fxRow
                    if (2f * half >= need) {
                        for (i in 0 until k) out += TableSeatPos(cx + (i - (k - 1) / 2f) * (w + gap), top + h / 2f)
                        rowTop = top + h + gap
                        remaining -= k
                        placed = true
                        break
                    }
                    top += 4f
                }
                if (placed) break
            }
            if (!placed) return null
        }
        return out
    }

    // Scales to try, largest first, always ending exactly on the floor (a plain repeated x0.94 can
    // step over it and never try the smallest size that would have fit).
    fun scaleCandidates(from: Float, floor: Float): List<Float> {
        val out = ArrayList<Float>()
        var v = from
        while (v > floor + 0.01f) { out += v; v *= 0.94f }
        out += floor
        return out
    }

    var rows: List<TableSeatPos>? = null
    var rowScale = baseSeatScale
    var compact = false
    if (narrow && seatCount > 0) {
        for (scl in scaleCandidates(baseSeatScale, 0.62f)) {
            rows = packSeatRows(scl, SEAT_W_UNIT, SEAT_H_UNIT)
            if (rows != null) { rowScale = scl; break }
        }
        if (rows == null) {
            for (scl in scaleCandidates(baseSeatScale, 0.5f)) {
                rows = packSeatRows(scl, SEAT_W_COMPACT, SEAT_H_COMPACT)
                if (rows != null) { rowScale = scl; compact = true; break }
            }
        }
    }
    // On a short table the top-center spot would sit on the piles, so opponents are split between the
    // left and right sides; with more than four of them a full plate no longer fits five-high, so the
    // compact plate (no fan) is used there too.
    val shortTable = tableH < 420f
    // A narrow table whose rows wouldn't fit (a big Card size setting makes the piles swallow the middle)
    // falls back to the arc, and a crowded one has no room there for full plates either.
    val arcCompact = seatCount > 4 && (shortTable || (narrow && rows == null))
    val arcWUnit = if (arcCompact) SEAT_W_COMPACT else SEAT_W_UNIT
    val arcHUnit = if (arcCompact) SEAT_H_COMPACT else SEAT_H_UNIT

    // Seat centers ride an ellipse inset far enough that the whole plate sits on the felt.
    fun seatRadii(scl: Float): Pair<Float, Float> =
        (rx - plateWidth(arcWUnit, scl) * 0.62f).coerceAtLeast(24f) to (ry - arcHUnit * scl * 0.62f).coerceAtLeast(24f)

    // Shrink the seats when many opponents would otherwise collide along the arc. Sized against the
    // plate's height, the larger dimension: along the sides of the oval neighbors stack vertically.
    val arcSeatScale = run {
        if (seatCount <= 1) return@run baseSeatScale
        val (a, b) = seatRadii(baseSeatScale)
        val perimeter = Math.PI.toFloat() * (3f * (a + b) - sqrt((3f * a + b) * (a + 3f * b)))
        val gap = perimeter * seatSpacingDeg / 360f
        val fit = (gap / (arcHUnit * baseSeatScale * 1.0f)).coerceAtMost(1f)
        (baseSeatScale * fit).coerceAtLeast(0.5f)
    }

    // Where each seat goes around the rim, in turn order (the next player sits on the local player's
    // left, play travels clockwise). On a short table opponents are split between the left and right
    // sides (see shortTable above), spread vertically.
    val angles: List<Float> = if (shortTable) {
        val left = (seatCount + 1) / 2
        val right = seatCount / 2
        fun spread(n: Int, from: Float, to: Float, single: Float): List<Float> =
            if (n == 1) listOf(single) else List(n) { from + (to - from) * it / (n - 1) }
        (if (left > 0) spread(left, 222f, 128f, 175f) else emptyList()) +
            (if (right > 0) spread(right, 52f, -42f, 5f) else emptyList())
    } else {
        List(seatCount) { 90f + seatSpacingDeg * ((seatCount - 1) / 2f - it) }
    }

    fun placeArc(scl: Float): List<TableSeatPos> {
        val (rxSeat, rySeat) = seatRadii(scl)
        val hw = plateWidth(arcWUnit, scl) / 2f
        val hh = arcHUnit * scl / 2f
        // Place each seat on its rim ellipse, then, if any corner of its plate would cross the
        // felt's edge (a rectangle in the curved upper corners of an oval), slide it toward the center
        // until the whole plate sits on the felt.
        val fx = rx - 14f
        val fy = ry - 14f
        fun plateOutsideFelt(px: Float, py: Float): Boolean =
            listOf(-hw to -hh, hw to -hh, -hw to hh, hw to hh).any { (ox, oy) ->
                val ex = (px + ox - cx) / fx
                val ey = (py + oy - cy) / fy
                ex * ex + ey * ey > 1f
            }
        return List(seatCount) { k ->
            val angleRad = Math.toRadians(angles[k].toDouble())
            val dirX = cos(angleRad).toFloat()
            val dirY = sin(angleRad).toFloat()
            var pull = 1f
            // `lift` slides a seat up (or, for a seat below the piles, down) until it clears the pile
            // cluster; done alongside the felt-edge fit so the fit sees the final spot.
            var lift = 0f
            repeat(60) {
                val px = cx + rxSeat * dirX * pull
                val py = cy - rySeat * dirY * pull - lift
                val outside = plateOutsideFelt(px, py)
                val hitsCluster = abs(px - cx) < clusterHalfW + hw + 10f &&
                    py + hh > clusterTop - 6f && py - hh < clusterBottom + 6f
                val aboveCluster = py <= clusterCenterY
                // Staying on the felt outranks staying clear of the piles: a seat is only slid up (or
                // down) while that doesn't push its plate over the rim. On a short table the two can't
                // both hold, and letting the lift win used to shove the top seat out past the felt
                // entirely; a few dp of overlap with the pile area is the lesser evil.
                when {
                    hitsCluster && aboveCluster && py - hh > 6f && !plateOutsideFelt(px, py - 5f) -> lift += 5f
                    hitsCluster && !aboveCluster && py + hh < tableH - 6f && !plateOutsideFelt(px, py + 5f) -> lift -= 5f
                    outside -> pull *= 0.97f
                }
            }
            TableSeatPos(cx + rxSeat * dirX * pull, cy - rySeat * dirY * pull - lift)
        }
    }

    /** Largest overlap between any two plates, as a fraction of one plate's area. */
    fun worstOverlap(seats: List<TableSeatPos>, w: Float, h: Float): Float {
        var worst = 0f
        for (i in seats.indices) for (j in i + 1 until seats.size) {
            val ow = w - abs(seats[i].cx - seats[j].cx)
            val oh = h - abs(seats[i].cy - seats[j].cy)
            if (ow > 0f && oh > 0f) worst = maxOf(worst, ow * oh / (w * h))
        }
        return worst
    }

    /** Largest share of any one plate that sits inside the pile cluster's footprint. */
    fun worstClusterOverlap(seats: List<TableSeatPos>, w: Float, h: Float): Float {
        var worst = 0f
        for (p in seats) {
            val ow = minOf(p.cx + w / 2f, cx + clusterHalfW) - maxOf(p.cx - w / 2f, cx - clusterHalfW)
            val oh = minOf(p.cy + h / 2f, clusterBottom) - maxOf(p.cy - h / 2f, clusterTop)
            if (ow > 0f && oh > 0f) worst = maxOf(worst, ow * oh / (w * h))
        }
        return worst
    }

    // Arc placement is tried at successively smaller seat sizes until no two plates crowd each other
    // or the piles (smaller plates also sit further out on the rim, clearing the middle).
    var arcScale = arcSeatScale
    var arcSeats: List<TableSeatPos> = emptyList()
    if (rows == null) {
        val candidates = scaleCandidates(arcSeatScale, 0.5f)
        for ((i, scl) in candidates.withIndex()) {
            arcSeats = placeArc(scl)
            arcScale = scl
            val pw = plateWidth(arcWUnit, scl)
            val ph = arcHUnit * scl
            if ((worstOverlap(arcSeats, pw, ph) <= 0.05f && worstClusterOverlap(arcSeats, pw, ph) <= 0.06f) || i == candidates.lastIndex) break
        }
    }
    // A narrow table whose rows didn't fit is left with an arc around a pile cluster that (at a big Card
    // size setting) is simply too wide for it, and no plate size fixes that -- so the piles give way
    // instead: retry with them a little smaller until the seats clear them or they hit the floor.
    if (rows == null && userScale > 0.75f &&
        worstClusterOverlap(arcSeats, plateWidth(arcWUnit, arcScale), arcHUnit * arcScale) > 0.10f
    ) {
        return computeTableLayout(tableW, tableH, seatCount, maxOf(0.75f, userScale * 0.92f))
    }
    val seatScale = if (rows != null) rowScale else arcScale
    val finalCompact = if (rows != null) compact else arcCompact
    val wUnit = if (finalCompact) SEAT_W_COMPACT else SEAT_W_UNIT
    val hUnit = if (finalCompact) SEAT_H_COMPACT else SEAT_H_UNIT
    val seats: List<TableSeatPos> = rows ?: arcSeats

    return TableLayout(
        discardW = discardW,
        discardH = discardH,
        drawW = drawW,
        seatScale = seatScale,
        seatW = plateWidth(wUnit, seatScale),
        seatH = hUnit * seatScale,
        compact = finalCompact,
        seats = seats,
        feltLeft = feltLeft,
        feltTop = feltTop,
        feltRight = feltRight,
        feltBottom = feltBottom,
        clusterYFrac = clusterYFrac,
        usedRows = rows != null,
        clusterHalfW = clusterHalfW,
        clusterTop = clusterTop,
        clusterBottom = clusterBottom,
        clusterCenterX = cx
    )
}
