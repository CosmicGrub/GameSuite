package com.gamesuite.games.tictactoe

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * First proof-of-concept game module, since upgraded with a real research
 * pass (see README item 9a): a perfect minimax bot backing HARD difficulty,
 * difficulty scaling down from it, a winning-line callout, and session-long
 * score tracking across rounds (standard tic-tac-toe is a single fixed
 * board with nothing at stake round-to-round otherwise — a running score is
 * what gives repeated play a reason to continue, mirroring the "matches are
 * a stream of rounds" shape UNO already has). Pure logic, exposed as
 * Compose state so a TicTacToeScreen composable can render it reactively.
 *
 * Variants pass (README item 13s) added [misere]: identical rules except
 * completing 3-in-a-row *loses* for whoever completed it. Every place the
 * standard rules treat "line completed" as "mover wins" is mirrored to
 * treat it as "mover loses" instead when the flag is set — the human-move
 * scoring in [cellClicked], the minimax terminal scoring in [minimax], and
 * the MEDIUM/EASY heuristic's win-seeking in [heuristicMove]. The board
 * stays a fixed 3x3 (WIN_LINES unchanged) — only the win/loss meaning flips.
 */
class TicTacToeGame : GameModule {
    override val gameId = "tic-tac-toe"
    override val displayName = "Tic-Tac-Toe"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT
    )

    // 0 = empty, 1 = P1, 2 = P2 — Compose state so UI recomposes on change.
    val board = mutableStateOf(IntArray(9))
    val currentPlayer = mutableStateOf(1)

    /** True once the current board has a winner or is full — distinct from [matchOver] below. */
    val roundOver = mutableStateOf(false)

    /** The 3 winning cell indices, so the UI can highlight them. Null while in progress or on a draw. */
    val winningLine = mutableStateOf<List<Int>?>(null)

    val scoreP1 = mutableStateOf(0)
    val scoreP2 = mutableStateOf(0)
    val draws = mutableStateOf(0)
    val roundNumber = mutableStateOf(1)

    /** True only once the whole session ends (user leaves via the round-over panel), not per-round. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** Pre-set by the UI (see TicTacToeScreen's `misere` param) before startMatch(). Reverse-win rules: false = standard. */
    var misere: Boolean = false

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** Alternates who opens each round, for fairness — reset to 1 at the start of a fresh session. */
    private var startingPlayer = 1

    override fun init(context: GameContext) {
        this.context = context
        board.value = IntArray(9)
        currentPlayer.value = 1
        startingPlayer = 1
        roundOver.value = false
        winningLine.value = null
        scoreP1.value = 0
        scoreP2.value = 0
        draws.value = 0
        roundNumber.value = 1
        matchOver.value = false
    }

    override fun startMatch() {
        // Nothing extra needed for local pass-and-play; the bot's moves are
        // driven from the UI via playBotTurn() when it becomes its turn —
        // see TicTacToeScreen's LaunchedEffect, mirroring MancalaScreen.
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Called when the shell wants to know when this match ends, e.g. to navigate back. */
    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    /**
     * Called from the round-over panel's "Back to Menu" button — ends the whole
     * session (not just the current round), reporting the cumulative score.
     */
    fun leaveSession() {
        if (matchOver.value) return
        val scores = mutableListOf<PlayerScore>()
        context.players.getOrNull(0)?.let {
            scores += PlayerScore(playerId = it.playerId, score = scoreP1.value, isWinner = scoreP1.value > scoreP2.value)
        }
        context.players.getOrNull(1)?.let {
            scores += PlayerScore(playerId = it.playerId, score = scoreP2.value, isWinner = scoreP2.value > scoreP1.value)
        }
        endMatch(GameResult(scores = scores))
    }

    /** Called from the round-over panel's "Play Again" button — keeps the running score, resets the board. */
    fun playAgain() {
        if (matchOver.value) return
        startingPlayer = if (startingPlayer == 1) 2 else 1
        roundNumber.value += 1
        board.value = IntArray(9)
        winningLine.value = null
        roundOver.value = false
        currentPlayer.value = startingPlayer
    }

    /** Call this from the UI when a cell is tapped. */
    fun cellClicked(index: Int) {
        if (roundOver.value || matchOver.value || board.value[index] != 0) return

        val newBoard = board.value.copyOf()
        newBoard[index] = currentPlayer.value
        board.value = newBoard

        val line = findWinningLine(newBoard)
        if (line != null) {
            winningLine.value = line
            // Standard: the mover just completed a line, so the mover scores.
            // Misere: completing a line LOSES, so the point goes to whoever
            // didn't complete it — currentPlayer.value is still the mover here,
            // cellClicked() returns before it ever flips to the other player.
            val loserIsMover = misere
            val scorerIsP1 = if (loserIsMover) currentPlayer.value != 1 else currentPlayer.value == 1
            if (scorerIsP1) scoreP1.value += 1 else scoreP2.value += 1
            roundOver.value = true
            return
        }
        if (isBoardFull(newBoard)) {
            draws.value += 1
            roundOver.value = true
            return
        }

        currentPlayer.value = if (currentPlayer.value == 1) 2 else 1
    }

    /**
     * Call this from the UI when it becomes a bot's turn (see TicTacToeScreen's
     * LaunchedEffect keyed on currentPlayer, mirroring MancalaScreen.playBotTurn()).
     * No-ops if the round/match is over or the current player isn't actually a
     * bot, so it's safe to call speculatively.
     */
    fun playBotTurn() {
        if (roundOver.value || matchOver.value) return
        val botIndex = currentPlayer.value - 1
        if (context.players.getOrNull(botIndex)?.isBot != true) return

        val move = chooseBotMove(board.value) ?: return
        cellClicked(move)
    }

    /**
     * HARD plays perfect minimax (see [minimaxBestMove]) — genuinely
     * unbeatable, the best a human can force is a draw, matching the known
     * game-theoretic result that tic-tac-toe is a forced draw with optimal
     * play on both sides. MEDIUM keeps the original one-ply heuristic (win
     * if possible, else block, else positional preference) — solid but not
     * exhaustive, so it's beatable with a deliberate fork. EASY mostly
     * moves at random, only reaching for the heuristic move a third of the
     * time, so it still occasionally blocks/wins but loses far more often —
     * a real skill ladder rather than three re-skins of the same bot.
     */
    private fun chooseBotMove(b: IntArray): Int? = when (difficulty) {
        CpuDifficulty.HARD -> minimaxBestMove(b, currentPlayer.value)
        CpuDifficulty.MEDIUM -> heuristicMove(b)
        CpuDifficulty.EASY -> if (Random.nextFloat() < 0.35f) heuristicMove(b) else randomMove(b)
    }

    private fun randomMove(b: IntArray): Int? = b.indices.filter { b[it] == 0 }.randomOrNull()

    /**
     * Win if possible, else block the opponent's win, else prefer center,
     * then corners, then edges. Under [misere] this naive win-seeking would
     * be actively self-destructive (the bot would race to complete its own
     * line, which loses), so that case is delegated to [misereHeuristicMove]
     * instead — see its KDoc.
     */
    private fun heuristicMove(b: IntArray): Int? {
        val bot = currentPlayer.value
        val opponent = if (bot == 1) 2 else 1

        if (misere) return misereHeuristicMove(b, bot, opponent)

        winningMove(b, bot)?.let { return it }
        winningMove(b, opponent)?.let { return it }

        val preferredOrder = listOf(4, 0, 2, 6, 8, 1, 3, 5, 7)
        return preferredOrder.firstOrNull { b[it] == 0 }
    }

    /**
     * Misere one-ply heuristic. The standard heuristic's two priorities both
     * invert: completing a line is a loss (never volunteer for it, where a
     * safe alternative exists), and there is no "block the opponent's win" —
     * the opponent doesn't want to complete a line either, so instead this
     * prefers whichever safe move leaves the opponent with the fewest safe
     * replies of their own, nudging them toward eventually being the one
     * forced to complete a line. It's a proxy for real lookahead, not exact
     * play — HARD's minimax is what actually solves misere optimally.
     */
    private fun misereHeuristicMove(b: IntArray, bot: Int, opponent: Int): Int? {
        val empty = b.indices.filter { b[it] == 0 }
        val selfLosingMoves = completingMoves(b, bot).toSet()
        val safeMoves = empty.filter { it !in selfLosingMoves }
        // If every remaining cell would complete a line, a loss this round is
        // unavoidable — fall back to considering all of them.
        val candidates = safeMoves.ifEmpty { empty }

        val preferredOrder = listOf(4, 0, 2, 6, 8, 1, 3, 5, 7)
        return candidates.minByOrNull { move ->
            val next = b.copyOf()
            next[move] = bot
            val opponentSelfLosing = completingMoves(next, opponent).toSet()
            val opponentSafeCount = next.indices.count { next[it] == 0 && it !in opponentSelfLosing }
            // Primary key: fewer safe replies left for the opponent is better.
            // Tie-break with the same positional preference used elsewhere.
            opponentSafeCount * 10 + preferredOrder.indexOf(move)
        }
    }

    /**
     * Full minimax over the (tiny — at most 9! ≈ 362,880 nodes, in practice
     * far fewer once winning lines end the search early) game tree. No
     * alpha-beta pruning needed at this size; added complexity wouldn't be
     * worth it for a 3x3 board. Depth is factored into the score so the bot
     * prefers a *faster* win and a *slower* loss when several lines lead to
     * the same outcome, matching how a skilled human actually plays rather
     * than winning "eventually" in a way that looks careless.
     */
    private fun minimaxBestMove(b: IntArray, player: Int): Int? {
        val opponent = if (player == 1) 2 else 1
        var bestScore = Int.MIN_VALUE
        var bestMove: Int? = null
        for (i in b.indices) {
            if (b[i] != 0) continue
            val next = b.copyOf()
            next[i] = player
            val score = minimax(next, depth = 1, isMaximizing = false, maximizer = player, minimizer = opponent)
            if (score > bestScore) {
                bestScore = score
                bestMove = i
            }
        }
        return bestMove
    }

    private fun minimax(b: IntArray, depth: Int, isMaximizing: Boolean, maximizer: Int, minimizer: Int): Int {
        findWinningLine(b)?.let { line ->
            val winner = b[line[0]]
            // Standard: completing the line wins for whoever completed it.
            // Misere: completing the line LOSES for whoever completed it —
            // same depth-preference (faster win / slower loss), just with
            // which side "winning the game" maps to flipped.
            val goodForMaximizer = if (misere) winner != maximizer else winner == maximizer
            return if (goodForMaximizer) 10 - depth else depth - 10
        }
        if (isBoardFull(b)) return 0

        val player = if (isMaximizing) maximizer else minimizer
        var best = if (isMaximizing) Int.MIN_VALUE else Int.MAX_VALUE
        for (i in b.indices) {
            if (b[i] != 0) continue
            val next = b.copyOf()
            next[i] = player
            val score = minimax(next, depth + 1, !isMaximizing, maximizer, minimizer)
            best = if (isMaximizing) maxOf(best, score) else minOf(best, score)
        }
        return best
    }

    /** Returns the cell that completes a line for `player`, if one exists. */
    private fun winningMove(b: IntArray, player: Int): Int? = completingMoves(b, player).firstOrNull()

    /** Returns every empty cell that would complete a line for `player` if played there. */
    private fun completingMoves(b: IntArray, player: Int): List<Int> {
        val moves = mutableListOf<Int>()
        for (line in WIN_LINES) {
            val cells = line.map { b[it] }
            if (cells.count { it == player } == 2 && cells.count { it == 0 } == 1) {
                moves += line[cells.indexOf(0)]
            }
        }
        return moves
    }

    /** Returns the 3 indices of the completed line, if any, regardless of which player completed it. */
    private fun findWinningLine(b: IntArray): List<Int>? {
        for (line in WIN_LINES) {
            val (a, c, d) = line
            if (b[a] != 0 && b[a] == b[c] && b[c] == b[d]) return line.toList()
        }
        return null
    }

    private fun isBoardFull(b: IntArray) = b.none { it == 0 }

    companion object {
        private val WIN_LINES = listOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)
        )
    }
}
