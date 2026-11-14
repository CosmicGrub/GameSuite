package com.gamesuite.games.sixball

/**
 * Six-Ball Puzzle rules. Six distinguishable balls (symbol AND colour, index 0..5) sit on a small
 * tray: a width x height grid of cells, some of them fixed posts. Tilting the tray one of four ways
 * rolls EVERY ball as far as it can go (a post, the rail or another ball stops it). The tray is
 * solved when every ball rests on the slot that carries its own symbol.
 *
 * Everything here is pure Kotlin (no Android) so it is unit tested, and the solver is breadth-first
 * search, so a par is always the true shortest solution.
 */
const val SIXBALL_BALLS = 6

/** The four tilts, in the order they are tried and numbered (the number is what saves and the wire carry). */
enum class Tilt(val dx: Int, val dy: Int) {
    UP(0, -1), RIGHT(1, 0), DOWN(0, 1), LEFT(-1, 0);

    companion object {
        fun fromCode(code: Int): Tilt? = values().getOrNull(code)
    }
}

/**
 * One level: the tray [width] x [height] (at most 64 cells so a position packs into a Long), the
 * [walls] (cell indices of posts), where the balls begin ([start], ball i at cell start[i]) and the
 * slot each one belongs on ([target]). [par] is the length of the shortest solution.
 */
data class SixBallLevel(
    val id: String,
    val width: Int,
    val height: Int,
    val walls: List<Int>,
    val start: List<Int>,
    val target: List<Int>,
    val par: Int
) {
    val cellCount: Int get() = width * height
    private val wallSet: BooleanArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        BooleanArray(width * height).also { a -> walls.forEach { if (it in a.indices) a[it] = true } }
    }

    fun isWall(cell: Int): Boolean = cell in wallSet.indices && wallSet[cell]

    /** Structural sanity: the geometry is legal and no two things share a cell. Used on every level that enters the game. */
    fun isWellFormed(): Boolean {
        if (width !in 2..8 || height !in 2..8 || width * height > 64) return false
        if (start.size != SIXBALL_BALLS || target.size != SIXBALL_BALLS) return false
        val cells = width * height
        if (walls.any { it !in 0 until cells } || walls.toSet().size != walls.size) return false
        if (start.any { it !in 0 until cells || isWall(it) } || start.toSet().size != SIXBALL_BALLS) return false
        if (target.any { it !in 0 until cells || isWall(it) } || target.toSet().size != SIXBALL_BALLS) return false
        return par >= 0
    }
}

object SixBallRules {
    /** Cell index of (x, y). */
    fun cell(level: SixBallLevel, x: Int, y: Int) = y * level.width + x

    /**
     * Tilts the tray. Balls nearest the leading rail settle first, so a row of balls piles up
     * against the rail in the order it was already in. Returns a NEW array; it equals [positions]
     * when nothing could move (such a tilt is not a move).
     */
    fun tilt(level: SixBallLevel, positions: IntArray, dir: Tilt): IntArray {
        val w = level.width
        val h = level.height
        val out = positions.copyOf()
        val n = out.size
        // order of settling: the ball furthest along the tilt first
        val order = IntArray(n) { it }
        fun progress(i: Int) = (out[i] % w) * dir.dx + (out[i] / w) * dir.dy
        for (a in 1 until n) { // insertion sort, n = 6
            val v = order[a]
            var b = a - 1
            while (b >= 0 && progress(order[b]) < progress(v)) { order[b + 1] = order[b]; b-- }
            order[b + 1] = v
        }
        val occupied = BooleanArray(w * h)
        for (c in out) occupied[c] = true
        for (idx in order) {
            var x = out[idx] % w
            var y = out[idx] / w
            occupied[out[idx]] = false
            while (true) {
                val nx = x + dir.dx
                val ny = y + dir.dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) break
                val c = ny * w + nx
                if (level.isWall(c) || occupied[c]) break
                x = nx; y = ny
            }
            out[idx] = y * w + x
            occupied[out[idx]] = true
        }
        return out
    }

    fun isSolved(level: SixBallLevel, positions: IntArray): Boolean =
        positions.size == SIXBALL_BALLS && (0 until SIXBALL_BALLS).all { positions[it] == level.target[it] }

    /** Per-ball "is it on its own slot" flags, for the screen and for announcements. */
    fun settled(level: SixBallLevel, positions: IntArray): BooleanArray =
        BooleanArray(SIXBALL_BALLS) { positions[it] == level.target[it] }

    /**
     * Replays [moves] (tilt codes) from the level start. Returns the final position, or null when a
     * code is invalid, a tilt moves nothing (not a legal move), or a move is made after the tray is
     * solved. This is what a saved game and the host of a race both use to VALIDATE a move list.
     */
    fun replay(level: SixBallLevel, moves: List<Int>): IntArray? {
        var p = level.start.toIntArray()
        for (code in moves) {
            if (isSolved(level, p)) return null
            val dir = Tilt.fromCode(code) ?: return null
            val q = tilt(level, p, dir)
            if (q.contentEquals(p)) return null
            p = q
        }
        return p
    }

    fun pack(positions: IntArray): Long {
        var v = 0L
        for (i in positions.indices) v = v or (positions[i].toLong() shl (6 * i))
        return v
    }

    fun unpack(packed: Long, into: IntArray = IntArray(SIXBALL_BALLS)): IntArray {
        for (i in into.indices) into[i] = ((packed shr (6 * i)) and 63L).toInt()
        return into
    }

    // ---- search ------------------------------------------------------------------------------

    /** What a breadth-first [explore] found. States are in discovery (so non-decreasing depth) order; index 0 is the start. */
    class Exploration(
        val states: LongArray,
        val parent: IntArray,
        val via: ByteArray,
        val depth: IntArray,
        val size: Int,
        /** False when the node budget ran out before the search finished (the result is partial). */
        val complete: Boolean,
        /** Index of the target state when one was asked for and found, else -1. */
        val found: Int
    ) {
        fun path(index: Int): List<Tilt> {
            val out = ArrayList<Tilt>()
            var i = index
            while (i > 0) { out += Tilt.values()[via[i].toInt()]; i = parent[i] }
            out.reverse()
            return out
        }
    }

    /**
     * Breadth-first search over tray positions from [start]. Stops early at [target] (when >= 0),
     * never expands states deeper than [maxDepth], and gives up after [maxNodes] states
     * ([Exploration.complete] is then false).
     */
    fun explore(
        level: SixBallLevel, start: IntArray, target: Long = -1L,
        maxDepth: Int = Int.MAX_VALUE, maxNodes: Int = 600_000
    ): Exploration {
        var states = LongArray(1024)
        var parent = IntArray(1024)
        var via = ByteArray(1024)
        var depth = IntArray(1024)
        var table = IntArray(4096) { -1 }
        var mask = table.size - 1
        var size = 0

        fun hash(k: Long): Int {
            var x = k * -0x61c8864680b583ebL
            x = x xor (x ushr 29)
            return (x xor (x ushr 32)).toInt()
        }
        fun find(k: Long): Int {
            var s = hash(k) and mask
            while (true) {
                val e = table[s]
                if (e < 0) return -1 - s // not present; encodes the free slot
                if (states[e] == k) return e
                s = (s + 1) and mask
            }
        }
        fun grow() {
            val newTable = IntArray(table.size * 2) { -1 }
            val m = newTable.size - 1
            for (i in 0 until size) {
                var s = hash(states[i]) and m
                while (newTable[s] >= 0) s = (s + 1) and m
                newTable[s] = i
            }
            table = newTable; mask = m
        }
        fun add(k: Long, par: Int, v: Int, d: Int): Int {
            if (size == states.size) {
                states = states.copyOf(size * 2); parent = parent.copyOf(size * 2)
                via = via.copyOf(size * 2); depth = depth.copyOf(size * 2)
            }
            if ((size + 1) * 2 > table.size) grow()
            var s = hash(k) and mask
            while (table[s] >= 0) s = (s + 1) and mask
            table[s] = size
            states[size] = k; parent[size] = par; via[size] = v.toByte(); depth[size] = d
            return size++
        }

        val startKey = pack(start)
        add(startKey, -1, 0, 0)
        if (startKey == target) return Exploration(states, parent, via, depth, size, true, 0)
        val cur = IntArray(SIXBALL_BALLS)
        var head = 0
        var complete = true
        var found = -1
        search@ while (head < size) {
            val i = head++
            if (depth[i] >= maxDepth) continue
            unpack(states[i], cur)
            for (dir in Tilt.values()) {
                val next = tilt(level, cur, dir)
                val key = pack(next)
                if (key == states[i]) continue
                if (find(key) >= 0) continue
                if (size >= maxNodes) { complete = false; break@search }
                val idx = add(key, i, dir.ordinal, depth[i] + 1)
                if (key == target) { found = idx; break@search }
            }
        }
        return Exploration(states, parent, via, depth, size, complete, found)
    }

    /**
     * The shortest list of tilts that takes [from] to the level's target: empty when already there,
     * null when the target cannot be reached from [from] (a dead end) or the search budget ran out.
     */
    fun solve(level: SixBallLevel, from: IntArray = level.start.toIntArray(), maxNodes: Int = 1_500_000): List<Tilt>? {
        val ex = explore(level, from, pack(level.target.toIntArray()), maxNodes = maxNodes)
        if (ex.found < 0) return null
        return ex.path(ex.found)
    }

    /** True when a search from [from] ran to completion without finding the target (a certain dead end). */
    fun isDeadEnd(level: SixBallLevel, from: IntArray, maxNodes: Int = 1_500_000): Boolean {
        val ex = explore(level, from, pack(level.target.toIntArray()), maxNodes = maxNodes)
        return ex.found < 0 && ex.complete
    }

    /** Stars for finishing in [moves] against [par]: 3 at par or better, 2 within half again, 1 otherwise. */
    fun stars(moves: Int, par: Int): Int = when {
        moves <= par -> 3
        moves * 2 <= par * 3 -> 2
        else -> 1
    }
}
