package com.gamesuite.games.backgammon

/**
 * One checker move using ONE die.
 *
 * [from] is a board index 0..23 or [BAR]; [to] is a board index 0..23 or [OFF]. Board indices are
 * absolute and shared by both sides: index `i` is White's point number `i + 1` and Black's point
 * number `24 - i` (see [BackgammonRules.pointNumber]). [hit] is true when the move lands on, and
 * sends to the bar, a lone enemy checker (a "blot").
 */
data class BackgammonMove(val from: Int, val to: Int, val die: Int, val hit: Boolean = false) {
    val fromBar: Boolean get() = from == BAR
    val bearsOff: Boolean get() = to == OFF

    companion object {
        /** [from] value of a checker entering from the bar. */
        const val BAR = -1

        /** [to] value of a checker being borne off. */
        const val OFF = -2
    }
}

/** A complete legal turn: the ordered [moves] and the position they leave behind. */
class BackgammonTurn(val moves: List<BackgammonMove>, val result: BackgammonBoard)

/** How a finished game scores. Single-game scoring only: the doubling cube is out of scope. */
enum class BackgammonResultKind(val points: Int) {
    /** The loser has borne off at least one checker. */
    NORMAL(1),

    /** The loser has borne off none (a gammon). */
    GAMMON(2),

    /** A gammon where the loser also still has a checker on the bar or in the winner's home board. */
    BACKGAMMON(3)
}

/**
 * A backgammon position: 24 points, the bar and the borne-off trays for two sides. Immutable.
 *
 * Side 0 ("White") and side 1 ("Black") are the two roster seats. Checkers are stored as a SIGNED
 * count per point (positive = side 0, negative = side 1), so a point can never hold both sides.
 * Side 0 moves from high indices to low and bears off past index 0 (its home board is indices 0..5);
 * side 1 moves from low to high and bears off past index 23 (home 18..23).
 */
class BackgammonBoard private constructor(
    private val pts: IntArray,
    private val barCounts: IntArray,
    private val offCounts: IntArray
) {
    /** Signed checker count at [index]: positive for side 0, negative for side 1. */
    fun signed(index: Int): Int = pts[index]

    /** Checkers of [side] on point [index] (0 when the point is empty or held by the other side). */
    fun count(side: Int, index: Int): Int {
        val v = pts[index]
        return if (side == 0) (if (v > 0) v else 0) else (if (v < 0) -v else 0)
    }

    fun bar(side: Int): Int = barCounts[side]
    fun off(side: Int): Int = offCounts[side]

    /** Every checker of [side]: on points, on the bar and borne off. */
    fun total(side: Int): Int {
        var n = barCounts[side] + offCounts[side]
        for (i in 0 until BackgammonRules.POINTS) n += count(side, i)
        return n
    }

    fun pointsList(): List<Int> = pts.toList()
    fun barList(): List<Int> = barCounts.toList()
    fun offList(): List<Int> = offCounts.toList()

    /** Total pips [side] still has to travel (a checker on the bar counts 25). */
    fun pips(side: Int): Int {
        var n = barCounts[side] * 25
        for (i in 0 until BackgammonRules.POINTS) n += count(side, i) * BackgammonRules.pointNumber(side, i)
        return n
    }

    /** Returns the position after [side] plays [move]; a lone enemy checker on the target is hit. */
    fun apply(side: Int, move: BackgammonMove): BackgammonBoard {
        val p = pts.copyOf()
        val b = barCounts.copyOf()
        val o = offCounts.copyOf()
        val sign = if (side == 0) 1 else -1
        if (move.from == BackgammonMove.BAR) b[side] -= 1 else p[move.from] -= sign
        if (move.to == BackgammonMove.OFF) {
            o[side] += 1
        } else {
            if (p[move.to] == -sign) {
                p[move.to] = 0
                b[1 - side] += 1
            }
            p[move.to] += sign
        }
        return BackgammonBoard(p, b, o)
    }

    /** A compact identity string used to de-duplicate positions during search. */
    fun key(): String {
        val chars = CharArray(BackgammonRules.POINTS + 4)
        for (i in 0 until BackgammonRules.POINTS) chars[i] = (pts[i] + 40).toChar()
        chars[BackgammonRules.POINTS] = (barCounts[0] + 40).toChar()
        chars[BackgammonRules.POINTS + 1] = (barCounts[1] + 40).toChar()
        chars[BackgammonRules.POINTS + 2] = (offCounts[0] + 40).toChar()
        chars[BackgammonRules.POINTS + 3] = (offCounts[1] + 40).toChar()
        return String(chars)
    }

    override fun equals(other: Any?): Boolean =
        other is BackgammonBoard &&
            pts.contentEquals(other.pts) &&
            barCounts.contentEquals(other.barCounts) &&
            offCounts.contentEquals(other.offCounts)

    override fun hashCode(): Int =
        31 * (31 * pts.contentHashCode() + barCounts.contentHashCode()) + offCounts.contentHashCode()

    override fun toString(): String = "BackgammonBoard(points=${pts.toList()}, bar=${barCounts.toList()}, off=${offCounts.toList()})"

    companion object {
        /** The standard opening setup: 15 checkers a side on the 24, 13, 8 and 6 points (own numbering). */
        fun initial(): BackgammonBoard = custom(
            white = mapOf(24 to 2, 13 to 5, 8 to 3, 6 to 5),
            black = mapOf(24 to 2, 13 to 5, 8 to 3, 6 to 5)
        )

        /**
         * Builds a position from per-side maps of OWN point number (1..24, 24 = farthest from home)
         * to checker count. The counts are not required to total 15: this is the fixture builder
         * (tests, hint planning). A point claimed by both sides is rejected.
         */
        fun custom(
            white: Map<Int, Int> = emptyMap(),
            black: Map<Int, Int> = emptyMap(),
            whiteBar: Int = 0,
            blackBar: Int = 0,
            whiteOff: Int = 0,
            blackOff: Int = 0
        ): BackgammonBoard {
            val p = IntArray(BackgammonRules.POINTS)
            for ((n, c) in white) {
                require(n in 1..24 && c >= 0) { "bad white point $n x $c" }
                p[BackgammonRules.indexOf(0, n)] += c
            }
            for ((n, c) in black) {
                require(n in 1..24 && c >= 0) { "bad black point $n x $c" }
                val i = BackgammonRules.indexOf(1, n)
                require(p[i] <= 0) { "point $n of black collides with a white point" }
                p[i] -= c
            }
            return BackgammonBoard(p, intArrayOf(whiteBar, blackBar), intArrayOf(whiteOff, blackOff))
        }

        /**
         * Validates and rebuilds a position from its stored parts (a saved game): 24 points, two
         * bar counts, two off counts, no negative count, and exactly 15 checkers per side. Null for
         * anything else, so a corrupt save can never become a position.
         */
        fun fromParts(points: List<Int>, bar: List<Int>, off: List<Int>): BackgammonBoard? {
            if (points.size != BackgammonRules.POINTS || bar.size != 2 || off.size != 2) return null
            if (bar.any { it < 0 || it > BackgammonRules.CHECKERS } || off.any { it < 0 || it > BackgammonRules.CHECKERS }) return null
            if (points.any { it > BackgammonRules.CHECKERS || it < -BackgammonRules.CHECKERS }) return null
            val board = BackgammonBoard(points.toIntArray(), bar.toIntArray(), off.toIntArray())
            if (board.total(0) != BackgammonRules.CHECKERS || board.total(1) != BackgammonRules.CHECKERS) return null
            return board
        }
    }
}

/**
 * The rules of backgammon as pure functions over a [BackgammonBoard]: movement, blocking, hitting,
 * entering from the bar, bearing off, the "use as many dice as possible / the larger die if only
 * one can be played" rules, turn generation and scoring.
 *
 * SCOPE: single-game scoring only (normal 1 / gammon 2 / backgammon 3). The doubling cube,
 * Crawford and Jacoby rules are deliberately out of scope.
 */
object BackgammonRules {
    const val POINTS = 24
    const val CHECKERS = 15

    /** Index step of one pip for [side]: side 0 moves down the indices, side 1 up. */
    fun direction(side: Int): Int = if (side == 0) -1 else 1

    /** [side]'s own number (1 = next to bearing off, 24 = farthest) for absolute point [index]. */
    fun pointNumber(side: Int, index: Int): Int = if (side == 0) index + 1 else POINTS - index

    /** The absolute index of [side]'s own point [pointNumber]. */
    fun indexOf(side: Int, pointNumber: Int): Int = if (side == 0) pointNumber - 1 else POINTS - pointNumber

    /** True when absolute point [index] is in [side]'s home board (own points 1..6). */
    fun isHome(side: Int, index: Int): Boolean = pointNumber(side, index) <= 6

    /** True when every checker of [side] is in its home board or borne off and none is on the bar. */
    fun allHome(board: BackgammonBoard, side: Int): Boolean {
        if (board.bar(side) > 0) return false
        for (i in 0 until POINTS) if (board.count(side, i) > 0 && !isHome(side, i)) return false
        return true
    }

    fun gameOver(board: BackgammonBoard): Boolean = winner(board) != null

    /** The side with all 15 checkers borne off, or null while the game goes on. */
    fun winner(board: BackgammonBoard): Int? = when {
        board.off(0) >= CHECKERS -> 0
        board.off(1) >= CHECKERS -> 1
        else -> null
    }

    /** How the finished game scores for [winner]: normal, gammon or backgammon. */
    fun resultKind(board: BackgammonBoard, winner: Int): BackgammonResultKind {
        val loser = 1 - winner
        if (board.off(loser) > 0) return BackgammonResultKind.NORMAL
        var behind = board.bar(loser) > 0
        if (!behind) {
            for (i in 0 until POINTS) if (isHome(winner, i) && board.count(loser, i) > 0) { behind = true; break }
        }
        return if (behind) BackgammonResultKind.BACKGAMMON else BackgammonResultKind.GAMMON
    }

    /**
     * Every single move [side] could make with one [die], ignoring the "use all dice" rules: a
     * checker on the bar must enter first; a point holding two or more enemy checkers is blocked; a
     * lone enemy checker is hit; bearing off needs every checker home, and a die larger than the
     * checker's point number bears it off only when no checker is farther back.
     */
    fun singleMoves(board: BackgammonBoard, side: Int, die: Int): List<BackgammonMove> {
        val opp = 1 - side
        val dir = direction(side)
        val out = ArrayList<BackgammonMove>(8)
        if (board.bar(side) > 0) {
            val entry = if (side == 0) POINTS else -1
            val target = entry + dir * die
            if (target in 0 until POINTS) {
                val enemy = board.count(opp, target)
                if (enemy <= 1) out.add(BackgammonMove(BackgammonMove.BAR, target, die, enemy == 1))
            }
            return out
        }
        val canOff = allHome(board, side)
        var farthest = 0
        if (canOff) {
            for (i in 0 until POINTS) {
                if (board.count(side, i) > 0) {
                    val n = pointNumber(side, i)
                    if (n > farthest) farthest = n
                }
            }
        }
        for (i in 0 until POINTS) {
            if (board.count(side, i) == 0) continue
            val t = i + dir * die
            if (t in 0 until POINTS) {
                val enemy = board.count(opp, t)
                if (enemy <= 1) out.add(BackgammonMove(i, t, die, enemy == 1))
            } else if (canOff) {
                val n = pointNumber(side, i)
                if (die == n || (die > n && n == farthest)) out.add(BackgammonMove(i, BackgammonMove.OFF, die))
            }
        }
        return out
    }

    private fun without(dice: List<Int>, die: Int): List<Int> {
        val l = dice.toMutableList()
        l.remove(die)
        return l
    }

    private fun diceKey(dice: List<Int>): String = dice.sorted().joinToString("")

    /** The most dice that can still be played from [board] with [rem] (memoised on position + dice). */
    private fun maxDepth(board: BackgammonBoard, side: Int, rem: List<Int>, memo: HashMap<String, Int>): Int {
        if (rem.isEmpty()) return 0
        val key = board.key() + ":" + diceKey(rem)
        memo[key]?.let { return it }
        var best = 0
        search@ for (die in rem.distinct()) {
            val rest = without(rem, die)
            for (m in singleMoves(board, side, die)) {
                val d = 1 + maxDepth(board.apply(side, m), side, rest, memo)
                if (d > best) best = d
                if (best == rem.size) break@search
            }
        }
        memo[key] = best
        return best
    }

    /**
     * The moves [side] may play NEXT with the dice still unused in [remaining]: a move is legal only
     * if some continuation still uses as many dice as any other line of play could, and when only one
     * die of a non-double roll can be played at all, it must be the larger one. This is what makes
     * a partly played turn always completable, so the UI can offer one checker move at a time.
     * Empty when nothing can be played.
     */
    fun legalMoves(board: BackgammonBoard, side: Int, remaining: List<Int>): List<BackgammonMove> {
        if (remaining.isEmpty()) return emptyList()
        val memo = HashMap<String, Int>()
        val target = maxDepth(board, side, remaining, memo)
        if (target == 0) return emptyList()
        val out = ArrayList<BackgammonMove>()
        for (die in remaining.distinct()) {
            val rest = without(remaining, die)
            for (m in singleMoves(board, side, die)) {
                if (1 + maxDepth(board.apply(side, m), side, rest, memo) == target) out.add(m)
            }
        }
        if (target == 1 && remaining.size == 2 && remaining[0] != remaining[1]) {
            val high = maxOf(remaining[0], remaining[1])
            if (out.any { it.die == high }) return out.filter { it.die == high }
        }
        return out
    }

    /**
     * Every complete legal turn for [side] with the dice in [remaining], one per distinct resulting
     * position (different move orders reaching the same position collapse into the first found).
     * Empty when no die can be played at all.
     */
    fun generateTurns(board: BackgammonBoard, side: Int, remaining: List<Int>): List<BackgammonTurn> {
        val leaves = ArrayList<Pair<List<BackgammonMove>, BackgammonBoard>>()
        val seen = HashSet<String>()
        val path = ArrayList<BackgammonMove>()

        fun dfs(b: BackgammonBoard, rem: List<Int>) {
            if (rem.isNotEmpty() && !seen.add(b.key() + ":" + diceKey(rem))) return
            var any = false
            for (die in rem.distinct()) {
                val rest = without(rem, die)
                for (m in singleMoves(b, side, die)) {
                    any = true
                    path.add(m)
                    dfs(b.apply(side, m), rest)
                    path.removeAt(path.size - 1)
                }
            }
            if (!any) leaves.add(path.toList() to b)
        }

        dfs(board, remaining)
        val longest = leaves.maxOfOrNull { it.first.size } ?: 0
        if (longest == 0) return emptyList()
        var best = leaves.filter { it.first.size == longest }
        if (longest == 1 && remaining.size == 2 && remaining[0] != remaining[1]) {
            val high = maxOf(remaining[0], remaining[1])
            if (best.any { it.first[0].die == high }) best = best.filter { it.first[0].die == high }
        }
        val unique = LinkedHashMap<String, BackgammonTurn>()
        for ((moves, result) in best) unique.getOrPut(result.key()) { BackgammonTurn(moves, result) }
        return unique.values.toList()
    }
}
