package com.gamesuite.games.sevens

import kotlinx.serialization.Serializable

@Serializable
enum class SvPhase { PLAY, ROUND_OVER }

object SvEvent {
    const val DEAL = "deal"
    const val PLAY = "play"
    const val PASS = "pass"
    const val ROUND_END = "round_end"
    val ALL = setOf(DEAL, PLAY, PASS, ROUND_END)
}

/** Placeholder for a card a viewer may not see. */
const val SV_HIDDEN_CARD = -1

/**
 * One seat. [hand] is card ids ([com.gamesuite.games.president.PsDeck]) sorted by suit then rank; a guest
 * holds only its own, the rest are [SV_HIDDEN_CARD] placeholders of the right length. [passes] counts this
 * round's passes, [roundScore] is filled when the round ends (remaining card points plus pass penalty) and
 * [total] is the running sum over the rounds played at this table.
 */
@Serializable
data class SvSeat(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean,
    val hand: List<Int> = emptyList(),
    val passes: Int = 0,
    val roundScore: Int = 0,
    val total: Int = 0
)

@Serializable
data class SevensState(
    val seats: List<SvSeat>,
    val rules: SevensRuleSet = SevensRuleSet(),
    val phase: SvPhase = SvPhase.PLAY,
    val currentSeat: Int = 0,
    /** Per suit (clubs, diamonds, hearts, spades): the lowest and highest rank index laid, 0 = not started. */
    val low: List<Int> = List(4) { 0 },
    val high: List<Int> = List(4) { 0 },
    val roundNumber: Int = 1,
    val winnerSeat: Int? = null,
    /** Rounds won per player id over the time at this table. */
    val wins: Map<String, Int> = emptyMap(),
    val event: String = SvEvent.DEAL,
    val eventSeat: Int? = null,
    val eventCard: Int? = null,
    val eventSerial: Int = 0
) {
    val roundOver: Boolean get() = phase == SvPhase.ROUND_OVER
    val started: Boolean get() = low[1] != 0

    /** How many cards lie on the table. */
    fun tableCount(): Int = (0 until 4).sumOf { if (low[it] == 0) 0 else high[it] - low[it] + 1 }
}

/** [s] as [viewerPlayerId] may see it: every other hand replaced by placeholders (null = a watcher, no hand). */
fun sevensRedactFor(s: SevensState, viewerPlayerId: String?): SevensState {
    val me = if (viewerPlayerId == null) -1 else s.seats.indexOfFirst { it.playerId == viewerPlayerId }
    return s.copy(seats = s.seats.mapIndexed { i, seat ->
        if (i == me) seat else seat.copy(hand = List(seat.hand.size) { SV_HIDDEN_CARD })
    })
}

const val SV_MAX_SPECTATORS = 16
const val SV_SPECTATOR_SENDER = "spectator"
