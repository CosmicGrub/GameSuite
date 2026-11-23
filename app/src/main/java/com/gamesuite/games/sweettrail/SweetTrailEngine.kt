package com.gamesuite.games.sweettrail

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * The rules of Sweet Trail as a pure engine: no Compose, no clock, no global randomness. The only source of
 * chance is the seed given to [newBoard] (it shuffles the deck and any reshuffle); every other call is a
 * deterministic function of the state, so a seeded board replays exactly.
 */
interface SweetTrailEngine {
    /** A fresh board: pieces on the start square, a shuffled pile, [wishSeats] holding one wish each. */
    fun newBoard(
        players: List<SweetTrailPlayer>,
        rules: SweetTrailRules,
        seed: Long,
        wishSeats: Set<Int>,
        starter: Int
    ): SweetTrailState

    /** Why a draw would do nothing right now, or NONE if it will draw. */
    fun explainDraw(state: SweetTrailState): SweetTrailReject

    /** The seat on turn draws (or, when stuck, pulls free and the turn passes). */
    fun draw(state: SweetTrailState): SweetTrailState

    /** Why a keep ([wish] false) or wish ([wish] true) would do nothing right now, or NONE. */
    fun explainDecision(state: SweetTrailState, wish: Boolean): SweetTrailReject

    /** Resolves a pending card: keep it (the piece moves) or spend the wish (new card, which must be kept). */
    fun decide(state: SweetTrailState, wish: Boolean): SweetTrailState

    /** Every broken invariant of [state] (empty when consistent); used by tests, restore and the future network host. */
    fun validate(state: SweetTrailState): List<String>
}

/**
 * The scaffold engine. [newBoard] builds a real, valid opening position; every other method returns a safe value
 * (the state unchanged, NONE or an empty list). Phase 0 of docs/design/boardgames/sweet-trail.md replaces this
 * with the real rules; nothing else in the package needs to change.
 */
class StubSweetTrailEngine : SweetTrailEngine {
    override fun newBoard(
        players: List<SweetTrailPlayer>,
        rules: SweetTrailRules,
        seed: Long,
        wishSeats: Set<Int>,
        starter: Int
    ): SweetTrailState {
        val n = players.size
        val pile = SweetTrailDeck.cards(rules.length).map { it.id }.shuffled(Random(seed))
        return SweetTrailState(
            rules = rules,
            players = players,
            positions = List(n) { rules.startSquare },
            stuck = List(n) { 0 },
            wishes = List(n) { if (it in wishSeats) 1 else 0 },
            currentPlayerIndex = if (n > 0) starter.coerceIn(0, n - 1) else 0,
            pile = pile,
            discard = emptyList(),
            shortcutsFound = List(n) { 0 },
            puddleStops = List(n) { 0 }
        )
    }

    override fun explainDraw(state: SweetTrailState): SweetTrailReject =
        if (state.over) SweetTrailReject.OVER else SweetTrailReject.NONE

    override fun draw(state: SweetTrailState): SweetTrailState = state

    override fun explainDecision(state: SweetTrailState, wish: Boolean): SweetTrailReject = when {
        state.over -> SweetTrailReject.OVER
        !state.pending -> SweetTrailReject.NOTHING_TO_DECIDE
        wish && state.wishes.getOrElse(state.currentPlayerIndex) { 0 } <= 0 -> SweetTrailReject.NO_WISH
        else -> SweetTrailReject.NONE
    }

    override fun decide(state: SweetTrailState, wish: Boolean): SweetTrailState = state

    override fun validate(state: SweetTrailState): List<String> = emptyList()
}

/**
 * The computer's only decision in this game: whether to spend a wish on the card it just drew. Kept pure (the
 * coin for Easy is passed in) so it is testable and never touches the deck stream. The scaffold never wishes.
 *
 * Intended rules (see the doc, "Bot behaviour"): [gain] is the squares the card would carry the piece
 * (shortcut and puddle effects included); EASY flips [coin]; MEDIUM wishes when gain <= 0 or the card lands in a
 * puddle; HARD wishes when gain < 4 or it lands in a puddle; no level wishes away a win or a shortcut.
 */
object SweetTrailBot {
    fun wantsWish(
        gain: Int,
        landsInPuddle: Boolean,
        wins: Boolean,
        takesShortcut: Boolean,
        difficulty: CpuDifficulty,
        coin: Random
    ): Boolean = false
}
