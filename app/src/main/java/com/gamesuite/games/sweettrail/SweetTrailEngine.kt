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
 * What one card does to one piece. Pure and total, shared by the engine, the bot (to look at a pending card) and
 * the snapshot check (to replay the last move). [landed] is the square the card carried the piece to (the Party
 * Table when the colour ran out); [to] is where the piece ends up after a shortcut.
 */
object SweetTrailMoves {
    data class Step(val from: Int, val landed: Int, val to: Int, val event: String, val stuck: Boolean, val shortcut: Boolean) {
        val won: Boolean get() = to == SweetTrailBoard.FINISH
        val passed: Boolean get() = event == SweetTrailEvent.PASSED

        /** Squares gained (a win counts to the Party Table, a dance is 0). */
        val gain: Int get() = to - from
    }

    fun step(rules: SweetTrailRules, from: Int, card: TrailCard): Step {
        if (from >= SweetTrailBoard.FINISH) {
            return Step(from, from, from, SweetTrailEvent.PASSED, stuck = false, shortcut = false)
        }
        var passed = false
        val landed = when (card.kind) {
            TrailCardKind.SINGLE -> SweetTrailBoard.nextSquareOfColour(from, card.colour ?: TrailColour.CHERRY, 1)
            TrailCardKind.DOUBLE -> SweetTrailBoard.nextSquareOfColour(from, card.colour ?: TrailColour.CHERRY, 2)
            TrailCardKind.LANDMARK -> {
                val square = card.landmark?.square ?: from
                if (square > from) square else { passed = true; from }
            }
        }
        if (landed >= SweetTrailBoard.FINISH) {
            return Step(from, SweetTrailBoard.FINISH, SweetTrailBoard.FINISH, SweetTrailEvent.WIN, stuck = false, shortcut = false)
        }
        val jump = if (rules.shortcutsOn) SweetTrailBoard.SHORTCUTS[landed] else null
        val to = jump ?: landed
        val stuck = to != from && to in rules.puddleSquares
        val event = when {
            passed -> SweetTrailEvent.PASSED
            jump != null -> SweetTrailEvent.SHORTCUT
            stuck -> SweetTrailEvent.PUDDLE
            else -> SweetTrailEvent.MOVE
        }
        return Step(from, landed, to, event, stuck, shortcut = jump != null)
    }

    /** The shortcut start a piece used to arrive at [to] (a shortcut end), for the screen's glide; null if none. */
    fun shortcutStartFor(to: Int): Int? = SweetTrailBoard.SHORTCUTS.entries.firstOrNull { it.value == to }?.key
}

/** The real rules. See docs/design/boardgames/sweet-trail.md, part 2. */
class StandardSweetTrailEngine : SweetTrailEngine {
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
            puddleStops = List(n) { 0 },
            boardSeed = seed
        )
    }

    override fun explainDraw(state: SweetTrailState): SweetTrailReject = when {
        state.over -> SweetTrailReject.OVER
        state.pending -> SweetTrailReject.DECIDE_FIRST
        else -> SweetTrailReject.NONE
    }

    override fun explainDecision(state: SweetTrailState, wish: Boolean): SweetTrailReject = when {
        state.over -> SweetTrailReject.OVER
        !state.pending -> SweetTrailReject.NOTHING_TO_DECIDE
        wish && state.wishes.getOrElse(state.currentPlayerIndex) { 0 } <= 0 -> SweetTrailReject.NO_WISH
        else -> SweetTrailReject.NONE
    }

    private class Drawn(val card: Int, val pile: List<Int>, val discard: List<Int>, val reshuffles: Int, val reshuffled: Boolean)

    /** The top card; an empty pile is first rebuilt by shuffling the discard (reshuffle n uses seed xor n * golden). */
    private fun takeTop(pile: List<Int>, discard: List<Int>, seed: Long, reshuffles: Int): Drawn? {
        var p = pile
        var d = discard
        var r = reshuffles
        var again = false
        if (p.isEmpty()) {
            if (d.isEmpty()) return null
            r += 1
            p = d.shuffled(Random(seed xor (r.toLong() * GOLDEN)))
            d = emptyList()
            again = true
        }
        return Drawn(p.last(), p.dropLast(1), d, r, again)
    }

    private fun nextSeat(s: SweetTrailState) = (s.currentPlayerIndex + 1) % s.players.size

    override fun draw(state: SweetTrailState): SweetTrailState {
        if (explainDraw(state) != SweetTrailReject.NONE) return state
        val seat = state.currentPlayerIndex
        val here = state.positions[seat]
        if (state.stuck[seat] > 0) {
            // pulls free: no card this turn (so no wish either), the turn passes
            return state.copy(
                stuck = state.stuck.mapIndexed { i, v -> if (i == seat) 0 else v },
                event = SweetTrailEvent.STUCK, eventSeat = seat, eventFrom = here, eventTo = here,
                eventCard = null, eventWished = false, eventReshuffled = false,
                currentPlayerIndex = nextSeat(state), moveSerial = state.moveSerial + 1, turnsTaken = state.turnsTaken + 1
            )
        }
        val d = takeTop(state.pile, state.discard, state.boardSeed, state.reshuffles) ?: return state
        val base = state.copy(pile = d.pile, discard = d.discard, reshuffles = d.reshuffles, drawSerial = state.drawSerial + 1)
        return if (state.wishes[seat] > 0) {
            base.copy(
                card = d.card, pending = true, event = SweetTrailEvent.PENDING, eventSeat = seat,
                eventFrom = here, eventTo = here, eventCard = d.card, eventWished = false, eventReshuffled = d.reshuffled
            )
        } else {
            resolve(base, seat, d.card, wished = false, reshuffled = d.reshuffled)
        }
    }

    override fun decide(state: SweetTrailState, wish: Boolean): SweetTrailState {
        if (explainDecision(state, wish) != SweetTrailReject.NONE) return state
        val seat = state.currentPlayerIndex
        val card = state.card ?: return state
        if (!wish) return resolve(state, seat, card, wished = false, reshuffled = state.eventReshuffled)
        val discard = state.discard + card
        val d = takeTop(state.pile, discard, state.boardSeed, state.reshuffles) ?: return state
        val next = state.copy(
            pile = d.pile, discard = d.discard, reshuffles = d.reshuffles, drawSerial = state.drawSerial + 1,
            wishes = state.wishes.mapIndexed { i, v -> if (i == seat) 0 else v }
        )
        return resolve(next, seat, d.card, wished = true, reshuffled = state.eventReshuffled || d.reshuffled)
    }

    /** Moves the seat on turn by [cardId], discards the card and passes the turn (or ends the board). */
    private fun resolve(s: SweetTrailState, seat: Int, cardId: Int, wished: Boolean, reshuffled: Boolean): SweetTrailState {
        val card = SweetTrailDeck.byId(cardId, s.rules.length) ?: return s
        val from = s.positions[seat]
        val step = SweetTrailMoves.step(s.rules, from, card)
        val won = step.won
        return s.copy(
            positions = s.positions.mapIndexed { i, p -> if (i == seat) step.to else p },
            stuck = s.stuck.mapIndexed { i, v -> if (i == seat) (if (step.stuck) 1 else 0) else v },
            currentPlayerIndex = if (won) seat else nextSeat(s),
            discard = s.discard + cardId,
            card = null, pending = false,
            event = step.event, eventSeat = seat, eventFrom = from, eventTo = step.to, eventCard = cardId,
            eventWished = wished, eventReshuffled = reshuffled,
            moveSerial = s.moveSerial + 1, turnsTaken = s.turnsTaken + 1,
            shortcutsFound = s.shortcutsFound.mapIndexed { i, v -> if (i == seat && step.shortcut) v + 1 else v },
            puddleStops = s.puddleStops.mapIndexed { i, v -> if (i == seat && step.stuck) v + 1 else v },
            over = won, winnerPlayerId = if (won) s.players[seat].playerId else null
        )
    }

    override fun validate(state: SweetTrailState): List<String> {
        val out = ArrayList<String>()
        val n = state.players.size
        if (n !in 2..4) return listOf("seats")
        if (state.positions.size != n) out += "positions.size"
        if (state.stuck.size != n) out += "stuck.size"
        if (state.wishes.size != n) out += "wishes.size"
        if (state.shortcutsFound.size != n) out += "shortcutsFound.size"
        if (state.puddleStops.size != n) out += "puddleStops.size"
        if (out.isNotEmpty()) return out
        val rules = state.rules
        val start = rules.startSquare
        val finish = SweetTrailBoard.FINISH
        val ids = state.players.map { it.playerId }
        if (ids.any { it.isBlank() } || ids.toSet().size != n) out += "playerIds"
        if (state.positions.any { it !in start..finish }) out += "positions.range"
        if (state.stuck.any { it !in 0..1 }) out += "stuck.range"
        state.stuck.forEachIndexed { i, v -> if (v == 1 && state.positions[i] !in rules.puddleSquares) out += "stuck.notOnPuddle"; if (v == 1 && state.positions[i] == finish) out += "stuck.finish" }
        if (state.wishes.any { it !in 0..1 }) out += "wishes.range"
        if (state.currentPlayerIndex !in 0 until n) out += "currentPlayerIndex"
        if (state.eventSeat != null && state.eventSeat !in 0 until n) out += "eventSeat"
        if (state.event !in SweetTrailEvent.ALL) out += "event"
        if (state.drawSerial !in 0..MAX_SERIAL || state.moveSerial !in 0..MAX_SERIAL) out += "serials"
        if (state.turnsTaken != state.moveSerial) out += "turnsTaken"
        if (state.reshuffles !in 0..MAX_SERIAL) out += "reshuffles"
        if (state.shortcutsFound.any { it !in 0..state.turnsTaken.coerceAtLeast(0) }) out += "shortcutsFound.range"
        if (state.puddleStops.any { it !in 0..state.turnsTaken.coerceAtLeast(0) }) out += "puddleStops.range"
        if (!rules.shortcutsOn && state.shortcutsFound.any { it != 0 }) out += "shortcutsFound.off"
        if (state.eventCard != null && state.eventCard !in 0 until SweetTrailDeck.SIZE) out += "eventCard"
        if (state.card != null && state.card !in 0 until SweetTrailDeck.SIZE) out += "card"
        val cards = state.pile + state.discard + listOfNotNull(state.card)
        if (cards.size != SweetTrailDeck.SIZE || cards.toSet() != (0 until SweetTrailDeck.SIZE).toSet()) out += "cards"
        if (state.pending != (state.card != null)) out += "pending.card"
        if (state.pending != (state.event == SweetTrailEvent.PENDING)) out += "pending.event"
        if (state.over != (state.winnerPlayerId != null)) out += "winner"
        val atFinish = state.positions.indices.filter { state.positions.getOrNull(it) == finish }
        val winnerSeat = ids.indexOf(state.winnerPlayerId)
        if (state.over) {
            if (winnerSeat < 0 || atFinish != listOf(winnerSeat)) out += "winner.finish"
        } else if (atFinish.isNotEmpty()) out += "finish.notOver"
        // the per-event rules index arrays, so they run only when the structure above is sound
        if (out.isNotEmpty()) return out
        val seat = state.eventSeat
        when (state.event) {
            SweetTrailEvent.START -> {
                if (seat != null || state.eventCard != null || state.moveSerial != 0 || state.drawSerial != 0 || state.over ||
                    state.discard.isNotEmpty() || state.positions.any { it != start } || state.stuck.any { it != 0 } ||
                    state.eventWished || state.reshuffles != 0
                ) out += "event.start"
            }
            SweetTrailEvent.PENDING -> {
                val cur = state.currentPlayerIndex
                if (state.over || seat != cur || state.wishes[cur] <= 0 || state.stuck[cur] != 0 ||
                    state.eventCard != state.card || state.eventFrom != state.positions[cur] || state.eventTo != state.positions[cur] ||
                    state.drawSerial < 1 || state.eventWished
                ) out += "event.pending"
            }
            SweetTrailEvent.STUCK -> {
                if (seat == null || state.eventCard != null || state.over || state.eventWished ||
                    state.eventFrom != state.positions[seat] || state.eventTo != state.positions[seat] ||
                    state.positions[seat] !in rules.puddleSquares || state.stuck[seat] != 0 ||
                    state.currentPlayerIndex != (seat + 1) % n || state.moveSerial < 1
                ) out += "event.stuck"
            }
            else -> {
                // MOVE, SHORTCUT, PUDDLE, PASSED, WIN: replay the card from where the piece stood
                val cardId = state.eventCard
                val card = cardId?.let { SweetTrailDeck.byId(it, rules.length) }
                if (seat == null || card == null || state.eventFrom !in start until finish) {
                    out += "event.move"
                } else {
                    val step = SweetTrailMoves.step(rules, state.eventFrom, card)
                    if (step.to != state.eventTo || step.event != state.event || state.positions[seat] != state.eventTo ||
                        state.stuck[seat] != (if (step.stuck) 1 else 0) ||
                        (state.event == SweetTrailEvent.WIN) != state.over ||
                        state.currentPlayerIndex != (if (state.over) seat else (seat + 1) % n) ||
                        state.moveSerial < 1 || state.drawSerial < 1 ||
                        (state.eventWished && state.wishes[seat] != 0)
                    ) out += "event.replay"
                }
            }
        }
        return out
    }

    companion object {
        /** Far beyond any real board; keeps `serial + 1` from overflowing (the screen reads a drop as a reset). */
        const val MAX_SERIAL = 1_000_000

        // 0x9E3779B97F4A7C15 as a signed Long
        private const val GOLDEN = -0x61c8864680b583ebL
    }
}

/**
 * The computer's only decision in this game: whether to spend a wish on the card it just drew. Pure (the coin
 * for Easy is passed in, derived from the state by [decide]) so it is testable and never touches the deck stream.
 *
 * [gain] is the squares the card would carry the piece (shortcut and puddle effects included); EASY flips [coin];
 * MEDIUM wishes when gain <= 0 or the card lands in a puddle; HARD wishes when gain < 4 or it lands in a puddle;
 * no level wishes away a win or a shortcut.
 */
object SweetTrailBot {
    fun wantsWish(
        gain: Int,
        landsInPuddle: Boolean,
        wins: Boolean,
        takesShortcut: Boolean,
        difficulty: CpuDifficulty,
        coin: Random
    ): Boolean {
        if (wins || takesShortcut) return false
        return when (difficulty) {
            CpuDifficulty.EASY -> coin.nextBoolean()
            CpuDifficulty.MEDIUM -> gain <= 0 || landsInPuddle
            CpuDifficulty.HARD -> gain < 4 || landsInPuddle
        }
    }

    /** The decision for the pending card of [state]; false (keep) whenever there is nothing to decide. */
    fun decide(state: SweetTrailState, difficulty: CpuDifficulty): Boolean {
        val seat = state.currentPlayerIndex
        if (!state.pending || state.over || state.wishes.getOrElse(seat) { 0 } <= 0) return false
        val cardId = state.card ?: return false
        val card = SweetTrailDeck.byId(cardId, state.rules.length) ?: return false
        val here = state.positions.getOrElse(seat) { return false }
        val step = SweetTrailMoves.step(state.rules, here, card)
        // a coin made from the state, never from the deck stream, so the bot cannot disturb a seeded board
        val coin = Random(state.drawSerial * 31L + here + seat)
        return wantsWish(step.gain, step.stuck, step.won, step.shortcut, difficulty, coin)
    }
}
