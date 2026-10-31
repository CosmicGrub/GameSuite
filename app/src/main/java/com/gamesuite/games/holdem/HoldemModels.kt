package com.gamesuite.games.holdem

import kotlinx.serialization.Serializable

/*
 * Texas Hold'em (PLAY CHIPS ONLY: there is no real money anywhere in this game).
 *
 * Cards are plain ints 0..51 using the shared standard-deck numbering of games/cards/Card.kt
 * (id = suit.ordinal * 13 + rank.ordinal, so rank = 2 + id % 13 and suit = id / 13). A hole card
 * that a viewer may not see is the sentinel [HIDDEN_CARD].
 */

const val HIDDEN_CARD = -1

/** Where the betting stands. A hand that ends early keeps the street it ended on. */
@Serializable
enum class HoldemStreet { PREFLOP, FLOP, TURN, RIVER }

/** How fast the forced bets grow. [handsPerLevel] 0 means they never rise. */
enum class BlindSpeed(val id: String, val handsPerLevel: Int) {
    OFF("off", 0), SLOW("slow", 12), NORMAL("normal", 8), FAST("fast", 4);

    companion object {
        fun fromId(id: String?): BlindSpeed = values().firstOrNull { it.id == id } ?: NORMAL
    }
}

/** Per-match options. [startingChips] is every seat's stack; the blinds scale from it. */
@Serializable
data class HoldemConfig(
    val startingChips: Int = 1000,
    val blindSpeed: String = "normal",
    /** End the tournament once no human seat has chips left (a CPU-only table is not worth watching). */
    val endWhenHumansOut: Boolean = true
) {
    val speed: BlindSpeed get() = BlindSpeed.fromId(blindSpeed)

    companion object {
        const val MIN_START = 100
        const val MAX_START = 100_000
    }
}

@Serializable
enum class HoldemActionKind { FOLD, CHECK, CALL, RAISE_TO, ALL_IN }

/** [amount] is only read for [HoldemActionKind.RAISE_TO]: the seat's TOTAL bet this street after raising. */
@Serializable
data class HoldemAction(val kind: HoldemActionKind, val amount: Int = 0) {
    companion object {
        val FOLD = HoldemAction(HoldemActionKind.FOLD)
        val CHECK = HoldemAction(HoldemActionKind.CHECK)
        val CALL = HoldemAction(HoldemActionKind.CALL)
        val ALL_IN = HoldemAction(HoldemActionKind.ALL_IN)
        fun raiseTo(total: Int) = HoldemAction(HoldemActionKind.RAISE_TO, total)
    }
}

/** Why an action was refused. The screen turns each into a one-line "why". */
enum class HoldemReject {
    NONE, NO_HAND, HAND_OVER, MATCH_OVER, NOT_YOUR_TURN, MUST_CALL, NOTHING_TO_CALL,
    BELOW_MIN_RAISE, ABOVE_STACK, CANNOT_RAISE, NO_CHIPS, BAD_SEAT
}

/** What the seat on turn may do right now. All amounts are chips; raise amounts are "raise to" totals. */
data class HoldemOptions(
    val canCheck: Boolean,
    val canCall: Boolean,
    /** Chips the call costs (less than the bet when the seat is short: an all-in call). */
    val callAmount: Int,
    val canRaise: Boolean,
    val minRaiseTo: Int,
    val maxRaiseTo: Int,
    val canAllIn: Boolean,
    /** The seat's total street bet if it pushes everything in. */
    val allInTo: Int,
    val toCall: Int
)

/** Event codes carried by [HoldemState.event]; the screen words, animates and announces them. */
object HoldemEvent {
    const val START = "start"
    const val HAND = "hand"
    const val FOLD = "fold"
    const val CHECK = "check"
    const val CALL = "call"
    const val BET = "bet"
    const val RAISE = "raise"
    const val ALL_IN = "all_in"
    const val SHOWDOWN = "showdown"
    const val WIN = "win"
    const val UNCONTESTED = "uncontested"
    const val BUST = "bust"
    const val MATCH_OVER = "match_over"
    const val LEFT = "left"
    const val RETURNED = "returned"
    val ALL = setOf(START, HAND, FOLD, CHECK, CALL, BET, RAISE, ALL_IN, SHOWDOWN, WIN, UNCONTESTED, BUST, MATCH_OVER, LEFT, RETURNED)
}

@Serializable
data class HoldemSeat(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean,
    /** Chips behind (not yet committed). */
    val chips: Int,
    /** Committed this betting round. */
    val bet: Int = 0,
    /** Committed over the whole hand (includes [bet]). */
    val committed: Int = 0,
    val folded: Boolean = false,
    val allIn: Boolean = false,
    /** Dealt into the current hand. */
    val inHand: Boolean = false,
    /** Out of the tournament. */
    val busted: Boolean = false,
    /** Two card ids, or [HIDDEN_CARD] entries for a seat the viewer may not see. Empty when not dealt in. */
    val hole: List<Int> = emptyList(),
    /** The seat's cards are face up for everyone (showdown or an all-in run-out). */
    val revealed: Boolean = false,
    /** [HoldemState.raiseSerial] when the seat last acted this round; -1 until it has. */
    val actedAt: Int = -1,
    /** "fold", "check", "call", "bet", "raise", "allin", "sb", "bb" or "" for display badges. */
    val lastAction: String = "",
    val lastAmount: Int = 0,
    /** Chips this seat was paid out at the end of the hand. */
    val won: Int = 0
) {
    val live: Boolean get() = inHand && !folded
    val active: Boolean get() = inHand && !folded && !allIn
}

/** One (side) pot as paid out. [refund] marks an uncalled bet handed back to its owner. */
@Serializable
data class HoldemPotResult(
    val amount: Int,
    val eligible: List<Int>,
    val winners: List<Int>,
    val refund: Boolean = false
)

/** A showdown participant's final hand: [score] orders hands (see [HandEvaluator]), [best] is the five cards that make it. */
@Serializable
data class HoldemSeatResult(val seat: Int, val score: Int, val best: List<Int>)

@Serializable
data class HoldemState(
    val seats: List<HoldemSeat>,
    val config: HoldemConfig = HoldemConfig(),
    val button: Int = 0,
    val smallBlindSeat: Int = -1,
    val bigBlindSeat: Int = -1,
    val street: HoldemStreet = HoldemStreet.PREFLOP,
    val board: List<Int> = emptyList(),
    /** Seat on turn, or -1 when nobody is (between hands, or while the board runs out). */
    val toAct: Int = -1,
    val currentBet: Int = 0,
    /** The smallest legal raise INCREMENT (the last full raise, never less than the big blind). */
    val minRaise: Int = 0,
    /** Counts full raises this round; a seat that acted before the latest full raise may raise again, others may not. */
    val raiseSerial: Int = 0,
    /** Chips collected from finished betting rounds (current bets are on the seats). */
    val pot: Int = 0,
    val smallBlind: Int = 0,
    val bigBlind: Int = 0,
    val blindLevel: Int = 0,
    val handNumber: Int = 0,
    val handOver: Boolean = true,
    /** All betting is finished: the remaining board cards are dealt one street at a time. */
    val runout: Boolean = false,
    /** The hand ended with a showdown (otherwise everyone else folded). */
    val showdown: Boolean = false,
    val results: List<HoldemPotResult> = emptyList(),
    val seatResults: List<HoldemSeatResult> = emptyList(),
    val matchOver: Boolean = false,
    val winnerPlayerId: String? = null,
    val event: String = HoldemEvent.START,
    val eventSeat: Int = -1,
    val eventAmount: Int = 0,
    /** Grows with every committed state; keys the screen's animations and sounds. */
    val eventSerial: Int = 0
) {
    val totalPot: Int get() = pot + seats.sumOf { it.bet }
    fun toCallFor(seat: Int): Int = (currentBet - seats[seat].bet).coerceAtLeast(0)
    val liveCount: Int get() = seats.count { it.live }
    val chipsInPlay: Int get() = seats.sumOf { it.chips + it.committed }
}
