package com.gamesuite.games.gemcascade

/** A maximal straight run of 3+ gems of one colour. */
data class Run(val horizontal: Boolean, val start: Pos, val length: Int, val kind: Int) {
    fun cells(): List<Pos> = List(length) { i -> if (horizontal) Pos(start.r, start.c + i) else Pos(start.r + i, start.c) }
}

/** Connected runs of one colour (an L, a T, a cross or a single line), with the shape that decides the special it makes. */
data class MatchGroup(val kind: Int, val cells: List<Pos>, val runs: List<Run>, val shape: MatchShape) {
    /** The special this shape creates. */
    val special: SpecialKind
        get() = when (shape) {
            MatchShape.LINE_3 -> SpecialKind.NONE
            MatchShape.LINE_4 -> {
                val run = runs.first { it.length == 4 }
                // a horizontal run makes a column flare (it clears across the line), a vertical run a row flare
                if (run.horizontal) SpecialKind.FLARE_COL else SpecialKind.FLARE_ROW
            }
            MatchShape.LINE_5 -> SpecialKind.PRISM
            MatchShape.L, MatchShape.T, MatchShape.CROSS -> SpecialKind.BURST
            MatchShape.DOUBLE_4 -> SpecialKind.NOVA
        }

    /**
     * Where the special goes: the first [pivots] cell (the cells the player just moved) that is in the group and
     * holds no special; otherwise the group's natural centre. Null when every candidate already holds a special.
     */
    fun specialPos(board: GemBoard, pivots: List<Pos>): Pos? {
        if (special == SpecialKind.NONE) return null
        val set = cells.toHashSet()
        val free = { p: Pos -> board[p].special == SpecialKind.NONE && !board[p].locked }
        pivots.firstOrNull { it in set && free(it) }?.let { return it }
        val centre: Pos? = when (shape) {
            MatchShape.LINE_4, MatchShape.LINE_5 -> {
                val run = runs.maxByOrNull { it.length }!!
                run.cells()[run.length / 2]
            }
            else -> intersection()
        }
        if (centre != null && free(centre)) return centre
        return cells.sorted().firstOrNull(free)
    }

    private fun intersection(): Pos? {
        for (h in runs.filter { it.horizontal }) for (v in runs.filter { !it.horizontal }) {
            val p = Pos(h.start.r, v.start.c)
            if (p in h.cells() && p in v.cells()) return p
        }
        return null
    }
}

object GemMatcher {
    /** Every maximal run of 3+ same-colour gems in rows and columns. */
    fun runs(board: GemBoard): List<Run> {
        val out = ArrayList<Run>()
        for (r in 0 until board.rows) {
            var c = 0
            while (c < board.cols) {
                val k = board[r, c].matchKind
                if (k < 0) { c++; continue }
                var e = c + 1
                while (e < board.cols && board[r, e].matchKind == k) e++
                if (e - c >= 3) out.add(Run(true, Pos(r, c), e - c, k))
                c = e
            }
        }
        for (c in 0 until board.cols) {
            var r = 0
            while (r < board.rows) {
                val k = board[r, c].matchKind
                if (k < 0) { r++; continue }
                var e = r + 1
                while (e < board.rows && board[e, c].matchKind == k) e++
                if (e - r >= 3) out.add(Run(false, Pos(r, c), e - r, k))
                r = e
            }
        }
        return out
    }

    /** True when any match exists (cheap early-out version of [groups]). */
    fun hasAny(board: GemBoard): Boolean {
        for (r in 0 until board.rows) for (c in 0 until board.cols) {
            val k = board[r, c].matchKind
            if (k < 0) continue
            if (c + 2 < board.cols && board[r, c + 1].matchKind == k && board[r, c + 2].matchKind == k) return true
            if (r + 2 < board.rows && board[r + 1, c].matchKind == k && board[r + 2, c].matchKind == k) return true
        }
        return false
    }

    /** Runs merged into groups of connected same-colour runs, each classified. Sorted by first cell. */
    fun groups(board: GemBoard): List<MatchGroup> {
        val runs = runs(board)
        if (runs.isEmpty()) return emptyList()
        val parent = IntArray(runs.size) { it }
        fun find(x: Int): Int {
            var a = x
            while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }
            return a
        }
        val cellsOf = runs.map { it.cells() }
        for (i in runs.indices) for (j in i + 1 until runs.size) {
            if (runs[i].kind != runs[j].kind || runs[i].horizontal == runs[j].horizontal) continue
            if (cellsOf[i].any { it in cellsOf[j] }) parent[find(i)] = find(j)
        }
        val byRoot = LinkedHashMap<Int, MutableList<Int>>()
        for (i in runs.indices) byRoot.getOrPut(find(i)) { ArrayList() }.add(i)
        val out = ArrayList<MatchGroup>()
        for (members in byRoot.values) {
            val rs = members.map { runs[it] }
            val cells = members.flatMap { cellsOf[it] }.toSortedSet().toList()
            out.add(MatchGroup(rs[0].kind, cells, rs, classify(rs)))
        }
        return out.sortedBy { it.cells.first() }
    }

    private fun classify(rs: List<Run>): MatchShape {
        if (rs.count { it.length >= 4 } >= 2) return MatchShape.DOUBLE_4
        if (rs.any { it.length >= 5 }) return MatchShape.LINE_5
        val hs = rs.filter { it.horizontal }
        val vs = rs.filter { !it.horizontal }
        if (hs.isNotEmpty() && vs.isNotEmpty()) {
            for (h in hs) for (v in vs) {
                val p = Pos(h.start.r, v.start.c)
                if (p in h.cells() && p in v.cells()) {
                    val endH = p.c == h.start.c || p.c == h.start.c + h.length - 1
                    val endV = p.r == v.start.r || p.r == v.start.r + v.length - 1
                    return when {
                        endH && endV -> MatchShape.L
                        endH || endV -> MatchShape.T
                        else -> MatchShape.CROSS
                    }
                }
            }
        }
        return if (rs.any { it.length == 4 }) MatchShape.LINE_4 else MatchShape.LINE_3
    }
}

/** Special gem effects and the special + special combo table. */
object GemSpecials {
    /** The combo table: every unordered pair of special classes and its effect. */
    val comboTable: Map<Pair<SpecialClass, SpecialClass>, ComboEffect> = mapOf(
        (SpecialClass.FLARE to SpecialClass.FLARE) to ComboEffect.CROSS,
        (SpecialClass.FLARE to SpecialClass.PRISM) to ComboEffect.COLOUR_TO_FLARES,
        (SpecialClass.FLARE to SpecialClass.BURST) to ComboEffect.FAT_CROSS_1,
        (SpecialClass.FLARE to SpecialClass.NOVA) to ComboEffect.FAT_CROSS_2,
        (SpecialClass.PRISM to SpecialClass.PRISM) to ComboEffect.BOARD,
        (SpecialClass.PRISM to SpecialClass.BURST) to ComboEffect.COLOUR_TO_BURSTS,
        (SpecialClass.PRISM to SpecialClass.NOVA) to ComboEffect.COLOUR_TO_NOVAS,
        (SpecialClass.BURST to SpecialClass.BURST) to ComboEffect.AREA_2,
        (SpecialClass.BURST to SpecialClass.NOVA) to ComboEffect.AREA_3,
        (SpecialClass.NOVA to SpecialClass.NOVA) to ComboEffect.BOARD
    )

    fun comboFor(a: SpecialKind, b: SpecialKind): ComboEffect? {
        val ca = a.specialClass ?: return null
        val cb = b.specialClass ?: return null
        val key = if (ca.ordinal <= cb.ordinal) ca to cb else cb to ca
        return comboTable[key]
    }

    /** Cells in the (2 * radius + 1) square around [p], clipped to the board. */
    fun area(board: GemBoard, p: Pos, radius: Int): Set<Pos> {
        val out = LinkedHashSet<Pos>()
        for (r in p.r - radius..p.r + radius) for (c in p.c - radius..p.c + radius) if (board.inside(r, c)) out.add(Pos(r, c))
        return out
    }

    /** The rows and columns within [width] of [p]'s row and column. */
    fun fatCross(board: GemBoard, p: Pos, width: Int): Set<Pos> {
        val out = LinkedHashSet<Pos>()
        for (r in 0 until board.rows) for (c in 0 until board.cols) {
            if (kotlin.math.abs(r - p.r) <= width || kotlin.math.abs(c - p.c) <= width) out.add(Pos(r, c))
        }
        return out
    }

    fun row(board: GemBoard, r: Int): Set<Pos> = (0 until board.cols).map { Pos(r, it) }.toCollection(LinkedHashSet())
    fun col(board: GemBoard, c: Int): Set<Pos> = (0 until board.rows).map { Pos(it, c) }.toCollection(LinkedHashSet())

    /** The cells a single special at [p] covers (not the prism: see [colourCells]). */
    fun affected(board: GemBoard, kind: SpecialKind, p: Pos): Set<Pos> = when (kind) {
        SpecialKind.FLARE_ROW -> row(board, p.r)
        SpecialKind.FLARE_COL -> col(board, p.c)
        SpecialKind.BURST -> area(board, p, 1)
        SpecialKind.NOVA -> LinkedHashSet<Pos>().apply { addAll(row(board, p.r)); addAll(col(board, p.c)); addAll(area(board, p, 1)) }
        SpecialKind.PRISM, SpecialKind.NONE -> setOf(p)
    }

    /** Every gem of [colour] (movable-colour gems only, prisms excluded), plus [also]. */
    fun colourCells(board: GemBoard, colour: Int, also: Pos? = null): Set<Pos> {
        val out = LinkedHashSet<Pos>()
        for (r in 0 until board.rows) for (c in 0 until board.cols) if (board[r, c].matchKind == colour) out.add(Pos(r, c))
        if (also != null) out.add(also)
        return out
    }

    /** The commonest gem colour on the board (ties: lowest id); -1 when there are no gems. */
    fun commonestColour(board: GemBoard): Int {
        val n = IntArray(GemKinds.COUNT)
        for (cell in board.cells) if (cell.matchKind in 0 until GemKinds.COUNT) n[cell.matchKind]++
        var best = -1
        for (k in n.indices) if (n[k] > 0 && (best < 0 || n[k] > n[best])) best = k
        return best
    }

    fun allCells(board: GemBoard): Set<Pos> = board.allPositions().toCollection(LinkedHashSet())
}
