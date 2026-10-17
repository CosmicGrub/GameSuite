package com.gamesuite.games.dominoes

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything needed to put a Dominoes session back exactly as it was, hidden information included:
 * both (all) hands, the boneyard IN ORDER (index 0 is the next tile drawn), the chain with each
 * tile's orientation, whose turn it is, the consecutive-pass count, the running session scores and
 * the count of finished hands. One shape serves Resume (disk) and Undo (memory).
 */
@Serializable
private data class DominoSnapshot(
    val players: List<DominoSnapshotPlayer>,
    val boneyard: List<DominoSnapshotTile>,
    val chain: List<DominoSnapshotPlaced>,
    val currentPlayerIndex: Int,
    val consecutivePasses: Int,
    val lastAction: String,
    val handOver: Boolean,
    val winnerPlayerId: String?,
    val sessionScores: Map<String, Int>,
    val handsFinished: Int,
    val difficulty: String
)

@Serializable
private data class DominoSnapshotTile(val a: Int, val b: Int, val id: Int)

@Serializable
private data class DominoSnapshotPlaced(val tile: DominoSnapshotTile, val flipped: Boolean)

@Serializable
private data class DominoSnapshotPlayer(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean,
    val hand: List<DominoSnapshotTile>
)

private val dominoSnapshotJson = Json { ignoreUnknownKeys = true }

private const val DOMINO_SET_SIZE = 28

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
class DominoGame : GameModule, ResumableGame, UndoableGame {
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

    /**
     * Hub "who plays first" override: the roster seat that opens the FIRST hand of the session
     * (consumed by the first [startMatch]; later hands, resumes and null use the standard rule
     * of highest double / heaviest tile). Seat 0 is left to that standard rule by the screen
     * (it only sets this for another seat), because the rule is fair and not "seat 0 always".
     */
    var firstOpenerIndex: Int? = null

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var boneyard: MutableList<Domino> = mutableListOf()

    /**
     * Hands this session that reached their own end (a win or a blocked hand). The screen uses it to
     * decide whether leaving mid-hand aborts or scores the session; it is part of the snapshot so a
     * resumed (or undone) session keeps it honest.
     */
    val handsFinished = mutableStateOf(0)

    /** Snapshots taken just BEFORE each human action (play, draw, pass), newest last; popping one is Undo. Per hand. */
    private val history = SnapshotHistory()

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        sessionScores.value = context.players.associate { it.playerId to 0 }
        handsFinished.value = 0
        history.clear()
        firstOpenerIndex = null
    }

    // --- Resume -----------------------------------------------------------------------------

    private fun tileToSnap(d: Domino) = DominoSnapshotTile(d.a, d.b, d.instanceId)

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return dominoSnapshotJson.encodeToString(
            DominoSnapshot.serializer(),
            DominoSnapshot(
                players = s.players.map { p ->
                    DominoSnapshotPlayer(p.playerId, p.displayName, p.isBot, p.hand.map { tileToSnap(it) })
                },
                boneyard = boneyard.map { tileToSnap(it) },
                chain = s.chain.map { DominoSnapshotPlaced(tileToSnap(it.domino), it.flipped) },
                currentPlayerIndex = s.currentPlayerIndex,
                consecutivePasses = s.consecutivePasses,
                lastAction = s.lastAction,
                handOver = s.handOver,
                winnerPlayerId = s.winnerPlayerId,
                sessionScores = sessionScores.value,
                handsFinished = handsFinished.value,
                difficulty = difficulty.name
            )
        )
    }

    /** Decodes and sanity-checks a snapshot against THIS game's roster and the 28-tile set; null if unusable. */
    private fun decodeSnapshot(text: String): DominoSnapshot? {
        val snap = try {
            dominoSnapshotJson.decodeFromString(DominoSnapshot.serializer(), text)
        } catch (_: Exception) {
            return null
        }
        val roster = context.players
        if (snap.players.size != roster.size) return null
        if (!snap.players.indices.all { snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot }) return null
        if (snap.currentPlayerIndex !in snap.players.indices) return null
        if (snap.consecutivePasses < 0 || snap.handsFinished < 0) return null
        if (snap.winnerPlayerId != null && snap.players.none { it.playerId == snap.winnerPlayerId }) return null
        if (!roster.all { (snap.sessionScores[it.playerId] ?: -1) >= 0 }) return null

        // The whole 28-tile set must be accounted for exactly once across hands, boneyard and chain.
        val tiles = snap.players.flatMap { it.hand } + snap.boneyard + snap.chain.map { it.tile }
        if (tiles.size != DOMINO_SET_SIZE) return null
        if (tiles.map { it.id }.toSet().size != DOMINO_SET_SIZE) return null
        // Each id must carry its real pips (ids are dealt in (0-0, 0-1, ... 6-6) order), so a stale or
        // edited save cannot hold two tiles with the same pips.
        val canonical = ArrayList<Pair<Int, Int>>(DOMINO_SET_SIZE)
        for (a in 0..6) for (b in a..6) canonical.add(a to b)
        if (!tiles.all { it.id in 0 until DOMINO_SET_SIZE && canonical[it.id] == (it.a to it.b) }) return null

        // Neighbouring chain tiles must actually touch with matching pips.
        for (i in 0 until snap.chain.size - 1) {
            val left = snap.chain[i]
            val right = snap.chain[i + 1]
            val leftRight = if (left.flipped) left.tile.a else left.tile.b
            val rightLeft = if (right.flipped) right.tile.b else right.tile.a
            if (leftRight != rightLeft) return null
        }
        return snap
    }

    private fun snapToTile(t: DominoSnapshotTile) = Domino(t.a, t.b, t.id)

    private fun applySnapshot(snap: DominoSnapshot) {
        val newBoneyard = snap.boneyard.map { snapToTile(it) }.toMutableList()
        boneyard = newBoneyard
        state.value = DominoState(
            players = snap.players.map { p ->
                DominoPlayerState(p.playerId, p.displayName, p.isBot, p.hand.map { snapToTile(it) })
            },
            boneyardSize = newBoneyard.size,
            chain = snap.chain.map { PlacedDomino(snapToTile(it.tile), it.flipped) },
            currentPlayerIndex = snap.currentPlayerIndex,
            consecutivePasses = snap.consecutivePasses,
            lastAction = snap.lastAction,
            handOver = snap.handOver,
            winnerPlayerId = snap.winnerPlayerId
        )
        sessionScores.value = context.players.associate { it.playerId to (snap.sessionScores[it.playerId] ?: 0) }
        handsFinished.value = snap.handsFinished
        difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
    }

    /** Null when there is nothing to save: no hand yet, the session already ended, or a mode whose state lives with other players. */
    override fun snapshot(): String? {
        if (matchOver.value || state.value == null) return null
        if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
        return try {
            captureSnapshot()
        } catch (_: Exception) {
            null
        }
    }

    override fun restore(snapshot: String): Boolean {
        return try {
            val snap = decodeSnapshot(snapshot) ?: return false
            matchOver.value = false
            applySnapshot(snap)
            history.clear()
            true
        } catch (_: Exception) {
            false
        }
    }

    // --- Undo -------------------------------------------------------------------------------

    /** Not while the CPU is on the move (its turn is mid-flight); a finished hand can still be taken back. */
    override val canUndo: Boolean
        get() {
            if (history.depth <= 0 || matchOver.value) return false
            val s = state.value ?: return false
            return s.handOver || s.players.getOrNull(s.currentPlayerIndex)?.isBot == false
        }

    /**
     * Takes back the last human action (a played tile, a draw, or a pass). Against the CPU that also
     * takes back the CPU's reply, because the snapshot was taken before the human's action. An undone
     * draw puts the tile back on top of the boneyard (the order is in the snapshot). Undoing the
     * winning tile re-opens the hand and takes its points and finished-hand count back.
     */
    override fun undo() {
        if (!canUndo) return
        val previous = history.pop() ?: return
        val snap = decodeSnapshot(previous) ?: return
        applySnapshot(snap)
    }

    /** Records an undo step before a HUMAN action is applied; the CPU's own moves never leave a step. */
    private fun recordUndoStep(actor: DominoPlayerState) {
        if (actor.isBot) return
        captureSnapshot()?.let { history.push(it) }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        // A new hand starts a new undo history: undo never reaches back into the previous hand.
        history.clear()
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

        val override = firstOpenerIndex?.takeIf { it in players.indices }
        firstOpenerIndex = null // one-shot: only the first hand is overridden
        state.value = DominoState(
            players = players,
            boneyardSize = boneyard.size,
            chain = emptyList(),
            currentPlayerIndex = override ?: startIndex,
            consecutivePasses = 0,
            lastAction = "Game started"
        )
    }

    // Nothing in a hand of dominoes runs on a clock, so there is no state to freeze: pause() and
    // resume() are stateless and therefore trivially idempotent (calling either any number of
    // times, in any order, changes nothing).
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
        if (matchOver.value) return // the session already ended (left or aborted): ignore stray input
        val s = state.value ?: return
        if (s.handOver || playerIndex != s.currentPlayerIndex) return
        val player = s.players[playerIndex]
        if (!player.hand.any { it.instanceId == domino.instanceId }) return
        if (!canPlace(domino, attachToLeft)) return // doesn't match, illegal
        recordUndoStep(player)

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
        if (matchOver.value) return
        val s = state.value ?: return
        if (s.handOver || playerIndex != s.currentPlayerIndex || boneyard.isEmpty()) return
        if (canPlay(playerIndex)) return // must play a legal tile instead of drawing
        val player = s.players[playerIndex]
        recordUndoStep(player)
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
        if (matchOver.value) return
        val s = state.value ?: return
        if (s.handOver || playerIndex != s.currentPlayerIndex) return
        // Only allowed once the boneyard is empty and the player genuinely has no legal move.
        if (boneyard.isNotEmpty() || canPlay(playerIndex)) return
        recordUndoStep(s.players[playerIndex])
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
        if (matchOver.value) return
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
        handsFinished.value += 1
        state.value = s.copy(handOver = true, winnerPlayerId = winner.playerId, lastAction = "${winner.displayName} wins!")
    }

    private fun finishBlocked() {
        val s = state.value ?: return
        val pipTotals = s.players.associate { it.playerId to it.hand.sumOf { d -> d.a + d.b } }
        val winnerId = pipTotals.minByOrNull { it.value }?.key
        awardHandPoints(winnerId, s.players)
        handsFinished.value += 1
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
