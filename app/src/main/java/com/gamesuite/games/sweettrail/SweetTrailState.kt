package com.gamesuite.games.sweettrail

/** What the last change to the table was; the screen words, animates and announces it. */
object SweetTrailEvent {
    const val START = "start"

    /** A card is waiting for its player to keep it or spend the wish. */
    const val PENDING = "pending"
    const val MOVE = "move"
    const val SHORTCUT = "shortcut"
    const val PUDDLE = "puddle"

    /** A stuck seat waited out its turn and is free again. */
    const val STUCK = "stuck"

    /** A landmark card for a place already passed: the piece stays and dances. */
    const val PASSED = "passed"
    const val WIN = "win"
    val ALL = setOf(START, PENDING, MOVE, SHORTCUT, PUDDLE, STUCK, PASSED, WIN)

    /** The events that describe a finished move (they carry a consistent card / from / to). */
    val MOVES = setOf(MOVE, SHORTCUT, PUDDLE, PASSED, WIN)
}

enum class SweetTrailReject {
    NONE, OVER, NOT_YOUR_TURN, DECIDE_FIRST, NOTHING_TO_DECIDE, NO_WISH,

    /** A draw / keep / wish was accepted and the screen has not reported it settled yet (see [SweetTrailGame.holdInputUntilSettled]). */
    BUSY
}

/** Seat colour and shape are both `seat` (the screen picks them by index), so identity never rests on hue alone. */
data class SweetTrailPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

/** The rules for one board (the Hub's options). */
data class SweetTrailRules(
    val length: TrailLength = TrailLength.STANDARD,
    val sticky: Boolean = true
) {
    val startSquare: Int get() = length.startSquare
    val shortcutsOn: Boolean get() = length.shortcuts
    val puddleSquares: Set<Int> get() = if (sticky) length.puddles else emptySet()
}

/**
 * The whole table. [positions] is one square (0..61) per seat; [stuck] the turns each seat still has to wait
 * (0 or 1); [wishes] the wish cards each seat still holds (the handicap). [pile] is the face-down draw pile (card
 * ids, next draw LAST) and [discard] the face-up discards; [card] is the card on the table while it is being
 * played. [pending] is true while the seat on turn holds a card it may still swap with a wish.
 * [drawSerial] / [moveSerial] only ever grow and key the screen's card and hop animations.
 *
 * [eventCard] is the card that produced the last event (the card in play for PENDING, the resolved card for a move,
 * null for START and STUCK); [card] is only non-null while [pending]. Every card is in exactly one of [pile],
 * [discard] and [card]. [boardSeed] and [reshuffles] let the engine replay a reshuffle without any global state.
 * [turnsTaken] always equals [moveSerial] (a stuck wait is a turn too).
 */
data class SweetTrailState(
    val rules: SweetTrailRules,
    val players: List<SweetTrailPlayer>,
    val positions: List<Int>,
    val stuck: List<Int>,
    val wishes: List<Int>,
    val currentPlayerIndex: Int,
    val pile: List<Int>,
    val discard: List<Int>,
    val card: Int? = null,
    val pending: Boolean = false,
    val event: String = SweetTrailEvent.START,
    val eventSeat: Int? = null,
    val eventFrom: Int = 0,
    val eventTo: Int = 0,
    val eventWished: Boolean = false,
    val eventReshuffled: Boolean = false,
    val drawSerial: Int = 0,
    val moveSerial: Int = 0,
    val shortcutsFound: List<Int> = emptyList(),
    val puddleStops: List<Int> = emptyList(),
    val turnsTaken: Int = 0,
    val over: Boolean = false,
    val winnerPlayerId: String? = null,
    val eventCard: Int? = null,
    val boardSeed: Long = 0L,
    val reshuffles: Int = 0
)
