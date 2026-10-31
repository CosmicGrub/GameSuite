package com.gamesuite.ui.party

import kotlin.math.abs
import kotlin.random.Random

/*
 * Pure logic behind the Party Toolkit "game kit" tools (turn order, first player, game clock,
 * round tracker, card dealer). Nothing in this file touches Android or Compose, so every rule is
 * covered by plain JUnit4 tests with a seeded [Random] and a fake clock (see PartyLogicTest).
 */

// ---------------------------------------------------------------------------
// Roster
// ---------------------------------------------------------------------------

object PartyRoster {
    const val MIN_PLAYERS = 2
    const val MAX_PLAYERS = 12

    fun clampCount(count: Int): Int = count.coerceIn(MIN_PLAYERS, MAX_PLAYERS)

    /**
     * The names a tool should use. A roster with at least one non-blank name wins (trimmed, blanks
     * dropped); an empty roster falls back to [defaultName] for seats 1..[fallbackCount].
     */
    fun resolve(roster: List<String>, fallbackCount: Int, defaultName: (Int) -> String): List<String> {
        val cleaned = roster.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isNotEmpty()) return cleaned
        return List(clampCount(fallbackCount)) { defaultName(it + 1) }
    }
}

// ---------------------------------------------------------------------------
// Turn order
// ---------------------------------------------------------------------------

object PartyTurnOrder {
    /** Seat i holds player i. */
    fun initial(count: Int): List<Int> = List(count.coerceAtLeast(0)) { it }

    fun isPermutation(order: List<Int>, count: Int): Boolean =
        order.size == count && order.toSet() == (0 until count).toSet()

    /**
     * Re-shuffles [order] (seat -> player index). Seats in [lockedSeats] keep their player; every
     * other player is shuffled among the remaining seats.
     */
    fun shuffle(order: List<Int>, lockedSeats: Set<Int>, random: Random): List<Int> {
        val freeSeats = order.indices.filter { it !in lockedSeats }
        val players = freeSeats.map { order[it] }.shuffled(random)
        val result = order.toMutableList()
        freeSeats.forEachIndexed { i, seat -> result[seat] = players[i] }
        return result
    }

    /** Number of seats a shuffle can actually move. */
    fun freeSeatCount(order: List<Int>, lockedSeats: Set<Int>): Int = order.indices.count { it !in lockedSeats }

    /** "1. Ana\n2. Ben ..." (one seat per line). */
    fun asText(names: List<String>, order: List<Int>): String =
        order.mapIndexed { seat, player -> "${seat + 1}. ${names.getOrElse(player) { "?" }}" }.joinToString("\n")
}

// ---------------------------------------------------------------------------
// First player
// ---------------------------------------------------------------------------

object PartyFirstPick {
    const val MIN_STEPS = 16

    /** Uniform pick in 0 until [count]; -1 when there is nobody to pick. */
    fun pick(count: Int, random: Random): Int = if (count <= 0) -1 else random.nextInt(count)

    /**
     * The highlight positions of the suspense animation: consecutive seats (wrapping) that end on
     * [finalIndex]. At least [minSteps] long and at least one full lap.
     */
    fun suspenseSequence(count: Int, finalIndex: Int, minSteps: Int = MIN_STEPS): List<Int> {
        if (count <= 0) return emptyList()
        val target = finalIndex.coerceIn(0, count - 1)
        val steps = maxOf(minSteps, count)
        return List(steps) { i -> Math.floorMod(target - (steps - 1 - i), count) }
    }

    /** Pause after step [step] of [steps]: starts quick (60 ms) and slows to about 300 ms. */
    fun delayMs(step: Int, steps: Int): Long {
        if (steps <= 0) return 60L
        val s = step.coerceIn(0, steps).toLong()
        return 60L + (240L * s * s) / (steps.toLong() * steps)
    }
}

// ---------------------------------------------------------------------------
// Game clock (multi-player, chess-clock style). All times in ms; `now` is injected.
// ---------------------------------------------------------------------------

data class PartyClockConfig(
    /** Starting time bank of every player. */
    val bankMs: Long = 10 * 60_000L,
    /** Added to the bank of a player who passes the turn (Fischer increment). 0 = none. */
    val incrementMs: Long = 0L,
    /** Maximum time for a single turn. 0 = no per-turn limit. */
    val turnLimitMs: Long = 0L
)

data class PartyClockState(
    val config: PartyClockConfig,
    val banksMs: List<Long>,
    val active: Int,
    val running: Boolean,
    /** The `now` value the banks were last charged up to. */
    val lastTickMs: Long,
    val turnElapsedMs: Long,
    /** Players whose bank ran out; they are skipped and cannot play on. */
    val outPlayers: Set<Int>,
    /** The active player used up the per-turn limit and the turn must be passed on. */
    val turnTimedOut: Boolean,
    /** Bumped once every time somebody is flagged, so the UI can fire its cue exactly once. */
    val flagSeq: Int,
    val turns: Int
)

object PartyClock {
    fun create(players: Int, config: PartyClockConfig = PartyClockConfig()): PartyClockState {
        val n = players.coerceAtLeast(1)
        return PartyClockState(
            config = config,
            banksMs = List(n) { config.bankMs },
            active = 0,
            running = false,
            lastTickMs = 0L,
            turnElapsedMs = 0L,
            outPlayers = emptySet(),
            turnTimedOut = false,
            flagSeq = 0,
            turns = 0
        )
    }

    /** True while nothing has happened yet, so the settings may still change. */
    fun isFresh(state: PartyClockState): Boolean =
        state.turns == 0 && !state.running && state.turnElapsedMs == 0L &&
            state.outPlayers.isEmpty() && state.banksMs.all { it == state.config.bankMs }

    fun aliveCount(state: PartyClockState): Int = state.banksMs.size - state.outPlayers.size

    /** Only one player (or nobody) is left with time. A single-player clock is over when that player runs out. */
    fun isFinished(state: PartyClockState): Boolean {
        val n = state.banksMs.size
        return if (n > 1) aliveCount(state) <= 1 else aliveCount(state) <= 0
    }

    /** Players still in the game. */
    fun alivePlayers(state: PartyClockState): List<Int> = state.banksMs.indices.filter { it !in state.outPlayers }

    /** The next seat after [from] that still has time; [from] when nobody else does. */
    fun nextAlive(from: Int, count: Int, out: Set<Int>): Int {
        for (k in 1..count) {
            val candidate = (from + k) % count
            if (candidate !in out) return candidate
        }
        return from
    }

    /** True when the clock waits for a tap: not running, not flagged, not over. */
    fun isWaiting(state: PartyClockState): Boolean =
        !state.running && !state.turnTimedOut && state.active !in state.outPlayers && !isFinished(state)

    /**
     * Charges the active player for the time since the last tick. Running out of bank, or hitting
     * the per-turn limit, stops the clock exactly at that instant and flags the player.
     */
    fun tick(state: PartyClockState, nowMs: Long): PartyClockState {
        if (!state.running) return state
        val delta = (nowMs - state.lastTickMs).coerceAtLeast(0L)
        val bank = state.banksMs[state.active]
        val limit = state.config.turnLimitMs
        val turnRoom = if (limit > 0L) (limit - state.turnElapsedMs).coerceAtLeast(0L) else Long.MAX_VALUE
        val allowed = minOf(bank.coerceAtLeast(0L), turnRoom)
        if (delta < allowed) {
            return state.copy(
                banksMs = state.banksMs.replaced(state.active, bank - delta),
                turnElapsedMs = state.turnElapsedMs + delta,
                lastTickMs = nowMs
            )
        }
        val newBank = bank - allowed
        val newElapsed = state.turnElapsedMs + allowed
        val bankOut = newBank <= 0L
        val turnOut = limit > 0L && newElapsed >= limit
        return state.copy(
            banksMs = state.banksMs.replaced(state.active, newBank.coerceAtLeast(0L)),
            turnElapsedMs = newElapsed,
            lastTickMs = nowMs,
            running = false,
            outPlayers = if (bankOut) state.outPlayers + state.active else state.outPlayers,
            turnTimedOut = turnOut && !bankOut,
            flagSeq = state.flagSeq + 1
        )
    }

    /** Starts or resumes the active player's clock. */
    fun resume(state: PartyClockState, nowMs: Long): PartyClockState {
        if (!isWaiting(state)) return state
        return state.copy(running = true, lastTickMs = nowMs)
    }

    fun pause(state: PartyClockState, nowMs: Long): PartyClockState {
        val ticked = tick(state, nowMs)
        return if (ticked.running) ticked.copy(running = false) else ticked
    }

    /**
     * The "tap anywhere" action. A waiting clock starts; a running (or just-flagged) clock hands the
     * turn to the next player that still has time, adding the increment to the player who passes.
     */
    fun pass(state: PartyClockState, nowMs: Long): PartyClockState {
        val ticked = tick(state, nowMs)
        if (isFinished(ticked)) return ticked
        if (isWaiting(ticked)) return resume(ticked, nowMs)
        val from = ticked.active
        val passerOut = from in ticked.outPlayers
        val bonus = if (passerOut || ticked.turnTimedOut) 0L else ticked.config.incrementMs
        val banks = if (bonus > 0L) ticked.banksMs.replaced(from, ticked.banksMs[from] + bonus) else ticked.banksMs
        return ticked.copy(
            banksMs = banks,
            active = nextAlive(from, banks.size, ticked.outPlayers),
            running = true,
            lastTickMs = nowMs,
            turnElapsedMs = 0L,
            turnTimedOut = false,
            turns = ticked.turns + 1
        )
    }

    /** Moves on without the increment. A paused clock stays paused. */
    fun skip(state: PartyClockState, nowMs: Long): PartyClockState {
        val ticked = tick(state, nowMs)
        if (isFinished(ticked)) return ticked
        val wasWaiting = isWaiting(ticked)
        return ticked.copy(
            active = nextAlive(ticked.active, ticked.banksMs.size, ticked.outPlayers),
            running = !wasWaiting,
            lastTickMs = nowMs,
            turnElapsedMs = 0L,
            turnTimedOut = false,
            turns = ticked.turns + 1
        )
    }

    /** Whole seconds, rounded up, as "m:ss" (or "h:mm:ss" from one hour). */
    fun format(ms: Long): String {
        val total = ((ms.coerceAtLeast(0L) + 999L) / 1000L)
        val hours = total / 3600L
        val minutes = (total % 3600L) / 60L
        val seconds = total % 60L
        return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(total / 60L, seconds)
    }

    private fun List<Long>.replaced(index: Int, value: Long): List<Long> =
        mapIndexed { i, v -> if (i == index) value else v }
}

// ---------------------------------------------------------------------------
// Round / bid tracker
// ---------------------------------------------------------------------------

enum class PartyMissRule { MINUS_BID, MINUS_DIFFERENCE, ZERO }

enum class PartyRoundMode { BID_TRICKS, MANUAL }

data class PartyBidRules(
    /** Flat bonus for making the bid (10 gives the classic "10 + bid"). */
    val madeBonus: Int = 10,
    /** Points per trick bid. */
    val perBid: Int = 1,
    /** When true, taking MORE tricks than the bid still makes it (Spades style); otherwise the bid must be exact. */
    val overMakes: Boolean = false,
    /** Points per extra trick when [overMakes] is on. */
    val overPoint: Int = 1,
    val missRule: PartyMissRule = PartyMissRule.MINUS_BID
)

object PartyBidScoring {
    fun score(bid: Int, tricks: Int, rules: PartyBidRules): Int {
        val made = if (rules.overMakes) tricks >= bid else tricks == bid
        if (made) {
            val extra = if (rules.overMakes) (tricks - bid) * rules.overPoint else 0
            return rules.madeBonus + bid * rules.perBid + extra
        }
        return when (rules.missRule) {
            PartyMissRule.MINUS_BID -> -bid
            PartyMissRule.MINUS_DIFFERENCE -> -abs(tricks - bid)
            PartyMissRule.ZERO -> 0
        }
    }
}

data class PartyRound(val bids: List<Int>, val tricks: List<Int>, val points: List<Int>)

data class PartyRoundState(
    val mode: PartyRoundMode = PartyRoundMode.BID_TRICKS,
    val rules: PartyBidRules = PartyBidRules(),
    val rounds: List<PartyRound> = emptyList(),
    val bids: List<Int> = emptyList(),
    val tricks: List<Int> = emptyList(),
    val points: List<Int> = emptyList(),
    val players: Int = 0
)

object PartyRoundTracker {
    const val MAX_TRICKS = 30
    const val MAX_POINTS = 999

    /** The state resized for [players]; a changed player count starts a fresh game but keeps mode and rules. */
    fun fit(state: PartyRoundState, players: Int): PartyRoundState {
        if (state.players == players && state.bids.size == players) return state
        return PartyRoundState(
            mode = state.mode,
            rules = state.rules,
            bids = List(players) { 0 },
            tricks = List(players) { 0 },
            points = List(players) { 0 },
            players = players
        )
    }

    fun withValue(values: List<Int>, index: Int, value: Int): List<Int> =
        values.mapIndexed { i, v -> if (i == index) value else v }

    /** What each player would score if the round were closed now. */
    fun draftScores(state: PartyRoundState): List<Int> = when (state.mode) {
        PartyRoundMode.BID_TRICKS -> state.bids.indices.map { PartyBidScoring.score(state.bids[it], state.tricks[it], state.rules) }
        PartyRoundMode.MANUAL -> state.points
    }

    fun commit(state: PartyRoundState): PartyRoundState {
        val round = PartyRound(bids = state.bids, tricks = state.tricks, points = draftScores(state))
        val zero = List(state.players) { 0 }
        return state.copy(rounds = state.rounds + round, bids = zero, tricks = zero, points = zero)
    }

    fun undo(state: PartyRoundState): PartyRoundState =
        if (state.rounds.isEmpty()) state else state.copy(rounds = state.rounds.dropLast(1))

    fun totals(state: PartyRoundState): List<Int> =
        List(state.players) { p -> state.rounds.sumOf { it.points.getOrElse(p) { 0 } } }

    /** Indices holding the highest total (all of them on a tie); empty when there are no rounds yet. */
    fun leaders(totals: List<Int>, roundsPlayed: Int): List<Int> {
        if (roundsPlayed <= 0 || totals.isEmpty()) return emptyList()
        val best = totals.maxOrNull() ?: return emptyList()
        return totals.indices.filter { totals[it] == best }
    }

    fun reset(state: PartyRoundState): PartyRoundState = fit(PartyRoundState(mode = state.mode, rules = state.rules), state.players)
}

// ---------------------------------------------------------------------------
// Card dealer
// ---------------------------------------------------------------------------

enum class PartyDeckKind { STANDARD, CUSTOM }

data class PartyDeckSpec(
    val kind: PartyDeckKind = PartyDeckKind.STANDARD,
    /** 0..2, standard deck only. */
    val jokers: Int = 0,
    /** Cards numbered 1..N, custom deck only. */
    val customSize: Int = 40
) {
    val size: Int
        get() = when (kind) {
            PartyDeckKind.STANDARD -> PartyDeck.STANDARD_SIZE + jokers.coerceIn(0, 2)
            PartyDeckKind.CUSTOM -> customSize.coerceIn(PartyDeck.MIN_CUSTOM, PartyDeck.MAX_CUSTOM)
        }
}

/** The result of a deal: one hand per player and the undealt stock (top of the stock first). */
data class PartyDealt(val spec: PartyDeckSpec, val hands: List<List<Int>>, val stock: List<Int>)

object PartyDeck {
    const val STANDARD_SIZE = 52
    const val MIN_CUSTOM = 2
    const val MAX_CUSTOM = 120

    private val RANKS = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
    private val SUITS = listOf("♠", "♥", "♦", "♣") // spades, hearts, diamonds, clubs

    fun isJoker(spec: PartyDeckSpec, card: Int): Boolean = spec.kind == PartyDeckKind.STANDARD && card >= STANDARD_SIZE

    /** 0 = A .. 12 = K. */
    fun rankIndex(card: Int): Int = card % 13

    /** 0 spades, 1 hearts, 2 diamonds, 3 clubs. */
    fun suitIndex(card: Int): Int = (card / 13) % 4

    /** Hearts and diamonds. The suit symbol is always printed too, so colour is never the only cue. */
    fun isRed(spec: PartyDeckSpec, card: Int): Boolean =
        spec.kind == PartyDeckKind.STANDARD && card < STANDARD_SIZE && suitIndex(card) in 1..2

    fun shortLabel(spec: PartyDeckSpec, card: Int): String = when {
        spec.kind == PartyDeckKind.CUSTOM -> (card + 1).toString()
        card >= STANDARD_SIZE -> "JKR"
        else -> RANKS[rankIndex(card)] + SUITS[suitIndex(card)]
    }

    fun shuffled(spec: PartyDeckSpec, random: Random): List<Int> = List(spec.size) { it }.shuffled(random)

    /** The most cards each of [players] can get from a deck of [deckSize]. */
    fun maxPerPlayer(deckSize: Int, players: Int): Int = if (players <= 0) 0 else deckSize / players

    /** Shuffles a fresh deck and deals [perPlayer] cards (clamped to what the deck allows) round-robin. */
    fun deal(spec: PartyDeckSpec, players: Int, perPlayer: Int, random: Random): PartyDealt {
        val deck = shuffled(spec, random)
        val per = perPlayer.coerceIn(0, maxPerPlayer(deck.size, players))
        val hands = List(players.coerceAtLeast(0)) { p -> List(per) { r -> deck[r * players + p] } }
        return PartyDealt(spec, hands, deck.drop(per * players.coerceAtLeast(0)))
    }

    /** One more card to everyone, or null when the stock cannot cover every player. */
    fun drawOneEach(dealt: PartyDealt): PartyDealt? {
        val n = dealt.hands.size
        if (n == 0 || dealt.stock.size < n) return null
        return PartyDealt(
            spec = dealt.spec,
            hands = dealt.hands.mapIndexed { p, hand -> hand + dealt.stock[p] },
            stock = dealt.stock.drop(n)
        )
    }
}
