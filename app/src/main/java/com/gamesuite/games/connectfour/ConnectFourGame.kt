package com.gamesuite.games.connectfour

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty

/** A player's identity + bot flag — mirrors DotsAndBoxesPlayerState's own shape, kept as a list even though this game is always exactly 2 players, for consistency with that established idiom. */
data class ConnectFourPlayerState(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean
)

/**
 * [cells] is row-major ([rows] x [cols]); each entry is the index into
 * [players] who owns that disc, or null if empty. Row 0 is the TOP row —
 * dropping into a column (see [ConnectFourGame.dropDisc]) always lands in
 * the largest-index (bottommost) empty row of that column, the real game's
 * gravity rule.
 */
data class ConnectFourState(
    val rows: Int,
    val cols: Int,
    val cells: List<Int?>,
    val players: List<ConnectFourPlayerState>,
    val currentPlayerIndex: Int,
    val lastAction: String,
    /** True once a player has connected four or the board is completely full (a draw) — distinct from [ConnectFourGame.matchOver], which only flips once the whole session ends. */
    val boardOver: Boolean = false,
    /** The winning playerId once [boardOver], or null for a draw. */
    val winnerPlayerId: String? = null,
    /** Every connected cell index that made up the winning four-plus-in-a-row, so the UI can highlight it. Null while in progress or on a draw. May hold more than 4 indices if the winning move happened to complete a longer run. */
    val winningLine: List<Int>? = null
)

/**
 * Connect Four: players alternate dropping a disc into one of [ConnectFourState.cols]
 * columns; gravity drops it to the lowest empty cell in that column; the
 * first to connect four discs in a row — horizontally, vertically, or
 * diagonally in either direction — wins. Standard 7-wide x 6-tall board (see
 * [ROWS]/[COLS]). The second two-player game in this batch (after Dots and
 * Boxes) — reuses that engine's own session shape almost exactly
 * (`sessionWins`/`sessionDraws`/`matchOver`/alternating starting player via
 * `nextStartingPlayerIndex`/`leaveSession`/`playAgain`), since both are
 * fixed-board, `difficulty`-tunes-the-bot 2-player games, not scaled solo
 * puzzles. Unlike Dots and Boxes, there is no "go again" rule here — every
 * drop always passes the turn, whether or not it was part of a longer setup —
 * so [playBotTurn] never needs to recurse the way
 * `DotsAndBoxesGame.playBotTurn` does.
 *
 * BOT STRATEGY (see [chooseBotMove]/[minimax]): a real minimax search with
 * alpha-beta pruning, scaled by SEARCH DEPTH per [CpuDifficulty] tier
 * (`EASY`=2 ply, `MEDIUM`=4, `HARD`=6 — see [difficultySearchDepth]) rather
 * than a full solve — Connect Four's full game tree (~4.5 trillion legal
 * positions) is far too large for that, unlike Tic-Tac-Toe's, which this
 * app's own `TicTacToeGame.minimaxBestMove` can afford to search exhaustively.
 * A depth-limited search needs a position EVALUATION for the non-terminal
 * cutoff (see [evaluate]/[scoreWindow]) — the standard "score every possible
 * 4-cell window by how many of the mover's/opponent's discs it already
 * contains, plus a flat center-column bonus" heuristic, a well-known
 * technique for this exact game (a center disc participates in more
 * possible winning lines than an edge one). Legal columns are searched
 * center-first (see [columnSearchOrder]) purely as alpha-beta MOVE ORDERING
 * — a center move is essentially always at least as strong as an edge one
 * here, so trying it first lets pruning cut off far more of the tree, not a
 * gameplay rule of its own. Deliberately fully deterministic at every tier
 * (no `.random()` tie-break the way `DotsAndBoxesGame`'s bot uses one) — a
 * depth-2 EASY search is already weak/beatable on its own merits (it can't
 * see a multi-move trap coming), so no added randomness was needed to make
 * it beatable, and staying deterministic keeps this bot's behavior
 * reproducible and directly testable.
 *
 * DELIBERATE SCOPE CUT, honest MVP, same spirit as this batch's other
 * documented cuts: no opening book, no bitboard/transposition-table
 * optimization, no iterative deepening — a plain depth-limited alpha-beta
 * search is already fast enough on a 6x7 board at these depths (worst case
 * on the order of `7^6` nodes before pruning, and pruning cuts that down
 * substantially with center-first ordering) for real-time play on a phone.
 * A stronger, iterative-deepening/transposition-table engine is a real
 * future upgrade, not required for a correct, playable first version.
 */
class ConnectFourGame : GameModule {
    override val gameId = "connect-four"
    override val displayName = "Connect Four"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT
    )

    val state = mutableStateOf<ConnectFourState?>(null)

    /** Boards won this session, keyed by playerId; a drawn board increments nobody (see [sessionDraws] instead). Mirrors DotsAndBoxesGame's own shape. */
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val sessionDraws = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via the board-over panel), not per-board. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** Alternates who opens each new board (including across Play Again rounds), for fairness — same idiom TicTacToeGame/DotsAndBoxesGame's own Play Again uses. */
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
        val players = context.players.map { ConnectFourPlayerState(it.playerId, it.displayName, it.isBot) }
        // A fresh board is always playable, regardless of whether a PRIOR
        // board's endMatch() left matchOver stuck true -- built in from the
        // start here (not found after the fact) since this exact fix was
        // already known to be needed from Minesweeper/Sudoku/Lights Out/
        // Dots and Boxes/Color Flood, all of which needed it.
        matchOver.value = false
        state.value = ConnectFourState(
            rows = ROWS,
            cols = COLS,
            cells = List(ROWS * COLS) { null },
            players = players,
            currentPlayerIndex = nextStartingPlayerIndex % players.size,
            lastAction = "Game started"
        )
        nextStartingPlayerIndex = (nextStartingPlayerIndex + 1) % players.size
    }

    /** No solve timer here (this is a 2-player game, not a solo puzzle — same as DotsAndBoxesGame/TicTacToeGame), so there's no pause/resume state to track. */
    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /**
     * Called from the board-over panel's "Back to Menu" button — ends the
     * whole session (not just the current board), reporting the session's
     * cumulative boards-won tally. Mirrors DotsAndBoxesGame.leaveSession().
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
     * session. Reuses [startMatch] itself, same as DotsAndBoxesGame.playAgain().
     */
    fun playAgain() {
        if (matchOver.value) return
        startMatch()
    }

    /**
     * Drops the current player's disc into [col]: it falls to the lowest
     * empty row of that column (see [lowestEmptyRow]). No-op if the board is
     * already over, [col] is out of range or already full (no empty row), or
     * the whole session has already ended via [leaveSession]/[endMatch] —
     * the same `matchOver`-not-checked gap found and fixed across every
     * other engine in this batch (Minesweeper/Sudoku/Lights Out/Dots and
     * Boxes/Color Flood — see those classes' own KDocs), built in here from
     * the start rather than waiting to rediscover it a sixth time.
     */
    fun dropDisc(col: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver) return
        if (col !in 0 until s.cols) return
        val row = lowestEmptyRow(s.cells, col, s.rows, s.cols) ?: return // column full

        val newCells = s.cells.toMutableList()
        val index = row * s.cols + col
        newCells[index] = s.currentPlayerIndex

        val winningLine = findWinningLine(newCells, s.rows, s.cols, index, s.currentPlayerIndex)
        val boardFull = winningLine == null && newCells.none { it == null }
        val boardOver = winningLine != null || boardFull
        val mover = s.players[s.currentPlayerIndex]
        val winnerId = if (winningLine != null) mover.playerId else null

        if (boardOver) {
            if (winnerId != null) {
                sessionWins.value = sessionWins.value + (winnerId to (sessionWins.value[winnerId] ?: 0) + 1)
            } else {
                sessionDraws.value += 1
            }
        }

        state.value = s.copy(
            cells = newCells,
            currentPlayerIndex = if (boardOver) s.currentPlayerIndex else (s.currentPlayerIndex + 1) % s.players.size,
            boardOver = boardOver,
            winnerPlayerId = winnerId,
            winningLine = winningLine,
            lastAction = when {
                winningLine != null -> "${mover.displayName} connects four and wins!"
                boardFull -> "It's a tie!"
                else -> "${mover.displayName} dropped a disc in column ${col + 1}"
            }
        )
    }

    /**
     * Bounded bot: choose one column via [chooseBotMove] and play it. Unlike
     * `DotsAndBoxesGame.playBotTurn`, this never recurses — there is no
     * "go again" rule in Connect Four, every drop passes the turn (or ends
     * the board). No-op if the board/match is already over or the current
     * player isn't actually a bot, so it's safe to call speculatively.
     */
    fun playBotTurn() {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver) return
        val bot = s.players[s.currentPlayerIndex]
        if (!bot.isBot) return

        val col = chooseBotMove(s) ?: return
        dropDisc(col)
    }

    /** Search depth (in plies) per [CpuDifficulty] tier — see this class's own KDoc for why this is depth-scaled rather than a full solve. */
    private val difficultySearchDepth: Map<CpuDifficulty, Int> = mapOf(
        CpuDifficulty.EASY to 2,
        CpuDifficulty.MEDIUM to 4,
        CpuDifficulty.HARD to 6
    )

    /**
     * Runs [minimax] from [s]'s current position for every legal column and
     * returns the single best-scoring one (ties keep the FIRST found, which
     * — since [columnSearchOrder] is center-first — means a tie is broken
     * toward the center, itself a reasonable positional default). Null only
     * if every column is already full (shouldn't be reachable: [playBotTurn]
     * only calls this while `!s.boardOver`, and a full board is always
     * `boardOver`).
     *
     * Threads the running [alpha] forward across SIBLING top-level columns
     * (not just down into each one's own subtree) — once one column's exact
     * score is known, every later sibling only needs to prove "better than
     * that" rather than being searched with a wide-open window, letting its
     * own internal alpha-beta cut off more of the tree. This is the standard
     * root-level move-ordering technique and provably can't change WHICH
     * column ends up chosen (only ever tightens `alpha`, never `beta`, so
     * either a sibling's exact score is still returned when it truly beats
     * the current best, or it's returned as some bound already known to be
     * no better — which correctly still loses the `score > bestScore`
     * comparison either way).
     */
    private fun chooseBotMove(s: ConnectFourState): Int? {
        val depth = difficultySearchDepth[difficulty] ?: difficultySearchDepth.getValue(CpuDifficulty.MEDIUM)
        val bot = s.currentPlayerIndex
        val opponent = (bot + 1) % s.players.size
        val cells = s.cells.toTypedArray()

        var alpha = Int.MIN_VALUE
        var bestScore = Int.MIN_VALUE
        var bestCol: Int? = null
        for (col in columnSearchOrder) {
            val row = lowestEmptyRow(cells.asList(), col, s.rows, s.cols) ?: continue
            val index = row * s.cols + col
            cells[index] = bot
            val score = minimax(
                cells, s.rows, s.cols, depth = depth - 1,
                alpha = alpha, beta = Int.MAX_VALUE, maximizing = false,
                bot = bot, opponent = opponent, lastMoveIndex = index, lastMover = bot
            )
            cells[index] = null
            if (score > bestScore) {
                bestScore = score
                bestCol = col
                alpha = maxOf(alpha, bestScore)
            }
        }
        return bestCol
    }

    /**
     * Alpha-beta minimax, always evaluated from [bot]'s own perspective
     * (positive is good for [bot], regardless of whose ply it actually is —
     * `maximizing` tracks whose turn it is, [bot] itself never changes
     * across one top-level [chooseBotMove] search). [depth] counts DOWN
     * (plies remaining), not up — a terminal win/loss found while [depth]
     * is still large happened SOONER (fewer plies were consumed to reach
     * it), so scoring the win bonus as `+depth` (and the loss penalty as
     * `-depth`) naturally prefers a faster win / slower loss, the same
     * "prefer the quicker forced outcome" idea `TicTacToeGame.minimax` uses
     * with its own (oppositely-directed, elapsed-not-remaining) depth
     * counter.
     */
    private fun minimax(
        cells: Array<Int?>, rows: Int, cols: Int, depth: Int,
        alpha: Int, beta: Int, maximizing: Boolean,
        bot: Int, opponent: Int, lastMoveIndex: Int, lastMover: Int
    ): Int {
        if (findWinningLine(cells.asList(), rows, cols, lastMoveIndex, lastMover) != null) {
            return if (lastMover == bot) WIN_SCORE + depth else -WIN_SCORE - depth
        }
        // Each legal column's landing row is computed exactly once here (not
        // once to build a "which columns are legal" list and then AGAIN per
        // column in the search loop below) -- already in center-first order
        // since columnSearchOrder is iterated to build it.
        val legalMoves = columnSearchOrder.mapNotNull { col -> lowestEmptyRow(cells.asList(), col, rows, cols)?.let { row -> col to row } }
        if (legalMoves.isEmpty()) return 0 // board full with no winner -- a draw
        if (depth == 0) return evaluate(cells, rows, cols, bot, opponent)

        val player = if (maximizing) bot else opponent
        var a = alpha
        var b = beta
        var best = if (maximizing) Int.MIN_VALUE else Int.MAX_VALUE
        for ((col, row) in legalMoves) {
            val index = row * cols + col
            cells[index] = player
            val score = minimax(cells, rows, cols, depth - 1, a, b, !maximizing, bot, opponent, index, player)
            cells[index] = null
            if (maximizing) {
                best = maxOf(best, score)
                a = maxOf(a, best)
            } else {
                best = minOf(best, score)
                b = minOf(b, best)
            }
            // Alpha-beta cutoff: the side above us in the tree already has a
            // better option elsewhere, so it will never let play reach this
            // branch -- no need to keep exploring it.
            if (b <= a) break
        }
        return best
    }

    /**
     * Center-first column order — pure alpha-beta MOVE-ORDERING (tried first
     * so pruning cuts off more of the tree earlier), not a gameplay rule;
     * see this class's own KDoc. Computed ONCE (not a function re-sorting
     * the same 7-element list on every single node of every search — this
     * board's size never changes at runtime, unlike Minesweeper/Sudoku/
     * Lights Out's difficulty-scaled boards), since [minimax] can visit
     * many thousands of nodes at HARD's depth.
     */
    private val columnSearchOrder: List<Int> = (0 until COLS).sortedBy { kotlin.math.abs(it - COLS / 2) }

    /**
     * Non-terminal position estimate for [minimax]'s depth cutoff: a flat
     * bonus per [bot] disc in the center column (a center disc sits on more
     * possible 4-in-a-row lines than an edge one), plus [scoreWindow] summed
     * over every possible 4-cell window (horizontal/vertical/both
     * diagonals) on the board — the standard, well-known Connect Four
     * heuristic. Always from [bot]'s own perspective (see [minimax]'s KDoc).
     */
    private fun evaluate(cells: Array<Int?>, rows: Int, cols: Int, bot: Int, opponent: Int): Int {
        var score = 0
        val centerCol = cols / 2
        score += (0 until rows).count { cells[it * cols + centerCol] == bot } * CENTER_COLUMN_WEIGHT

        for (r in 0 until rows) for (c in 0..cols - WIN_LENGTH) {
            score += scoreWindow((0 until WIN_LENGTH).map { cells[r * cols + c + it] }, bot, opponent)
        }
        for (c in 0 until cols) for (r in 0..rows - WIN_LENGTH) {
            score += scoreWindow((0 until WIN_LENGTH).map { cells[(r + it) * cols + c] }, bot, opponent)
        }
        for (r in 0..rows - WIN_LENGTH) for (c in 0..cols - WIN_LENGTH) {
            score += scoreWindow((0 until WIN_LENGTH).map { cells[(r + it) * cols + c + it] }, bot, opponent)
        }
        for (r in WIN_LENGTH - 1 until rows) for (c in 0..cols - WIN_LENGTH) {
            score += scoreWindow((0 until WIN_LENGTH).map { cells[(r - it) * cols + c + it] }, bot, opponent)
        }
        return score
    }

    /** How good a single 4-cell [window] is for [bot] — more of [bot]'s own discs with the rest empty is good, more of [opponent]'s discs with the rest empty is bad (weighted slightly higher than the equivalent bot threat, since leaving an opponent's 3-with-a-gap unblocked is usually more urgent than building one's own). A window already containing BOTH players' discs can never become a line for either, so it scores 0 — this is only ever reached for a genuinely non-terminal position ([minimax] checks for an actual 4-in-a-row separately), so a 4-of-bot's/4-of-opponent's window can't actually occur here in practice, but the cases are handled defensively anyway. */
    private fun scoreWindow(window: List<Int?>, bot: Int, opponent: Int): Int {
        val botCount = window.count { it == bot }
        val opponentCount = window.count { it == opponent }
        if (botCount > 0 && opponentCount > 0) return 0 // window is dead -- can never complete for either side
        val emptyCount = window.count { it == null }
        return when {
            botCount == 4 -> WIN_SCORE
            botCount == 3 && emptyCount == 1 -> 50
            botCount == 2 && emptyCount == 2 -> 5
            opponentCount == 3 && emptyCount == 1 -> -80
            opponentCount == 4 -> -WIN_SCORE
            else -> 0
        }
    }

    /** The largest (bottommost) row index in [col] whose cell is empty, i.e. where a dropped disc would land — null if [col] is completely full. */
    private fun lowestEmptyRow(cells: List<Int?>, col: Int, rows: Int, cols: Int): Int? {
        for (r in rows - 1 downTo 0) {
            if (cells[r * cols + col] == null) return r
        }
        return null
    }

    /**
     * Checks only the (up to 4) lines actually passing through [placedIndex]
     * — the cell [player] just played — rather than rescanning the whole
     * board, since no OTHER line could have just become a win this turn.
     * Extends outward from [placedIndex] in each of the 4 line directions
     * (horizontal, vertical, both diagonals), collecting every contiguous
     * same-owner cell on both sides; returns the full connected run
     * (may be longer than [WIN_LENGTH] if the winning move extended an
     * already-long run) once any direction reaches at least [WIN_LENGTH]
     * cells, or null if none do.
     */
    private fun findWinningLine(cells: List<Int?>, rows: Int, cols: Int, placedIndex: Int, player: Int): List<Int>? {
        val placedRow = placedIndex / cols
        val placedCol = placedIndex % cols
        val directions = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        for ((dr, dc) in directions) {
            val line = mutableListOf(placedIndex)
            var r = placedRow + dr
            var c = placedCol + dc
            while (r in 0 until rows && c in 0 until cols && cells[r * cols + c] == player) {
                line += r * cols + c
                r += dr; c += dc
            }
            r = placedRow - dr
            c = placedCol - dc
            while (r in 0 until rows && c in 0 until cols && cells[r * cols + c] == player) {
                line += r * cols + c
                r -= dr; c -= dc
            }
            if (line.size >= WIN_LENGTH) return line
        }
        return null
    }

    private companion object {
        const val ROWS = 6
        const val COLS = 7
        const val WIN_LENGTH = 4
        const val CENTER_COLUMN_WEIGHT = 3
        /** Magnitude of a confirmed win/loss score — comfortably larger than any possible sum of [scoreWindow] heuristic scores (bounded by a small constant times the board's ~69 total windows), so a real win is always preferred over any merely-good heuristic position. */
        const val WIN_SCORE = 1_000_000
    }
}
