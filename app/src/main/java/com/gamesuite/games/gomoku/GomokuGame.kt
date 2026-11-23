package com.gamesuite.games.gomoku

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
private data class GomokuSnapshot(
    val cells: List<Int?>,
    val players: List<GomokuSnapshotPlayer>,
    val currentPlayerIndex: Int,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    val winLine: List<Int>,
    val lastMove: Int?,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int,
    val nextStartingPlayerIndex: Int,
    val difficulty: String,
    val exactFive: Boolean = false
)

@Serializable
private data class GomokuSnapshotPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

private val gomokuJson = Json { ignoreUnknownKeys = true }

/** A player's identity + bot flag (Gomoku is always exactly two players). */
data class GomokuPlayerState(val playerId: String, val displayName: String, val isBot: Boolean)

/**
 * [cells] is 225 entries, row-major (index = row * 15 + col): the index into [players] who owns that stone,
 * or null. [winLine] is the winning run (empty unless a player won); [lastMove] the most recent stone, for
 * the UI's marker. A drawn board is [boardOver] with no [winnerPlayerId].
 */
data class GomokuState(
    val cells: List<Int?>,
    val players: List<GomokuPlayerState>,
    val currentPlayerIndex: Int,
    val boardOver: Boolean = false,
    val winnerPlayerId: String? = null,
    val winLine: List<Int> = emptyList(),
    val lastMove: Int? = null,
    val exactFive: Boolean = false
) {
    /** Stones on the board. */
    val stones: Int get() = cells.count { it != null }
}

/** Why a [GomokuGame.placeStone] tap did or did not do anything; the screen turns it into a message. */
enum class GomokuPlaceResult {
    PLACED, SENT_TO_HOST, OCCUPIED, OUT_OF_RANGE, NOT_YOUR_TURN, BOARD_OVER, OPPONENT_LEFT, NO_BOARD
}

/**
 * Whether [cells] with the given outcome is a position the rules could produce. Shared by Resume and the
 * guest's net-state check so neither trusts a stored or received position blindly.
 */
internal fun gomokuPositionConsistent(
    engine: GomokuEngine, cells: List<Int?>, winnerIndex: Int?, winLine: List<Int>,
    lastMove: Int?, boardOver: Boolean
): Boolean {
    if (cells.size != engine.cellCount || !cells.all { it == null || it == 0 || it == 1 }) return false
    if (kotlin.math.abs(cells.count { it == 0 } - cells.count { it == 1 }) > 1) return false
    if (lastMove != null && (lastMove !in cells.indices || cells[lastMove] == null)) return false
    if (winLine.any { it !in cells.indices }) return false
    val board = engine.toBoard(cells)
    if (winnerIndex != null) {
        if (!boardOver || winLine.size < GOMOKU_WIN_LENGTH || winLine.any { cells[it] != winnerIndex }) return false
        return engine.winLine(board, winLine.first(), winnerIndex) != null
    }
    if (winLine.isNotEmpty()) return false
    // Not over and nobody credited: a five already on the board would make this state impossible.
    for (i in cells.indices) {
        val owner = cells[i] ?: continue
        if (engine.winLine(board, i, owner) != null) return false
    }
    return if (boardOver) engine.isFull(board) else !engine.isFull(board)
}

/**
 * Gomoku (five in a row) on a 15 x 15 board for two players; see [GomokuEngine] for the rules and CPU.
 * Reuses the session shape of Reversi ([sessionWins]/[sessionDraws]/[matchOver]/[leaveSession]/[playAgain]).
 * Seat 0 is shown with dark stones and seat 1 with light, whichever seat opens the board.
 *
 * Networked play (LOCAL_AD_HOC / ONLINE) is host-authoritative; see [GomokuNetMessage]. The CPU search and
 * the hint run off the main thread and are dropped if the position changed meanwhile.
 */
class GomokuGame : GameModule, ResumableGame, UndoableGame {
    override val gameId = "gomoku"
    override val displayName = "Gomoku"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<GomokuState?>(null)

    /** Free-style (false, six or more also wins) or exactly-five rules (true) for the NEXT board dealt. */
    var exactFive: Boolean = false
        set(value) {
            field = value
            engine = GomokuEngine(GOMOKU_SIZE, value)
        }

    @Volatile
    private var engine: GomokuEngine = GomokuEngine(GOMOKU_SIZE, false)

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    private var stateVersion = com.gamesuite.core.SyncEpoch.base()
    private var lastAppliedRemoteVersion = 0

    /** Networked only: the other seat left; no further moves or rematches. */
    val opponentLeft = mutableStateOf(false)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val sessionDraws = mutableStateOf(0)
    val matchOver = mutableStateOf(false)

    /** Pre-set from the Hub's CPU level before [init]. */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** Tests can seed the EASY level's randomness. */
    var random: Random = Random.Default

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var nextStartingPlayerIndex = 0

    /** The roster seat that opens the first board (the Hub's "who goes first"). */
    var firstOpenerIndex: Int = 0

    private val history = SnapshotHistory()

    /** The point the last hint suggested, ringed until the next move, undo or new board. */
    val hintMove = mutableStateOf<Int?>(null)

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        sessionDraws.value = 0
        nextStartingPlayerIndex = if (!isNetworked && firstOpenerIndex in context.players.indices) firstOpenerIndex else 0
        history.clear()
        hintMove.value = null
        opponentLeft.value = false
        stateVersion = com.gamesuite.core.SyncEpoch.base()
        lastAppliedRemoteVersion = 0
        if (isNetworked) registerNetworkListeners()
    }

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { fromPlayerId, payload -> handleNetworkMessage(fromPlayerId, payload) }
        if (!isHost) sendToHost(GomokuNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcastState() else sendToHost(GomokuNetMessage.RequestState) }
        transport.onPlayerLeft { handlePlayerLeft(it) }
        transport.onPlayerJoined { handlePlayerJoined(it) }
    }

    private fun otherSeatId(): String? = context.players.getOrNull(1 - context.localPlayerIndex.coerceIn(0, 1))?.playerId

    private fun handlePlayerLeft(playerId: String) {
        if (!isNetworked || playerId != otherSeatId()) return
        opponentLeft.value = true
        val s = state.value ?: return
        commit(s)
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked || !opponentLeft.value || playerId != otherSeatId()) return
        opponentLeft.value = false
        val s = state.value ?: return
        if (isHost) commit(s)
    }

    // --- Resume -----------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return gomokuJson.encodeToString(
            GomokuSnapshot.serializer(),
            GomokuSnapshot(
                cells = s.cells,
                players = s.players.map { GomokuSnapshotPlayer(it.playerId, it.displayName, it.isBot) },
                currentPlayerIndex = s.currentPlayerIndex,
                boardOver = s.boardOver,
                winnerPlayerId = s.winnerPlayerId,
                winLine = s.winLine,
                lastMove = s.lastMove,
                sessionWins = sessionWins.value,
                sessionDraws = sessionDraws.value,
                nextStartingPlayerIndex = nextStartingPlayerIndex,
                difficulty = difficulty.name,
                exactFive = s.exactFive
            )
        )
    }

    private fun decodeSnapshot(text: String): GomokuSnapshot? = try {
        val snap = gomokuJson.decodeFromString(GomokuSnapshot.serializer(), text)
        val roster = context.players
        val winnerIndex = snap.winnerPlayerId?.let { id -> snap.players.indexOfFirst { it.playerId == id } }
        val valid = snap.players.size == 2 && snap.players.size == roster.size &&
            snap.players.indices.all {
                snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot
            } &&
            snap.currentPlayerIndex in 0..1 && snap.nextStartingPlayerIndex in 0..1 &&
            snap.sessionDraws >= 0 &&
            snap.sessionWins.all { (id, wins) -> wins >= 0 && roster.any { it.playerId == id } } &&
            (snap.winnerPlayerId == null || (winnerIndex != null && winnerIndex >= 0)) &&
            gomokuPositionConsistent(
                GomokuEngine(GOMOKU_SIZE, snap.exactFive), snap.cells, winnerIndex?.takeIf { it >= 0 },
                snap.winLine, snap.lastMove, snap.boardOver
            )
        if (valid) snap else null
    } catch (_: Exception) {
        null
    }

    private fun applySnapshot(snap: GomokuSnapshot, restoreDifficulty: Boolean) {
        exactFive = snap.exactFive
        state.value = GomokuState(
            cells = snap.cells,
            players = snap.players.map { GomokuPlayerState(it.playerId, it.displayName, it.isBot) },
            currentPlayerIndex = snap.currentPlayerIndex,
            boardOver = snap.boardOver,
            winnerPlayerId = snap.winnerPlayerId,
            winLine = snap.winLine,
            lastMove = snap.lastMove,
            exactFive = snap.exactFive
        )
        sessionWins.value = snap.sessionWins
        sessionDraws.value = snap.sessionDraws
        nextStartingPlayerIndex = snap.nextStartingPlayerIndex
        if (restoreDifficulty) difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        hintMove.value = null
    }

    override fun snapshot(): String? = try {
        if (matchOver.value || state.value == null) null
        else if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT &&
            context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
        ) null
        else captureSnapshot()
    } catch (_: Exception) {
        null
    }

    override fun restore(snapshot: String): Boolean {
        val snap = decodeSnapshot(snapshot) ?: return false
        matchOver.value = false
        applySnapshot(snap, restoreDifficulty = true)
        history.clear()
        return true
    }

    // --- Undo -------------------------------------------------------------------------------

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value && !isNetworked

    /** Takes back the last human move (and, against the CPU, its reply); re-opens a finished board and takes its result off the tally. */
    override fun undo() {
        if (matchOver.value || isNetworked) return
        val previous = history.pop() ?: return
        val snap = decodeSnapshot(previous) ?: return
        applySnapshot(snap, restoreDifficulty = false)
    }

    // --- Hint -------------------------------------------------------------------------------

    private fun hintAllowed(s: GomokuState): Boolean {
        if (isNetworked || matchOver.value || s.boardOver) return false
        return !s.players[s.currentPlayerIndex].isBot
    }

    private fun searchHint(rules: GomokuEngine, s: GomokuState): Int? =
        rules.chooseMove(
            rules.toBoard(s.cells), s.currentPlayerIndex, CpuDifficulty.HARD,
            System.nanoTime() + HINT_TIME_BUDGET_MS * 1_000_000L, Random(0)
        )

    /** The strongest move the CPU search finds for the player to move, highlighted as [hintMove]; synchronous. */
    fun hint(): Int? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        return searchHint(engine, s).also { hintMove.value = it }
    }

    /** [hint] with the search on [Dispatchers.Default]; dropped if the position changed meanwhile. */
    suspend fun requestHint(): Int? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val rules = engine
        val best = withContext(Dispatchers.Default) { searchHint(rules, s) }
        if (state.value !== s || matchOver.value) return null
        hintMove.value = best
        return best
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        if (isNetworked && !isHost) return
        val players = context.players.map { GomokuPlayerState(it.playerId, it.displayName, it.isBot) }
        matchOver.value = false
        history.clear()
        hintMove.value = null
        val starting = nextStartingPlayerIndex % players.size
        commit(
            GomokuState(
                cells = List(engine.cellCount) { null },
                players = players,
                currentPlayerIndex = starting,
                exactFive = engine.exactFive
            )
        )
        nextStartingPlayerIndex = (nextStartingPlayerIndex + 1) % players.size
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Ends the whole session, reporting boards won; only a strict leader wins. */
    fun leaveSession() {
        if (matchOver.value) return
        val wins = sessionWins.value
        val bestWins = wins.values.maxOrNull() ?: 0
        val leaders = context.players.count { (wins[it.playerId] ?: 0) == bestWins }
        endMatch(
            GameResult(
                scores = context.players.map {
                    val w = wins[it.playerId] ?: 0
                    PlayerScore(it.playerId, w, isWinner = bestWins > 0 && w == bestWins && leaders == 1)
                }
            )
        )
    }

    /** Deals a fresh board in the same session (a guest asks the host). */
    fun playAgain() {
        if (!com.gamesuite.core.LaunchPolicy.requestNewRound()) return
        if (matchOver.value) return
        if (isNetworked) {
            if (opponentLeft.value || state.value?.boardOver != true) return
            if (isHost) startMatch() else sendToHost(GomokuNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    /** Why a tap at [index] would be refused right now (null when it would be accepted). Pure; the UI uses it for the message. */
    fun rejectionFor(index: Int): GomokuPlaceResult? {
        val s = state.value ?: return GomokuPlaceResult.NO_BOARD
        return when {
            matchOver.value || s.boardOver -> GomokuPlaceResult.BOARD_OVER
            index !in s.cells.indices -> GomokuPlaceResult.OUT_OF_RANGE
            isNetworked && opponentLeft.value -> GomokuPlaceResult.OPPONENT_LEFT
            isNetworked && s.currentPlayerIndex != context.localPlayerIndex -> GomokuPlaceResult.NOT_YOUR_TURN
            !isNetworked && s.players[s.currentPlayerIndex].isBot -> GomokuPlaceResult.NOT_YOUR_TURN
            s.cells[index] != null -> GomokuPlaceResult.OCCUPIED
            else -> null
        }
    }

    /** Places the current player's stone at [index] (a guest asks the host instead). */
    fun placeStone(index: Int): GomokuPlaceResult {
        rejectionFor(index)?.let { return it }
        if (isNetworked && !isHost) {
            sendToHost(GomokuNetMessage.PlaceStone(index))
            return GomokuPlaceResult.SENT_TO_HOST
        }
        applyPlacement(index)
        return GomokuPlaceResult.PLACED
    }

    private fun applyPlacement(index: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver || index !in s.cells.indices || s.cells[index] != null) return
        val mover = s.players[s.currentPlayerIndex]
        if (!mover.isBot && !isNetworked) captureSnapshot()?.let { history.push(it) }
        hintMove.value = null
        val newCells = s.cells.toMutableList().also { it[index] = s.currentPlayerIndex }
        val board = engine.toBoard(newCells)
        val line = engine.winLine(board, index, s.currentPlayerIndex)
        val next = when {
            line != null -> s.copy(
                cells = newCells, boardOver = true, winnerPlayerId = mover.playerId, winLine = line, lastMove = index
            ).also { sessionWins.value = sessionWins.value + (mover.playerId to (sessionWins.value[mover.playerId] ?: 0) + 1) }
            engine.isFull(board) -> s.copy(cells = newCells, boardOver = true, lastMove = index)
                .also { sessionDraws.value += 1 }
            else -> s.copy(cells = newCells, currentPlayerIndex = 1 - s.currentPlayerIndex, lastMove = index)
        }
        commit(next)
    }

    // ---- Networked internals ----

    private fun commit(newState: GomokuState) {
        state.value = newState
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
    }

    private fun toNetState(s: GomokuState) = GomokuNetState(
        cells = s.cells,
        players = s.players.map { GomokuNetPlayer(it.playerId, it.displayName) },
        currentPlayerIndex = s.currentPlayerIndex,
        boardOver = s.boardOver,
        winnerPlayerId = s.winnerPlayerId,
        winLine = s.winLine,
        lastMove = s.lastMove,
        sessionWins = sessionWins.value,
        sessionDraws = sessionDraws.value,
        exactFive = s.exactFive
    )

    private fun broadcastState() {
        val s = state.value ?: return
        val guestId = context.players.getOrNull(1)?.playerId ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val payload = Json.encodeToString(
            GomokuNetMessage.serializer(), GomokuNetMessage.StateSync(stateVersion, toNetState(s))
        ).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = hostId, toPlayerId = guestId, payload = payload)
    }

    private fun sendToHost(message: GomokuNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = Json.encodeToString(GomokuNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            com.gamesuite.core.NetJson.decodeFromString(GomokuNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val guestId = context.players.getOrNull(1)?.playerId
        when (message) {
            is GomokuNetMessage.StateSync ->
                if (!isHost && fromPlayerId == hostId) applyRemoteState(message.version, message.state)
            is GomokuNetMessage.RequestState ->
                if (isHost && fromPlayerId == guestId) broadcastState()
            is GomokuNetMessage.PlaceStone ->
                if (isHost && fromPlayerId == guestId) applyGuestPlacement(message.cell)
            is GomokuNetMessage.RequestPlayAgain ->
                if (isHost && fromPlayerId == guestId && !opponentLeft.value &&
                    state.value?.boardOver == true && !matchOver.value
                ) startMatch()
        }
    }

    private fun applyGuestPlacement(cell: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver || opponentLeft.value) return
        if (s.currentPlayerIndex != 1) return
        if (cell !in s.cells.indices || s.cells[cell] != null) return
        applyPlacement(cell)
    }

    private fun applyRemoteState(version: Int, net: GomokuNetState) {
        if (version <= lastAppliedRemoteVersion) return
        val roster = context.players
        val winnerIndex = net.winnerPlayerId?.let { id -> net.players.indexOfFirst { it.playerId == id } }
        val valid = net.players.size == 2 && roster.size == 2 &&
            net.players.indices.all { net.players[it].playerId == roster[it].playerId } &&
            net.currentPlayerIndex in 0..1 &&
            (net.winnerPlayerId == null || (winnerIndex != null && winnerIndex >= 0)) &&
            gomokuPositionConsistent(
                GomokuEngine(GOMOKU_SIZE, net.exactFive), net.cells, winnerIndex?.takeIf { it >= 0 },
                net.winLine, net.lastMove, net.boardOver
            )
        if (!valid) return
        if (!com.gamesuite.core.sessionTalliesValid(net.sessionWins, net.sessionDraws, context.players)) return
        lastAppliedRemoteVersion = version
        exactFive = net.exactFive
        state.value = GomokuState(
            cells = net.cells,
            players = net.players.mapIndexed { i, p -> GomokuPlayerState(p.playerId, p.displayName, roster[i].isBot) },
            currentPlayerIndex = net.currentPlayerIndex,
            boardOver = net.boardOver,
            winnerPlayerId = net.winnerPlayerId,
            winLine = net.winLine,
            lastMove = net.lastMove,
            exactFive = net.exactFive
        )
        sessionWins.value = net.sessionWins
        sessionDraws.value = net.sessionDraws
        hintMove.value = null
    }

    // ---- CPU ----

    private fun botToMove(s: GomokuState): Boolean = !matchOver.value && !s.boardOver && s.players[s.currentPlayerIndex].isBot

    /** The CPU's reply, synchronously. No-op unless it is a bot's turn. The screen uses [playBotTurnAsync]. */
    fun playBotTurn() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val index = engine.chooseMove(engine.toBoard(s.cells), s.currentPlayerIndex, difficulty, random = random) ?: return
        botPlace(index)
    }

    /** A bot's stone: [placeStone] refuses taps on a bot's turn, so the CPU goes through this check instead. */
    private fun botPlace(index: Int) {
        val s = state.value ?: return
        if (!botToMove(s) || index !in s.cells.indices || s.cells[index] != null) return
        applyPlacement(index)
    }

    /** [playBotTurn] with the search on [Dispatchers.Default], capped at about [BOT_TIME_CAP_MS]; dropped if the position changed. */
    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val rules = engine
        val level = difficulty
        val rng = random
        val deadline = System.nanoTime() + BOT_TIME_CAP_MS * 1_000_000L
        val index = withContext(Dispatchers.Default) {
            rules.chooseMove(rules.toBoard(s.cells), s.currentPlayerIndex, level, deadline, rng)
        } ?: return
        if (state.value !== s || matchOver.value) return
        botPlace(index)
    }

    private companion object {
        const val HINT_TIME_BUDGET_MS = 700L
        const val BOT_TIME_CAP_MS = 1_500L
    }
}
