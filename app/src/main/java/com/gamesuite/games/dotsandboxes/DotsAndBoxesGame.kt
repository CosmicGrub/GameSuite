package com.gamesuite.games.dotsandboxes

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty

/** A player's identity + bot flag — mirrors DominoGame's own per-player state, kept as a list even though this game is always exactly 2 players, for consistency with that established shape. */
data class DotsAndBoxesPlayerState(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean
)

/**
 * [horizontalEdges] is (boxRows+1) rows x boxCols cols, row-major: entry
 * `r*boxCols+c` is the edge along the top of box (r, c) (equivalently the
 * bottom of box (r-1, c)). [verticalEdges] is boxRows rows x (boxCols+1)
 * cols, row-major: entry `r*(boxCols+1)+c` is the edge along the left of
 * box (r, c) (equivalently the right of box (r, c-1)). [boxOwner] is
 * boxRows*boxCols, row-major: the index into [players] who claimed that
 * box, or null if unclaimed.
 */
data class DotsAndBoxesState(
    val boxRows: Int,
    val boxCols: Int,
    val horizontalEdges: List<Boolean>,
    val verticalEdges: List<Boolean>,
    val boxOwner: List<Int?>,
    val players: List<DotsAndBoxesPlayerState>,
    val currentPlayerIndex: Int,
    /** This BOARD's box count per player index — distinct from [DotsAndBoxesGame.sessionWins], which tallies boards won across a whole Play Again session. */
    val scores: List<Int>,
    val lastAction: String,
    /** True once every box is claimed — distinct from [DotsAndBoxesGame.matchOver], which only flips once the whole session ends (the player leaves via the board-over panel's "Back to Menu" rather than "Play Again"). */
    val boardOver: Boolean = false,
    /** The winning playerId once [boardOver], or null for a genuine tie. */
    val winnerPlayerId: String? = null
) {
    val totalBoxes: Int get() = boxRows * boxCols
}

/**
 * Dots and Boxes: players take turns drawing one edge of a dot grid;
 * completing a box's 4th edge claims it AND grants that same player another
 * turn (the signature rule — a player can chain together many free turns in
 * a row this way); once every box is claimed, whoever claimed the most
 * wins. Board size is FIXED (5x5 boxes, the classic size), not scaled by
 * [difficulty] — unlike the solo puzzles in this batch (Minesweeper/Sudoku/
 * Lights Out), this is a 2-player competitive game where [difficulty] tunes
 * the BOT's strategy, same idiom as DominoGame/TicTacToeGame's own bots,
 * not the puzzle itself.
 *
 * BOT STRATEGY (see [chooseBotMove]'s own KDoc for the full tier
 * breakdown): the real skill in this game is refusing to create a
 * "3-sided" box (one edge away from complete) unless forced to, since that
 * hands the opponent a free box. EASY ignores this entirely (uniform random
 * over every legal edge); MEDIUM takes free boxes and avoids creating
 * 3-sided ones but picks blindly once forced to sacrifice; HARD does the
 * same but picks the SMALLEST forced sacrifice via a one-ply chain-reaction
 * simulation (see [chainSizeIfOpened]).
 *
 * DELIBERATE SCOPE CUT, honest MVP: no "double-cross" strategy (the real
 * expert technique of deliberately leaving the LAST 2 boxes of a long chain
 * unclaimed to force the opponent to open the next chain instead of you) —
 * a genuinely more complex, chain-parity-aware counter-strategy on top of
 * what HARD already does here. Left for a later pass, same spirit as this
 * project's other documented AI-scope cuts (e.g. MinesweeperGame's skipped
 * hint/solver).
 */
class DotsAndBoxesGame : GameModule {
    override val gameId = "dots-and-boxes"
    override val displayName = "Dots and Boxes"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT
    )

    val state = mutableStateOf<DotsAndBoxesState?>(null)

    /** Boards won this session, keyed by playerId; a tied board increments nobody (see [sessionDraws] instead). Mirrors TicTacToeGame's own scoreP1/scoreP2/draws idea, generalized to a map (though this game is always exactly 2 players). */
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val sessionDraws = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via the board-over panel), not per-board. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** Alternates who opens each new board (including across Play Again rounds), for fairness — same idiom TicTacToeGame's own Play Again uses. */
    private var nextStartingPlayerIndex = 0

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        sessionDraws.value = 0
        nextStartingPlayerIndex = 0
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        val players = context.players.map { DotsAndBoxesPlayerState(it.playerId, it.displayName, it.isBot) }
        val totalH = (BOX_ROWS + 1) * BOX_COLS
        val totalV = BOX_ROWS * (BOX_COLS + 1)
        // A fresh board is always playable, regardless of whether a PRIOR
        // board's endMatch() left matchOver stuck true (a real bug found by
        // adversarial review of a sibling game in this batch, fixed
        // proactively here from the start rather than waiting to
        // rediscover it — see MinesweeperGame/SudokuGame/LightsOutGame's own
        // KDocs on this exact fix).
        matchOver.value = false
        state.value = DotsAndBoxesState(
            boxRows = BOX_ROWS,
            boxCols = BOX_COLS,
            horizontalEdges = List(totalH) { false },
            verticalEdges = List(totalV) { false },
            boxOwner = List(BOX_ROWS * BOX_COLS) { null },
            players = players,
            currentPlayerIndex = nextStartingPlayerIndex % players.size,
            scores = List(players.size) { 0 },
            lastAction = "Game started"
        )
        nextStartingPlayerIndex = (nextStartingPlayerIndex + 1) % players.size
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /**
     * Called from the board-over panel's "Back to Menu" button — ends the
     * whole session (not just the current board), reporting the session's
     * cumulative boards-won tally. Mirrors DominoGame.leaveSession().
     */
    fun leaveSession() {
        if (matchOver.value) return
        val wins = sessionWins.value
        val bestWins = wins.values.maxOrNull() ?: 0
        val scores = context.players.map {
            val w = wins[it.playerId] ?: 0
            PlayerScore(playerId = it.playerId, score = w, isWinner = bestWins > 0 && w == bestWins)
        }
        endMatch(GameResult(scores = scores))
    }

    /**
     * Called from the board-over panel's "Play Again" button — keeps the
     * running session tally and deals a fresh board within the same
     * session. Reuses [startMatch] itself, same as DominoGame.playAgain().
     */
    fun playAgain() {
        if (matchOver.value) return
        startMatch()
    }

    fun drawHorizontalEdge(row: Int, col: Int) = drawEdge(isHorizontal = true, row = row, col = col)
    fun drawVerticalEdge(row: Int, col: Int) = drawEdge(isHorizontal = false, row = row, col = col)

    /**
     * See [drawHorizontalEdge]/[drawVerticalEdge]. No-op if the board is
     * already over, the edge is out of range or already drawn, or the whole
     * session has already ended via [leaveSession]/[endMatch] — found
     * missing by ColorFloodGame.pick()'s own adversarial review (only the
     * per-board `boardOver` was checked, never `matchOver`, so a call after
     * the final `GameResult` had already been delivered could still mutate
     * state, including a phantom box claim/win with no second result ever
     * reported — see that class's KDoc). Not currently reachable through
     * this app's real screens, but a real gap worth closing defensively
     * regardless.
     */
    private fun drawEdge(isHorizontal: Boolean, row: Int, col: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver) return
        if (!isValidEdge(isHorizontal, row, col, s.boxRows, s.boxCols)) return
        if (isEdgeDrawn(s, isHorizontal, row, col)) return

        val newH = if (isHorizontal) setEdge(s.horizontalEdges, row * s.boxCols + col) else s.horizontalEdges
        val newV = if (!isHorizontal) setEdge(s.verticalEdges, row * (s.boxCols + 1) + col) else s.verticalEdges
        val affected = affectedBoxes(isHorizontal, row, col, s.boxRows, s.boxCols)

        val newOwner = s.boxOwner.toMutableList()
        var completedCount = 0
        for (boxIndex in affected) {
            if (newOwner[boxIndex] != null) continue
            val br = boxIndex / s.boxCols
            val bc = boxIndex % s.boxCols
            if (countDrawnEdges(br, bc, newH, newV, s.boxCols) == 4) {
                newOwner[boxIndex] = s.currentPlayerIndex
                completedCount++
            }
        }

        val newScores = s.scores.toMutableList()
        if (completedCount > 0) newScores[s.currentPlayerIndex] = newScores[s.currentPlayerIndex] + completedCount

        val allClaimed = newOwner.all { it != null }
        val mover = s.players[s.currentPlayerIndex]
        // Completing >=1 box grants an extra turn -- the same player goes
        // again. This is the entire reason a single tap can chain into a
        // long run of consecutive moves for one player. Deliberately NOT
        // conditioned on `!allClaimed`: every edge borders at least one box
        // (affectedBoxes always returns 1 or 2 indices, never 0), so the
        // move that completes the LAST box on the board always has
        // completedCount > 0 too -- an earlier version of this line added
        // `&& !allClaimed`, which is unsatisfiable at exactly that moment
        // and made currentPlayerIndex wrongly name the player who did NOT
        // just move as "current" in the terminal state (found by
        // adversarial review). boardOver freezes all further play either
        // way, so this only affects which player the final state names as
        // current, never actual gameplay.
        val nextIndex = if (completedCount > 0) s.currentPlayerIndex else (s.currentPlayerIndex + 1) % s.players.size

        val winnerId = if (allClaimed) finishBoard(newScores, s.players) else null

        state.value = s.copy(
            horizontalEdges = newH,
            verticalEdges = newV,
            boxOwner = newOwner,
            scores = newScores,
            currentPlayerIndex = nextIndex,
            boardOver = allClaimed,
            winnerPlayerId = winnerId,
            lastAction = when {
                allClaimed && winnerId != null -> "${s.players.first { it.playerId == winnerId }.displayName} wins the board!"
                allClaimed -> "It's a tie!"
                completedCount > 0 -> "${mover.displayName} claimed $completedCount box${if (completedCount > 1) "es" else ""} and goes again"
                else -> "${mover.displayName} drew a line"
            }
        )
    }

    /**
     * Bounded bot, mirroring DominoGame.playBotTurn()'s own shape: choose
     * one move via [chooseBotMove], play it, then — since completing a box
     * grants an extra turn — re-check whether it's STILL this bot's turn
     * and recurse if so. [chooseBotMove] always returns a real move while
     * the board isn't over (there's always at least one undrawn edge), so
     * this terminates once the board actually ends.
     *
     * Also bails out once the whole session has already ended via
     * [leaveSession]/[endMatch], same as [drawEdge] (see that method's
     * KDoc) — required here specifically to keep this method's own
     * termination argument true: without it, a `matchOver` session with a
     * still-unfinished board (`boardOver` stays false; [leaveSession] never
     * touches it) would make every [drawEdge] call silently no-op forever
     * while this method kept recursing, an infinite-recursion regression
     * this guard prevents rather than one this method already had.
     */
    fun playBotTurn() {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver) return
        val bot = s.players[s.currentPlayerIndex]
        if (!bot.isBot) return

        val move = chooseBotMove(s) ?: return
        val (isHorizontal, row, col) = move
        drawEdge(isHorizontal, row, col)

        val after = state.value
        if (after != null && !after.boardOver && after.players[after.currentPlayerIndex].isBot) {
            playBotTurn()
        }
    }

    /**
     * EASY: uniform random over every legal edge, no strategy at all —
     * will occasionally complete boxes by accident and just as often hand
     * them away, genuinely weak, same spirit as DominoGame's own EASY tier.
     *
     * MEDIUM and HARD both: (1) if any move completes one or more boxes
     * right now, take the one completing the MOST (never leaves a free box
     * on the table); else (2) if any "safe" move exists — one that doesn't
     * bring any box to exactly 3 drawn edges — play a random safe move;
     * else (3) forced to sacrifice a box somewhere. HARD picks the
     * sacrifice that opens the SMALLEST chain reaction (via
     * [chainSizeIfOpened]'s one-ply simulation of "how many boxes could the
     * opponent immediately sweep up from here"); MEDIUM just picks
     * randomly among the forced options, same "no chain-awareness when
     * cornered" honest limitation DominoGame's own MEDIUM tier has relative
     * to HARD.
     */
    private fun chooseBotMove(s: DotsAndBoxesState): Triple<Boolean, Int, Int>? {
        val candidates = allUndrawnEdges(s)
        if (candidates.isEmpty()) return null
        if (difficulty == CpuDifficulty.EASY) return candidates.random()

        val completing = candidates.filter { boxesCompletedIfDrawn(s, it) > 0 }
        if (completing.isNotEmpty()) return completing.maxByOrNull { boxesCompletedIfDrawn(s, it) }!!

        val safe = candidates.filter { isSafeMove(s, it) }
        if (safe.isNotEmpty()) return safe.random()

        return when (difficulty) {
            CpuDifficulty.HARD -> candidates.minByOrNull { chainSizeIfOpened(s, it) }!!
            else -> candidates.random()
        }
    }

    private fun finishBoard(scores: List<Int>, players: List<DotsAndBoxesPlayerState>): String? {
        val maxScore = scores.max()
        val leaders = players.indices.filter { scores[it] == maxScore }
        return if (leaders.size == 1) {
            val winner = players[leaders[0]]
            sessionWins.value = sessionWins.value + (winner.playerId to (sessionWins.value[winner.playerId] ?: 0) + 1)
            winner.playerId
        } else {
            sessionDraws.value += 1
            null
        }
    }

    private fun setEdge(edges: List<Boolean>, index: Int): List<Boolean> =
        edges.toMutableList().also { it[index] = true }

    private fun isEdgeDrawn(s: DotsAndBoxesState, isHorizontal: Boolean, row: Int, col: Int): Boolean =
        if (isHorizontal) s.horizontalEdges[row * s.boxCols + col] else s.verticalEdges[row * (s.boxCols + 1) + col]

    private fun isValidEdge(isHorizontal: Boolean, row: Int, col: Int, boxRows: Int, boxCols: Int): Boolean =
        if (isHorizontal) row in 0..boxRows && col in 0 until boxCols
        else row in 0 until boxRows && col in 0..boxCols

    /** Every box index (0 until totalBoxes) bordered by the given edge — 1 for an edge on the board's outer rim, 2 for an interior edge. */
    private fun affectedBoxes(isHorizontal: Boolean, row: Int, col: Int, boxRows: Int, boxCols: Int): List<Int> {
        val result = mutableListOf<Int>()
        if (isHorizontal) {
            if (row > 0) result += (row - 1) * boxCols + col // box above
            if (row < boxRows) result += row * boxCols + col // box below
        } else {
            if (col > 0) result += row * boxCols + (col - 1) // box to the left
            if (col < boxCols) result += row * boxCols + col // box to the right
        }
        return result
    }

    private fun countDrawnEdges(boxRow: Int, boxCol: Int, h: List<Boolean>, v: List<Boolean>, boxCols: Int): Int {
        var count = 0
        if (h[boxRow * boxCols + boxCol]) count++
        if (h[(boxRow + 1) * boxCols + boxCol]) count++
        if (v[boxRow * (boxCols + 1) + boxCol]) count++
        if (v[boxRow * (boxCols + 1) + boxCol + 1]) count++
        return count
    }

    /** Which of a 3-edge box's 4 edges is the missing one — used only by [chainSizeIfOpened]'s simulation to "complete" a box it finds at exactly 3 drawn edges. Undefined or run past its use if called on anything but a genuinely 3-edge box. */
    private fun missingEdgeOf(boxRow: Int, boxCol: Int, h: List<Boolean>, v: List<Boolean>, boxCols: Int): Pair<Boolean, Int> {
        val topIdx = boxRow * boxCols + boxCol
        val bottomIdx = (boxRow + 1) * boxCols + boxCol
        val leftIdx = boxRow * (boxCols + 1) + boxCol
        val rightIdx = boxRow * (boxCols + 1) + boxCol + 1
        if (!h[topIdx]) return true to topIdx
        if (!h[bottomIdx]) return true to bottomIdx
        if (!v[leftIdx]) return false to leftIdx
        return false to rightIdx
    }

    private fun allUndrawnEdges(s: DotsAndBoxesState): List<Triple<Boolean, Int, Int>> {
        val result = mutableListOf<Triple<Boolean, Int, Int>>()
        for (r in 0..s.boxRows) for (c in 0 until s.boxCols) {
            if (!s.horizontalEdges[r * s.boxCols + c]) result += Triple(true, r, c)
        }
        for (r in 0 until s.boxRows) for (c in 0..s.boxCols) {
            if (!s.verticalEdges[r * (s.boxCols + 1) + c]) result += Triple(false, r, c)
        }
        return result
    }

    private fun boxesCompletedIfDrawn(s: DotsAndBoxesState, edge: Triple<Boolean, Int, Int>): Int {
        val (isH, row, col) = edge
        val newH = if (isH) setEdge(s.horizontalEdges, row * s.boxCols + col) else s.horizontalEdges
        val newV = if (!isH) setEdge(s.verticalEdges, row * (s.boxCols + 1) + col) else s.verticalEdges
        val affected = affectedBoxes(isH, row, col, s.boxRows, s.boxCols)
        return affected.count { boxIndex ->
            val br = boxIndex / s.boxCols
            val bc = boxIndex % s.boxCols
            countDrawnEdges(br, bc, newH, newV, s.boxCols) == 4
        }
    }

    /** True iff drawing [edge] would NOT bring any adjacent box to exactly 3 drawn edges (i.e. wouldn't hand the opponent a free box next turn). Only meaningful for an edge that doesn't itself complete a box — callers already filter those out first. */
    private fun isSafeMove(s: DotsAndBoxesState, edge: Triple<Boolean, Int, Int>): Boolean {
        val (isH, row, col) = edge
        val newH = if (isH) setEdge(s.horizontalEdges, row * s.boxCols + col) else s.horizontalEdges
        val newV = if (!isH) setEdge(s.verticalEdges, row * (s.boxCols + 1) + col) else s.verticalEdges
        val affected = affectedBoxes(isH, row, col, s.boxRows, s.boxCols)
        return affected.all { boxIndex ->
            val br = boxIndex / s.boxCols
            val bc = boxIndex % s.boxCols
            countDrawnEdges(br, bc, newH, newV, s.boxCols) != 3
        }
    }

    /**
     * Simulates drawing [edge] (which — since it's only ever called on a
     * move that isn't safe — brings at least one box to exactly 3 drawn
     * edges) and then repeatedly "completing" every 3-edge box it finds
     * (drawing that box's one missing edge), which may itself expose
     * further 3-edge boxes in a chain. Returns the total number of boxes
     * swept up this way — HARD uses this purely as an evaluation heuristic
     * (a one-ply "how bad is this sacrifice" estimate assuming the
     * opponent greedily takes every box available), never to actually
     * mutate real game state.
     */
    private fun chainSizeIfOpened(s: DotsAndBoxesState, edge: Triple<Boolean, Int, Int>): Int {
        val (isH, row, col) = edge
        var h = if (isH) setEdge(s.horizontalEdges, row * s.boxCols + col) else s.horizontalEdges
        var v = if (!isH) setEdge(s.verticalEdges, row * (s.boxCols + 1) + col) else s.verticalEdges

        var completed = 0
        var changed = true
        while (changed) {
            changed = false
            for (boxIndex in 0 until s.totalBoxes) {
                val br = boxIndex / s.boxCols
                val bc = boxIndex % s.boxCols
                if (countDrawnEdges(br, bc, h, v, s.boxCols) == 3) {
                    val (missingIsH, missingIndex) = missingEdgeOf(br, bc, h, v, s.boxCols)
                    if (missingIsH) h = setEdge(h, missingIndex) else v = setEdge(v, missingIndex)
                    completed++
                    changed = true
                }
            }
        }
        return completed
    }

    private companion object {
        const val BOX_ROWS = 5
        const val BOX_COLS = 5
    }
}
