package com.gamesuite.games.shogi

import kotlin.random.Random

/**
 * Pure Shogi / Minishogi rules and search. No Android, no Compose: everything here can run on any
 * thread against its own [ShogiPosition].
 *
 * Piece codes: 0 is an empty cell; otherwise `side * 16 + type` with side 0 = Sente (moves first, starts at
 * the bottom, advances toward row 0) and side 1 = Gote. Types: [PAWN]..[ROOK] (1..7, the droppable
 * set), [KING] 8, and the promoted forms 9 (tokin), 10, 11, 12 (promoted lance/knight/silver, all
 * moving as a gold), 14 (horse) and 15 (dragon). A move is one Int: `from | to shl 8 | promote shl 16`;
 * a drop has `from = cellCount + type`.
 */
object ShogiPiece {
    const val PAWN = 1
    const val LANCE = 2
    const val KNIGHT = 3
    const val SILVER = 4
    const val GOLD = 5
    const val BISHOP = 6
    const val ROOK = 7
    const val KING = 8
    const val PROMOTED_OFFSET = 8
    const val HORSE = 14
    const val DRAGON = 15

    fun side(code: Int): Int = code shr 4
    fun type(code: Int): Int = code and 15
    fun code(side: Int, type: Int): Int = side * 16 + type
    fun canPromote(type: Int): Boolean = type in 1..7 && type != GOLD
    fun isPromoted(type: Int): Boolean = type > KING
    fun promote(type: Int): Int = type + PROMOTED_OFFSET
    fun base(type: Int): Int = if (type > KING) type - PROMOTED_OFFSET else type

    /** Piece types that can be held in hand (and dropped). */
    val HAND_TYPES: List<Int> = listOf(ROOK, BISHOP, GOLD, SILVER, KNIGHT, LANCE, PAWN)
}

enum class ShogiVariant(val n: Int, val gameId: String) {
    STANDARD(9, "shogi"),
    MINI(5, "mini-shogi");

    companion object {
        fun fromGameId(id: String): ShogiVariant = values().firstOrNull { it.gameId == id } ?: STANDARD
    }
}

object ShogiMove {
    fun make(from: Int, to: Int, promote: Boolean = false): Int = from or (to shl 8) or (if (promote) 1 shl 16 else 0)
    fun from(m: Int): Int = m and 0xFF
    fun to(m: Int): Int = (m shr 8) and 0xFF
    fun promote(m: Int): Boolean = (m shr 16) and 1 == 1
}

/** A mutable position. The search mutates one of these with make/unmake; the game keeps its own. */
class ShogiPosition(val n: Int) {
    val board = IntArray(n * n)

    /** Hand counts: index `side * 7 + (type - 1)` for types 1..7. */
    val hands = IntArray(14)
    var side = 0
    val kingSq = intArrayOf(-1, -1)

    fun copy(): ShogiPosition {
        val p = ShogiPosition(n)
        board.copyInto(p.board)
        hands.copyInto(p.hands)
        p.side = side
        p.kingSq[0] = kingSq[0]
        p.kingSq[1] = kingSq[1]
        return p
    }

    fun handCount(side: Int, type: Int): Int = hands[side * 7 + type - 1]

    fun recomputeKings() {
        kingSq[0] = -1
        kingSq[1] = -1
        for (i in board.indices) {
            val c = board[i]
            if (c != 0 && ShogiPiece.type(c) == ShogiPiece.KING) kingSq[ShogiPiece.side(c)] = i
        }
    }

    /** A stable string identifying board, hands and side to move (for repetition). */
    fun key(): String {
        val sb = StringBuilder(board.size * 2 + 20)
        for (c in board) sb.append(Char('A'.code + c))
        sb.append('|')
        for (h in hands) sb.append(Char('0'.code + h))
        sb.append(side)
        return sb.toString()
    }

    fun keyHash(): Long {
        var h = -0x340d631b7bdddcdbL
        for (ch in key()) {
            h = h xor ch.code.toLong()
            h *= 0x100000001b3L
        }
        return h
    }
}

class MoveList(capacity: Int = 700) {
    var a = IntArray(capacity)
    var size = 0
    fun add(m: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = m
    }
    operator fun get(i: Int) = a[i]
    fun toList(): List<Int> = a.asList().subList(0, size).toList()
}

class ShogiRules private constructor(val variant: ShogiVariant) {
    val n = variant.n
    val cellCount = n * n

    /** Rows (for side 0) counted as the promotion zone: 3 on 9x9, 1 on 5x5. */
    private val zoneDepth = if (n == 9) 3 else 1

    private val dr = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
    private val dc = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)

    private fun dirIndex(r: Int, c: Int): Int = (0 until 8).first { dr[it] == r && dc[it] == c }

    /** `[side][type]` direction bitmasks over [dr]/[dc]: one step, and any-distance slides. */
    private val stepMask = Array(2) { IntArray(16) }
    private val slideMask = Array(2) { IntArray(16) }

    init {
        fun mask(vararg v: Pair<Int, Int>): Int = v.fold(0) { acc, (r, c) -> acc or (1 shl dirIndex(r, c)) }
        val diag = arrayOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
        val orth = arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        val goldSteps = mask(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to 0)
        val s = Array(16) { 0 }
        val sl = Array(16) { 0 }
        s[ShogiPiece.PAWN] = mask(-1 to 0)
        sl[ShogiPiece.LANCE] = mask(-1 to 0)
        s[ShogiPiece.SILVER] = mask(-1 to -1, -1 to 0, -1 to 1, 1 to -1, 1 to 1)
        s[ShogiPiece.GOLD] = goldSteps
        for (t in 9..12) s[t] = goldSteps
        sl[ShogiPiece.BISHOP] = mask(*diag)
        sl[ShogiPiece.ROOK] = mask(*orth)
        sl[ShogiPiece.HORSE] = mask(*diag)
        s[ShogiPiece.HORSE] = mask(*orth)
        sl[ShogiPiece.DRAGON] = mask(*orth)
        s[ShogiPiece.DRAGON] = mask(*diag)
        s[ShogiPiece.KING] = mask(*diag, *orth)
        for (t in 0 until 16) {
            stepMask[0][t] = s[t]
            slideMask[0][t] = sl[t]
            stepMask[1][t] = flipRows(s[t])
            slideMask[1][t] = flipRows(sl[t])
        }
    }

    private fun flipRows(m: Int): Int {
        var out = 0
        for (i in 0 until 8) if (m and (1 shl i) != 0) out = out or (1 shl dirIndex(-dr[i], dc[i]))
        return out
    }

    private fun forward(side: Int) = if (side == 0) -1 else 1

    /** True when [row] lies in [side]'s promotion zone (the far rows for that side). */
    fun inZone(side: Int, row: Int): Boolean = if (side == 0) row < zoneDepth else row >= n - zoneDepth

    /** Rows from the far edge: 0 is the last rank. Used for "piece would have no move" checks. */
    private fun rowsFromFar(side: Int, row: Int): Int = if (side == 0) row else n - 1 - row

    fun startPosition(): ShogiPosition {
        val p = ShogiPosition(n)
        fun put(side: Int, row: Int, col: Int, type: Int) {
            p.board[row * n + col] = ShogiPiece.code(side, type)
        }
        if (n == 9) {
            val back = intArrayOf(
                ShogiPiece.LANCE, ShogiPiece.KNIGHT, ShogiPiece.SILVER, ShogiPiece.GOLD, ShogiPiece.KING,
                ShogiPiece.GOLD, ShogiPiece.SILVER, ShogiPiece.KNIGHT, ShogiPiece.LANCE
            )
            for (c in 0 until 9) {
                put(1, 0, c, back[c]); put(0, 8, c, back[c])
                put(1, 2, c, ShogiPiece.PAWN); put(0, 6, c, ShogiPiece.PAWN)
            }
            put(1, 1, 1, ShogiPiece.ROOK); put(1, 1, 7, ShogiPiece.BISHOP)
            put(0, 7, 7, ShogiPiece.ROOK); put(0, 7, 1, ShogiPiece.BISHOP)
        } else {
            val gote = intArrayOf(ShogiPiece.ROOK, ShogiPiece.BISHOP, ShogiPiece.SILVER, ShogiPiece.GOLD, ShogiPiece.KING)
            val sente = intArrayOf(ShogiPiece.KING, ShogiPiece.GOLD, ShogiPiece.SILVER, ShogiPiece.BISHOP, ShogiPiece.ROOK)
            for (c in 0 until 5) {
                put(1, 0, c, gote[c]); put(0, 4, c, sente[c])
            }
            put(1, 1, 4, ShogiPiece.PAWN); put(0, 3, 0, ShogiPiece.PAWN)
        }
        p.recomputeKings()
        return p
    }

    // ---- attacks ----

    /** True when any [by] piece attacks [sq] (ignoring pins: this is pure geometry). */
    fun isAttacked(p: ShogiPosition, sq: Int, by: Int): Boolean {
        val r = sq / n
        val c = sq % n
        for (i in 0 until 8) {
            var rr = r + dr[i]
            var cc = c + dc[i]
            var k = 1
            while (rr in 0 until n && cc in 0 until n) {
                val code = p.board[rr * n + cc]
                if (code != 0) {
                    if (ShogiPiece.side(code) == by) {
                        val t = ShogiPiece.type(code)
                        val opp = 7 - i
                        if (slideMask[by][t] and (1 shl opp) != 0) return true
                        if (k == 1 && stepMask[by][t] and (1 shl opp) != 0) return true
                    }
                    break
                }
                rr += dr[i]
                cc += dc[i]
                k++
            }
        }
        val f = forward(by)
        val kr = r - 2 * f
        if (kr in 0 until n) {
            val knight = ShogiPiece.code(by, ShogiPiece.KNIGHT)
            if (c - 1 >= 0 && p.board[kr * n + c - 1] == knight) return true
            if (c + 1 < n && p.board[kr * n + c + 1] == knight) return true
        }
        return false
    }

    fun inCheck(p: ShogiPosition, side: Int): Boolean {
        val k = p.kingSq[side]
        return k >= 0 && isAttacked(p, k, 1 - side)
    }

    // ---- make / unmake ----

    /** Applies [m] for the side to move. Returns the captured piece code (0 if none). */
    fun make(p: ShogiPosition, m: Int): Int {
        val from = ShogiMove.from(m)
        val to = ShogiMove.to(m)
        val side = p.side
        var captured = 0
        if (from >= cellCount) {
            val type = from - cellCount
            p.board[to] = ShogiPiece.code(side, type)
            p.hands[side * 7 + type - 1]--
        } else {
            val piece = p.board[from]
            captured = p.board[to]
            p.board[from] = 0
            p.board[to] = if (ShogiMove.promote(m)) piece + ShogiPiece.PROMOTED_OFFSET else piece
            if (captured != 0) {
                val bt = ShogiPiece.base(ShogiPiece.type(captured))
                p.hands[side * 7 + bt - 1]++
            }
            if (ShogiPiece.type(piece) == ShogiPiece.KING) p.kingSq[side] = to
        }
        p.side = 1 - side
        return captured
    }

    fun unmake(p: ShogiPosition, m: Int, captured: Int) {
        val from = ShogiMove.from(m)
        val to = ShogiMove.to(m)
        val side = 1 - p.side
        p.side = side
        if (from >= cellCount) {
            val type = from - cellCount
            p.board[to] = 0
            p.hands[side * 7 + type - 1]++
        } else {
            val piece = if (ShogiMove.promote(m)) p.board[to] - ShogiPiece.PROMOTED_OFFSET else p.board[to]
            p.board[from] = piece
            p.board[to] = captured
            if (captured != 0) {
                val bt = ShogiPiece.base(ShogiPiece.type(captured))
                p.hands[side * 7 + bt - 1]--
            }
            if (ShogiPiece.type(piece) == ShogiPiece.KING) p.kingSq[side] = from
        }
    }

    // ---- move generation ----

    /** True when a piece of [type] placed on [row] for [side] would have no move ever (so must not be dropped/left there). */
    private fun deadOnRow(side: Int, type: Int, row: Int): Boolean {
        val far = rowsFromFar(side, row)
        return when (type) {
            ShogiPiece.PAWN, ShogiPiece.LANCE -> far == 0
            ShogiPiece.KNIGHT -> far <= 1
            else -> false
        }
    }

    private fun pseudo(p: ShogiPosition, out: MoveList, capturesOnly: Boolean = false) {
        val side = p.side
        val f = forward(side)
        for (from in 0 until cellCount) {
            val code = p.board[from]
            if (code == 0 || ShogiPiece.side(code) != side) continue
            val t = ShogiPiece.type(code)
            val r = from / n
            val c = from % n
            fun emit(to: Int) {
                val target = p.board[to]
                if (capturesOnly && target == 0) return
                val tr = to / n
                if (ShogiPiece.canPromote(t) && (inZone(side, r) || inZone(side, tr))) {
                    out.add(ShogiMove.make(from, to, true))
                    if (!deadOnRow(side, t, tr)) out.add(ShogiMove.make(from, to, false))
                } else {
                    out.add(ShogiMove.make(from, to, false))
                }
            }
            val st = stepMask[side][t]
            val sl = slideMask[side][t]
            for (i in 0 until 8) {
                val bit = 1 shl i
                if ((st or sl) and bit == 0) continue
                var rr = r + dr[i]
                var cc = c + dc[i]
                var first = true
                while (rr in 0 until n && cc in 0 until n) {
                    val to = rr * n + cc
                    val target = p.board[to]
                    if (target != 0 && ShogiPiece.side(target) == side) break
                    if (first || sl and bit != 0) emit(to)
                    if (target != 0) break
                    if (sl and bit == 0) break
                    first = false
                    rr += dr[i]
                    cc += dc[i]
                }
            }
            if (t == ShogiPiece.KNIGHT) {
                val tr = r + 2 * f
                if (tr in 0 until n) {
                    if (c - 1 >= 0 && !isOwn(p, tr * n + c - 1, side)) emit(tr * n + c - 1)
                    if (c + 1 < n && !isOwn(p, tr * n + c + 1, side)) emit(tr * n + c + 1)
                }
            }
        }
        if (capturesOnly) return
        for (type in ShogiPiece.HAND_TYPES) {
            if (p.hands[side * 7 + type - 1] == 0) continue
            for (to in 0 until cellCount) {
                if (p.board[to] != 0) continue
                if (deadOnRow(side, type, to / n)) continue
                if (type == ShogiPiece.PAWN && hasPawnInFile(p, side, to % n)) continue // nifu
                out.add(ShogiMove.make(cellCount + type, to, false))
            }
        }
    }

    /** Pseudo-legal moves (may leave the own king in check, drops not yet uchifuzume-filtered). */
    fun pseudoLegal(p: ShogiPosition): MoveList = MoveList().also { pseudo(p, it) }

    private fun isOwn(p: ShogiPosition, sq: Int, side: Int): Boolean {
        val c = p.board[sq]
        return c != 0 && ShogiPiece.side(c) == side
    }

    fun hasPawnInFile(p: ShogiPosition, side: Int, col: Int): Boolean {
        val pawn = ShogiPiece.code(side, ShogiPiece.PAWN)
        for (r in 0 until n) if (p.board[r * n + col] == pawn) return true
        return false
    }

    /** Fully legal moves for the side to move (own king safe, no uchifuzume). */
    fun legalMoves(p: ShogiPosition, capturesOnly: Boolean = false): MoveList {
        val raw = MoveList()
        pseudo(p, raw, capturesOnly)
        val out = MoveList(raw.size + 1)
        val side = p.side
        for (i in 0 until raw.size) {
            val m = raw[i]
            val cap = make(p, m)
            var ok = !inCheck(p, side)
            if (ok && ShogiMove.from(m) == cellCount + ShogiPiece.PAWN && inCheck(p, 1 - side)) {
                // uchifuzume: a pawn drop that gives check may not be checkmate.
                if (!hasLegalMove(p)) ok = false
            }
            unmake(p, m, cap)
            if (ok) out.add(m)
        }
        return out
    }

    /** Whether the side to move has at least one legal move (early exit). */
    fun hasLegalMove(p: ShogiPosition): Boolean {
        val raw = MoveList()
        pseudo(p, raw)
        val side = p.side
        for (i in 0 until raw.size) {
            val m = raw[i]
            val cap = make(p, m)
            val ok = !inCheck(p, side)
            unmake(p, m, cap)
            if (ok) return true
        }
        return false
    }

    /**
     * Why a drop of [type] on [to] is not allowed, or null if it is allowed (for the side to move).
     * One of: "occupied", "nifu", "dead", "uchifuzume", "check", "nohand".
     */
    fun dropRefusal(p: ShogiPosition, type: Int, to: Int): String? {
        val side = p.side
        if (p.hands[side * 7 + type - 1] == 0) return "nohand"
        if (p.board[to] != 0) return "occupied"
        if (deadOnRow(side, type, to / n)) return "dead"
        if (type == ShogiPiece.PAWN && hasPawnInFile(p, side, to % n)) return "nifu"
        val m = ShogiMove.make(cellCount + type, to)
        val cap = make(p, m)
        var reason: String? = null
        if (inCheck(p, side)) reason = "check"
        else if (type == ShogiPiece.PAWN && inCheck(p, 1 - side) && !hasLegalMove(p)) reason = "uchifuzume"
        unmake(p, m, cap)
        return reason
    }

    fun perft(p: ShogiPosition, depth: Int): Long {
        if (depth == 0) return 1
        val moves = legalMoves(p)
        if (depth == 1) return moves.size.toLong()
        var total = 0L
        for (i in 0 until moves.size) {
            val m = moves[i]
            val cap = make(p, m)
            total += perft(p, depth - 1)
            unmake(p, m, cap)
        }
        return total
    }

    // ---- impasse (jishogi), standard board only ----

    /** The 27-point declaration: Sente needs 28 points, Gote 27, with 10+ other pieces in the enemy camp. */
    fun canDeclareImpasse(p: ShogiPosition, side: Int): Boolean {
        if (n != 9 || p.side != side) return false
        val k = p.kingSq[side]
        if (k < 0 || !inZone(side, k / n) || inCheck(p, side)) return false
        var inCamp = 0
        var points = 0
        for (i in 0 until cellCount) {
            val code = p.board[i]
            if (code == 0 || ShogiPiece.side(code) != side) continue
            val t = ShogiPiece.type(code)
            if (t == ShogiPiece.KING || !inZone(side, i / n)) continue
            inCamp++
            points += if (ShogiPiece.base(t) == ShogiPiece.BISHOP || ShogiPiece.base(t) == ShogiPiece.ROOK) 5 else 1
        }
        if (inCamp < 10) return false
        for (t in ShogiPiece.HAND_TYPES) {
            points += p.handCount(side, t) * (if (t == ShogiPiece.BISHOP || t == ShogiPiece.ROOK) 5 else 1)
        }
        return points >= (if (side == 0) 28 else 27)
    }

    // ---- evaluation and search ----

    private val value = intArrayOf(0, 100, 300, 350, 450, 500, 850, 1000, 0, 420, 480, 480, 500, 0, 1050, 1250)

    fun evaluate(p: ShogiPosition): Int {
        var score = 0 // from Sente's view
        for (i in 0 until cellCount) {
            val code = p.board[i]
            if (code == 0) continue
            val s = ShogiPiece.side(code)
            val t = ShogiPiece.type(code)
            var v = value[t]
            if (t != ShogiPiece.KING) {
                val adv = rowsFromFar(s, i / n) // closer to far rank = smaller
                v += (n - 1 - adv) * (if (t == ShogiPiece.PAWN) 4 else 1)
            } else {
                // a king wants friendly gold/silver neighbours
                val r = i / n
                val c = i % n
                for (j in 0 until 8) {
                    val rr = r + dr[j]
                    val cc = c + dc[j]
                    if (rr !in 0 until n || cc !in 0 until n) continue
                    val o = p.board[rr * n + cc]
                    if (o != 0 && ShogiPiece.side(o) == s) {
                        val ot = ShogiPiece.type(o)
                        if (ot == ShogiPiece.GOLD || ot == ShogiPiece.SILVER || (ot in 9..12)) v += 18
                    }
                }
            }
            score += if (s == 0) v else -v
        }
        for (t in ShogiPiece.HAND_TYPES) {
            score += (p.handCount(0, t) - p.handCount(1, t)) * (value[t] + value[t] / 8)
        }
        return if (p.side == 0) score else -score
    }

    private class Abort : RuntimeException(null, null, false, false)

    private inner class Search(val deadlineNanos: Long) {
        var nodes = 0L
        fun check() {
            if ((++nodes and 1023L) == 0L && System.nanoTime() > deadlineNanos) throw Abort()
        }

        fun order(p: ShogiPosition, moves: MoveList): IntArray {
            val keys = IntArray(moves.size)
            for (i in 0 until moves.size) {
                val m = moves[i]
                val from = ShogiMove.from(m)
                val to = ShogiMove.to(m)
                var k = 0
                val target = p.board[to]
                if (target != 0) k += 10 * value[ShogiPiece.type(target)] - (if (from < cellCount) value[ShogiPiece.type(p.board[from])] else 0) / 10 + 1000
                if (ShogiMove.promote(m)) k += 400
                keys[i] = k
            }
            val idx = (0 until moves.size).sortedByDescending { keys[it] }
            return IntArray(moves.size) { moves[idx[it]] }
        }

        fun negamax(p: ShogiPosition, depth: Int, alphaIn: Int, beta: Int, ply: Int): Int {
            check()
            var alpha = alphaIn
            if (depth <= 0) return quiesce(p, alpha, beta, 2)
            val moves = legalMoves(p)
            if (moves.size == 0) return -MATE + ply
            for (m in order(p, moves)) {
                val cap = make(p, m)
                val score = -negamax(p, depth - 1, -beta, -alpha, ply + 1)
                unmake(p, m, cap)
                if (score >= beta) return score
                if (score > alpha) alpha = score
            }
            return alpha
        }

        fun quiesce(p: ShogiPosition, alphaIn: Int, beta: Int, depth: Int): Int {
            check()
            var alpha = alphaIn
            val stand = evaluate(p)
            if (depth == 0 || stand >= beta) return stand
            if (stand > alpha) alpha = stand
            val moves = legalMoves(p, capturesOnly = true)
            for (m in order(p, moves)) {
                val cap = make(p, m)
                val score = -quiesce(p, -beta, -alpha, depth - 1)
                unmake(p, m, cap)
                if (score >= beta) return score
                if (score > alpha) alpha = score
            }
            return alpha
        }
    }

    /**
     * Picks a move for the side to move in [position] (not mutated; searched on a copy), or null if
     * there is none. Iterative deepening to [maxDepth] within [timeLimitMs]; the best move of the last
     * completed depth wins. With [noiseCp] > 0 (Easy) the choice is random among root moves scoring
     * within that many centipawns of the best, using [random].
     */
    fun chooseMove(
        position: ShogiPosition,
        maxDepth: Int,
        timeLimitMs: Long,
        noiseCp: Int = 0,
        random: Random = Random.Default
    ): Int? {
        val p = position.copy()
        val root = legalMoves(p)
        if (root.size == 0) return null
        if (root.size == 1) return root[0]
        val deadline = System.nanoTime() + timeLimitMs * 1_000_000L
        val search = Search(deadline)
        var scored: List<Pair<Int, Int>> = search.order(p, root).map { it to 0 }
        for (depth in 1..maxDepth) {
            try {
                val results = ArrayList<Pair<Int, Int>>()
                var alpha = -INF
                for ((m, _) in scored) {
                    val cap = make(p, m)
                    // Easy needs real scores for every root move; others thread alpha for pruning.
                    val score = if (noiseCp > 0) {
                        -search.negamax(p, depth - 1, -INF, INF, 1)
                    } else {
                        -search.negamax(p, depth - 1, -INF, -alpha, 1)
                    }
                    unmake(p, m, cap)
                    results += m to score
                    if (score > alpha) alpha = score
                }
                scored = results.sortedByDescending { it.second }
                if (alpha >= MATE - 100) break
            } catch (_: Abort) {
                // Position is in a half-searched state: rebuild from the untouched original.
                break
            }
        }
        if (noiseCp > 0) {
            val best = scored.first().second
            val pool = scored.filter { it.second >= best - noiseCp }
            return pool[random.nextInt(pool.size)].first
        }
        return scored.first().first
    }

    companion object {
        const val MATE = 100_000
        const val INF = 1_000_000
        private val cache = java.util.concurrent.ConcurrentHashMap<ShogiVariant, ShogiRules>()
        fun of(variant: ShogiVariant): ShogiRules = cache.getOrPut(variant) { ShogiRules(variant) }
    }
}
