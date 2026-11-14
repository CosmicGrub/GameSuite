package com.gamesuite.games.bumprun

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * The rules of Bump and Run as a pure engine: no Compose, no clock, no global randomness. The only source of
 * chance is the seed given to [newBoard] (shuffle and reshuffles); every other call is a deterministic function of
 * the state, so a seeded board replays exactly.
 */
interface BumpRunEngine {
    /** A fresh board: all pawns in the Nest (a [helperSeats] seat starts with pawn 0 on its exit square and 3 do-overs). */
    fun newBoard(
        players: List<BumpRunPlayer>,
        rules: BumpRunRules,
        seed: Long,
        helperSeats: Set<Int>,
        starter: Int
    ): BumpRunState

    /** Why a draw would do nothing right now, or NONE. */
    fun explainDraw(state: BumpRunState): BumpRunReject

    /** The seat on turn draws the top card (reshuffling the discards first if the pile is empty). */
    fun draw(state: BumpRunState): BumpRunState

    /** Every legal play for the card in play (empty = no move). Pure and cheap (budget 0.2 ms). */
    fun legalMoves(state: BumpRunState): List<BumpMove>

    /** Why [move] is not legal right now, or NONE. */
    fun explainMove(state: BumpRunState, move: BumpMove): BumpRunReject

    /** Plays [move], including any pad hop, bump and win check, and passes the turn (or draws again after Again). */
    fun play(state: BumpRunState, move: BumpMove): BumpRunState

    /** Spends a do-over: the card in play is discarded and the next one drawn. */
    fun doOver(state: BumpRunState): BumpRunState

    /** Every broken invariant of [state] (empty when consistent); used by tests, restore and the future network host. */
    fun validate(state: BumpRunState): List<String>
}

/**
 * The scaffold engine. [newBoard] builds a real, valid opening position; every other method returns a safe value
 * (the state unchanged, NONE or an empty list). Phase 0 of docs/design/boardgames/bump-run.md replaces it.
 */
class StubBumpRunEngine : BumpRunEngine {
    override fun newBoard(
        players: List<BumpRunPlayer>,
        rules: BumpRunRules,
        seed: Long,
        helperSeats: Set<Int>,
        starter: Int
    ): BumpRunState {
        val n = players.size
        val per = rules.pawnsPerSeat
        val pawns = MutableList(n * per) { BumpRunBoard.NEST }
        for (seat in helperSeats) if (seat in 0 until n) pawns[seat * per] = 0
        return BumpRunState(
            rules = rules,
            players = players,
            sides = BumpRunBoard.sidesFor(n),
            pawns = pawns,
            shields = List(n * per) { false },
            doOvers = List(n) { if (it in helperSeats) HELPER_DO_OVERS else 0 },
            currentPlayerIndex = if (n > 0) starter.coerceIn(0, n - 1) else 0,
            phase = BumpPhase.AWAIT_DRAW,
            pile = List(BumpRunDeck.SIZE) { it }.shuffled(Random(seed)),
            discard = emptyList(),
            bumpsMade = List(n) { 0 },
            bumpsTaken = List(n) { 0 }
        )
    }

    override fun explainDraw(state: BumpRunState): BumpRunReject = when {
        state.over -> BumpRunReject.OVER
        state.phase == BumpPhase.AWAIT_CHOICE -> BumpRunReject.DECIDE_FIRST
        else -> BumpRunReject.NONE
    }

    override fun draw(state: BumpRunState): BumpRunState = state

    override fun legalMoves(state: BumpRunState): List<BumpMove> = emptyList()

    override fun explainMove(state: BumpRunState, move: BumpMove): BumpRunReject = when {
        state.over -> BumpRunReject.OVER
        state.phase == BumpPhase.AWAIT_DRAW -> BumpRunReject.DRAW_FIRST
        else -> BumpRunReject.CANNOT_MOVE
    }

    override fun play(state: BumpRunState, move: BumpMove): BumpRunState = state

    override fun doOver(state: BumpRunState): BumpRunState = state

    override fun validate(state: BumpRunState): List<String> = emptyList()

    companion object {
        const val HELPER_DO_OVERS = 3
    }
}

/**
 * The computer's decisions. The scaffold takes the first legal move and never spends a do-over.
 *
 * Intended behaviour per level is specified in docs/design/boardgames/bump-run.md ("Bot behaviour"): EASY mostly
 * greedy with 35% random moves and no danger sense; MEDIUM scores every move (home > bump > exit > progress, minus
 * exposure); HARD adds card counting over the public discards and one ply of lookahead. Pure functions of
 * (state, legal moves, a Random derived from the state), so a bot game is deterministic.
 */
object BumpRunBot {
    fun choose(state: BumpRunState, legal: List<BumpMove>, difficulty: CpuDifficulty, random: Random): BumpMove? =
        legal.firstOrNull()

    fun wantsDoOver(state: BumpRunState, legal: List<BumpMove>, difficulty: CpuDifficulty, random: Random): Boolean = false
}
