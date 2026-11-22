package com.gamesuite.games.gemcascade

/** Legal-move finding and board dealing helpers (pure). */
object GemMoves {
    /** True when the gem at [p] sits in a run of 3+ (rows or columns). */
    fun matchAt(board: GemBoard, p: Pos): Boolean {
        val k = board[p].matchKind
        if (k < 0) return false
        var h = 1
        var c = p.c - 1
        while (c >= 0 && board[p.r, c].matchKind == k) { h++; c-- }
        c = p.c + 1
        while (c < board.cols && board[p.r, c].matchKind == k) { h++; c++ }
        if (h >= 3) return true
        var v = 1
        var r = p.r - 1
        while (r >= 0 && board[r, p.c].matchKind == k) { v++; r-- }
        r = p.r + 1
        while (r < board.rows && board[r, p.c].matchKind == k) { v++; r++ }
        return v >= 3
    }

    /** Would swapping these two adjacent cells be accepted by the engine? (Board is restored.) */
    fun swapIsLegal(board: GemBoard, a: Pos, b: Pos): Boolean {
        val ca = board[a]
        val cb = board[b]
        if (!ca.isMovable || !cb.isMovable) return false
        if (ca.special != SpecialKind.NONE && cb.special != SpecialKind.NONE) return true
        if (ca.special == SpecialKind.PRISM || cb.special == SpecialKind.PRISM) return true
        board.swapCells(a, b)
        val ok = matchAt(board, a) || matchAt(board, b)
        board.swapCells(a, b)
        return ok
    }

    /** All swaps that would be accepted, each pair once (right and down neighbours), row-major. */
    fun legalSwaps(board: GemBoard): List<GemAction.SwapAction> {
        val out = ArrayList<GemAction.SwapAction>()
        for (r in 0 until board.rows) for (c in 0 until board.cols) {
            val a = Pos(r, c)
            if (!board[a].isMovable) continue
            if (c + 1 < board.cols) {
                val b = Pos(r, c + 1)
                if (swapIsLegal(board, a, b)) out.add(GemAction.SwapAction(a, b))
            }
            if (r + 1 < board.rows) {
                val b = Pos(r + 1, c)
                if (swapIsLegal(board, a, b)) out.add(GemAction.SwapAction(a, b))
            }
        }
        return out
    }

    /** Quarter turns (1..3) in the given direction that first make a match, or 0. Board is restored. */
    fun rotationSteps(board: GemBoard, tl: Pos, clockwise: Boolean): Int {
        if (tl.r < 0 || tl.c < 0 || tl.r + 1 >= board.rows || tl.c + 1 >= board.cols) return 0
        val block = listOf(tl, Pos(tl.r, tl.c + 1), Pos(tl.r + 1, tl.c), Pos(tl.r + 1, tl.c + 1))
        if (block.any { !board[it].isMovable }) return 0
        var found = 0
        var done = 0
        while (done < 3) {
            GemRotation.rotate(board, tl, clockwise)
            done++
            if (block.any { matchAt(board, it) }) { found = done; break }
        }
        repeat(4 - done) { GemRotation.rotate(board, tl, clockwise) }
        return found
    }

    fun legalRotations(board: GemBoard): List<GemAction.RotateAction> {
        val out = ArrayList<GemAction.RotateAction>()
        for (r in 0 until board.rows - 1) for (c in 0 until board.cols - 1) {
            val tl = Pos(r, c)
            if (rotationSteps(board, tl, true) > 0) out.add(GemAction.RotateAction(tl, true))
            if (rotationSteps(board, tl, false) > 0) out.add(GemAction.RotateAction(tl, false))
        }
        return out
    }

    fun legal(board: GemBoard, rules: ModeRules): List<GemAction> {
        val out = ArrayList<GemAction>()
        if (rules.allowSwap) out.addAll(legalSwaps(board))
        if (rules.allowRotate) out.addAll(legalRotations(board))
        return out
    }

    fun hasLegal(board: GemBoard, rules: ModeRules): Boolean = legal(board, rules).isNotEmpty()
}

/** Board dealing: no initial matches, at least one legal move, all deterministic from the injected RNG. */
object GemDeal {
    private val DIRS = listOf(Pos(-1, 0), Pos(1, 0), Pos(0, -1), Pos(0, 1))

    /** Fills every EMPTY cell of [board] with a random gem that creates no run of 3 with what is already there. */
    fun fillEmpty(board: GemBoard, rng: GemRng, kinds: Int) {
        for (r in 0 until board.rows) for (c in 0 until board.cols) {
            if (board[r, c].type != PieceType.EMPTY) continue
            val allowed = ArrayList<Int>(kinds)
            for (k in 0 until kinds) {
                val left = c >= 2 && board[r, c - 1].matchKind == k && board[r, c - 2].matchKind == k
                val up = r >= 2 && board[r - 1, c].matchKind == k && board[r - 2, c].matchKind == k
                if (!left && !up) allowed.add(k)
            }
            board[r, c] = Cell.gem(allowed[rng.nextInt(allowed.size)])
        }
    }

    /**
     * Deals the gems onto [proto] (obstacles already placed, plain gem cells EMPTY; ice set). Retries until a legal
     * move exists, then falls back to forcing one. Returns false only for a layout that cannot hold a legal move.
     */
    fun deal(proto: GemBoard, rng: GemRng, kinds: Int, rules: ModeRules): Boolean {
        val gemCells = proto.allPositions().filter { proto[it].type == PieceType.EMPTY }
        for (attempt in 0 until 200) {
            for (p in gemCells) proto[p] = Cell.EMPTY
            fillEmpty(proto, rng, kinds)
            if (!GemMatcher.hasAny(proto) && GemMoves.hasLegal(proto, rules)) return true
        }
        return forceMove(proto, rng, kinds, rules)
    }

    /**
     * Makes a legal move by writing "X X Y X" (or its column form) into a window of plain gems, keeping the board
     * free of matches. Restores the board and returns false when no window works.
     */
    fun forceMove(board: GemBoard, rng: GemRng, kinds: Int, rules: ModeRules): Boolean {
        val plain = { p: Pos -> board.inside(p) && board[p].type == PieceType.GEM && !board[p].locked && board[p].special == SpecialKind.NONE }
        val start = if (board.rows * board.cols > 0) rng.nextInt(board.rows * board.cols) else 0
        val total = board.rows * board.cols
        for (off in 0 until total) {
            val i = (start + off) % total
            val r = i / board.cols
            val c = i % board.cols
            for (horizontal in listOf(true, false)) {
                val win = List(4) { if (horizontal) Pos(r, c + it) else Pos(r + it, c) }
                if (!win.all(plain)) continue
                val saved = win.map { board[it] }
                for (x in 0 until kinds) for (y in 0 until kinds) {
                    if (x == y) continue
                    board[win[0]] = Cell.gem(x); board[win[1]] = Cell.gem(x); board[win[2]] = Cell.gem(y); board[win[3]] = Cell.gem(x)
                    if (!GemMatcher.hasAny(board) && GemMoves.hasLegal(board, rules)) return true
                }
                win.indices.forEach { board[win[it]] = saved[it] }
            }
        }
        return false
    }
}
