package com.gamesuite.games.dotsandboxes

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.MultiplayerTransport
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Everything needed to put a Dots and Boxes session back exactly as it was: the lattice, the box
 * owners, whose turn it is (a pending go-again turn is simply `currentPlayerIndex` staying on the
 * same player), the per-board scores, the session tally, who opens the next board and the
 * difficulty. One shape serves Resume (disk) and Undo (memory). Not carried, on purpose:
 * `lastBoxCompleted`/`eventSeq` are one-shot animation events (a restored or undone board has no
 * "latest claim" to burst on).
 */
@Serializable
private data class DotsAndBoxesSnapshot(
    val boxRows: Int,
    val boxCols: Int,
    val horizontalEdges: List<Boolean>,
    val verticalEdges: List<Boolean>,
    val boxOwner: List<Int?>,
    val players: List<DotsAndBoxesSnapshotPlayer>,
    val currentPlayerIndex: Int,
    val scores: List<Int>,
    val lastAction: String,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int,
    val nextStartingPlayerIndex: Int,
    val difficulty: String
)

@Serializable
private data class DotsAndBoxesSnapshotPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

private val dotsAndBoxesSnapshotJson = Json { ignoreUnknownKeys = true }

/** One edge of the lattice, as the hint reports it: same (isHorizontal, row, col) addressing as [DotsAndBoxesGame.drawHorizontalEdge]/[DotsAndBoxesGame.drawVerticalEdge]. */
data class DotsAndBoxesHintEdge(val horizontal: Boolean, val row: Int, val col: Int)

/** A player's identity + bot flag — mirrors DominoGame's own per-player state, kept as a list even though this game is always exactly 2 players, for consistency with that established shape. */
data class DotsAndBoxesPlayerState(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean
)

/** One move's own completed-box payload plus a monotonic [seq] — the UI keys a one-shot effect
 *  (a small particle burst + camera-shake nudge) off [seq] changing, same "seq-numbered event,
 *  persists until the next one" idiom [com.gamesuite.games.towerdefence.TowerDefenceGame]'s own
 *  `lastEnemyDeath`/`TowerDefenceEnemyDeathEvent` already use. [boxIndices] holds EVERY box a
 *  single edge just newly completed — almost always one, but a shared edge between two adjacent
 *  boxes can complete both at once (a "double-cross"), and the UI needs to burst at both
 *  positions, not just one. [playerIndex] is whoever gets credit for the claim (the mover at the
 *  time of the edge, i.e. `currentPlayerIndex` BEFORE this move's own extra-turn adjustment), so
 *  the UI can burst in that player's own established color. */
data class DotsAndBoxesBoxCompletedEvent(val seq: Long, val boxIndices: List<Int>, val playerIndex: Int)

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
    val winnerPlayerId: String? = null,
    /** The most recent move's completed-box(es), for the UI's own per-box particle-burst effect —
     *  see [DotsAndBoxesBoxCompletedEvent]'s own KDoc. Untouched (carries the previous event
     *  forward unchanged) on a move that completes zero boxes, same "only advance on a real new
     *  event" idiom that event's KDoc references. */
    val lastBoxCompleted: DotsAndBoxesBoxCompletedEvent? = null
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
class DotsAndBoxesGame : GameModule, ResumableGame, UndoableGame {
    override val gameId = "dots-and-boxes"
    override val displayName = "Dots and Boxes"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<DotsAndBoxesState?>(null)

    /** Boards won this session, keyed by playerId; a tied board increments nobody (see [sessionDraws] instead). Mirrors TicTacToeGame's own scoreP1/scoreP2/draws idea, generalized to a map (though this game is always exactly 2 players). */
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val sessionDraws = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via the board-over panel), not per-board. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /**
     * The roster seat that draws the FIRST line of the FIRST board (Game Hub's first-mover option).
     * Set by the screen before [init]; [init] seeds the next-opener field from it, so the usual
     * alternation carries on from there. A resumed game ignores it: [restore] overwrites the
     * next-opener field from the snapshot.
     */
    var firstOpenerIndex: Int = 0

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** Alternates who opens each new board (including across Play Again rounds), for fairness — same idiom TicTacToeGame's own Play Again uses. */
    private var nextStartingPlayerIndex = 0

    /** Monotonic counter behind [DotsAndBoxesBoxCompletedEvent.seq] — same idiom as
     *  TowerDefenceGame's own `eventSeq`, reset per fresh board in [startMatch] so a UI
     *  effect never fires for a stale event carried over from a PRIOR board's own [state]. */
    private var eventSeq = 0L

    /** Snapshots taken just BEFORE each human edge, newest last; popping one is Undo. Per board. */
    private val history = SnapshotHistory()

    /** The edge the last [hint] suggested, shown until the next edge, undo or new board. */
    val hintEdge = mutableStateOf<DotsAndBoxesHintEdge?>(null)

    // ---- Networked play bookkeeping (LOCAL_AD_HOC / ONLINE, two seats: host = 0, guest = 1) ----

    /** True in the two networked modes. Safe to read before [init] (false). */
    val isNetworkedMode: Boolean
        get() = ::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)

    /** True on the device that owns the engine (seat 0). Meaningful only when [isNetworkedMode]. */
    private val isHost: Boolean get() = context.localPlayerIndex == 0

    /** This device's seat in a networked match (0 host, 1 guest); -1 outside one. */
    val localSeat: Int get() = if (isNetworkedMode) context.localPlayerIndex else -1

    /** Set when the other seat's transport reported a departure; input is frozen and the UI offers the way out. */
    val opponentLeft = mutableStateOf(false)

    private var stateVersion = 0
    private var lastAppliedRemoteVersion = 0
    private var listenersOn: MultiplayerTransport? = null

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        opponentLeft.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        sessionDraws.value = 0
        nextStartingPlayerIndex = if (context.players.isEmpty()) 0 else firstOpenerIndex.coerceIn(0, context.players.size - 1)
        history.clear()
        hintEdge.value = null
        lastAppliedRemoteVersion = 0
        if (isNetworkedMode && listenersOn !== context.transport) {
            listenersOn = context.transport
            context.transport.onMessageReceived { fromPlayerId, payload -> handleNetworkMessage(fromPlayerId, payload) }
            // After a blip the transport recovered from: a guest may have missed broadcasts, so it
            // asks again; the host proactively re-broadcasts (a guest's own link may have stayed up).
            context.transport.onReconnected {
                if (isHost) {
                    state.value?.let { broadcastState(toPlayerId = null) }
                } else {
                    sendToHost(DotsAndBoxesNetMessage.RequestState)
                }
            }
            context.transport.onPlayerLeft { playerId -> handlePlayerLeft(playerId) }
            // Covers the startup race where the host dealt before this listener existed.
            if (!isHost) sendToHost(DotsAndBoxesNetMessage.RequestState)
        }
    }

    private fun handlePlayerLeft(playerId: String) {
        if (!isNetworkedMode) return
        val otherSeat = if (isHost) 1 else 0
        if (context.players.getOrNull(otherSeat)?.playerId != playerId) return
        opponentLeft.value = true
        // Local only: no result is reported, so nothing is recorded as a loss. The UI offers the way out.
        state.value?.let { state.value = it.copy(lastAction = "Opponent disconnected") }
    }

    private fun sendToHost(message: DotsAndBoxesNetMessage) {
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        context.transport.send(localId, hostId, encodeNet(message))
    }

    private fun encodeNet(message: DotsAndBoxesNetMessage): ByteArray =
        Json.encodeToString<DotsAndBoxesNetMessage>(message).toByteArray(Charsets.UTF_8)

    /** Host only: sends the authoritative state (to one seat, or to every guest) at the CURRENT [stateVersion]. */
    private fun broadcastState(toPlayerId: String?) {
        if (!isNetworkedMode || !isHost) return
        val text = captureSnapshot() ?: return
        val payload = encodeNet(DotsAndBoxesNetMessage.StateSync(stateVersion, text))
        val hostId = context.players[0].playerId
        if (toPlayerId != null) {
            context.transport.send(hostId, toPlayerId, payload)
        } else {
            context.players.forEach { if (it.playerId != hostId) context.transport.send(hostId, it.playerId, payload) }
        }
    }

    /** Host only: a state change just happened; bump the version and tell the guest. */
    private fun commitAndBroadcast() {
        if (!isNetworkedMode || !isHost) return
        stateVersion++
        broadcastState(toPlayerId = null)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        if (!isNetworkedMode) return
        val message = try {
            Json.decodeFromString<DotsAndBoxesNetMessage>(String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            return // malformed or foreign payload
        }
        val hostId = context.players.getOrNull(0)?.playerId
        when (message) {
            // Only the host is authoritative: a StateSync from any other sender is a forgery.
            is DotsAndBoxesNetMessage.StateSync ->
                if (!isHost && fromPlayerId == hostId) applyRemoteState(message.version, message.snapshot)
            is DotsAndBoxesNetMessage.RequestState ->
                if (isHost && context.players.drop(1).any { it.playerId == fromPlayerId }) {
                    if (state.value != null) broadcastState(toPlayerId = fromPlayerId)
                }
            is DotsAndBoxesNetMessage.DrawEdge ->
                if (isHost) applyGuestEdge(fromPlayerId, message)
            is DotsAndBoxesNetMessage.PlayAgain ->
                if (isHost && context.players.drop(1).any { it.playerId == fromPlayerId }) hostPlayAgain()
        }
    }

    /** Host: a guest's tap. Every check here must pass or the request is dropped silently (no broadcast). */
    private fun applyGuestEdge(fromPlayerId: String, request: DotsAndBoxesNetMessage.DrawEdge) {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver || opponentLeft.value) return
        val seat = context.players.indexOfFirst { it.playerId == fromPlayerId }
        if (seat <= 0 || seat != s.currentPlayerIndex) return // unknown sender, the host itself, or not their turn
        if (!isValidEdge(request.horizontal, request.row, request.col, s.boxRows, s.boxCols)) return
        if (isEdgeDrawn(s, request.horizontal, request.row, request.col)) return
        drawEdge(request.horizontal, request.row, request.col)
    }

    /** Guest: adopt the host's snapshot if it is newer than anything applied so far. */
    private fun applyRemoteState(version: Int, text: String) {
        if (version <= lastAppliedRemoteVersion) return
        val snap = decodeSnapshot(text) ?: return
        lastAppliedRemoteVersion = version
        val before = state.value
        applySnapshot(snap)
        val after = state.value ?: return
        // applySnapshot clears the one-shot claim event; rebuild it from the difference so the
        // guest still gets the claim cue, and restart the counter on a fresh board.
        val freshBoard = before == null || drawnEdgeCount(after) < drawnEdgeCount(before)
        if (freshBoard) {
            eventSeq = 0L
            return
        }
        val newBoxes = after.boxOwner.indices.filter { after.boxOwner[it] != null && before!!.boxOwner[it] == null }
        if (newBoxes.isNotEmpty()) {
            eventSeq++
            state.value = after.copy(
                lastBoxCompleted = DotsAndBoxesBoxCompletedEvent(eventSeq, newBoxes, after.boxOwner[newBoxes.first()] ?: 0)
            )
        }
    }

    private fun drawnEdgeCount(s: DotsAndBoxesState): Int = s.horizontalEdges.count { it } + s.verticalEdges.count { it }

    /** Host: re-deal only while the current board is over, so a duplicate request cannot wipe a board in play. */
    private fun hostPlayAgain() {
        val s = state.value ?: return
        if (matchOver.value || opponentLeft.value || !s.boardOver) return
        startMatch()
    }

    // --- Resume -----------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return dotsAndBoxesSnapshotJson.encodeToString(
            DotsAndBoxesSnapshot.serializer(),
            DotsAndBoxesSnapshot(
                boxRows = s.boxRows,
                boxCols = s.boxCols,
                horizontalEdges = s.horizontalEdges,
                verticalEdges = s.verticalEdges,
                boxOwner = s.boxOwner,
                players = s.players.map { DotsAndBoxesSnapshotPlayer(it.playerId, it.displayName, it.isBot) },
                currentPlayerIndex = s.currentPlayerIndex,
                scores = s.scores,
                lastAction = s.lastAction,
                boardOver = s.boardOver,
                winnerPlayerId = s.winnerPlayerId,
                sessionWins = sessionWins.value,
                sessionDraws = sessionDraws.value,
                nextStartingPlayerIndex = nextStartingPlayerIndex,
                difficulty = difficulty.name
            )
        )
    }

    /** Decodes and sanity-checks a snapshot against THIS game's board shape and roster; null if unusable. */
    private fun decodeSnapshot(text: String): DotsAndBoxesSnapshot? {
        val snap = try {
            dotsAndBoxesSnapshotJson.decodeFromString(DotsAndBoxesSnapshot.serializer(), text)
        } catch (_: Exception) {
            return null
        }
        val roster = context.players
        val valid = snap.boxRows == BOX_ROWS && snap.boxCols == BOX_COLS &&
            snap.horizontalEdges.size == (BOX_ROWS + 1) * BOX_COLS &&
            snap.verticalEdges.size == BOX_ROWS * (BOX_COLS + 1) &&
            snap.boxOwner.size == BOX_ROWS * BOX_COLS &&
            snap.players.size == roster.size &&
            snap.players.indices.all { snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot } &&
            snap.boxOwner.all { it == null || it in snap.players.indices } &&
            snap.scores.size == snap.players.size &&
            snap.scores.all { it >= 0 } &&
            snap.currentPlayerIndex in snap.players.indices &&
            snap.nextStartingPlayerIndex in snap.players.indices &&
            snap.sessionDraws >= 0 &&
            snap.sessionWins.keys == roster.map { it.playerId }.toSet() &&
            snap.sessionWins.values.all { it >= 0 } &&
            (snap.winnerPlayerId == null || snap.players.any { it.playerId == snap.winnerPlayerId })
        return if (valid) snap else null
    }

    /** Puts the engine into [snap] (shared by restore and undo). */
    private fun applySnapshot(snap: DotsAndBoxesSnapshot) {
        state.value = DotsAndBoxesState(
            boxRows = snap.boxRows,
            boxCols = snap.boxCols,
            horizontalEdges = snap.horizontalEdges,
            verticalEdges = snap.verticalEdges,
            boxOwner = snap.boxOwner,
            players = snap.players.map { DotsAndBoxesPlayerState(it.playerId, it.displayName, it.isBot) },
            currentPlayerIndex = snap.currentPlayerIndex,
            scores = snap.scores,
            lastAction = snap.lastAction,
            boardOver = snap.boardOver,
            winnerPlayerId = snap.winnerPlayerId,
            // No "latest claim" after a restore/undo, so the UI bursts and cues nothing. eventSeq is
            // NOT rewound: the next real claim must carry a seq the screen has never seen.
            lastBoxCompleted = null
        )
        sessionWins.value = snap.sessionWins
        sessionDraws.value = snap.sessionDraws
        nextStartingPlayerIndex = snap.nextStartingPlayerIndex
        difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        hintEdge.value = null
    }

    /** Null when there is nothing to save: no board yet, the session already ended, or a mode whose state lives with other players. */
    override fun snapshot(): String? {
        return try {
            if (matchOver.value || state.value == null) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            captureSnapshot()
        } catch (_: Exception) {
            null
        }
    }

    override fun restore(snapshot: String): Boolean {
        if (isNetworkedMode) return false // a networked board lives with the host, never on disk
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

    override val canUndo: Boolean get() = !isNetworkedMode && history.depth > 0 && !matchOver.value

    /**
     * Takes back the last human edge. Because completing a box keeps the turn, one step is exactly
     * one human edge; when that edge handed the turn to the CPU, the CPU's whole reply chain goes
     * with it (the snapshot was taken before the human's edge). Undoing the board-finishing edge
     * re-opens the board and takes its win off the session tally. Nothing here only counts upward.
     */
    override fun undo() {
        if (matchOver.value || isNetworkedMode) return
        val previous = history.pop() ?: return
        val snap = decodeSnapshot(previous) ?: return
        applySnapshot(snap)
    }

    // --- Hint -------------------------------------------------------------------------------

    /**
     * Suggests an edge for the player to move: one that completes a box (the most boxes) if any;
     * else one that does not leave a box with 3 sides for the opponent; else the sacrifice that
     * gives away the fewest boxes ([chainSizeIfOpened]). Deterministic (first best in scan order),
     * independent of the CPU difficulty. Highlights it via [hintEdge]; null when it is not a
     * human's turn or the board is over. Dots and Boxes keeps no records, so a hint costs nothing.
     */
    fun hint(): DotsAndBoxesHintEdge? {
        if (isNetworkedMode) return null
        val s = state.value ?: return null
        if (matchOver.value || s.boardOver) return null
        if (s.players[s.currentPlayerIndex].isBot) return null
        val candidates = allUndrawnEdges(s)
        if (candidates.isEmpty()) return null
        val completing = candidates.filter { boxesCompletedIfDrawn(s, it) > 0 }
        val pick = if (completing.isNotEmpty()) {
            completing.maxByOrNull { boxesCompletedIfDrawn(s, it) }!!
        } else {
            candidates.firstOrNull { isSafeMove(s, it) } ?: candidates.minByOrNull { chainSizeIfOpened(s, it) }!!
        }
        val edge = DotsAndBoxesHintEdge(pick.first, pick.second, pick.third)
        hintEdge.value = edge
        return edge
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        // A guest never deals: it waits for the host's StateSync (it already asked in init).
        if (isNetworkedMode && !isHost) return
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
        eventSeq = 0L
        // A new board starts a new undo history: undo never reaches back into the previous board.
        history.clear()
        hintEdge.value = null
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
        commitAndBroadcast()
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
     * cumulative boards-won tally. Mirrors DominoGame.leaveSession(), except that only a
     * STRICT leader is flagged the winner: an evenly split tally (1-1, 2-2) or one with no board
     * won at all has no winner, so [com.gamesuite.core.GameSessionManager] records it as a draw.
     * (It used to flag both players on an equal non-zero tally, which recorded a 1-1 session as a
     * win for the local player. Same fix as ConnectFourGame.leaveSession.)
     */
    fun leaveSession() {
        if (matchOver.value) return
        val wins = sessionWins.value
        val bestWins = wins.values.maxOrNull() ?: 0
        val leaders = context.players.count { (wins[it.playerId] ?: 0) == bestWins }
        val scores = context.players.map {
            val w = wins[it.playerId] ?: 0
            PlayerScore(playerId = it.playerId, score = w, isWinner = bestWins > 0 && w == bestWins && leaders == 1)
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
        if (isNetworkedMode) {
            // Either seat may ask; only the host deals, and only once the board is over.
            if (opponentLeft.value || state.value?.boardOver != true) return
            if (isHost) hostPlayAgain() else sendToHost(DotsAndBoxesNetMessage.PlayAgain)
            return
        }
        startMatch()
    }

    fun drawHorizontalEdge(row: Int, col: Int) = humanDrawEdge(isHorizontal = true, row = row, col = col)
    fun drawVerticalEdge(row: Int, col: Int) = humanDrawEdge(isHorizontal = false, row = row, col = col)

    /**
     * The entry point for a player's own edge (the bot goes through [drawEdge] directly): after
     * the same validity checks [drawEdge] makes, records an undo step, so a rejected tap leaves
     * none. Nothing is recorded if the bot is the one to move.
     */
    private fun humanDrawEdge(isHorizontal: Boolean, row: Int, col: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver) return
        if (!isValidEdge(isHorizontal, row, col, s.boxRows, s.boxCols)) return
        if (isEdgeDrawn(s, isHorizontal, row, col)) return
        if (isNetworkedMode) {
            // Only on this seat's own turn (which repeats after a claim). The host applies it
            // itself; a guest only asks the host and waits for the broadcast: it never mutates.
            if (opponentLeft.value || s.currentPlayerIndex != context.localPlayerIndex) return
            if (isHost) drawEdge(isHorizontal, row, col)
            else sendToHost(DotsAndBoxesNetMessage.DrawEdge(isHorizontal, row, col))
            return
        }
        if (!s.players[s.currentPlayerIndex].isBot) captureSnapshot()?.let { history.push(it) }
        drawEdge(isHorizontal, row, col)
    }

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
        hintEdge.value = null

        val newH = if (isHorizontal) setEdge(s.horizontalEdges, row * s.boxCols + col) else s.horizontalEdges
        val newV = if (!isHorizontal) setEdge(s.verticalEdges, row * (s.boxCols + 1) + col) else s.verticalEdges
        val affected = affectedBoxes(isHorizontal, row, col, s.boxRows, s.boxCols)

        val newOwner = s.boxOwner.toMutableList()
        // Collected (not just counted) so DotsAndBoxesBoxCompletedEvent can name exactly which
        // box(es) this single edge just claimed -- almost always one, but a shared edge between
        // two adjacent boxes can complete both at once (a "double-cross"), and the UI's own
        // per-box particle burst needs both positions, not just a count.
        val completedBoxIndices = mutableListOf<Int>()
        for (boxIndex in affected) {
            if (newOwner[boxIndex] != null) continue
            val br = boxIndex / s.boxCols
            val bc = boxIndex % s.boxCols
            if (countDrawnEdges(br, bc, newH, newV, s.boxCols) == 4) {
                newOwner[boxIndex] = s.currentPlayerIndex
                completedBoxIndices += boxIndex
            }
        }
        val completedCount = completedBoxIndices.size

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

        // Only advance the event on a REAL new completion -- a zero-box move leaves
        // lastBoxCompleted carrying the previous event forward unchanged, same "seq changing is
        // the only signal, not non-null-ness" idiom DotsAndBoxesBoxCompletedEvent's own KDoc
        // documents (mirrors TowerDefenceGame's lastEnemyDeath).
        val boxCompletedEvent = if (completedCount > 0) {
            eventSeq++
            DotsAndBoxesBoxCompletedEvent(eventSeq, completedBoxIndices.toList(), s.currentPlayerIndex)
        } else {
            s.lastBoxCompleted
        }

        state.value = s.copy(
            horizontalEdges = newH,
            verticalEdges = newV,
            boxOwner = newOwner,
            scores = newScores,
            currentPlayerIndex = nextIndex,
            boardOver = allClaimed,
            winnerPlayerId = winnerId,
            lastBoxCompleted = boxCompletedEvent,
            // Past tense / participle only, so the wording reads correctly whether the display
            // name is "CPU", "Player 2" or the local player's "You" ("You wins the board!" and
            // "You claimed 1 box and goes again" were both ungrammatical).
            lastAction = when {
                allClaimed && winnerId != null -> "${s.players.first { it.playerId == winnerId }.displayName} won the board!"
                allClaimed -> "It's a tie!"
                completedCount > 0 -> "${mover.displayName} claimed $completedCount box${if (completedCount > 1) "es" else ""}, going again"
                else -> "${mover.displayName} drew a line"
            }
        )
        commitAndBroadcast()
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
