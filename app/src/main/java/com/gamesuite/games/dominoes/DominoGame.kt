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
    val difficulty: String,
    /** Highest pip of the tile set (6 = double-six, 9 = double-nine). Saves from before the option read as 6. */
    val maxPip: Int = 6
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

private val dominoSnapshotJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * The tile set a session is played with. Tile ids are dealt in (0-0, 0-1, ... max-max) order, so an
 * id always carries the same pips for a given set. [key] is the Hub option value ("set").
 */
enum class DominoSet(val key: String, val maxPip: Int) {
    DOUBLE_SIX("double-six", 6),
    DOUBLE_NINE("double-nine", 9);

    /** 28 for double-six, 55 for double-nine. */
    val tileCount: Int get() = (maxPip + 1) * (maxPip + 2) / 2

    /**
     * Tiles dealt to each player: double-six 7 (two players) or 5 (three or four); double-nine 10
     * (two players) or 7 (three or four), which still leaves a boneyard to draw from.
     */
    fun handSize(playerCount: Int): Int = if (playerCount <= 2) {
        if (maxPip == 9) 10 else 7
    } else {
        if (maxPip == 9) 7 else 5
    }

    /** Every tile of the set in id order, ids 0 until [tileCount]. */
    fun fullSet(): List<Domino> {
        val tiles = ArrayList<Domino>(tileCount)
        var id = 0
        for (a in 0..maxPip) for (b in a..maxPip) tiles.add(Domino(a, b, id++))
        return tiles
    }

    companion object {
        /** The set for a Hub option value; anything unknown (or null) is the default double-six. */
        fun fromKey(key: String?): DominoSet = values().firstOrNull { it.key == key } ?: DOUBLE_SIX

        fun fromMaxPip(maxPip: Int): DominoSet? = values().firstOrNull { it.maxPip == maxPip }
    }
}

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
    val winnerPlayerId: String? = null,
    /**
     * Networked guest only: pip totals of hands whose tiles this device never learns (the host's),
     * known once the hand is over so the result can show how it was scored. Empty everywhere else.
     */
    val knownPips: Map<String, Int> = emptyMap()
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
 * pips in hand wins). A double-nine set (55 tiles) is available through [tileSet].
 *
 * BLOCKED HANDS (standard rule). When nobody can play and the boneyard is empty, every player
 * passes in turn; once all of them have passed in a row the hand is blocked and ends at once. The
 * player with the LOWEST pip total left in hand wins the hand and scores the DIFFERENCE: for each
 * opponent, that opponent's pips minus the winner's own (with two players simply the gap between
 * the two hands). A hand won by emptying the hand scores the same way, the winner holding 0 pips.
 * If the lowest total is SHARED the hand is a TIE: nobody scores, [DominoState.winnerPlayerId] is
 * null, the hand still counts as finished ([handsFinished]) and Play Again deals a fresh one.
 * Drawing is only possible while the boneyard has tiles and nothing in hand fits; passing is only
 * possible once the boneyard is empty and nothing fits.
 *
 * NETWORKED PLAY (PlayMode.LOCAL_AD_HOC / ONLINE, two seats; host = seat 0 owns this engine,
 * guest = seat 1 mirrors it). See [DominoNetMessage]. The guest only ever holds its own hand and
 * the COUNTS of the other hand and the boneyard; the host validates every request against the
 * sender's seat and turn. Network games are never resumable and have no Undo or Hint.
 */
class DominoGame : GameModule, ResumableGame, UndoableGame {
    override val gameId = "dominoes"
    override val displayName = "Dominoes"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<DominoState?>(null)

    /**
     * Networked play only: true once the other seat has left (transport onPlayerLeft). Input is
     * refused from then on and [leaveSession] aborts instead of recording a result.
     */
    val opponentLeft = mutableStateOf(false)

    /**
     * The tile set of this session (Hub option "set"). Set by the screen after [init] and before
     * the first [startMatch]; a restored game takes the saved set and a networked guest the
     * host's. [init] resets it to the default double-six.
     */
    var tileSet: DominoSet = DominoSet.DOUBLE_SIX

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

    // ---- Networked play bookkeeping (PlayMode.LOCAL_AD_HOC / ONLINE): host = seat 0 owns the engine ----
    private val isNetworked: Boolean
        get() = context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    private var stateVersion = 0
    private var lastAppliedRemoteVersion = 0

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        sessionScores.value = context.players.associate { it.playerId to 0 }
        handsFinished.value = 0
        history.clear()
        firstOpenerIndex = null
        tileSet = DominoSet.DOUBLE_SIX
        opponentLeft.value = false
        stateVersion = 0
        lastAppliedRemoteVersion = 0
        boneyard = mutableListOf()
        if (isNetworked) {
            // A guest renders only what the host sends; drop any hand left over from an earlier game.
            if (!isHost) state.value = null
            registerNetworkListeners()
            if (!isHost) sendToHost(DominoNetMessage.RequestState)
        }
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
                difficulty = difficulty.name,
                maxPip = tileSet.maxPip
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

        // The saved tile set must be one we know (double-six or double-nine).
        val set = DominoSet.fromMaxPip(snap.maxPip) ?: return null
        // The whole set (28 or 55 tiles) must be accounted for exactly once across hands, boneyard and chain.
        val tiles = snap.players.flatMap { it.hand } + snap.boneyard + snap.chain.map { it.tile }
        if (tiles.size != set.tileCount) return null
        if (tiles.map { it.id }.toSet().size != set.tileCount) return null
        // Each id must carry its real pips (ids are dealt in (0-0, 0-1, ... max-max) order), so a stale or
        // edited save cannot hold two tiles with the same pips.
        val canonical = set.fullSet()
        if (!tiles.all { it.id in 0 until set.tileCount && canonical[it.id].a == it.a && canonical[it.id].b == it.b }) return null

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
        tileSet = DominoSet.fromMaxPip(snap.maxPip) ?: tileSet
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
        if (isNetworked) return false // networked hands are never resumed
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
            if (history.depth <= 0 || matchOver.value || isNetworked) return false
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
        if (isNetworked && !isHost) return // guest: wait for the host's StateSync (or its reply to RequestState)
        // A new hand starts a new undo history: undo never reaches back into the previous hand.
        history.clear()
        val full = tileSet.fullSet().toMutableList()
        full.shuffle()
        boneyard = full

        val handSize = tileSet.handSize(context.players.size)
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
        publishIfHost()
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
        // The other player is gone: nothing to settle, and a walk-away must never be recorded as a loss.
        if (isNetworked && opponentLeft.value) {
            abortMatch()
            return
        }
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
        if (isNetworked) {
            if (opponentLeft.value) return
            if (!isHost) {
                // Only worth asking while the hand we are looking at is over; the host re-checks.
                if (state.value?.handOver == true) sendToHost(DominoNetMessage.PlayAgainRequest)
                return
            }
            // Host: a request (or tap) arriving after the hand was already re-dealt must not wipe it.
            if (state.value?.handOver != true) return
        }
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
        if (playerIndex !in s.players.indices) return
        if (isNetworked) {
            // Networked taps are only ever the local player's own, on their turn.
            if (opponentLeft.value || playerIndex != context.localPlayerIndex) return
            if (!isHost) {
                // The guest never mutates its engine from a tap: validate cheaply, ask the host, render its reply.
                if (s.players[playerIndex].hand.none { it.instanceId == domino.instanceId }) return
                if (!canPlace(domino, attachToLeft)) return
                sendToHost(DominoNetMessage.PlayRequest(domino.instanceId, attachToLeft))
                return
            }
        }
        applyPlay(playerIndex, domino, attachToLeft)
    }

    private fun applyPlay(playerIndex: Int, domino: Domino, attachToLeft: Boolean) {
        val s = state.value ?: return
        if (s.handOver || playerIndex != s.currentPlayerIndex) return
        val player = s.players[playerIndex]
        if (!player.hand.any { it.instanceId == domino.instanceId }) return
        if (!canPlace(domino, attachToLeft)) return // doesn't match, illegal
        if (!isNetworked) recordUndoStep(player)

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
        publishIfHost()
    }

    /** Draws one tile from the boneyard; only legal while it has tiles AND nothing in hand fits. */
    fun drawFromBoneyard(playerIndex: Int) {
        if (matchOver.value) return
        val s = state.value ?: return
        if (s.handOver || playerIndex != s.currentPlayerIndex) return
        if (playerIndex !in s.players.indices) return
        if (isNetworked) {
            if (opponentLeft.value || playerIndex != context.localPlayerIndex) return
            if (!isHost) {
                if (s.boneyardSize <= 0 || canPlay(playerIndex)) return
                sendToHost(DominoNetMessage.DrawRequest)
                return
            }
        }
        applyDraw(playerIndex)
    }

    private fun applyDraw(playerIndex: Int) {
        val s = state.value ?: return
        if (s.handOver || playerIndex != s.currentPlayerIndex || boneyard.isEmpty()) return
        if (canPlay(playerIndex)) return // must play a legal tile instead of drawing
        val player = s.players[playerIndex]
        if (!isNetworked) recordUndoStep(player)
        val drawn = draw(1)
        val updatedPlayers = s.players.toMutableList()
        updatedPlayers[playerIndex] = player.copy(hand = player.hand + drawn)
        state.value = s.copy(
            players = updatedPlayers,
            boneyardSize = boneyard.size,
            lastAction = "${player.displayName} drew a tile"
        )
        publishIfHost()
    }

    /** Passes the turn; only legal once the boneyard is empty AND nothing in hand fits. */
    fun pass(playerIndex: Int) {
        if (matchOver.value) return
        val s = state.value ?: return
        if (s.handOver || playerIndex != s.currentPlayerIndex) return
        if (playerIndex !in s.players.indices) return
        if (isNetworked) {
            if (opponentLeft.value || playerIndex != context.localPlayerIndex) return
            if (!isHost) {
                if (s.boneyardSize > 0 || canPlay(playerIndex)) return
                sendToHost(DominoNetMessage.PassRequest)
                return
            }
        }
        applyPass(playerIndex)
    }

    private fun applyPass(playerIndex: Int) {
        val s = state.value ?: return
        if (s.handOver || playerIndex != s.currentPlayerIndex) return
        // Only allowed once the boneyard is empty and the player genuinely has no legal move.
        if (boneyard.isNotEmpty() || canPlay(playerIndex)) return
        if (!isNetworked) recordUndoStep(s.players[playerIndex])
        val consecutivePasses = s.consecutivePasses + 1
        val blocked = consecutivePasses >= s.players.size

        state.value = s.copy(
            currentPlayerIndex = (playerIndex + 1) % s.players.size,
            consecutivePasses = consecutivePasses,
            lastAction = "${s.players[playerIndex].displayName} passed",
            handOver = blocked
        )
        if (blocked) finishBlocked()
        publishIfHost()
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
        if (matchOver.value || isNetworked) return // a network table has no CPU seat
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

    /**
     * A blocked hand (everyone passed in a row with the boneyard empty): the player with the strictly
     * LOWEST pip total wins and scores the difference (see [awardHandPoints]). When the lowest total
     * is shared the hand is a TIE: [DominoState.winnerPlayerId] stays null and nobody scores, yet the
     * hand counts as finished and Play Again deals a fresh one.
     */
    private fun finishBlocked() {
        val s = state.value ?: return
        val pipTotals = s.players.map { p -> p.hand.sumOf { d -> d.a + d.b } }
        val lowest = pipTotals.minOrNull()
        val leaders = pipTotals.indices.filter { pipTotals[it] == lowest }
        val winnerId = if (leaders.size == 1) s.players[leaders[0]].playerId else null
        awardHandPoints(winnerId, s.players)
        handsFinished.value += 1
        state.value = s.copy(
            winnerPlayerId = winnerId,
            lastAction = if (winnerId == null) "Blocked — level pips, no score" else "Blocked — lowest pips wins"
        )
    }

    /**
     * Hand scoring: the winner scores, for every OTHER player, that player's pips left minus the
     * winner's own pips left. A winner who went out holds 0 pips, so this is simply the pips left in
     * the other hands; in a blocked hand it is the DIFFERENCE between the hands (two players: the gap
     * between the totals). Accumulates into [sessionScores] so the running total survives
     * [playAgain]; only [leaveSession] reports it and ends the match. A null [winnerId] (a tied
     * blocked hand) awards nothing for that hand.
     */
    private fun awardHandPoints(winnerId: String?, players: List<DominoPlayerState>) {
        if (winnerId == null) return
        val winnerPips = players.firstOrNull { it.playerId == winnerId }?.hand?.sumOf { d -> d.a + d.b } ?: return
        val points = players.filter { it.playerId != winnerId }
            .sumOf { p -> (p.hand.sumOf { d -> d.a + d.b } - winnerPips).coerceAtLeast(0) }
        val current = sessionScores.value
        sessionScores.value = current + (winnerId to (current[winnerId] ?: 0) + points)
    }

    // --- Networked play ---------------------------------------------------------------------

    private fun hostId(): String = context.players[0].playerId

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { fromPlayerId, payload -> handleNetworkMessage(fromPlayerId, payload) }
        // After a blip the transport recovered from: a guest asks again, the host re-sends (a guest
        // whose own link stayed up would never think to ask).
        transport.onReconnected {
            if (isHost) {
                if (state.value != null) broadcastState()
            } else {
                sendToHost(DominoNetMessage.RequestState)
            }
        }
        transport.onPlayerLeft { playerId ->
            val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId
            if (playerId != localId && context.players.any { it.playerId == playerId }) {
                opponentLeft.value = true
                state.value = state.value?.copy(lastAction = OPPONENT_DISCONNECTED)
            }
        }
        transport.onPlayerJoined { playerId ->
            val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId
            if (playerId != localId && context.players.any { it.playerId == playerId } && opponentLeft.value) {
                opponentLeft.value = false
                if (isHost && state.value != null) broadcastState()
            }
        }
    }

    private fun publishIfHost() {
        if (isNetworked && isHost && state.value != null) broadcastState()
    }

    private fun netTile(d: Domino) = DominoNetTile(d.a, d.b, d.instanceId)

    /**
     * The host's view for the seat [recipientIndex]: public table + that seat's OWN hand + the COUNT
     * of every hand and of the boneyard. No other hand's tile and no boneyard tile is ever put in
     * here; once the hand is over only the pip totals are added (so the result can be explained).
     */
    private fun toNetState(s: DominoState, recipientIndex: Int) = DominoNetState(
        maxPip = tileSet.maxPip,
        chain = s.chain.map { DominoNetPlaced(netTile(it.domino), it.flipped) },
        myHand = s.players[recipientIndex].hand.map { netTile(it) },
        handSizes = s.players.map { it.hand.size },
        boneyardSize = s.boneyardSize,
        currentPlayerIndex = s.currentPlayerIndex,
        consecutivePasses = s.consecutivePasses,
        lastAction = s.lastAction,
        handOver = s.handOver,
        winnerPlayerId = s.winnerPlayerId,
        sessionScores = sessionScores.value,
        handsFinished = handsFinished.value,
        handPips = if (s.handOver) s.players.map { p -> p.hand.sumOf { d -> d.a + d.b } } else null
    )

    /** Host: bumps the version and sends each other seat its own redacted view. */
    private fun broadcastState() {
        val s = state.value ?: return
        stateVersion++
        context.players.forEachIndexed { index, recipient ->
            if (index != 0) send(recipient.playerId, DominoNetMessage.StateSync(stateVersion, toNetState(s, index)))
        }
    }

    private fun sendStateTo(recipientIndex: Int, s: DominoState) {
        send(
            context.players[recipientIndex].playerId,
            DominoNetMessage.StateSync(stateVersion, toNetState(s, recipientIndex))
        )
    }

    private fun sendToHost(message: DominoNetMessage) {
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId(), payload = encode(message))
    }

    private fun send(toPlayerId: String, message: DominoNetMessage) {
        context.transport.send(fromPlayerId = hostId(), toPlayerId = toPlayerId, payload = encode(message))
    }

    private fun encode(message: DominoNetMessage): ByteArray =
        Json.encodeToString(DominoNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            Json.decodeFromString(DominoNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            return // malformed or foreign payload
        }
        if (!isNetworked || matchOver.value) return
        val seat = context.players.indexOfFirst { it.playerId == fromPlayerId }
        when (message) {
            // Only the host is authoritative: a StateSync from any other seat is a forgery.
            is DominoNetMessage.StateSync ->
                if (!isHost && fromPlayerId == hostId()) applyRemoteState(message.version, message.state)
            is DominoNetMessage.RequestState ->
                if (isHost && seat > 0) state.value?.let { sendStateTo(seat, it) }
            is DominoNetMessage.PlayRequest ->
                if (isHost && seat > 0) handlePlayRequest(seat, message.tileId, message.attachToLeft)
            is DominoNetMessage.DrawRequest ->
                if (isHost && seat > 0) handleSeatRequest(seat) { applyDraw(seat) }
            is DominoNetMessage.PassRequest ->
                if (isHost && seat > 0) handleSeatRequest(seat) { applyPass(seat) }
            is DominoNetMessage.PlayAgainRequest ->
                if (isHost && seat > 0 && !opponentLeft.value && state.value?.handOver == true) startMatch()
        }
    }

    /**
     * Host: runs [action] only if [seat] (a real guest seat, derived from the transport's sender, never
     * from the payload) is on turn in a live hand. [applyDraw]/[applyPass] re-check the rule itself.
     */
    private fun handleSeatRequest(seat: Int, action: () -> Unit) {
        val s = state.value ?: return
        if (matchOver.value || s.handOver || opponentLeft.value) return
        if (seat <= 0 || seat != s.currentPlayerIndex) return
        action()
    }

    /** Host: a guest's play is applied only for a tile that is in THAT guest's hand and fits the chosen end. */
    private fun handlePlayRequest(seat: Int, tileId: Int, attachToLeft: Boolean) {
        handleSeatRequest(seat) {
            val s = state.value ?: return@handleSeatRequest
            val tile = s.players[seat].hand.firstOrNull { it.instanceId == tileId } ?: return@handleSeatRequest
            applyPlay(seat, tile, attachToLeft)
        }
    }

    /** Guest: accepts only strictly newer, well-formed host state, and rejects anything inconsistent. */
    private fun applyRemoteState(version: Int, net: DominoNetState) {
        if (version <= lastAppliedRemoteVersion) return
        val n = context.players.size
        val me = context.localPlayerIndex
        val set = DominoSet.fromMaxPip(net.maxPip) ?: return
        if (me !in 0 until n) return
        val canonical = set.fullSet()
        fun real(t: DominoNetTile) = t.id in 0 until set.tileCount && canonical[t.id].a == t.a && canonical[t.id].b == t.b
        val visible = net.chain.map { it.tile } + net.myHand
        val valid = net.handSizes.size == n &&
            net.handSizes.all { it >= 0 } &&
            net.handSizes[me] == net.myHand.size &&
            net.boneyardSize >= 0 &&
            visible.all { real(it) } &&
            visible.map { it.id }.toSet().size == visible.size &&
            // every tile is in the chain, in a hand or in the boneyard -- exactly once
            net.chain.size + net.handSizes.sum() + net.boneyardSize == set.tileCount &&
            net.currentPlayerIndex in 0 until n &&
            net.consecutivePasses >= 0 && net.handsFinished >= 0 &&
            (net.winnerPlayerId == null || context.players.any { it.playerId == net.winnerPlayerId }) &&
            context.players.all { (net.sessionScores[it.playerId] ?: -1) >= 0 } &&
            (net.handPips == null || (net.handPips.size == n && net.handPips.all { it >= 0 })) &&
            (!net.handOver || net.handPips != null) &&
            chainIsContiguous(net.chain)
        if (!valid) return
        lastAppliedRemoteVersion = version
        tileSet = set
        boneyard = mutableListOf() // the guest never knows the boneyard; only its size (in the state) is shown
        state.value = DominoState(
            players = context.players.mapIndexed { index, info ->
                DominoPlayerState(
                    playerId = info.playerId,
                    displayName = info.displayName,
                    isBot = false,
                    hand = if (index == me) {
                        net.myHand.map { Domino(it.a, it.b, it.id) }
                    } else {
                        // Hidden tiles: placeholders that carry a count and nothing else (negative id, no pips).
                        List(net.handSizes[index]) { k -> Domino(-1, -1, -(k + 1)) }
                    }
                )
            },
            boneyardSize = net.boneyardSize,
            chain = net.chain.map { PlacedDomino(Domino(it.tile.a, it.tile.b, it.tile.id), it.flipped) },
            currentPlayerIndex = net.currentPlayerIndex,
            consecutivePasses = net.consecutivePasses,
            lastAction = if (opponentLeft.value) OPPONENT_DISCONNECTED else net.lastAction,
            handOver = net.handOver,
            winnerPlayerId = net.winnerPlayerId,
            knownPips = if (net.handOver && net.handPips != null) {
                context.players.indices.associate { context.players[it].playerId to net.handPips[it] }
            } else {
                emptyMap()
            }
        )
        sessionScores.value = context.players.associate { it.playerId to (net.sessionScores[it.playerId] ?: 0) }
        handsFinished.value = net.handsFinished
    }

    /** True when neighbouring chain tiles touch with matching pips (the same check a saved game gets). */
    private fun chainIsContiguous(chain: List<DominoNetPlaced>): Boolean {
        for (i in 0 until chain.size - 1) {
            val left = chain[i]
            val right = chain[i + 1]
            val leftRight = if (left.flipped) left.tile.a else left.tile.b
            val rightLeft = if (right.flipped) right.tile.b else right.tile.a
            if (leftRight != rightLeft) return false
        }
        return true
    }

    private fun draw(count: Int): List<Domino> {
        val n = minOf(count, boneyard.size)
        val drawn = boneyard.take(n)
        boneyard = boneyard.drop(n).toMutableList()
        return drawn
    }

    companion object {
        const val OPPONENT_DISCONNECTED = "Opponent disconnected"
    }
}
