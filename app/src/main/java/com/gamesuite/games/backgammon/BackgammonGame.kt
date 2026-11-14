package com.gamesuite.games.backgammon

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameContext
import com.gamesuite.core.GameModule
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerScore
import com.gamesuite.core.ResumableGame
import com.gamesuite.core.SnapshotHistory
import com.gamesuite.core.UndoableGame
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Everything needed to put a Backgammon session back exactly as it was: the checkers (points, bar,
 * borne off), whose turn it is and in which phase, the dice and which are still unused, the
 * opening-roll state, the session tally and the difficulty. One shape serves Resume (written to
 * disk) and Undo (kept in memory) so a position and its tally can never be restored out of step.
 */
@Serializable
private data class BackgammonSnapshot(
    val points: List<Int>,
    val bar: List<Int>,
    val off: List<Int>,
    val players: List<BackgammonSnapshotPlayer>,
    val currentPlayerIndex: Int,
    val phase: String,
    val dice: List<Int> = emptyList(),
    val remaining: List<Int> = emptyList(),
    val openingDice: List<Int> = emptyList(),
    val openingTies: Int = 0,
    val sessionPoints: Map<String, Int> = emptyMap(),
    val sessionWins: Map<String, Int> = emptyMap(),
    val difficulty: String = CpuDifficulty.MEDIUM.name
)

@Serializable
private data class BackgammonSnapshotPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

private val backgammonSnapshotJson = Json { ignoreUnknownKeys = true }

/** A snapshot that passed every sanity check, with its position already rebuilt. */
private class BackgammonDecoded(val snap: BackgammonSnapshot, val board: BackgammonBoard, val phase: BackgammonPhase)

/** Where a game is in its life. */
enum class BackgammonPhase {
    /** The opening roll: each side rolls one die, the higher roll starts (ties roll again). */
    OPENING,

    /** [BackgammonState.currentPlayerIndex] must roll the dice. */
    TO_ROLL,

    /** Dice are rolled; checkers move until the dice are used or nothing can be played. */
    MOVING,

    /** A side has borne off all 15 checkers. */
    OVER
}

data class BackgammonPlayerState(val playerId: String, val displayName: String, val isBot: Boolean)

/**
 * The visible state of a game. Seat 0 is White (side 0) and seat 1 is Black (side 1).
 *
 * [dice] are the two dice as rolled; [remaining] the dice not yet used (four copies for a double);
 * [legalMoves] the single checker moves playable right now (already honouring "use as many dice as
 * possible" and "the larger die if only one can be played"). When [phase] is MOVING and
 * [legalMoves] is empty the turn is complete: either every die was used or nothing more can be
 * played, and the player ends the turn.
 */
data class BackgammonState(
    val board: BackgammonBoard,
    val players: List<BackgammonPlayerState>,
    val currentPlayerIndex: Int,
    val phase: BackgammonPhase,
    val dice: List<Int> = emptyList(),
    val remaining: List<Int> = emptyList(),
    /** The latest opening roll as [seat 0's die, seat 1's die]; empty before the first. A tie stays here. */
    val openingDice: List<Int> = emptyList(),
    val openingTies: Int = 0,
    val legalMoves: List<BackgammonMove> = emptyList(),
    val lastMove: BackgammonMove? = null,
    /** Bumped by every checker move so the screen can animate it exactly once. */
    val moveSeq: Int = 0,
    val winnerPlayerId: String? = null,
    val resultKind: BackgammonResultKind? = null,
    val pointsScored: Int = 0
) {
    /** True when the current player has nothing more to play and may end the turn. */
    val turnComplete: Boolean get() = phase == BackgammonPhase.MOVING && legalMoves.isEmpty()
}

/** A frozen position handed to a background search (see [BackgammonGame.playBotTurnAsync]). */
class BackgammonSearchPlan internal constructor(
    internal val position: BackgammonState,
    internal val epoch: Int
)

/**
 * Backgammon for two seats: standard rules, single-game scoring.
 *
 * RULES IMPLEMENTED (see [BackgammonRules]): the standard opening setup; two dice a turn, a double
 * giving four moves; a lone enemy checker ("blot") is hit and sent to the bar; a point with two or
 * more enemy checkers is blocked; a checker on the bar must enter before anything else moves; both
 * dice must be played if possible, otherwise the larger one if only one can be; checkers may be
 * borne off only when all fifteen are in the home board, with the exact die or a larger one when no
 * checker is farther back. The first side to bear off all fifteen wins and scores 1 point, 2 for a
 * gammon (the loser has borne off none) and 3 for a backgammon (a gammon with a loser's checker
 * still on the bar or in the winner's home board).
 *
 * OUT OF SCOPE: the doubling cube (and so Crawford / Jacoby / beavers). Scoring is single-game only.
 *
 * OPENING: each side rolls one die (both dice are rolled together by [rollDice]); the higher die
 * starts and plays the two numbers as its first move; a tie rolls again ([BackgammonState.openingTies]
 * counts them). [firstOpenerIndex] forces a seat to start with an ordinary roll instead.
 *
 * TURN FLOW: [rollDice] -> [moveChecker] (once per die) -> [endTurn]. A roll cannot be undone;
 * [undo] takes back single checker moves of the current turn, human turns only.
 *
 * CPU: three levels of [BackgammonBot] (EASY random legal turn; MEDIUM hand-weighted evaluation;
 * HARD the same evaluation with a 2-ply expectimax over the dice within about a second). The bot
 * plays its turn through [playBotTurn] / [playBotTurnAsync]; the async form searches on
 * [Dispatchers.Default] and drops its answer if the position changed while it thought.
 *
 * Local modes only (pass and play, vs CPU): there is no networking in this engine.
 */
class BackgammonGame(
    private val random: Random = Random.Default,
    private val nanoClock: () -> Long = System::nanoTime
) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "backgammon"
    override val displayName = "Backgammon"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
    )

    val state = mutableStateOf<BackgammonState?>(null)

    /** Points scored this session per playerId (1 / 2 / 3 per game won). */
    val sessionPoints = mutableStateOf<Map<String, Int>>(emptyMap())

    /** Games won this session per playerId. */
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())

    /** True only once the whole session ends (the player leaves from the result panel). */
    val matchOver = mutableStateOf(false)

    /** The move the last [hint] suggested, shown until the next move, roll or undo. */
    val hintMove = mutableStateOf<BackgammonMove?>(null)

    /** Pre-set by the UI from the settings / Hub before [startMatch]. */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /**
     * The seat that takes the first turn of the FIRST game (the Hub's "who moves first"), set by the
     * screen before [startMatch] and consumed by it. Null = the opening roll decides. Every later
     * game opens with a roll again. A resumed game ignores it.
     */
    var firstOpenerIndex: Int? = null

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** Bumped whenever the position is replaced wholesale (new game, restore, undo, end of turn). */
    private var positionEpoch = 0

    /** Snapshots taken just BEFORE each human checker move, newest last; popping one is Undo. */
    private val history = SnapshotHistory()

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        state.value = null
        sessionPoints.value = context.players.associate { it.playerId to 0 }
        sessionWins.value = context.players.associate { it.playerId to 0 }
        history.clear()
        hintMove.value = null
        positionEpoch++
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    // --- Starting a game ----------------------------------------------------------------------

    override fun startMatch() {
        if (context.players.size != 2) return
        val players = context.players.map { BackgammonPlayerState(it.playerId, it.displayName, it.isBot) }
        val forced = firstOpenerIndex?.coerceIn(0, 1)
        firstOpenerIndex = null
        matchOver.value = false
        history.clear()
        hintMove.value = null
        positionEpoch++
        state.value = BackgammonState(
            board = BackgammonBoard.initial(),
            players = players,
            currentPlayerIndex = forced ?: 0,
            phase = if (forced != null) BackgammonPhase.TO_ROLL else BackgammonPhase.OPENING
        )
    }

    /** Called from the result panel's "Play Again": keeps the session tally and deals a new game. */
    fun playAgain() {
        if (!com.gamesuite.core.LaunchPolicy.requestNewRound()) return
        if (matchOver.value) return
        if (state.value?.phase != BackgammonPhase.OVER) return
        startMatch()
    }

    /**
     * Ends the whole session from the result panel, reporting points per player. Only a STRICT
     * leader on points is flagged the winner: an even tally (or no game finished) is a draw.
     */
    fun leaveSession() {
        if (matchOver.value) return
        val points = sessionPoints.value
        val best = points.values.maxOrNull() ?: 0
        val leaders = context.players.count { (points[it.playerId] ?: 0) == best }
        val scores = context.players.map {
            val p = points[it.playerId] ?: 0
            PlayerScore(playerId = it.playerId, score = p, isWinner = best > 0 && p == best && leaders == 1)
        }
        endMatch(GameResult(scores = scores))
    }

    // --- Rolling ------------------------------------------------------------------------------

    private fun rollDie(): Int = random.nextInt(1, 7)

    /**
     * Rolls for the current phase: the opening roll (both sides' dice at once) or the current
     * player's turn roll. Ignored in any other phase.
     */
    fun rollDice() {
        val s = state.value ?: return
        if (matchOver.value) return
        when (s.phase) {
            BackgammonPhase.OPENING -> {
                val a = rollDie()
                val b = rollDie()
                if (a == b) {
                    state.value = s.copy(openingDice = listOf(a, b), openingTies = s.openingTies + 1)
                } else {
                    val starter = if (a > b) 0 else 1
                    beginMoving(s.copy(currentPlayerIndex = starter, openingDice = listOf(a, b)), listOf(a, b))
                }
            }
            BackgammonPhase.TO_ROLL -> {
                val a = rollDie()
                val b = rollDie()
                beginMoving(s, listOf(a, b))
            }
            else -> Unit
        }
    }

    private fun expand(dice: List<Int>): List<Int> =
        if (dice.size == 2 && dice[0] == dice[1]) List(4) { dice[0] } else dice

    private fun beginMoving(s: BackgammonState, dice: List<Int>) {
        history.clear()
        hintMove.value = null
        positionEpoch++
        val remaining = expand(dice)
        state.value = s.copy(
            phase = BackgammonPhase.MOVING,
            dice = dice,
            remaining = remaining,
            legalMoves = BackgammonRules.legalMoves(s.board, s.currentPlayerIndex, remaining),
            lastMove = null
        )
    }

    // --- Moving -------------------------------------------------------------------------------

    /** The single moves playable now (empty unless the dice are rolled and something can be played). */
    fun legalMoves(): List<BackgammonMove> = state.value?.legalMoves ?: emptyList()

    /**
     * Plays the checker on [from] (a point index or [BackgammonMove.BAR]) to [to] (a point index or
     * [BackgammonMove.OFF]) with one die. When several dice could do it (a bear-off with a larger
     * die) the smallest sufficient one is used. False when no legal move matches.
     */
    fun moveChecker(from: Int, to: Int): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.phase != BackgammonPhase.MOVING) return false
        val move = s.legalMoves.filter { it.from == from && it.to == to }.minByOrNull { it.die } ?: return false
        applyMove(s, move)
        return true
    }

    /** Plays exactly [move] (die included) if it is currently legal; used by the CPU. */
    fun playMove(move: BackgammonMove): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.phase != BackgammonPhase.MOVING) return false
        if (move !in s.legalMoves) return false
        applyMove(s, move)
        return true
    }

    private fun applyMove(s: BackgammonState, move: BackgammonMove) {
        val seat = s.currentPlayerIndex
        // Undo only ever lands on a HUMAN's turn: remember the position before each human move.
        if (!s.players[seat].isBot) captureSnapshot()?.let { history.push(it) }
        hintMove.value = null
        val board = s.board.apply(seat, move)
        val remaining = s.remaining.toMutableList()
        remaining.remove(move.die)
        val winnerSeat = BackgammonRules.winner(board)
        if (winnerSeat != null) {
            val kind = BackgammonRules.resultKind(board, winnerSeat)
            val winnerId = s.players[winnerSeat].playerId
            sessionPoints.value = sessionPoints.value + (winnerId to ((sessionPoints.value[winnerId] ?: 0) + kind.points))
            sessionWins.value = sessionWins.value + (winnerId to ((sessionWins.value[winnerId] ?: 0) + 1))
            state.value = s.copy(
                board = board,
                remaining = remaining,
                legalMoves = emptyList(),
                phase = BackgammonPhase.OVER,
                lastMove = move,
                moveSeq = s.moveSeq + 1,
                winnerPlayerId = winnerId,
                resultKind = kind,
                pointsScored = kind.points
            )
            return
        }
        state.value = s.copy(
            board = board,
            remaining = remaining,
            legalMoves = BackgammonRules.legalMoves(board, seat, remaining),
            lastMove = move,
            moveSeq = s.moveSeq + 1
        )
    }

    /**
     * Hands the dice to the other side once the current player has nothing more to play (every die
     * used, or no legal move left). False while a legal move remains: the player must make it.
     */
    fun endTurn(): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || !s.turnComplete) return false
        history.clear()
        hintMove.value = null
        positionEpoch++
        state.value = s.copy(
            currentPlayerIndex = 1 - s.currentPlayerIndex,
            phase = BackgammonPhase.TO_ROLL,
            dice = emptyList(),
            remaining = emptyList(),
            legalMoves = emptyList()
        )
        return true
    }

    // --- Resume -------------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return backgammonSnapshotJson.encodeToString(
            BackgammonSnapshot.serializer(),
            BackgammonSnapshot(
                points = s.board.pointsList(),
                bar = s.board.barList(),
                off = s.board.offList(),
                players = s.players.map { BackgammonSnapshotPlayer(it.playerId, it.displayName, it.isBot) },
                currentPlayerIndex = s.currentPlayerIndex,
                phase = s.phase.name,
                dice = s.dice,
                remaining = s.remaining,
                openingDice = s.openingDice,
                openingTies = s.openingTies,
                sessionPoints = sessionPoints.value,
                sessionWins = sessionWins.value,
                difficulty = difficulty.name
            )
        )
    }

    /** Decodes and sanity-checks a snapshot against THIS roster; null if it is unusable. */
    private fun decodeSnapshot(text: String): BackgammonDecoded? {
        val snap = try {
            backgammonSnapshotJson.decodeFromString(BackgammonSnapshot.serializer(), text)
        } catch (_: Exception) {
            return null
        }
        val roster = context.players
        if (roster.size != 2 || snap.players.size != 2) return null
        if (!snap.players.indices.all {
                snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot
            }
        ) return null
        val board = BackgammonBoard.fromParts(snap.points, snap.bar, snap.off) ?: return null
        val phase = BackgammonPhase.values().firstOrNull { it.name == snap.phase } ?: return null
        if (snap.currentPlayerIndex !in 0..1) return null
        if (snap.dice.any { it !in 1..6 } || snap.remaining.any { it !in 1..6 } || snap.openingDice.any { it !in 1..6 }) return null
        if (snap.openingDice.isNotEmpty() && snap.openingDice.size != 2) return null
        if (snap.openingTies < 0 || snap.openingTies > 10_000) return null
        val ids = roster.map { it.playerId }.toSet()
        if (snap.sessionPoints.keys.any { it !in ids } || snap.sessionWins.keys.any { it !in ids }) return null
        if (snap.sessionPoints.values.any { it < 0 || it > 1_000_000 } || snap.sessionWins.values.any { it < 0 || it > 1_000_000 }) return null
        val over = BackgammonRules.gameOver(board)
        when (phase) {
            BackgammonPhase.OPENING -> {
                if (over || snap.dice.isNotEmpty() || snap.remaining.isNotEmpty()) return null
                if (snap.openingDice.isNotEmpty() && snap.openingDice[0] != snap.openingDice[1]) return null
            }
            BackgammonPhase.TO_ROLL -> {
                if (over || snap.dice.isNotEmpty() || snap.remaining.isNotEmpty()) return null
            }
            BackgammonPhase.MOVING -> {
                if (over || snap.dice.size != 2) return null
                val pool = expand(snap.dice).toMutableList()
                for (r in snap.remaining) if (!pool.remove(r)) return null
            }
            BackgammonPhase.OVER -> {
                if (!over) return null
            }
        }
        return BackgammonDecoded(snap, board, phase)
    }

    private fun applyDecoded(d: BackgammonDecoded) {
        val snap = d.snap
        val seat = snap.currentPlayerIndex
        val winnerSeat = if (d.phase == BackgammonPhase.OVER) BackgammonRules.winner(d.board) else null
        val kind = winnerSeat?.let { BackgammonRules.resultKind(d.board, it) }
        state.value = BackgammonState(
            board = d.board,
            players = snap.players.map { BackgammonPlayerState(it.playerId, it.displayName, it.isBot) },
            currentPlayerIndex = seat,
            phase = d.phase,
            dice = snap.dice,
            remaining = snap.remaining,
            openingDice = snap.openingDice,
            openingTies = snap.openingTies,
            legalMoves = if (d.phase == BackgammonPhase.MOVING) BackgammonRules.legalMoves(d.board, seat, snap.remaining) else emptyList(),
            lastMove = null,
            moveSeq = 0,
            winnerPlayerId = winnerSeat?.let { snap.players[it].playerId },
            resultKind = kind,
            pointsScored = kind?.points ?: 0
        )
        sessionPoints.value = snap.sessionPoints
        sessionWins.value = snap.sessionWins
        difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        hintMove.value = null
        positionEpoch++
    }

    /** Null when there is nothing to save: no game yet, the session already ended, or a non-local mode. */
    override fun snapshot(): String? {
        if (matchOver.value || state.value == null) return null
        if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
        return captureSnapshot()
    }

    override fun restore(snapshot: String): Boolean {
        if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return false
        val decoded = decodeSnapshot(snapshot) ?: return false
        matchOver.value = false
        applyDecoded(decoded)
        firstOpenerIndex = null
        history.clear()
        return true
    }

    // --- Undo ---------------------------------------------------------------------------------

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value

    /**
     * Takes back the last single checker move of the current (human) turn. A roll is never undone,
     * and the history is empty once the turn is ended. Undoing the move that won the game re-opens
     * it and takes the points back off the session tally.
     */
    override fun undo() {
        if (matchOver.value) return
        val previous = history.pop() ?: return
        val decoded = decodeSnapshot(previous) ?: return
        applyDecoded(decoded)
    }

    // --- Hint ---------------------------------------------------------------------------------

    /**
     * Suggests the next checker move for the player to move: the first move of the turn the HARD
     * evaluation likes best. Highlights it ([hintMove]) and returns it; null when it is not a
     * human's moving phase.
     */
    fun hint(): BackgammonMove? {
        val plan = planHint() ?: return null
        val move = BackgammonBot.hintMove(plan.position.board, plan.position.currentPlayerIndex, plan.position.remaining, random, nanoClock)
        return if (applyHint(plan, move)) move else null
    }

    /** [hint] for the UI: the search runs off the main thread and is dropped if the position moved on. */
    suspend fun hintAsync(): BackgammonMove? {
        val plan = planHint() ?: return null
        val move = withContext(Dispatchers.Default) {
            BackgammonBot.hintMove(plan.position.board, plan.position.currentPlayerIndex, plan.position.remaining, random, nanoClock)
        }
        return if (applyHint(plan, move)) move else null
    }

    private fun planHint(): BackgammonSearchPlan? {
        val s = state.value ?: return null
        if (matchOver.value || s.phase != BackgammonPhase.MOVING || s.legalMoves.isEmpty()) return null
        if (s.players[s.currentPlayerIndex].isBot) return null
        return BackgammonSearchPlan(s, positionEpoch)
    }

    private fun applyHint(plan: BackgammonSearchPlan, move: BackgammonMove?): Boolean {
        if (move == null || !isCurrent(plan) || move !in plan.position.legalMoves) return false
        hintMove.value = move
        return true
    }

    /** True when nothing has changed since [plan] was taken: same epoch, same position, session open. */
    private fun isCurrent(plan: BackgammonSearchPlan): Boolean =
        !matchOver.value && plan.epoch == positionEpoch && state.value == plan.position

    // --- CPU ----------------------------------------------------------------------------------

    private fun isBotToAct(s: BackgammonState): Boolean =
        (s.phase == BackgammonPhase.TO_ROLL || s.phase == BackgammonPhase.MOVING) &&
            s.players[s.currentPlayerIndex].isBot

    /**
     * Plays a whole CPU turn at once: rolls if needed, plays the chosen turn and ends it. A no-op
     * when it is not a CPU's turn, so it is safe to call speculatively.
     */
    fun playBotTurn() {
        var s = state.value ?: return
        if (matchOver.value || !isBotToAct(s)) return
        if (s.phase == BackgammonPhase.TO_ROLL) {
            rollDice()
            s = state.value ?: return
        }
        if (s.phase != BackgammonPhase.MOVING) return
        val moves = BackgammonBot.chooseTurn(s.board, s.currentPlayerIndex, s.remaining, difficulty, random, nanoClock)
        for (move in moves) if (!playMove(move)) return
        if (state.value?.phase == BackgammonPhase.MOVING) endTurn()
    }

    /**
     * [playBotTurn] for the UI. [pace] is awaited before the roll, before each checker move and
     * before the turn ends, so the screen can pause (scaled by the game speed) and animate between
     * them. The search runs on [Dispatchers.Default]; the answer is applied only while the position
     * is exactly the one searched (no undo, leave or restart in between).
     */
    suspend fun playBotTurnAsync(pace: suspend () -> Unit = {}) {
        var s = state.value ?: return
        if (matchOver.value || !isBotToAct(s)) return
        if (s.phase == BackgammonPhase.TO_ROLL) {
            pace()
            if (state.value != s || matchOver.value) return
            rollDice()
            s = state.value ?: return
        }
        if (s.phase != BackgammonPhase.MOVING) return
        val plan = BackgammonSearchPlan(s, positionEpoch)
        val moves = withContext(Dispatchers.Default) {
            BackgammonBot.chooseTurn(
                plan.position.board, plan.position.currentPlayerIndex, plan.position.remaining,
                difficulty, random, nanoClock
            )
        }
        if (!isCurrent(plan)) return
        var expected = s
        for (move in moves) {
            pace()
            if (matchOver.value || plan.epoch != positionEpoch || state.value != expected) return
            if (!playMove(move)) return
            expected = state.value ?: return
            if (expected.phase != BackgammonPhase.MOVING) return
        }
        pace()
        if (!matchOver.value && plan.epoch == positionEpoch && state.value == expected) endTurn()
    }

    // --- Test support -------------------------------------------------------------------------

    /**
     * Replaces the position (tests and fixtures): [seat] to move, with [dice] already rolled when
     * given (a double is expanded to four moves), otherwise waiting to roll.
     */
    internal fun debugSetPosition(board: BackgammonBoard, seat: Int, dice: List<Int>? = null) {
        val players = context.players.map { BackgammonPlayerState(it.playerId, it.displayName, it.isBot) }
        history.clear()
        hintMove.value = null
        positionEpoch++
        val base = BackgammonState(board = board, players = players, currentPlayerIndex = seat, phase = BackgammonPhase.TO_ROLL)
        if (dice == null) {
            state.value = base
        } else {
            val remaining = expand(dice)
            state.value = base.copy(
                phase = BackgammonPhase.MOVING,
                dice = dice,
                remaining = remaining,
                legalMoves = BackgammonRules.legalMoves(board, seat, remaining)
            )
        }
    }
}
