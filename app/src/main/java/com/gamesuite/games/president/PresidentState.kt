package com.gamesuite.games.president

import kotlinx.serialization.Serializable

@Serializable
enum class PsPhase { PLAY, EXCHANGE, ROUND_OVER, SESSION_OVER }

/** What the last change to the table was; the screen words, animates and announces it. */
object PsEvent {
    const val DEAL = "deal"
    const val PLAY = "play"
    const val PASS = "pass"
    const val EXCHANGE = "exchange"
    const val ROUND_END = "round_end"
    const val SESSION_END = "session_end"
    val ALL = setOf(DEAL, PLAY, PASS, EXCHANGE, ROUND_END, SESSION_END)
}

/**
 * One seat. [hand] holds card ids ([PsDeck]) weakest first; in a state sent to a guest the hands of the
 * other seats are the same length filled with [PS_HIDDEN_CARD]. [outPosition] is the place this round
 * (0 = went out first), -1 while still holding cards. [role] is the title earned in the PREVIOUS round
 * (empty in round 1); [roundPoints] and [score] are this round's and the session's points.
 */
@Serializable
data class PsSeat(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean,
    val hand: List<Int> = emptyList(),
    val outPosition: Int = -1,
    val role: String = "",
    val roundPoints: Int = 0,
    val score: Int = 0
)

@Serializable
data class PsTrickPlay(val seat: Int, val cards: List<Int>)

/** [from] still has to hand [count] cards of its choice to [to]. */
@Serializable
data class PsExchangeTask(val from: Int, val to: Int, val count: Int)

/** A completed swap leg; [auto] legs are the Scum's best cards taken automatically. */
@Serializable
data class PsExchangeRecord(val from: Int, val to: Int, val cards: List<Int>, val auto: Boolean)

/** Placeholder for a card a viewer may not see. Never a real id. */
const val PS_HIDDEN_CARD = -1

@Serializable
data class PresidentState(
    val seats: List<PsSeat>,
    val rules: PresidentRuleSet = PresidentRuleSet(),
    val phase: PsPhase = PsPhase.PLAY,
    val currentSeat: Int = 0,
    /** The plays of the trick in progress, oldest first; the last one is the play to beat. */
    val trick: List<PsTrickPlay> = emptyList(),
    /** Seats that passed (or were skipped) since the last play. */
    val passed: List<Int> = emptyList(),
    val revolution: Boolean = false,
    val roundNumber: Int = 1,
    /** Seats in the order they went out this round. */
    val finishOrder: List<Int> = emptyList(),
    /** Every card played this round (public: it lies face up on the table). */
    val playedCards: List<Int> = emptyList(),
    /** The card the first play of a session must contain (the three of clubs), else null. */
    val mustInclude: Int? = null,
    val pendingExchanges: List<PsExchangeTask> = emptyList(),
    val exchanges: List<PsExchangeRecord> = emptyList(),
    /** Seat that leads the round (the previous President, or the three-of-clubs holder). */
    val startSeat: Int = 0,
    val sessionWins: Map<String, Int> = emptyMap(),
    val event: String = PsEvent.DEAL,
    val eventSeat: Int? = null,
    val eventCards: List<Int> = emptyList(),
    val eventRevolution: Boolean = false,
    val eventCleared: Boolean = false,
    val eventSkipped: Int? = null,
    val eventOut: Boolean = false,
    val eventSerial: Int = 0
) {
    val top: PsTrickPlay? get() = trick.lastOrNull()
    val roundOver: Boolean get() = phase == PsPhase.ROUND_OVER || phase == PsPhase.SESSION_OVER
    val sessionOver: Boolean get() = phase == PsPhase.SESSION_OVER
    fun activeSeats(): List<Int> = seats.indices.filter { seats[it].hand.isNotEmpty() }
    fun roleOf(seat: Int): PresidentRole? = seats.getOrNull(seat)?.role?.let { r -> PresidentRole.values().firstOrNull { it.name == r } }
}

/** The title a finishing [position] earns this round (the one the NEXT round shows). */
fun PresidentState.roleForPosition(position: Int): PresidentRole = PresidentRules.roleFor(position, seats.size)

/**
 * The view of [s] that [viewerPlayerId] may hold: every other seat's hand is replaced by same-length
 * [PS_HIDDEN_CARD] placeholders (card counts stay real), and the cards of exchange legs the viewer took
 * no part in are hidden. A null viewer is a spectator and sees no hand at all. The scum's last cards of a
 * finished round stay private too: nothing about a hand leaves the host except its owner's.
 */
fun presidentRedactFor(s: PresidentState, viewerPlayerId: String?): PresidentState {
    val me = if (viewerPlayerId == null) -1 else s.seats.indexOfFirst { it.playerId == viewerPlayerId }
    return s.copy(
        seats = s.seats.mapIndexed { i, seat ->
            if (i == me) seat else seat.copy(hand = List(seat.hand.size) { PS_HIDDEN_CARD })
        },
        exchanges = s.exchanges.map { e ->
            if (e.from == me || e.to == me) e else e.copy(cards = List(e.cards.size) { PS_HIDDEN_CARD })
        },
        // the gift just handed over is announced to the two seats involved only
        eventCards = if (s.event == PsEvent.EXCHANGE && me != s.eventSeat && me != s.exchanges.lastOrNull()?.to)
            List(s.eventCards.size) { PS_HIDDEN_CARD } else s.eventCards
    )
}

const val PS_MAX_SPECTATORS = 16
const val PS_SPECTATOR_SENDER = "spectator"
