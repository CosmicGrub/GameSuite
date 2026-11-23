package com.gamesuite.games.towerdefence

import androidx.compose.ui.geometry.Offset
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Navigation grid, flow field and build validation of the open-field maps
 * (docs/design/tower-defence/MAP-MODEL.md sections 3 to 5). Pure Kotlin (only [Offset] from
 * Compose geometry, which is a plain value class), JVM tested.
 *
 * The field is 24 x 14 tiles; the nav grid has 2 x 2 cells per tile, so 48 x 28 = 1344 cells.
 * A tower occupies 2 x 2 cells (one tile). Positions in the open-field sim are in TILES.
 */
object TdGrid {
    const val COLS = 48
    const val ROWS = 28
    const val CELLS = COLS * ROWS
    const val CELLS_PER_TILE = 2
    const val TILES_X = 24f
    const val TILES_Y = 14f

    fun idx(x: Int, y: Int): Int = y * COLS + x
    fun cx(i: Int): Int = i % COLS
    fun cy(i: Int): Int = i / COLS
    fun inBounds(x: Int, y: Int): Boolean = x in 0 until COLS && y in 0 until ROWS

    /** Cell containing the tile-space point, clamped onto the grid. */
    fun cellOf(tileX: Float, tileY: Float): Int {
        val x = floor(tileX * CELLS_PER_TILE).toInt().coerceIn(0, COLS - 1)
        val y = floor(tileY * CELLS_PER_TILE).toInt().coerceIn(0, ROWS - 1)
        return idx(x, y)
    }

    /** Centre of a cell, in tiles. */
    fun centre(i: Int): Offset = Offset((cx(i) + 0.5f) / CELLS_PER_TILE, (cy(i) + 0.5f) / CELLS_PER_TILE)

    /** The four cells of a tower anchored at ([ax], [ay]); null when any is off the grid. */
    fun footprint(ax: Int, ay: Int): IntArray? {
        if (!inBounds(ax, ay) || !inBounds(ax + 1, ay + 1)) return null
        return intArrayOf(idx(ax, ay), idx(ax + 1, ay), idx(ax, ay + 1), idx(ax + 1, ay + 1))
    }

    /** Centre of a tower anchored at ([ax], [ay]), in tiles. */
    fun towerCentre(ax: Int, ay: Int): Offset = Offset((ax + 1) / 2f, (ay + 1) / 2f)
}

/**
 * One authored open map. [cost] is per cell (road 1.0, open ground 1.25, rough 3.0, blocked
 * [Float.POSITIVE_INFINITY]); [buildable] says where a tower may stand. [spawns] are west-edge cells,
 * [gate] the castle's front cells (the flow field's goal). [routeCap] is the anti-maze cap relative
 * to the open-field route (2.5 by default, [Float.POSITIVE_INFINITY] switches it off).
 */
class TdTerrain(
    val id: String,
    val cost: FloatArray,
    val buildable: BooleanArray,
    val spawns: List<Int>,
    val gate: List<Int>,
    /** Castle bounds in cells: x0, y0, x1, y1 inclusive. */
    val castle: IntArray,
    val routeCap: Float = 2.5f,
    /** Visual only: which blocked cells are water (the rest of the blocked cells are rock). */
    val water: BooleanArray = BooleanArray(TdGrid.CELLS)
) {
    init {
        require(cost.size == TdGrid.CELLS && buildable.size == TdGrid.CELLS)
        require(spawns.isNotEmpty() && gate.isNotEmpty())
    }

    val passable: BooleanArray = BooleanArray(TdGrid.CELLS) { cost[it].isFinite() }

    /** Where the castle's front door is, in tiles (flyers aim at it). */
    val gateCentre: Offset = run {
        var sx = 0f
        var sy = 0f
        for (g in gate) { val c = TdGrid.centre(g); sx += c.x; sy += c.y }
        Offset(sx / gate.size, sy / gate.size)
    }

    private val gateSet: BooleanArray = BooleanArray(TdGrid.CELLS).also { for (g in gate) it[g] = true }
    fun isGate(i: Int): Boolean = gateSet[i]

    /** The open-field flow (no towers); route baselines for the cap rule come from it. */
    val openFlow: TdFlow by lazy { TdFlowField.compute(this, null) }

    /** Cost-weighted route length (in cells) from each spawn with no towers on the field. */
    val baseline: FloatArray by lazy { FloatArray(spawns.size) { openFlow.dist[spawns[it]] } }
}

/** Distance-to-gate field plus the best next cell for every cell (-1 where none). */
class TdFlow(val dist: FloatArray, val next: IntArray) {
    fun reachable(cell: Int): Boolean = dist[cell].isFinite()
}

object TdFlowField {
    private val DX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
    private val DY = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
    private const val DIAG = 1.41421356f

    /**
     * Dijkstra from the gate over 8 neighbours, no corner cutting through blocked cells. Stable
     * tie-breaking (cost, then cell index; neighbour order fixed) keeps the result deterministic.
     * [blocked] marks cells towers occupy; null means open field.
     */
    fun compute(t: TdTerrain, blocked: BooleanArray?): TdFlow {
        val n = TdGrid.CELLS
        val dist = FloatArray(n) { Float.POSITIVE_INFINITY }
        fun ok(i: Int) = t.passable[i] && (blocked == null || !blocked[i])
        val pq = PriorityQueue<Long>()
        fun key(d: Float, i: Int): Long = (java.lang.Float.floatToIntBits(d).toLong() shl 32) or i.toLong()
        for (g in t.gate) if (ok(g)) { dist[g] = 0f; pq.add(key(0f, g)) }
        while (pq.isNotEmpty()) {
            val top = pq.poll() ?: break
            val u = (top and 0xFFFFFFFFL).toInt()
            val d = java.lang.Float.intBitsToFloat((top ushr 32).toInt())
            if (d > dist[u]) continue
            val ux = TdGrid.cx(u)
            val uy = TdGrid.cy(u)
            for (k in 0 until 8) {
                val vx = ux + DX[k]
                val vy = uy + DY[k]
                if (!TdGrid.inBounds(vx, vy)) continue
                val v = TdGrid.idx(vx, vy)
                if (!ok(v)) continue
                val diagonal = k >= 4
                if (diagonal && !(ok(TdGrid.idx(ux, vy)) && ok(TdGrid.idx(vx, uy)))) continue
                // The enemy steps from v into u, so entering u is what costs.
                val nd = d + t.cost[u] * (if (diagonal) DIAG else 1f)
                if (nd < dist[v]) {
                    dist[v] = nd
                    pq.add(key(nd, v))
                }
            }
        }
        val next = IntArray(n) { -1 }
        for (v in 0 until n) {
            if (!dist[v].isFinite() || dist[v] == 0f) continue
            val vx = TdGrid.cx(v)
            val vy = TdGrid.cy(v)
            var best = -1
            var bestD = Float.POSITIVE_INFINITY
            for (k in 0 until 8) {
                val ux = vx + DX[k]
                val uy = vy + DY[k]
                if (!TdGrid.inBounds(ux, uy)) continue
                val u = TdGrid.idx(ux, uy)
                if (!ok(u)) continue
                if (k >= 4 && !(ok(TdGrid.idx(vx, uy)) && ok(TdGrid.idx(ux, vy)))) continue
                if (dist[u] < bestD) { bestD = dist[u]; best = u }
            }
            next[v] = best
        }
        return TdFlow(dist, next)
    }

    /** Unit direction toward the best next cell of [cell] (zero at the goal or unreachable). */
    fun direction(f: TdFlow, cell: Int, t: TdTerrain): Offset {
        val n = f.next[cell]
        if (n < 0) return if (t.isGate(cell)) Offset(1f, 0f) else Offset.Zero
        val a = TdGrid.centre(cell)
        val b = TdGrid.centre(n)
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = sqrt(dx * dx + dy * dy)
        return if (len > 0f) Offset(dx / len, dy / len) else Offset.Zero
    }

    /** The route from [spawnCell] to the gate as tile points (for the path preview). */
    fun route(f: TdFlow, spawnCell: Int, maxSteps: Int = 400): List<Offset> {
        val out = ArrayList<Offset>()
        var c = spawnCell
        var steps = 0
        while (c >= 0 && steps++ < maxSteps) {
            out.add(TdGrid.centre(c))
            if (f.dist[c] == 0f) break
            c = f.next[c]
        }
        return out
    }
}

enum class TdReject { UNBUILDABLE, OVERLAP, NO_GOLD, BLOCKS_ROUTE, TOO_LONG }

sealed interface TdPlaceResult {
    /** The tower may be built; [detourTiles] is how much longer the longest route becomes. */
    data class Ok(val detourTiles: Float) : TdPlaceResult
    data class Rejected(val reason: TdReject) : TdPlaceResult
}

object TdPlacement {
    /** Cells covered by towers anchored at the given cells. */
    fun occupancy(anchors: List<Pair<Int, Int>>): BooleanArray {
        val occ = BooleanArray(TdGrid.CELLS)
        for ((ax, ay) in anchors) TdGrid.footprint(ax, ay)?.forEach { occ[it] = true }
        return occ
    }

    /**
     * The one hard rule (every spawn keeps a route to the gate) plus the soft one (the route may
     * not exceed [TdTerrain.routeCap] times the open-field length). Order: footprint, overlap,
     * gold, route. [occupied] must NOT include the tower being moved, if any.
     */
    fun validate(t: TdTerrain, occupied: BooleanArray, ax: Int, ay: Int, gold: Int, cost: Int): TdPlaceResult {
        val fp = TdGrid.footprint(ax, ay) ?: return TdPlaceResult.Rejected(TdReject.UNBUILDABLE)
        if (fp.any { !t.buildable[it] }) return TdPlaceResult.Rejected(TdReject.UNBUILDABLE)
        if (fp.any { occupied[it] }) return TdPlaceResult.Rejected(TdReject.OVERLAP)
        if (gold < cost) return TdPlaceResult.Rejected(TdReject.NO_GOLD)
        val before = TdFlowField.compute(t, occupied)
        val after = occupied.copyOf().also { occ -> fp.forEach { occ[it] = true } }
        val flow = TdFlowField.compute(t, after)
        var worstDetour = 0f
        for ((i, s) in t.spawns.withIndex()) {
            val d = flow.dist[s]
            if (!d.isFinite()) return TdPlaceResult.Rejected(TdReject.BLOCKS_ROUTE)
            if (t.routeCap.isFinite() && d > t.baseline[i] * t.routeCap) return TdPlaceResult.Rejected(TdReject.TOO_LONG)
            val old = before.dist[s]
            val detour = if (old.isFinite()) d - old else d
            worstDetour = maxOf(worstDetour, detour)
        }
        return TdPlaceResult.Ok(abs(worstDetour) / TdGrid.CELLS_PER_TILE)
    }
}

/** Authoring helper for [TdMaps]: paint terrain with rectangles, discs and thick polylines. */
class TdTerrainBuilder(private val id: String) {
    val cost = FloatArray(TdGrid.CELLS) { OPEN }
    val buildable = BooleanArray(TdGrid.CELLS) { true }
    private val spawns = ArrayList<Int>()
    private var castle = intArrayOf(44, 10, 47, 17)
    private val gate = ArrayList<Int>()
    private val water = BooleanArray(TdGrid.CELLS)
    var routeCap = 2.5f

    companion object {
        const val ROAD = 1.0f
        const val OPEN = 1.25f
        const val ROUGH = 3.0f
        val BLOCKED = Float.POSITIVE_INFINITY
    }

    private fun each(x0: Int, y0: Int, x1: Int, y1: Int, f: (Int) -> Unit) {
        for (y in y0..y1) for (x in x0..x1) if (TdGrid.inBounds(x, y)) f(TdGrid.idx(x, y))
    }

    /** Impassable and unbuildable (water, cliffs, rocks). */
    fun block(x0: Int, y0: Int, x1: Int, y1: Int) = each(x0, y0, x1, y1) { cost[it] = BLOCKED; buildable[it] = false }

    /** Impassable water: like [block] but drawn as water. */
    fun water(x0: Int, y0: Int, x1: Int, y1: Int) {
        block(x0, y0, x1, y1)
        each(x0, y0, x1, y1) { water[it] = true }
    }

    fun waterDisc(cx: Float, cy: Float, r: Float) {
        blockDisc(cx, cy, r)
        for (y in 0 until TdGrid.ROWS) for (x in 0 until TdGrid.COLS) {
            val dx = x + 0.5f - cx
            val dy = y + 0.5f - cy
            if (dx * dx + dy * dy <= r * r) water[TdGrid.idx(x, y)] = true
        }
    }

    fun blockDisc(cx: Float, cy: Float, r: Float) {
        for (y in 0 until TdGrid.ROWS) for (x in 0 until TdGrid.COLS) {
            val dx = x + 0.5f - cx
            val dy = y + 0.5f - cy
            if (dx * dx + dy * dy <= r * r) { cost[TdGrid.idx(x, y)] = BLOCKED; buildable[TdGrid.idx(x, y)] = false }
        }
    }

    /** Passable, buildable, slow ground (mud, bushes, a ford). */
    fun rough(x0: Int, y0: Int, x1: Int, y1: Int) = each(x0, y0, x1, y1) { if (cost[it].isFinite()) cost[it] = ROUGH }

    /** Passable but not buildable (a bridge). */
    fun bridge(x0: Int, y0: Int, x1: Int, y1: Int) = each(x0, y0, x1, y1) { cost[it] = ROAD; buildable[it] = false }

    /** A road: cheap cells along a polyline [halfWidth] cells either side, overwriting open ground and rough only. */
    fun road(points: List<Pair<Int, Int>>, halfWidth: Int = 2) {
        for (k in 0 until points.size - 1) {
            val (ax, ay) = points[k]
            val (bx, by) = points[k + 1]
            val steps = maxOf(abs(bx - ax), abs(by - ay)).coerceAtLeast(1)
            for (s in 0..steps) {
                val x = ax + (bx - ax) * s / steps
                val y = ay + (by - ay) * s / steps
                each(x - halfWidth, y - halfWidth, x + halfWidth, y + halfWidth) {
                    if (cost[it].isFinite() && cost[it] > ROAD) cost[it] = ROAD
                }
            }
        }
    }

    /** A west-edge entrance: a passable, unbuildable apron plus the spawn cell. */
    fun spawn(y: Int) {
        each(0, y - 2, 3, y + 2) { cost[it] = ROAD; buildable[it] = false }
        spawns.add(TdGrid.idx(1, y))
    }

    /** The castle on the east edge between rows [y0] and [y1] (cells); its front cells are the gate. */
    fun castle(y0: Int, y1: Int) {
        castle = intArrayOf(44, y0, 47, y1)
        each(44, y0, 47, y1) { cost[it] = BLOCKED; buildable[it] = false }
        each(40, y0 - 2, 43, y1 + 2) { if (cost[it].isFinite()) { cost[it] = ROAD; buildable[it] = false } }
        gate.clear()
        for (y in y0..y1) gate.add(TdGrid.idx(43, y))
    }

    fun build(): TdTerrain = TdTerrain(id, cost, buildable, spawns.toList(), gate.toList(), castle, routeCap, water)
}
