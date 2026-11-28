package com.gamesuite.games.bumprun

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * The computer's decisions (see docs/design/boardgames/bump-run.md, "Bot behaviour"). Pure functions of
 * (state, legal moves, a Random derived from the state), so a bot-only board is deterministic. Every level only
 * ever chooses a legal move and never touches the deck.
 *
 *  - EASY: 35% a random legal move, otherwise the largest progress gain (exit 25, Home 100). No sense of danger,
 *    never spends a do-over.
 *  - MEDIUM: scores every move (Home, bump, exit, lane, progress, swap, shield) minus the exposure of the landing
 *    square to a rival's exact hop (flat card odds); uses a do-over when the best score is under 12.
 *  - HARD: Medium scores, but exposure uses the exact remaining card mix (the pile's contents are public: 54 minus
 *    discards minus the card in play), covers every own ring pawn, adds the Chase threat, and spends a do-over
 *    only when the expected best score of a redraw beats the current best by more than 3. (No second-ply search:
 *    the exact-odds exposure is the whole of Hard's edge; see integration-notes.md.)
 */
object BumpRunBot {
    private val engine = StandardBumpRunEngine()

    /** Copies of each hop distance in the full deck, for flat odds. */
    private val hopCopies: Map<Int, Int> = BumpRunDeck.COMPOSITION
        .filter { it.first == BumpCardKind.HOP }.associate { it.second to it.third }

    fun choose(state: BumpRunState, legal: List<BumpMove>, difficulty: CpuDifficulty, random: Random): BumpMove? {
        if (legal.isEmpty()) return null
        return when (difficulty) {
            CpuDifficulty.EASY ->
                if (random.nextDouble() < 0.35) legal[random.nextInt(legal.size)]
                else legal.maxByOrNull { easyScore(state, it) + random.nextDouble() * 0.01 }
            CpuDifficulty.MEDIUM -> legal.maxByOrNull { score(state, it, false) + random.nextDouble() }
            else -> legal.maxByOrNull { score(state, it, true) + random.nextDouble() }
        }
    }

    fun wantsDoOver(state: BumpRunState, legal: List<BumpMove>, difficulty: CpuDifficulty, random: Random): Boolean {
        if (difficulty == CpuDifficulty.EASY) return false
        if (state.doOvers[state.currentPlayerIndex] <= 0) return false
        if (legal.isEmpty()) return true
        val best = legal.maxOf { score(state, it, difficulty == CpuDifficulty.HARD) }
        if (difficulty != CpuDifficulty.HARD) return best < 12.0
        if (state.pile.isEmpty()) return false
        return expectedRedraw(state) > best + 3.0
    }

    private fun expectedRedraw(state: BumpRunState): Double {
        val groups = state.pile.groupBy { id -> BumpRunDeck.byId(id)!!.let { Triple(it.kind, it.value, 0) } }
        var total = 0.0
        for ((_, ids) in groups) {
            val probe = state.copy(card = ids.first(), phase = BumpPhase.AWAIT_CHOICE)
            val moves = engine.legalMoves(probe)
            val kind = BumpRunDeck.byId(ids.first())!!.kind
            val best = when {
                kind == BumpCardKind.AGAIN -> 5.0
                moves.isEmpty() -> 0.0
                else -> moves.maxOf { score(probe, it, true) }
            }
            total += best * ids.size
        }
        return total / state.pile.size
    }

    private fun team(s: BumpRunState, seat: Int) = if (s.rules.teams) seat % 2 else seat

    private fun mine(s: BumpRunState): List<Int> {
        val per = s.rules.pawnsPerSeat
        val key = team(s, s.currentPlayerIndex)
        return s.pawns.indices.filter { team(s, it / per) == key }
    }

    private fun easyScore(state: BumpRunState, move: BumpMove): Double {
        if (move.kind == BumpMoveKind.EXIT) return 25.0
        if (move.kind == BumpMoveKind.STEP || move.kind == BumpMoveKind.CHASE) {
            return if (move.to == BumpRunBoard.HOME) 100.0 else (move.to - state.pawns[move.pawn]).toDouble()
        }
        return 1.0
    }

    private fun score(state: BumpRunState, move: BumpMove, hard: Boolean): Double {
        val after = engine.play(state, move)
        val seat = state.currentPlayerIndex
        val own = mine(state)
        val per = state.rules.pawnsPerSeat
        var gain = 0.0
        var exitBonus = 0.0
        var laneBonus = 0.0
        var homeBonus = 0.0
        for (p in own) {
            val b = state.pawns[p]
            val a = after.pawns[p]
            if (b < 0 && a >= 0) { exitBonus += 25.0; gain += a } else gain += a - b
            if (b < BumpRunBoard.LANE_START && a >= BumpRunBoard.LANE_START) laneBonus += 15.0
            if (b != BumpRunBoard.HOME && a == BumpRunBoard.HOME) homeBonus += 100.0
        }
        var rivalLoss = 0.0
        for (p in state.pawns.indices) if (team(state, p / per) != team(state, seat)) rivalLoss += state.pawns[p] - after.pawns[p]
        var total = gain + exitBonus + laneBonus + homeBonus
        val bumped = after.bumpsMade[seat] > state.bumpsMade[seat]
        if (move.kind == BumpMoveKind.SWAP) total += 10.0 + 0.8 * rivalLoss
        else if (bumped) total += 30.0 + rivalLoss
        if (move.kind == BumpMoveKind.SHIELD) total = 5.0 + 0.2 * state.pawns[move.pawn]
        total -= if (hard) exposureAll(after, state, own) else exposure(after, state, move.pawn)
        return total
    }

    private fun hopOdds(state: BumpRunState, hard: Boolean, d: Int): Double {
        if (!hard) return (hopCopies[d] ?: 0) / BumpRunDeck.SIZE.toDouble()
        if (state.pile.isEmpty()) return 0.0
        val n = state.pile.count { id -> BumpRunDeck.byId(id)!!.let { it.kind == BumpCardKind.HOP && it.value == d } }
        return n / state.pile.size.toDouble()
    }

    private fun exposure(after: BumpRunState, before: BumpRunState, pawn: Int): Double =
        pawnExposure(after, before.currentPlayerIndex, pawn, false)

    private fun exposureAll(after: BumpRunState, before: BumpRunState, own: List<Int>): Double =
        own.sumOf { pawnExposure(after, before.currentPlayerIndex, it, true) }

    /** Expected progress lost: odds that an unshielded rival lands on this pawn next turn, times what it is worth. */
    private fun pawnExposure(s: BumpRunState, seat: Int, pawn: Int, hard: Boolean): Double {
        if (pawn < 0 || pawn >= s.pawns.size || s.shields[pawn]) return 0.0
        val per = s.rules.pawnsPerSeat
        val g = s.pawns[pawn]
        val sq = BumpRunBoard.squareOf(s.sides[pawn / per], g) ?: return 0.0
        var e = 0.0
        val chaseOdds = if (hard && s.pile.isNotEmpty()) s.pile.count { BumpRunDeck.byId(it)!!.kind == BumpCardKind.CHASE } / s.pile.size.toDouble() else 0.0
        var nearestBehind = Int.MAX_VALUE
        for (r in s.pawns.indices) {
            if (team(s, r / per) == team(s, seat)) continue
            val rsq = BumpRunBoard.squareOf(s.sides[r / per], s.pawns[r]) ?: continue
            val d = ((sq - rsq) % BumpRunBoard.LOOP + BumpRunBoard.LOOP) % BumpRunBoard.LOOP
            if (d in 1..10) e += hopOdds(s, hard, d) * (g + 10)
            if (d in 1..BumpRunBoard.CHASE_RANGE && d < nearestBehind) nearestBehind = d
        }
        if (nearestBehind != Int.MAX_VALUE) e += chaseOdds * (g + 10)
        return e
    }
}
