package com.gamesuite.games.mancala

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*

/**
 * Board layout (14 pits, index 0-13):
 *   0-5   = player 0's pits (left to right from player 0's perspective)
 *   6     = player 0's store
 *   7-12  = player 1's pits
 *   13    = player 1's store
 * Standard Kalah rules: sow counter-clockwise, land in your own empty pit
 * with the last stone -> capture that pit + the opposite pit into your
 * store; land in any store you own -> extra turn; game ends when one
 * side's pits are all empty -> remaining stones on the other side go to
 * that side's store.
 */
data class MancalaState(
    val pits: List<Int>,
    val currentPlayerIndex: Int,
    val lastAction: String,
    val matchOver: Boolean = false,
    val winnerPlayerId: String? = null
)

class MancalaGame : GameModule {
    override val gameId = "mancala"
    override val displayName = "Mancala"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT
    )

    val state = mutableStateOf<MancalaState?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    private val player0Pits = 0..5
    private val player0Store = 6
    private val player1Pits = 7..12
    private val player1Store = 13

    override fun init(context: GameContext) {
        this.context = context
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        val pits = MutableList(14) { 0 }
        for (i in player0Pits) pits[i] = 4
        for (i in player1Pits) pits[i] = 4
        state.value = MancalaState(pits = pits, currentPlayerIndex = 0, lastAction = "Game started")
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        state.value = state.value?.copy(matchOver = true)
        onMatchEnd?.invoke(result)
    }

    fun sow(playerIndex: Int, pitIndex: Int) {
        val s = state.value ?: return
        if (s.matchOver || playerIndex != s.currentPlayerIndex) return

        val ownPits = if (playerIndex == 0) player0Pits else player1Pits
        val ownStore = if (playerIndex == 0) player0Store else player1Store
        val opponentStore = if (playerIndex == 0) player1Store else player0Store

        if (pitIndex !in ownPits || s.pits[pitIndex] == 0) return

        val pits = s.pits.toMutableList()
        var stones = pits[pitIndex]
        pits[pitIndex] = 0
        var cursor = pitIndex

        while (stones > 0) {
            cursor = (cursor + 1) % 14
            if (cursor == opponentStore) continue // skip opponent's store
            pits[cursor]++
            stones--
        }

        var extraTurn = false
        var captureMsg = ""

        // Landed in own empty pit (was 0 before this sow, now 1) -> capture.
        if (cursor in ownPits && pits[cursor] == 1) {
            val oppositeIndex = 12 - cursor
            if (pits[oppositeIndex] > 0) {
                val captured = pits[oppositeIndex] + pits[cursor]
                pits[oppositeIndex] = 0
                pits[cursor] = 0
                pits[ownStore] += captured
                captureMsg = " and captured $captured"
            }
        } else if (cursor == ownStore) {
            extraTurn = true
        }

        val player0Empty = player0Pits.all { pits[it] == 0 }
        val player1Empty = player1Pits.all { pits[it] == 0 }
        var matchOver = false
        var winnerId: String? = null

        if (player0Empty || player1Empty) {
            if (player0Empty) { for (i in player1Pits) { pits[player1Store] += pits[i]; pits[i] = 0 } }
            if (player1Empty) { for (i in player0Pits) { pits[player0Store] += pits[i]; pits[i] = 0 } }
            matchOver = true
            winnerId = when {
                pits[player0Store] > pits[player1Store] -> context.players.getOrNull(0)?.playerId
                pits[player1Store] > pits[player0Store] -> context.players.getOrNull(1)?.playerId
                else -> null // tie
            }
        }

        val nextPlayer = if (extraTurn && !matchOver) playerIndex else (playerIndex + 1) % 2
        val player = context.players[playerIndex]

        state.value = s.copy(
            pits = pits,
            currentPlayerIndex = nextPlayer,
            lastAction = "${player.displayName} sowed from pit ${pitIndex + 1}$captureMsg",
            matchOver = matchOver,
            winnerPlayerId = winnerId
        )

        if (matchOver) {
            val scores = context.players.mapIndexed { i, p ->
                val store = if (i == 0) pits[player0Store] else pits[player1Store]
                PlayerScore(playerId = p.playerId, score = store, isWinner = p.playerId == winnerId)
            }
            endMatch(GameResult(scores = scores))
        }
    }

    fun playBotTurn() {
        val s = state.value ?: return
        if (s.matchOver) return
        val botIndex = s.currentPlayerIndex
        if (context.players.getOrNull(botIndex)?.isBot != true) return

        val ownPits = if (botIndex == 0) player0Pits else player1Pits
        val ownStore = if (botIndex == 0) player0Store else player1Store

        // Prefer a move landing exactly in the store (extra turn); else first non-empty pit.
        val bonusMove = ownPits.firstOrNull { pit -> s.pits[pit] > 0 && (pit + s.pits[pit]) % 14 == ownStore }
        val move = bonusMove ?: ownPits.firstOrNull { s.pits[it] > 0 }
        if (move != null) sow(botIndex, move)
    }
}
