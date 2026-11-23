package com.gamesuite.games.hex

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
private data class HexSnapshot(
    val cells: List<Int?>,
    val players: List<HexSnapshotPlayer>,
    val currentPlayerIndex: Int,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    val lastMove: Int?,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int,
    val nextStartingPlayerIndex: Int,
    val difficulty: String,
    val size: Int = 11,
    val pieRule: Boolean = false,
    val pieOpen: Boolean = false,
    val swapped: Boolean = false
)

@Serializable
private data class HexSnapshotPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

private val hexJson = Json { ignoreUnknownKeys = true }

data class HexPlayerState(val playerId: String, val displayName: String, val isBot: Boolean)

/**
 * [cells] is size * size entries, row-major: the seat that owns the stone, or null. Seat 0 joins top to
 * bottom, seat 1 left to right. [winPath] is the winning chain (empty until someone connects). With the pie
 * rule on, [pieOpen] is true right after the opening stone: the player to move may swap instead of playing.
 * [swapped] records that a swap happened on this board. A finished board always has a winner (no draws).
 */
data class HexState(
    val cells: List<Int?>,
    val players: List<HexPlayerState>,
    val currentPlayerIndex: Int,
    val size: Int,
    val boardOver: Boolean = false,
    val winnerPlayerId: String? = null,
    val winPath: List<Int> = emptyList(),
    val lastMove: Int? = null,
    val pieRule: Boolean = false,
    val pieOpen: Boolean = false,
    val swapped: Boolean = false
) {
    val stones: Int get() = cells.count { it != null }
}

/** Why a [HexGame.placeStone] tap did or did not do anything; the screen turns it into a message. */
enum class HexPlaceResult {
    PLACED, SENT_TO_HOST, OCCUPIED, OUT_OF_RANGE, NOT_YOUR_TURN, BOARD_OVER, OPPONENT_LEFT, NO_BOARD
}

/** Whether a position (with its outcome and pie flags) is one the rules could produce. */
internal fun hexPositionConsistent(
    engine: HexEngine, cells: List<Int?>, currentIndex: Int, winnerIndex: Int?, lastMove: Int?,
    boardOver: Boolean, pieRule: Boolean, pieOpen: Boolean, swapped: Boolean
): Boolean {
    if (cells.size != engine.cellCount || !cells.all { it == null || it == 0 || it == 1 }) return false
    if (kotlin.math.abs(cells.count { it == 0 } - cells.count { it == 1 }) > 1) return false
    if (lastMove != null && (lastMove !in cells.indices || cells[lastMove] == null)) return false
    val board = engine.toBoard(cells)
    val c0 = engine.connected(board, 0)
    val c1 = engine.connected(board, 1)
    if (c0 && c1) return false
    if (boardOver != (c0 || c1)) return false
    if (boardOver && winnerIndex != (if (c0) 0 else 1)) return false
    if (!boardOver && winnerIndex != null) return false
    if (swapped && !pieRule) return false
    if (pieOpen) {
        val stones = cells.indices.filter { cells[it] != null }
        if (!pieRule || swapped || boardOver || stones.size != 1 || cells[stones[0]] != 1 - currentIndex) return false
    }
    return true
}

/**
 * Hex on an 9, 11 or 13 rhombus for two players; see [HexEngine] for the geometry, rules and CPU. Reuses
 * Reversi's session shape ([sessionWins]/[matchOver]/[leaveSession]/[playAgain]). Seat 0 (Red) connects top
 * and bottom, seat 1 (Blue) left and right. There are no draws, so [sessionDraws] stays 0 (kept for the
 * shared tally shape).
 *
 * PIE RULE (optional, [pieRule]): after the opening stone the second player may [swap] (take it over,
 * mirrored to their own goal) instead of moving, which keeps the first move from being a big advantage.
 *
 * Networked play (LOCAL_AD_HOC / ONLINE) is host-authoritative; see [HexNetMessage]. The CPU search and the
 * hint run off the main thread and are dropped if the position changed meanwhile.
 */
class HexGame : GameModule, ResumableGame, UndoableGame {
    override val gameId = "hex"
    override val displayName = "Hex"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<HexState?>(null)

    /** Board edge length for the NEXT board dealt (9, 11 or 13; anything else becomes 11). */
    var boardSize: Int = HexBoardSizes.STANDARD
        set(value) {
            field = if (value in HexBoardSizes.ALL) value else HexBoardSizes.STANDARD
            engine = HexEngine.of(field)
        }

    /** Pie rule for the NEXT board dealt. */
    var pieRule: Boolean = false

    @Volatile
    private var engine: HexEngine = HexEngine.of(HexBoardSizes.STANDARD)

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    private var stateVersion = com.gamesuite.core.SyncEpoch.base()
    private var lastAppliedRemoteVersion = 0

    val opponentLeft = mutableStateOf(false)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val sessionDraws = mutableStateOf(0)
    val matchOver = mutableStateOf(false)

    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** Tests can seed the CPU's randomness. */
    var random: Random = Random.Default

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var nextStartingPlayerIndex = 0

    /** The roster seat that opens the first board (the Hub's "who goes first"). */
    var firstOpenerIndex: Int = 0

    private val history = SnapshotHistory()
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
        if (!isHost) sendToHost(HexNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcastState() else sendToHost(HexNetMessage.RequestState) }
        transport.onPlayerLeft { handlePlayerLeft(it) }
        transport.onPlayerJoined { handlePlayerJoined(it) }
    }

    private fun otherSeatId(): String? = context.players.getOrNull(1 - context.localPlayerIndex.coerceIn(0, 1))?.playerId

    private fun handlePlayerLeft(playerId: String) {
        if (!isNetworked || playerId != otherSeatId()) return
        opponentLeft.value = true
        state.value?.let { commit(it) }
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked || !opponentLeft.value || playerId != otherSeatId()) return
        opponentLeft.value = false
        if (isHost) state.value?.let { commit(it) }
    }

    // --- Resume -----------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return hexJson.encodeToString(
            HexSnapshot.serializer(),
            HexSnapshot(
                cells = s.cells,
                players = s.players.map { HexSnapshotPlayer(it.playerId, it.displayName, it.isBot) },
                currentPlayerIndex = s.currentPlayerIndex,
                boardOver = s.boardOver,
                winnerPlayerId = s.winnerPlayerId,
                lastMove = s.lastMove,
                sessionWins = sessionWins.value,
                sessionDraws = sessionDraws.value,
                nextStartingPlayerIndex = nextStartingPlayerIndex,
                difficulty = difficulty.name,
                size = s.size,
                pieRule = s.pieRule,
                pieOpen = s.pieOpen,
                swapped = s.swapped
            )
        )
    }

    private fun decodeSnapshot(text: String): HexSnapshot? = try {
        val snap = hexJson.decodeFromString(HexSnapshot.serializer(), text)
        val roster = context.players
        val winnerIndex = snap.winnerPlayerId?.let { id -> snap.players.indexOfFirst { it.playerId == id } }
        val valid = snap.size in HexBoardSizes.ALL &&
            snap.players.size == 2 && snap.players.size == roster.size &&
            snap.players.indices.all {
                snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot
            } &&
            snap.currentPlayerIndex in 0..1 && snap.nextStartingPlayerIndex in 0..1 &&
            snap.sessionDraws >= 0 &&
            snap.sessionWins.all { (id, wins) -> wins >= 0 && roster.any { it.playerId == id } } &&
            (snap.winnerPlayerId == null || (winnerIndex != null && winnerIndex >= 0)) &&
            hexPositionConsistent(
                HexEngine.of(snap.size), snap.cells, snap.currentPlayerIndex, winnerIndex?.takeIf { it >= 0 },
                snap.lastMove, snap.boardOver, snap.pieRule, snap.pieOpen, snap.swapped
            )
        if (valid) snap else null
    } catch (_: Exception) {
        null
    }

    private fun applySnapshot(snap: HexSnapshot, restoreDifficulty: Boolean) {
        boardSize = snap.size
        pieRule = snap.pieRule
        state.value = buildState(
            snap.cells, snap.players.map { HexPlayerState(it.playerId, it.displayName, it.isBot) },
            snap.currentPlayerIndex, snap.size, snap.boardOver, snap.winnerPlayerId, snap.lastMove,
            snap.pieRule, snap.pieOpen, snap.swapped
        )
        sessionWins.value = snap.sessionWins
        sessionDraws.value = snap.sessionDraws
        nextStartingPlayerIndex = snap.nextStartingPlayerIndex
        if (restoreDifficulty) difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        hintMove.value = null
    }

    private fun buildState(
        cells: List<Int?>, players: List<HexPlayerState>, current: Int, size: Int, over: Boolean,
        winnerId: String?, lastMove: Int?, pie: Boolean, pieOpen: Boolean, swapped: Boolean
    ): HexState {
        val path = if (over) {
            val w = players.indexOfFirst { it.playerId == winnerId }
            if (w >= 0) HexEngine.of(size).winPath(HexEngine.of(size).toBoard(cells), w).orEmpty() else emptyList()
        } else emptyList()
        return HexState(cells, players, current, size, over, winnerId, path, lastMove, pie, pieOpen, swapped)
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

    /** Takes back the last human move or swap (and, against the CPU, its reply); re-opens a finished board and takes its win off the tally. */
    override fun undo() {
        if (matchOver.value || isNetworked) return
        val previous = history.pop() ?: return
        val snap = decodeSnapshot(previous) ?: return
        applySnapshot(snap, restoreDifficulty = false)
    }

    // --- Hint -------------------------------------------------------------------------------

    private fun hintAllowed(s: HexState): Boolean {
        if (isNetworked || matchOver.value || s.boardOver) return false
        return !s.players[s.currentPlayerIndex].isBot
    }

    private fun searchHint(rules: HexEngine, s: HexState): Int? =
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
        val players = context.players.map { HexPlayerState(it.playerId, it.displayName, it.isBot) }
        matchOver.value = false
        history.clear()
        hintMove.value = null
        val starting = nextStartingPlayerIndex % players.size
        commit(
            HexState(
                cells = List(engine.cellCount) { null }, players = players, currentPlayerIndex = starting,
                size = engine.n, pieRule = pieRule
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

    fun playAgain() {
        if (!com.gamesuite.core.LaunchPolicy.requestNewRound()) return
        if (matchOver.value) return
        if (isNetworked) {
            if (opponentLeft.value || state.value?.boardOver != true) return
            if (isHost) startMatch() else sendToHost(HexNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    /** Why a tap at [index] would be refused right now (null when accepted). */
    fun rejectionFor(index: Int): HexPlaceResult? {
        val s = state.value ?: return HexPlaceResult.NO_BOARD
        return when {
            matchOver.value || s.boardOver -> HexPlaceResult.BOARD_OVER
            index !in s.cells.indices -> HexPlaceResult.OUT_OF_RANGE
            isNetworked && opponentLeft.value -> HexPlaceResult.OPPONENT_LEFT
            isNetworked && s.currentPlayerIndex != context.localPlayerIndex -> HexPlaceResult.NOT_YOUR_TURN
            !isNetworked && s.players[s.currentPlayerIndex].isBot -> HexPlaceResult.NOT_YOUR_TURN
            s.cells[index] != null -> HexPlaceResult.OCCUPIED
            else -> null
        }
    }

    fun placeStone(index: Int): HexPlaceResult {
        rejectionFor(index)?.let { return it }
        if (isNetworked && !isHost) {
            sendToHost(HexNetMessage.PlaceStone(index))
            return HexPlaceResult.SENT_TO_HOST
        }
        applyPlacement(index)
        return HexPlaceResult.PLACED
    }

    /** True when the local human may take the swap right now. */
    val canSwap: Boolean
        get() {
            val s = state.value ?: return false
            if (!s.pieOpen || s.boardOver || matchOver.value) return false
            return if (isNetworked) s.currentPlayerIndex == context.localPlayerIndex && !opponentLeft.value
            else !s.players[s.currentPlayerIndex].isBot
        }

    /** The pie-rule swap: the player to move takes over the opening stone (mirrored to their own goal); the opener moves next. */
    fun swap(): Boolean {
        if (!canSwap) return false
        if (isNetworked && !isHost) {
            sendToHost(HexNetMessage.RequestSwap)
            return true
        }
        applySwap()
        return true
    }

    private fun applySwap() {
        val s = state.value ?: return
        if (!s.pieOpen || s.boardOver || matchOver.value) return
        val mover = s.players[s.currentPlayerIndex]
        if (!mover.isBot && !isNetworked) captureSnapshot()?.let { history.push(it) }
        hintMove.value = null
        val from = s.cells.indexOfFirst { it != null }
        val to = engine.swapCell(from)
        val cells = MutableList<Int?>(s.cells.size) { null }
        cells[to] = s.currentPlayerIndex
        commit(
            s.copy(
                cells = cells, currentPlayerIndex = 1 - s.currentPlayerIndex, lastMove = to,
                pieOpen = false, swapped = true
            )
        )
    }

    private fun applyPlacement(index: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver || index !in s.cells.indices || s.cells[index] != null) return
        val mover = s.players[s.currentPlayerIndex]
        if (!mover.isBot && !isNetworked) captureSnapshot()?.let { history.push(it) }
        hintMove.value = null
        val cells = s.cells.toMutableList().also { it[index] = s.currentPlayerIndex }
        val board = engine.toBoard(cells)
        val path = engine.winPath(board, s.currentPlayerIndex)
        val next = if (path != null) {
            sessionWins.value = sessionWins.value + (mover.playerId to (sessionWins.value[mover.playerId] ?: 0) + 1)
            s.copy(
                cells = cells, boardOver = true, winnerPlayerId = mover.playerId, winPath = path,
                lastMove = index, pieOpen = false
            )
        } else {
            s.copy(
                cells = cells, currentPlayerIndex = 1 - s.currentPlayerIndex, lastMove = index,
                pieOpen = s.pieRule && s.stones == 0
            )
        }
        commit(next)
    }

    // ---- Networked internals ----

    private fun commit(newState: HexState) {
        state.value = newState
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
    }

    private fun toNetState(s: HexState) = HexNetState(
        cells = s.cells,
        players = s.players.map { HexNetPlayer(it.playerId, it.displayName) },
        currentPlayerIndex = s.currentPlayerIndex,
        boardOver = s.boardOver,
        winnerPlayerId = s.winnerPlayerId,
        lastMove = s.lastMove,
        sessionWins = sessionWins.value,
        sessionDraws = sessionDraws.value,
        size = s.size,
        pieRule = s.pieRule,
        pieOpen = s.pieOpen,
        swapped = s.swapped
    )

    private fun broadcastState() {
        val s = state.value ?: return
        val guestId = context.players.getOrNull(1)?.playerId ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val payload = Json.encodeToString(HexNetMessage.serializer(), HexNetMessage.StateSync(stateVersion, toNetState(s)))
            .toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = hostId, toPlayerId = guestId, payload = payload)
    }

    private fun sendToHost(message: HexNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = Json.encodeToString(HexNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            com.gamesuite.core.NetJson.decodeFromString(HexNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val guestId = context.players.getOrNull(1)?.playerId
        when (message) {
            is HexNetMessage.StateSync ->
                if (!isHost && fromPlayerId == hostId) applyRemoteState(message.version, message.state)
            is HexNetMessage.RequestState ->
                if (isHost && fromPlayerId == guestId) broadcastState()
            is HexNetMessage.PlaceStone ->
                if (isHost && fromPlayerId == guestId) applyGuestPlacement(message.cell)
            is HexNetMessage.RequestSwap ->
                if (isHost && fromPlayerId == guestId && state.value?.currentPlayerIndex == 1 && !opponentLeft.value) applySwap()
            is HexNetMessage.RequestPlayAgain ->
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

    private fun applyRemoteState(version: Int, net: HexNetState) {
        if (version <= lastAppliedRemoteVersion) return
        val roster = context.players
        val winnerIndex = net.winnerPlayerId?.let { id -> net.players.indexOfFirst { it.playerId == id } }
        val valid = net.size in HexBoardSizes.ALL &&
            net.players.size == 2 && roster.size == 2 &&
            net.players.indices.all { net.players[it].playerId == roster[it].playerId } &&
            net.currentPlayerIndex in 0..1 &&
            (net.winnerPlayerId == null || (winnerIndex != null && winnerIndex >= 0)) &&
            hexPositionConsistent(
                HexEngine.of(net.size), net.cells, net.currentPlayerIndex, winnerIndex?.takeIf { it >= 0 },
                net.lastMove, net.boardOver, net.pieRule, net.pieOpen, net.swapped
            )
        if (!valid) return
        if (!com.gamesuite.core.sessionTalliesValid(net.sessionWins, net.sessionDraws, context.players)) return
        lastAppliedRemoteVersion = version
        boardSize = net.size
        pieRule = net.pieRule
        state.value = buildState(
            net.cells, net.players.mapIndexed { i, p -> HexPlayerState(p.playerId, p.displayName, roster[i].isBot) },
            net.currentPlayerIndex, net.size, net.boardOver, net.winnerPlayerId, net.lastMove,
            net.pieRule, net.pieOpen, net.swapped
        )
        sessionWins.value = net.sessionWins
        sessionDraws.value = net.sessionDraws
        hintMove.value = null
    }

    // ---- CPU ----

    private fun botToMove(s: HexState): Boolean = !matchOver.value && !s.boardOver && s.players[s.currentPlayerIndex].isBot

    /** The CPU's reply, synchronously: a pie-rule swap when it suits, else a stone. The screen uses [playBotTurnAsync]. */
    fun playBotTurn() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        decide(engine, s, difficulty, random, deadlineNanos = Long.MAX_VALUE).let { perform(it) }
    }

    /** [playBotTurn] with the search on [Dispatchers.Default], capped at about [BOT_TIME_CAP_MS]; dropped if the position changed. */
    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val rules = engine
        val level = difficulty
        val rng = random
        val deadline = System.nanoTime() + BOT_TIME_CAP_MS * 1_000_000L
        val choice = withContext(Dispatchers.Default) { decide(rules, s, level, rng, deadline) }
        if (state.value !== s || matchOver.value) return
        perform(choice)
    }

    /** -1 = swap, otherwise the cell to play; null when there is no move. */
    private fun decide(rules: HexEngine, s: HexState, level: CpuDifficulty, rng: Random, deadlineNanos: Long): Int? {
        val board = rules.toBoard(s.cells)
        if (s.pieOpen) {
            val stone = s.cells.indexOfFirst { it != null }
            // EASY swaps rarely enough to be fooled; the other levels judge the stone.
            val swap = rules.shouldSwap(stone) && (level != CpuDifficulty.EASY || rng.nextInt(3) != 0)
            if (swap) return SWAP
        } else if (s.pieRule && s.stones == 0) {
            return rules.pieOpening(rng)
        }
        return rules.chooseMove(board, s.currentPlayerIndex, level, deadlineNanos, rng)
    }

    private fun perform(choice: Int?) {
        when {
            choice == null -> {}
            choice == SWAP -> applySwapAsBot()
            else -> botPlace(choice)
        }
    }

    /** A bot's stone: [placeStone] refuses taps on a bot's turn, so the CPU goes through this check instead. */
    private fun botPlace(index: Int) {
        val s = state.value ?: return
        if (!botToMove(s) || index !in s.cells.indices || s.cells[index] != null) return
        applyPlacement(index)
    }

    private fun applySwapAsBot() {
        if (isNetworked) return
        applySwap()
    }

    private companion object {
        const val SWAP = -1
        const val HINT_TIME_BUDGET_MS = 700L
        const val BOT_TIME_CAP_MS = 1_500L
    }
}
