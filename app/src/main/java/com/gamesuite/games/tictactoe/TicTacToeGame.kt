package com.gamesuite.games.tictactoe

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*

/**
 * First proof-of-concept game module. Pure logic, exposed as Compose state
 * so a TicTacToeScreen composable can render it reactively. Wire a Compose
 * screen with a 3x3 grid calling cellClicked(i) on tap.
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
    val matchOver = mutableStateOf(false)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    override fun init(context: GameContext) {
        this.context = context
        board.value = IntArray(9)
        currentPlayer.value = 1
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

    /** Call this from the UI when a cell is tapped. */
    fun cellClicked(index: Int) {
        if (matchOver.value || board.value[index] != 0) return

        val newBoard = board.value.copyOf()
        newBoard[index] = currentPlayer.value
        board.value = newBoard

        val winner = checkWinner(newBoard)
        if (winner != 0 || isBoardFull(newBoard)) {
            val result = GameResult(
                scores = if (winner != 0) {
                    listOf(PlayerScore(playerId = "p$winner", score = 1, isWinner = true))
                } else emptyList()
            )
            endMatch(result)
            return
        }

        currentPlayer.value = if (currentPlayer.value == 1) 2 else 1
    }

    /**
     * Call this from the UI when it becomes a bot's turn (see TicTacToeScreen's
     * LaunchedEffect keyed on currentPlayer, mirroring MancalaScreen.playBotTurn()).
     * No-ops if the match is over or the current player isn't actually a bot,
     * so it's safe to call speculatively.
     */
    fun playBotTurn() {
        if (matchOver.value) return
        val botIndex = currentPlayer.value - 1
        if (context.players.getOrNull(botIndex)?.isBot != true) return

        val move = chooseBotMove(board.value) ?: return
        cellClicked(move)
    }

    /** Win if possible, else block the opponent's win, else prefer center, then corners, then edges. */
    private fun chooseBotMove(b: IntArray): Int? {
        val bot = currentPlayer.value
        val opponent = if (bot == 1) 2 else 1

        winningMove(b, bot)?.let { return it }
        winningMove(b, opponent)?.let { return it }

        val preferredOrder = listOf(4, 0, 2, 6, 8, 1, 3, 5, 7)
        return preferredOrder.firstOrNull { b[it] == 0 }
    }

    /** Returns the cell that completes a line for `player`, if one exists. */
    private fun winningMove(b: IntArray, player: Int): Int? {
        for (line in WIN_LINES) {
            val cells = line.map { b[it] }
            if (cells.count { it == player } == 2 && cells.count { it == 0 } == 1) {
                return line[cells.indexOf(0)]
            }
        }
        return null
    }

    private fun checkWinner(b: IntArray): Int {
        for (line in WIN_LINES) {
            val (a, c, d) = line
            if (b[a] != 0 && b[a] == b[c] && b[c] == b[d]) return b[a]
        }
        return 0
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
