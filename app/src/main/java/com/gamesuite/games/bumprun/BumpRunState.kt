package com.gamesuite.games.bumprun

/** What the last change to the table was; the screen words, animates and announces it. */
object BumpRunEvent {
    const val START = "start"
    const val DRAW = "draw"
    const val MOVE = "move"
    const val EXIT = "exit"
    const val BUMP = "bump"
    const val SWAP = "swap"
    const val SHIELD = "shield"
    const val PAD = "pad"
    const val HOME = "home"
    const val AGAIN = "again"
    const val NO_MOVE = "no_move"
    const val DO_OVER = "do_over"
    const val WIN = "win"
    val ALL = setOf(START, DRAW, MOVE, EXIT, BUMP, SWAP, SHIELD, PAD, HOME, AGAIN, NO_MOVE, DO_OVER, WIN)
}

enum class BumpRunReject {
    NONE, OVER, NOT_YOUR_TURN, DRAW_FIRST, DECIDE_FIRST, CANNOT_MOVE, EXACT_ROLL, OWN_PAWN, SHIELDED, NO_DO_OVER, MOVES_AVAILABLE
}

/** The turn phases. AWAIT_CHOICE with an empty legal list means "no move": do-over or pass. */
enum class BumpPhase { AWAIT_DRAW, AWAIT_CHOICE, OVER }

enum class BumpMoveKind { STEP, EXIT, CHASE, SWAP, SHIELD, PASS }

/**
 * One legal play. [pawn] is `seat * pawnsPerSeat + k` (-1 for PASS). [to] is the destination PROGRESS for STEP /
 * EXIT / CHASE (0 for the rest); [target] is the rival pawn index for SWAP (-1 otherwise).
 */
data class BumpMove(val pawn: Int, val kind: BumpMoveKind, val to: Int = 0, val target: Int = -1)

/** [teamId] is -1 without teams, else 0 or 1 (seats 0 and 2 against 1 and 3). */
data class BumpRunPlayer(val playerId: String, val displayName: String, val isBot: Boolean, val teamId: Int = -1)

/** The rules for one board (the Hub's options). */
data class BumpRunRules(
    val length: BumpLength = BumpLength.QUICK,
    val pads: Boolean = true,
    val autoPlay: Boolean = true,
    val teams: Boolean = false
) {
    val pawnsPerSeat: Int get() = length.pawns
}

/**
 * The whole table. [pawns] holds one progress value per pawn (index `seat * pawnsPerSeat + k`, see [BumpRunBoard]);
 * [shields] marks shielded pawns; [doOvers] the do-overs each seat still holds (the handicap); [sides] the board
 * side of each seat. [pile] is the face-down draw pile (card ids, next draw LAST), [discard] the face-up discards
 * and [card] the card in play. The legal moves are NOT stored: ask the engine (they are a pure function of the state).
 * [bumpsMade] / [bumpsTaken] are per seat, for the score card. [turnSerial] only ever grows and keys animations.
 * [winnerPlayerIds] has the winner (or both team mates) once [phase] is OVER.
 */
data class BumpRunState(
    val rules: BumpRunRules,
    val players: List<BumpRunPlayer>,
    val sides: List<Int>,
    val pawns: List<Int>,
    val shields: List<Boolean>,
    val doOvers: List<Int>,
    val currentPlayerIndex: Int,
    val phase: BumpPhase,
    val pile: List<Int>,
    val discard: List<Int>,
    val card: Int? = null,
    val event: String = BumpRunEvent.START,
    val eventSeat: Int? = null,
    val eventPawn: Int = -1,
    val eventFrom: Int = BumpRunBoard.NEST,
    val eventTo: Int = BumpRunBoard.NEST,
    val eventVictim: Int = -1,
    val eventReshuffled: Boolean = false,
    val turnSerial: Int = 0,
    val bumpsMade: List<Int> = emptyList(),
    val bumpsTaken: List<Int> = emptyList(),
    val turnsTaken: Int = 0,
    val winnerPlayerIds: List<String> = emptyList()
) {
    val over: Boolean get() = phase == BumpPhase.OVER
}
