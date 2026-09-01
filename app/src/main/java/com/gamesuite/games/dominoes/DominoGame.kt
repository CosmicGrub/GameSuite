package com.gamesuite.games.dominoes

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*

data class Domino(val a: Int, val b: Int, val instanceId: Int) {
    val isDouble: Boolean get() = a == b
}

data class PlacedDomino(val domino: Domino, val flipped: Boolean) {
    /** Value touching the left end of the chain as placed. */
    val leftValue: Int get() = if (flipped) domino.b else domino.a
    val rightValue: Int get() = if (flipped) domino.a else domino.b
}

data class DominoPlayerState(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean,
    val hand: List<Domino>
)

data class DominoState(
    val players: List<DominoPlayerState>,
    val boneyardSize: Int,
    val chain: List<PlacedDomino>,
    val currentPlayerIndex: Int,
    val consecutivePasses: Int,
    val lastAction: String,
    val matchOver: Boolean = false,
    val winnerPlayerId: String? = null
) {
    val leftEnd: Int? get() = chain.firstOrNull()?.leftValue
    val rightEnd: Int? get() = chain.lastOrNull()?.rightValue
}

/**
 * Standard double-six dominoes (28 tiles, 0-6 pips on each end). Draw hand
 * (7 for 2 players, 5-6 for 3-4), take turns extending either end of the
 * chain with a matching tile; draw from the boneyard if you can't play;
 * pass if the boneyard is empty and you still can't play. Game ends when a
 * player empties their hand (win) or every player is blocked (lowest total
 * pips in hand wins).
 */
class DominoGame : GameModule {
    override val gameId = "dominoes"
    override val displayName = "Dominoes"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT
    )

    val state = mutableStateOf<DominoState?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var boneyard: MutableList<Domino> = mutableListOf()

    override fun init(context: GameContext) {
        this.context = context
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        var id = 0
        val full = mutableListOf<Domino>()
        for (a in 0..6) for (b in a..6) full.add(Domino(a, b, id++))
        full.shuffle()
        boneyard = full

        val handSize = if (context.players.size <= 2) 7 else 5
        val players = context.players.map { info ->
            DominoPlayerState(
                playerId = info.playerId,
                displayName = info.displayName,
                isBot = info.isBot,
                hand = draw(handSize)
            )
        }

        // Standard convention: highest double (or highest total pips) goes first.
        val anyDouble = players.any { p -> p.hand.any { it.isDouble } }
        val startIndex = if (anyDouble) {
            players.indices.maxByOrNull { i ->
                players[i].hand.filter { it.isDouble }.maxOfOrNull { it.a } ?: -1
            } ?: 0
        } else {
            players.indices.maxByOrNull { i ->
                players[i].hand.maxOfOrNull { it.a + it.b } ?: -1
            } ?: 0
        }

        state.value = DominoState(
            players = players,
            boneyardSize = boneyard.size,
            chain = emptyList(),
            currentPlayerIndex = startIndex,
            consecutivePasses = 0,
            lastAction = "Game started"
        )
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        state.value = state.value?.copy(matchOver = true)
        onMatchEnd?.invoke(result)
    }

    /** attachToLeft=true plays on the left end, false plays on the right end. Auto-flips as needed. */
    fun playDomino(playerIndex: Int, domino: Domino, attachToLeft: Boolean) {
        val s = state.value ?: return
        if (s.matchOver || playerIndex != s.currentPlayerIndex) return
        val player = s.players[playerIndex]
        if (!player.hand.any { it.instanceId == domino.instanceId }) return

        val newChain: List<PlacedDomino>
        if (s.chain.isEmpty()) {
            newChain = listOf(PlacedDomino(domino, flipped = false))
        } else {
            val end = if (attachToLeft) s.leftEnd else s.rightEnd
            val flipped = if (attachToLeft) domino.b != end else domino.a != end
            if (!(domino.a == end || domino.b == end)) return // doesn't match, illegal
            val placed = PlacedDomino(domino, flipped)
            newChain = if (attachToLeft) listOf(placed) + s.chain else s.chain + placed
        }

        val newHand = player.hand.filterNot { it.instanceId == domino.instanceId }
        val updatedPlayers = s.players.toMutableList()
        updatedPlayers[playerIndex] = player.copy(hand = newHand)

        val nextIndex = (playerIndex + 1) % s.players.size
        state.value = s.copy(
            players = updatedPlayers,
            chain = newChain,
            currentPlayerIndex = nextIndex,
            consecutivePasses = 0,
            lastAction = "${player.displayName} played ${domino.a}-${domino.b}"
        )

        if (newHand.isEmpty()) finishWithWinner(playerIndex)
    }

    fun drawFromBoneyard(playerIndex: Int) {
        val s = state.value ?: return
        if (s.matchOver || playerIndex != s.currentPlayerIndex || boneyard.isEmpty()) return
        if (canPlay(playerIndex)) return // must play a legal tile instead of drawing
        val player = s.players[playerIndex]
        val drawn = draw(1)
        val updatedPlayers = s.players.toMutableList()
        updatedPlayers[playerIndex] = player.copy(hand = player.hand + drawn)
        state.value = s.copy(
            players = updatedPlayers,
            boneyardSize = boneyard.size,
            lastAction = "${player.displayName} drew a tile"
        )
    }

    fun pass(playerIndex: Int) {
        val s = state.value ?: return
        if (s.matchOver || playerIndex != s.currentPlayerIndex) return
        // Only allowed once the boneyard is empty and the player genuinely has no legal move.
        if (boneyard.isNotEmpty() || canPlay(playerIndex)) return
        val consecutivePasses = s.consecutivePasses + 1
        val blocked = consecutivePasses >= s.players.size

        state.value = s.copy(
            currentPlayerIndex = (playerIndex + 1) % s.players.size,
            consecutivePasses = consecutivePasses,
            lastAction = "${s.players[playerIndex].displayName} passed",
            matchOver = blocked
        )
        if (blocked) finishBlocked()
    }

    fun canPlay(playerIndex: Int): Boolean {
        val s = state.value ?: return false
        val player = s.players[playerIndex]
        if (s.chain.isEmpty()) return player.hand.isNotEmpty()
        return player.hand.any { it.a == s.leftEnd || it.b == s.leftEnd || it.a == s.rightEnd || it.b == s.rightEnd }
    }

    /** Bounded bot: play the first legal domino it finds (prefers doubles), else draw, else pass. */
    fun playBotTurn() {
        val s = state.value ?: return
        if (s.matchOver) return
        val botIndex = s.currentPlayerIndex
        val bot = s.players[botIndex]
        if (!bot.isBot) return

        if (s.chain.isEmpty()) {
            val best = bot.hand.maxByOrNull { if (it.isDouble) it.a + 10 else it.a + it.b }
            if (best != null) { playDomino(botIndex, best, attachToLeft = true); return }
        }

        val playableLeft = bot.hand.firstOrNull { it.a == s.leftEnd || it.b == s.leftEnd }
        val playableRight = bot.hand.firstOrNull { it.a == s.rightEnd || it.b == s.rightEnd }
        when {
            playableLeft != null -> playDomino(botIndex, playableLeft, attachToLeft = true)
            playableRight != null -> playDomino(botIndex, playableRight, attachToLeft = false)
            boneyard.isNotEmpty() -> {
                drawFromBoneyard(botIndex)
                // Re-evaluate with the newly drawn tile: play it if it now fits, keep
                // drawing while the boneyard still has tiles, or fall through to pass()
                // once it's empty. The boneyard shrinks by one each call, so this
                // terminates instead of leaving the bot (and the match) stuck.
                playBotTurn()
            }
            else -> pass(botIndex)
        }
    }

    private fun finishWithWinner(playerIndex: Int) {
        val s = state.value ?: return
        val winner = s.players[playerIndex]
        val scores = s.players.map {
            PlayerScore(playerId = it.playerId, score = it.hand.sumOf { d -> d.a + d.b }, isWinner = it.playerId == winner.playerId)
        }
        state.value = s.copy(matchOver = true, winnerPlayerId = winner.playerId, lastAction = "${winner.displayName} wins!")
        endMatch(GameResult(scores = scores))
    }

    private fun finishBlocked() {
        val s = state.value ?: return
        val pipTotals = s.players.associate { it.playerId to it.hand.sumOf { d -> d.a + d.b } }
        val winnerId = pipTotals.minByOrNull { it.value }?.key
        val scores = s.players.map { PlayerScore(playerId = it.playerId, score = pipTotals[it.playerId] ?: 0, isWinner = it.playerId == winnerId) }
        state.value = s.copy(winnerPlayerId = winnerId, lastAction = "Blocked — lowest pips wins")
        endMatch(GameResult(scores = scores))
    }

    private fun draw(count: Int): List<Domino> {
        val n = minOf(count, boneyard.size)
        val drawn = boneyard.take(n)
        boneyard = boneyard.drop(n).toMutableList()
        return drawn
    }
}
