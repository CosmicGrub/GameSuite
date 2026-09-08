package com.gamesuite.games.dominoes

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty

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
    /**
     * True once the current hand has ended (someone emptied their hand, or
     * everyone's blocked) -- distinct from [DominoGame.matchOver], which only
     * flips once the whole session ends (the player leaves via the
     * hand-over panel's "Back to Menu" rather than "Play Again").
     */
    val handOver: Boolean = false,
    val winnerPlayerId: String? = null
) {
    val leftEnd: Int? get() = chain.firstOrNull()?.leftValue
    val rightEnd: Int? get() = chain.lastOrNull()?.rightValue
}

/**
 * Standard double-six dominoes (28 tiles, 0-6 pips on each end). Draw hand
 * (7 for 2 players, 5 for 3-4), take turns extending either end of the
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

    /**
     * Running per-player score across hands this session, keyed by playerId
     * -- see [awardHandPoints]. Session-long scoring (mirroring
     * TicTacToeGame's scoreP1/scoreP2/draws) is what gives Play Again a
     * reason to exist: without it, every hand would just be its own
     * disconnected match.
     */
    val sessionScores = mutableStateOf<Map<String, Int>>(emptyMap())

    /** True only once the whole session ends (user leaves via the hand-over panel), not per-hand. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var boneyard: MutableList<Domino> = mutableListOf()

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        sessionScores.value = context.players.associate { it.playerId to 0 }
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
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /**
     * Called from the hand-over panel's "Back to Menu" button -- ends the
     * whole session (not just the current hand), reporting the session's
     * cumulative per-player score. Mirrors TicTacToeGame.leaveSession().
     */
    fun leaveSession() {
        if (matchOver.value) return
        val bestScore = sessionScores.value.values.maxOrNull() ?: 0
        val scores = context.players.map {
            val score = sessionScores.value[it.playerId] ?: 0
            PlayerScore(playerId = it.playerId, score = score, isWinner = bestScore > 0 && score == bestScore)
        }
        endMatch(GameResult(scores = scores))
    }

    /**
     * Called from the hand-over panel's "Play Again" button -- keeps the
     * running session score and deals a fresh hand within the same session.
     * Reuses [startMatch] itself (full reshuffle + redeal + opening-player
     * pick) rather than duplicating that setup, since "a new hand" and "the
     * first hand of the match" are the same operation. Mirrors
     * TicTacToeGame.playAgain().
     */
    fun playAgain() {
        if (matchOver.value) return
        startMatch()
    }

    /**
     * True if [domino] could legally attach to the [attachToLeft] end of the
     * current chain right now. This is the exact match rule [playDomino]
     * itself relies on internally (see below) rather than a parallel
     * re-implementation of it -- exposed as a pure, state-only read so a
     * drag-and-drop ghost/preview can query "would this be legal here?"
     * without ever risking drifting out of sync with the real rule.
     */
    fun canPlace(domino: Domino, attachToLeft: Boolean): Boolean {
        val s = state.value ?: return false
        if (s.chain.isEmpty()) return true
        val end = if (attachToLeft) s.leftEnd else s.rightEnd
        return domino.a == end || domino.b == end
    }

    /**
     * Whether placing [domino] at the [attachToLeft] end would need to render
     * it flipped (b-then-a) rather than as-is (a-then-b) to present its
     * matching pip value against the chain's exposed end. Mirrors
     * [playDomino]'s own orientation math; only meaningful when [canPlace]
     * is already true for the same arguments, and always false against an
     * empty chain (an opening tile has no forced orientation).
     */
    fun wouldFlip(domino: Domino, attachToLeft: Boolean): Boolean {
        val s = state.value ?: return false
        if (s.chain.isEmpty()) return false
        val end = if (attachToLeft) s.leftEnd else s.rightEnd
        return if (attachToLeft) domino.b != end else domino.a != end
    }

    /** attachToLeft=true plays on the left end, false plays on the right end. Auto-flips as needed. */
    fun playDomino(playerIndex: Int, domino: Domino, attachToLeft: Boolean) {
        val s = state.value ?: return
        if (s.handOver || playerIndex != s.currentPlayerIndex) return
        val player = s.players[playerIndex]
        if (!player.hand.any { it.instanceId == domino.instanceId }) return
        if (!canPlace(domino, attachToLeft)) return // doesn't match, illegal

        val newChain: List<PlacedDomino> = if (s.chain.isEmpty()) {
            listOf(PlacedDomino(domino, flipped = false))
        } else {
            val placed = PlacedDomino(domino, wouldFlip(domino, attachToLeft))
            if (attachToLeft) listOf(placed) + s.chain else s.chain + placed
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
        if (s.handOver || playerIndex != s.currentPlayerIndex || boneyard.isEmpty()) return
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
        if (s.handOver || playerIndex != s.currentPlayerIndex) return
        // Only allowed once the boneyard is empty and the player genuinely has no legal move.
        if (boneyard.isNotEmpty() || canPlay(playerIndex)) return
        val consecutivePasses = s.consecutivePasses + 1
        val blocked = consecutivePasses >= s.players.size

        state.value = s.copy(
            currentPlayerIndex = (playerIndex + 1) % s.players.size,
            consecutivePasses = consecutivePasses,
            lastAction = "${s.players[playerIndex].displayName} passed",
            handOver = blocked
        )
        if (blocked) finishBlocked()
    }

    fun canPlay(playerIndex: Int): Boolean {
        val s = state.value ?: return false
        val player = s.players[playerIndex]
        if (s.chain.isEmpty()) return player.hand.isNotEmpty()
        return player.hand.any { it.a == s.leftEnd || it.b == s.leftEnd || it.a == s.rightEnd || it.b == s.rightEnd }
    }

    /**
     * Bounded bot: play a legal domino if it has one, else draw, else pass —
     * that sequencing is the rules, not a skill lever, so it's the same at
     * every difficulty. *Which* legal domino to play is where the three
     * tiers actually differ (see [chooseBotPlay]'s KDoc); the original,
     * single "first legal, doubles-preferred-for-opening" bot lives on
     * unchanged as MEDIUM.
     */
    fun playBotTurn() {
        val s = state.value ?: return
        if (s.handOver) return
        val botIndex = s.currentPlayerIndex
        val bot = s.players[botIndex]
        if (!bot.isBot) return

        if (s.chain.isEmpty()) {
            val best = chooseOpeningPlay(bot.hand)
            if (best != null) { playDomino(botIndex, best, attachToLeft = true); return }
        }

        val legalLeft = bot.hand.filter { it.a == s.leftEnd || it.b == s.leftEnd }.map { it to true }
        val legalRight = bot.hand.filter { it.a == s.rightEnd || it.b == s.rightEnd }.map { it to false }
        val legalPlays = legalLeft + legalRight
        val chosen = chooseBotPlay(legalPlays)
        when {
            chosen != null -> playDomino(botIndex, chosen.first, attachToLeft = chosen.second)
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

    private fun chooseOpeningPlay(hand: List<Domino>): Domino? = when (difficulty) {
        CpuDifficulty.EASY -> hand.randomOrNull()
        CpuDifficulty.MEDIUM, CpuDifficulty.HARD -> hand.maxByOrNull { if (it.isDouble) it.a + 10 else it.a + it.b }
    }

    /**
     * EASY picks uniformly at random among every legal (domino, side) pair —
     * no double preference, no pip weighting, genuinely weaker than the
     * original bot rather than just relabeled. MEDIUM is the original
     * behavior byte-for-byte: try the left end first, first match wins,
     * only fall back to the right end if nothing on the hand fits left.
     * HARD applies real (if simple) dominoes strategy given only what a
     * human opponent could also see — no peeking at hidden hands — by
     * preferring to shed its heaviest tiles first: a blocked game is
     * scored on pips left in hand, so unloading high-value and double
     * tiles early minimizes that liability later, same reasoning as the
     * opening-move heuristic already used for MEDIUM/HARD above.
     */
    private fun chooseBotPlay(options: List<Pair<Domino, Boolean>>): Pair<Domino, Boolean>? {
        if (options.isEmpty()) return null
        return when (difficulty) {
            CpuDifficulty.EASY -> options.random()
            CpuDifficulty.MEDIUM -> options.first()
            CpuDifficulty.HARD -> options.maxByOrNull { (d, _) -> (if (d.isDouble) 100 else 0) + d.a + d.b }
        }
    }

    private fun finishWithWinner(playerIndex: Int) {
        val s = state.value ?: return
        val winner = s.players[playerIndex]
        awardHandPoints(winner.playerId, s.players)
        state.value = s.copy(handOver = true, winnerPlayerId = winner.playerId, lastAction = "${winner.displayName} wins!")
    }

    private fun finishBlocked() {
        val s = state.value ?: return
        val pipTotals = s.players.associate { it.playerId to it.hand.sumOf { d -> d.a + d.b } }
        val winnerId = pipTotals.minByOrNull { it.value }?.key
        awardHandPoints(winnerId, s.players)
        state.value = s.copy(winnerPlayerId = winnerId, lastAction = "Blocked — lowest pips wins")
    }

    /**
     * Standard "Draw Dominoes" hand scoring: whoever wins the hand (emptied
     * their hand, or -- in a blocked game -- held the fewest pips) scores
     * the pip total left in every OTHER player's hand, not just their own.
     * Accumulates into [sessionScores] so the running total survives
     * [playAgain]; only [leaveSession] reports it and ends the match. A null
     * [winnerId] (a genuine tie) awards nothing for that hand.
     */
    private fun awardHandPoints(winnerId: String?, players: List<DominoPlayerState>) {
        if (winnerId == null) return
        val points = players.filter { it.playerId != winnerId }.sumOf { it.hand.sumOf { d -> d.a + d.b } }
        val current = sessionScores.value
        sessionScores.value = current + (winnerId to (current[winnerId] ?: 0) + points)
    }

    private fun draw(count: Int): List<Domino> {
        val n = minOf(count, boneyard.size)
        val drawn = boneyard.take(n)
        boneyard = boneyard.drop(n).toMutableList()
        return drawn
    }
}
