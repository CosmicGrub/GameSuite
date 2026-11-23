package com.gamesuite.games.flowlines

import kotlin.math.abs
import kotlin.random.Random

/**
 * Pure rules for Flow Lines (a Flow Free style puzzle): no Compose, no Android, so the whole file
 * is covered by plain JUnit. Cells are row-major indices (`row * size + col`). A "path" is the list
 * of cells a colour currently occupies, STARTING at one of that colour's two endpoints and growing
 * one orthogonal step at a time; an empty list means the colour has not been touched.
 */

/** Board size per tier plus how many dot pairs (flows) a generated board has. */
enum class FlowTier(val size: Int, val flows: Int) {
    S5(5, 4),
    S6(6, 5),
    S7(7, 6),
    S8(8, 7),
    S9(9, 8);

    companion object {
        fun fromName(name: String): FlowTier? = entries.firstOrNull { it.name == name }
    }
}

/** The two dots of one colour. */
data class FlowPair(val a: Int, val b: Int)

/**
 * A board: its [size], the dot [pairs] (index = colour) and one known [solution] (a path per colour
 * running from the pair's `a` to its `b`, together covering every cell exactly once). The solution
 * is what makes the board solvable by construction and what hints reveal.
 */
data class FlowPuzzle(val size: Int, val pairs: List<FlowPair>, val solution: List<List<Int>>) {
    val cellCount: Int get() = size * size
    val colorCount: Int get() = pairs.size

    private val endpointColors: IntArray by lazy {
        val out = IntArray(size * size) { -1 }
        for ((color, pair) in pairs.withIndex()) {
            if (pair.a in out.indices) out[pair.a] = color
            if (pair.b in out.indices) out[pair.b] = color
        }
        out
    }

    /** The colour whose dot sits on [cell], or -1. */
    fun endpointColor(cell: Int): Int = if (cell in endpointColors.indices) endpointColors[cell] else -1

    /** The other dot of [color] given one dot [cell] of it. */
    fun partner(color: Int, cell: Int): Int = if (cell == pairs[color].a) pairs[color].b else pairs[color].a

    /**
     * True when this is a coherent board: sane size and colour count, in-range distinct dots, and a
     * [solution] of contiguous paths a-to-b that cover every cell exactly once. Used to reject a
     * hand-edited or corrupt save as a whole.
     */
    fun isValid(): Boolean {
        if (size !in 2..12) return false
        val n = size * size
        val k = pairs.size
        if (k < 1 || k > FlowRules.MAX_COLORS) return false
        if (solution.size != k) return false
        val dotSeen = BooleanArray(n)
        for (pair in pairs) {
            if (pair.a !in 0 until n || pair.b !in 0 until n || pair.a == pair.b) return false
            if (dotSeen[pair.a] || dotSeen[pair.b]) return false
            dotSeen[pair.a] = true
            dotSeen[pair.b] = true
        }
        val covered = BooleanArray(n)
        for (color in 0 until k) {
            val path = solution[color]
            if (path.size < 2) return false
            if (path.first() != pairs[color].a || path.last() != pairs[color].b) return false
            for ((i, cell) in path.withIndex()) {
                if (cell !in 0 until n || covered[cell]) return false
                covered[cell] = true
                if (i > 0 && !FlowRules.adjacent(size, path[i - 1], cell)) return false
                // A solution path may only touch its own colour's dots.
                val ec = endpointColor(cell)
                if (ec >= 0 && ec != color) return false
            }
        }
        return covered.all { it }
    }
}

/** Result of touching a cell: the colour now being drawn and the paths after the touch. */
data class FlowBegin(val color: Int, val paths: List<List<Int>>)

/** Result of a hint: the paths after revealing a segment, the colour it belongs to and the last revealed cell. */
data class FlowHint(val paths: List<List<Int>>, val color: Int, val cell: Int)

object FlowRules {
    /** Colours the UI can tell apart with a letter and a shape. */
    const val MAX_COLORS = 8

    /** How many cells of the correct path one hint reveals beyond what is already right. */
    const val HINT_STEP = 3

    fun adjacent(size: Int, a: Int, b: Int): Boolean {
        val dr = abs(a / size - b / size)
        val dc = abs(a % size - b % size)
        return dr + dc == 1
    }

    /** The colour whose path contains [cell], or -1. */
    fun ownerOf(paths: List<List<Int>>, cell: Int): Int {
        for (color in paths.indices) if (paths[color].contains(cell)) return color
        return -1
    }

    private fun replace(paths: List<List<Int>>, color: Int, path: List<Int>): List<List<Int>> {
        val out = paths.toMutableList()
        out[color] = path
        return out
    }

    /** A path is connected when it runs from one dot of its colour to the other. */
    fun isConnected(puzzle: FlowPuzzle, paths: List<List<Int>>, color: Int): Boolean {
        val p = paths[color]
        if (p.size < 2) return false
        return p.first() == puzzle.pairs[color].a && p.last() == puzzle.pairs[color].b ||
            p.first() == puzzle.pairs[color].b && p.last() == puzzle.pairs[color].a
    }

    fun connectedCount(puzzle: FlowPuzzle, paths: List<List<Int>>): Int =
        (0 until puzzle.colorCount).count { isConnected(puzzle, paths, it) }

    /**
     * Equal as drawings: a lone dot-only path (one cell) counts as nothing drawn, so touching a dot
     * without drawing is not a move.
     */
    fun sameDrawing(a: List<List<Int>>, b: List<List<Int>>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val x: List<Int> = if (a[i].size <= 1) emptyList<Int>() else a[i]
            val y: List<Int> = if (b[i].size <= 1) emptyList<Int>() else b[i]
            if (x != y) return false
        }
        return true
    }

    /** Number of distinct cells covered by paths. */
    fun filledCount(paths: List<List<Int>>): Int = paths.sumOf { it.size }

    /** Solved: every pair connected and every cell covered. */
    fun isSolved(puzzle: FlowPuzzle, paths: List<List<Int>>): Boolean {
        if (paths.size != puzzle.colorCount) return false
        for (color in 0 until puzzle.colorCount) if (!isConnected(puzzle, paths, color)) return false
        val covered = BooleanArray(puzzle.cellCount)
        var count = 0
        for (p in paths) for (cell in p) {
            if (cell !in covered.indices || covered[cell]) return false
            covered[cell] = true
            count++
        }
        return count == puzzle.cellCount
    }

    /**
     * Structural check of a set of paths against a puzzle: one entry per colour, every path
     * contiguous, starting on one of its own dots, never touching another colour's dot, never
     * passing through its own second dot except as its last cell, and no cell used twice.
     */
    fun validatePaths(puzzle: FlowPuzzle, paths: List<List<Int>>): Boolean {
        val n = puzzle.cellCount
        if (paths.size != puzzle.colorCount) return false
        val used = BooleanArray(n)
        for (color in paths.indices) {
            val p = paths[color]
            if (p.isEmpty()) continue
            if (p[0] != puzzle.pairs[color].a && p[0] != puzzle.pairs[color].b) return false
            for ((i, cell) in p.withIndex()) {
                if (cell !in 0 until n || used[cell]) return false
                used[cell] = true
                if (i > 0 && !adjacent(puzzle.size, p[i - 1], cell)) return false
                val ec = puzzle.endpointColor(cell)
                if (ec >= 0 && ec != color) return false
                if (ec == color && i != 0 && i != p.lastIndex) return false
            }
        }
        return true
    }

    /**
     * A finger lands on [cell]. On a dot: that colour's path restarts from the dot. On a path cell:
     * the path is cut back to that cell and continues from it. On an empty cell: nothing happens (null).
     */
    fun begin(puzzle: FlowPuzzle, paths: List<List<Int>>, cell: Int): FlowBegin? {
        if (cell !in 0 until puzzle.cellCount) return null
        val ec = puzzle.endpointColor(cell)
        if (ec >= 0) return FlowBegin(ec, replace(paths, ec, listOf(cell)))
        val owner = ownerOf(paths, cell)
        if (owner < 0) return null
        val p = paths[owner]
        return FlowBegin(owner, replace(paths, owner, p.take(p.indexOf(cell) + 1)))
    }

    /**
     * Moves the tip of [color]'s path onto the ORTHOGONALLY ADJACENT cell [to]:
     *  - onto the cell just behind the tip: retrace (the tip is removed);
     *  - onto the colour's own earlier cell: the path is cut back to it;
     *  - onto another colour's dot: refused;
     *  - onto another colour's path: that path is cut at the crossing and loses everything from it on;
     *  - onto the partner dot: the pair connects, and the path cannot grow past it afterwards.
     * Returns the SAME list instance when nothing changed (refused or not adjacent).
     */
    fun step(puzzle: FlowPuzzle, paths: List<List<Int>>, color: Int, to: Int): List<List<Int>> {
        if (color !in paths.indices || to !in 0 until puzzle.cellCount) return paths
        val p = paths[color]
        if (p.isEmpty()) return paths
        val tip = p.last()
        if (to == tip || !adjacent(puzzle.size, tip, to)) return paths
        if (p.size >= 2 && to == p[p.size - 2]) return replace(paths, color, p.dropLast(1))
        val complete = p.size > 1 && tip == puzzle.partner(color, p[0])
        if (complete) return paths
        val own = p.indexOf(to)
        if (own >= 0) return replace(paths, color, p.take(own + 1))
        val endColor = puzzle.endpointColor(to)
        if (endColor >= 0 && endColor != color) return paths
        val out = paths.toMutableList()
        val other = ownerOf(paths, to)
        if (other >= 0 && other != color) out[other] = paths[other].take(paths[other].indexOf(to))
        out[color] = p + to
        return out
    }

    /** The cell one orthogonal step from [from] toward [target] (the longer axis first). */
    fun nextToward(size: Int, from: Int, target: Int): Int {
        val dr = target / size - from / size
        val dc = target % size - from % size
        return if (abs(dr) >= abs(dc) && dr != 0) {
            from + (if (dr > 0) size else -size)
        } else if (dc != 0) {
            from + (if (dc > 0) 1 else -1)
        } else from
    }

    /**
     * A finger drags to [target], which may be several cells from the tip (a fast swipe skips
     * cells): applies one [step] per cell along the way and stops at the first refused step.
     */
    fun stepToward(puzzle: FlowPuzzle, paths: List<List<Int>>, color: Int, target: Int): List<List<Int>> {
        if (color !in paths.indices || target !in 0 until puzzle.cellCount) return paths
        var cur = paths
        repeat(2 * puzzle.size + 2) {
            val p = cur[color]
            if (p.isEmpty()) return cur
            val tip = p.last()
            if (tip == target) return cur
            val after = step(puzzle, cur, color, nextToward(puzzle.size, tip, target))
            if (after === cur) return cur
            cur = after
        }
        return cur
    }

    /**
     * Reveals a segment of a correct path: for the first colour whose path is not yet exactly its
     * solution path, the path is cut back to the part that already agrees with the solution and
     * extended by [HINT_STEP] solution cells. Other colours are cut where the revealed cells cross
     * them. Null when every path already equals the solution.
     */
    fun hintPaths(puzzle: FlowPuzzle, paths: List<List<Int>>): FlowHint? {
        for (color in 0 until puzzle.colorCount) {
            val sol = puzzle.solution[color]
            val cur = paths[color]
            val target = if (cur.isNotEmpty() && cur[0] == sol.last()) sol.reversed() else sol
            var m = 0
            while (m < cur.size && m < target.size && cur[m] == target[m]) m++
            if (m == target.size && cur.size == target.size) continue
            val newLen = minOf(target.size, maxOf(m, 1) + HINT_STEP)
            val newPath = target.take(newLen)
            val newSet = newPath.toHashSet()
            val out = paths.toMutableList()
            for (other in out.indices) {
                if (other == color) continue
                val idx = out[other].indexOfFirst { it in newSet }
                if (idx >= 0) out[other] = out[other].take(idx)
            }
            out[color] = newPath
            return FlowHint(out, color, newPath.last())
        }
        return null
    }
}

/** Builds boards that are solvable by construction. Deterministic for a seed. */
object FlowGenerator {
    /** Shortest solution path: 3 cells, so no pair is ever a trivial adjacent pair. */
    private const val MIN_PATH_LEN = 3

    /** The seed of the daily board for a day number (epoch day) and tier; same for every player. */
    fun dailySeed(epochDay: Long, tier: FlowTier): Long = epochDay * 7919L + tier.size * 104729L + 12345L

    fun generate(tier: FlowTier, seed: Long): FlowPuzzle = generate(tier.size, tier.flows, seed)

    /**
     * Starts from a boustrophedon Hamiltonian path of the grid, randomises it with many "backbite"
     * moves (each keeps it a Hamiltonian path), then cuts it into [flows] contiguous pieces of at
     * least [MIN_PATH_LEN] cells. Every piece is a simple path and together they tile the grid, so
     * the piece endpoints are the dot pairs and the pieces are a known solution.
     */
    fun generate(size: Int, flows: Int, seed: Long): FlowPuzzle {
        require(size >= 2) { "size must be at least 2" }
        require(flows in 1..FlowRules.MAX_COLORS) { "flows out of range: $flows" }
        val n = size * size
        require(flows * MIN_PATH_LEN <= n) { "too many flows for a $size x $size board" }
        val random = Random(seed)

        val order = IntArray(n)
        var i = 0
        for (r in 0 until size) {
            if (r % 2 == 0) for (c in 0 until size) order[i++] = r * size + c
            else for (c in size - 1 downTo 0) order[i++] = r * size + c
        }
        val pos = IntArray(n)
        for (idx in 0 until n) pos[order[idx]] = idx

        fun reverse(from: Int, to: Int) {
            var lo = from
            var hi = to
            while (lo < hi) {
                val t = order[lo]; order[lo] = order[hi]; order[hi] = t
                lo++; hi--
            }
            for (idx in from..to) pos[order[idx]] = idx
        }

        repeat(n * 40) {
            val head = random.nextBoolean()
            val end = if (head) order[0] else order[n - 1]
            val er = end / size
            val ec = end % size
            val options = ArrayList<Int>(4)
            if (er > 0) options += end - size
            if (er < size - 1) options += end + size
            if (ec > 0) options += end - 1
            if (ec < size - 1) options += end + 1
            val x = options[random.nextInt(options.size)]
            val j = pos[x]
            if (head) {
                if (j >= 2) reverse(0, j - 1)
            } else {
                if (j <= n - 3) reverse(j + 1, n - 1)
            }
        }

        val lengths = IntArray(flows) { MIN_PATH_LEN }
        repeat(n - flows * MIN_PATH_LEN) { lengths[random.nextInt(flows)]++ }

        val pairs = ArrayList<FlowPair>(flows)
        val solution = ArrayList<List<Int>>(flows)
        var start = 0
        for (color in 0 until flows) {
            var piece = (start until start + lengths[color]).map { order[it] }
            if (random.nextBoolean()) piece = piece.reversed()
            pairs += FlowPair(piece.first(), piece.last())
            solution += piece
            start += lengths[color]
        }
        return FlowPuzzle(size, pairs, solution)
    }
}

/**
 * A depth-first solver used to prove boards solvable (tests) and to count solutions. Colours grow
 * from their `a` dot simultaneously; the colour with the fewest legal moves moves next, and a state
 * is abandoned when an empty cell can no longer have two usable neighbours.
 */
object FlowSolver {
    /** [solutions] found (each a path per colour from a to b); [exhausted] is true when [maxNodes] ran out first. */
    class Result(val solutions: List<List<List<Int>>>, val exhausted: Boolean)

    fun solve(size: Int, pairs: List<FlowPair>, maxSolutions: Int = 1, maxNodes: Int = 2_000_000): Result =
        Search(size, pairs, maxSolutions, maxNodes).run()

    private class Search(val size: Int, val pairs: List<FlowPair>, val maxSolutions: Int, val maxNodes: Int) {
        val n = size * size
        val k = pairs.size
        val owner = IntArray(n) { -1 }
        val tip = IntArray(k)
        val done = BooleanArray(k)
        val paths = Array(k) { ArrayList<Int>() }
        var empties = n
        var doneCount = 0
        var nodes = 0
        var aborted = false
        val found = ArrayList<List<List<Int>>>()
        val nbrs: Array<IntArray> = Array(n) { cell ->
            val r = cell / size
            val c = cell % size
            val list = ArrayList<Int>(4)
            if (r > 0) list += cell - size
            if (c > 0) list += cell - 1
            if (c < size - 1) list += cell + 1
            if (r < size - 1) list += cell + size
            list.toIntArray()
        }

        fun run(): Result {
            for (color in 0 until k) {
                owner[pairs[color].a] = color
                owner[pairs[color].b] = color
                tip[color] = pairs[color].a
                paths[color].add(pairs[color].a)
            }
            empties = n - 2 * k
            dfs()
            return Result(found, aborted)
        }

        private fun movesCount(color: Int): Int {
            var count = 0
            for (nb in nbrs[tip[color]]) {
                if (owner[nb] == -1 || nb == pairs[color].b) count++
            }
            return count
        }

        private fun dfs() {
            if (aborted) return
            nodes++
            if (nodes > maxNodes) { aborted = true; return }
            if (doneCount == k) {
                if (empties == 0) found.add(paths.map { it.toList() })
                return
            }
            // An empty cell needs two neighbours that a path could still arrive from / leave to.
            val accessible = BooleanArray(n)
            for (cell in 0 until n) if (owner[cell] == -1) accessible[cell] = true
            for (color in 0 until k) {
                if (!done[color]) {
                    accessible[tip[color]] = true
                    accessible[pairs[color].b] = true
                }
            }
            for (cell in 0 until n) {
                if (owner[cell] != -1) continue
                var open = 0
                for (nb in nbrs[cell]) if (accessible[nb]) open++
                if (open < 2) return
            }
            var best = -1
            var bestMoves = Int.MAX_VALUE
            for (color in 0 until k) {
                if (done[color]) continue
                val m = movesCount(color)
                if (m == 0) return
                if (m < bestMoves) { best = color; bestMoves = m }
            }
            val from = tip[best]
            for (nb in nbrs[from]) {
                val isGoal = nb == pairs[best].b
                if (!isGoal && owner[nb] != -1) continue
                if (isGoal) {
                    done[best] = true
                    doneCount++
                } else {
                    owner[nb] = best
                    empties--
                }
                tip[best] = nb
                paths[best].add(nb)
                dfs()
                paths[best].removeAt(paths[best].size - 1)
                tip[best] = from
                if (isGoal) {
                    done[best] = false
                    doneCount--
                } else {
                    owner[nb] = -1
                    empties++
                }
                if (aborted || found.size >= maxSolutions) return
            }
        }
    }
}
