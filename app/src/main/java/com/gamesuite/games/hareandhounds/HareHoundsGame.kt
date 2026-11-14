package com.gamesuite.games.hareandhounds

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
private data class HoundsSnapshot(
    val cells: List<Int>,
    val players: List<HoundsSnapshotPlayer>,
    val houndsSeat: Int,
    val currentPlayerIndex: Int,
    val houndMoves: Int,
    val ply: Int,
    val lastMove: HoundsMove?,
    val lastMover: Int,
    val lastAction: String,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    val endReason: String?,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int,
    val nextHoundsSeat: Int,
    val difficulty: String
)

@Serializable
private data class HoundsSnapshotPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

private val houndsJson = Json { ignoreUnknownKeys = true }

data class HoundsPlayerState(val playerId: String, val displayName: String, val isBot: Boolean)

/** Hub option ids for which side the person at seat 0 plays. */
object HoundsSides {
    const val HOUNDS = "hounds"
    const val HARE = "hare"
    const val RANDOM = "random"
}

/**
 * [cells] has 11 entries: 0 empty, 1 hound, 2 hare. [houndsSeat] is the roster seat playing the
 * hounds (the other seat is the hare). [endReason] is "escaped" / "trapped" / "stuck" / "timeout"
 * once [boardOver]; there are no draws.
 */
data class HoundsState(
    val cells: List<Int>,
    val players: List<HoundsPlayerState>,
    val houndsSeat: Int,
    val currentPlayerIndex: Int,
    val houndMoves: Int,
    val legalMoves: List<HoundsMove>,
    val lastMove: HoundsMove?,
    val lastMover: Int,
    val ply: Int,
    val lastAction: String,
    val boardOver: Boolean = false,
    val winnerPlayerId: String? = null,
    val endReason: String? = null
) {
    val hareSeat: Int get() = 1 - houndsSeat
    val currentRole: Int get() = if (currentPlayerIndex == houndsSeat) HoundsRole.HOUNDS else HoundsRole.HARE
    val hareAt: Int get() = cells.indexOf(2)
    val houndsRemaining: Int get() = (HoundsBoard.MAX_HOUND_MOVES - houndMoves).coerceAtLeast(0)

    fun roleOfSeat(seat: Int): Int = if (seat == houndsSeat) HoundsRole.HOUNDS else HoundsRole.HARE

    fun movablePoints(): Set<Int> = legalMoves.mapTo(mutableSetOf()) { it.from }
    fun destinations(from: Int): Set<Int> = legalMoves.filter { it.from == from }.mapTo(mutableSetOf()) { it.to }
    fun move(from: Int, to: Int): HoundsMove? = legalMoves.firstOrNull { it.from == from && it.to == to }

    /** Why tapping [tapped] (with [selected] already picked up, or -1) does nothing; null when it is useful. */
    fun whyIllegal(tapped: Int, selected: Int, mine: Boolean, botTurn: Boolean): HoundsWhy? {
        if (boardOver) return HoundsWhy.GAME_OVER
        if (!mine) return if (botTurn) HoundsWhy.CPU_THINKING else HoundsWhy.NOT_YOUR_TURN
        val piece = cells.getOrElse(tapped) { 0 }
        val role = currentRole
        val mineKind = if (role == HoundsRole.HOUNDS) 1 else 2
        if (piece != 0) {
            if (piece != mineKind) return HoundsWhy.NOT_YOUR_PIECE
            return if (tapped in movablePoints()) null else HoundsWhy.BLOCKED_PIECE
        }
        if (selected < 0) return HoundsWhy.PICK_PIECE_FIRST
        if (tapped in destinations(selected)) return null
        if (role == HoundsRole.HOUNDS && tapped in HoundsBoard.ADJACENT[selected] &&
            HoundsBoard.COLS[tapped] < HoundsBoard.COLS[selected]
        ) return HoundsWhy.NO_BACKWARDS
        return HoundsWhy.TOO_FAR
    }
}

/**
 * Hare and Hounds: three hounds against one hare on the classic 11-point board. The hounds move
 * first and may only step forward or straight up and down; the hare steps anywhere along a line.
 * The hounds win by trapping the hare; the hare wins by slipping past every hound, by leaving the
 * hounds without a move, or by surviving [HoundsBoard.MAX_HOUND_MOVES] hound moves. Roles are asymmetric, so
 * a pass-and-play or networked rematch swaps them; against the CPU the person keeps their chosen side.
 */
class HareAndHoundsGame : GameModule, ResumableGame, UndoableGame {
    override val gameId = "hare-and-hounds"
    override val displayName = "Hare and Hounds"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<HoundsState?>(null)

    /** The Hub's "side" choice for the person at the first human seat; set before [init]. */
    var sidePreference: String = HoundsSides.HOUNDS

    /** Used for the "random" side; injectable for tests. */
    var random: Random = Random.Default

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

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** The seat that plays the hounds on the NEXT board. */
    private var nextHoundsSeat = 0

    private val history = SnapshotHistory()
    val hintMove = mutableStateOf<HoundsMove?>(null)

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        sessionDraws.value = 0
        val humanSeat = context.players.indexOfFirst { !it.isBot }.coerceAtLeast(0)
        val otherSeat = 1 - humanSeat.coerceIn(0, 1)
        val hounds = when (sidePreference) {
            HoundsSides.HARE -> false
            HoundsSides.RANDOM -> random.nextBoolean()
            else -> true
        }
        nextHoundsSeat = if (isNetworked) (if (sidePreference == HoundsSides.HARE) 1 else 0) else if (hounds) humanSeat else otherSeat
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
        if (!isHost) sendToHost(HoundsNetMessage.RequestState)
        transport.onReconnected {
            if (isHost) broadcastState() else sendToHost(HoundsNetMessage.RequestState)
        }
        transport.onPlayerLeft { handlePlayerLeft(it) }
        transport.onPlayerJoined { handlePlayerJoined(it) }
    }

    private fun otherSeatId(): String? = context.players.getOrNull(1 - context.localPlayerIndex.coerceIn(0, 1))?.playerId

    private fun handlePlayerLeft(playerId: String) {
        if (!isNetworked || playerId != otherSeatId()) return
        opponentLeft.value = true
        val s = state.value ?: return
        commit(s.copy(lastAction = "Opponent disconnected"))
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked || !opponentLeft.value || playerId != otherSeatId()) return
        opponentLeft.value = false
        val s = state.value ?: return
        if (isHost) commit(s.copy(lastAction = "Opponent reconnected"))
    }

    // --- Position <-> state ------------------------------------------------------------------

    internal fun toPosition(s: HoundsState): HoundsPosition {
        val mask = s.cells.indices.fold(0) { m, i -> if (s.cells[i] == 1) m or (1 shl i) else m }
        return HoundsPosition(mask, s.cells.indexOf(2), s.currentRole, s.houndMoves)
    }

    private fun cellsOf(pos: HoundsPosition): List<Int> =
        List(HoundsBoard.POINTS) { i -> if (i == pos.hare) 2 else if (pos.hounds and (1 shl i) != 0) 1 else 0 }

    private fun stateFrom(
        pos: HoundsPosition, players: List<HoundsPlayerState>, houndsSeat: Int,
        lastMove: HoundsMove?, lastMover: Int, ply: Int, lastAction: String,
        boardOver: Boolean, winnerPlayerId: String?, endReason: String?
    ): HoundsState {
        val seat = if (pos.turn == HoundsRole.HOUNDS) houndsSeat else 1 - houndsSeat
        return HoundsState(
            cells = cellsOf(pos),
            players = players,
            houndsSeat = houndsSeat,
            currentPlayerIndex = seat,
            houndMoves = pos.houndMoves,
            legalMoves = if (boardOver) emptyList() else HoundsEngine.legalMoves(pos),
            lastMove = lastMove,
            lastMover = lastMover,
            ply = ply,
            lastAction = lastAction,
            boardOver = boardOver,
            winnerPlayerId = winnerPlayerId,
            endReason = endReason
        )
    }

    private fun validPosition(cells: List<Int>, houndsSeat: Int, cur: Int, houndMoves: Int, ply: Int, boardOver: Boolean): HoundsPosition? {
        if (cells.size != HoundsBoard.POINTS || cells.any { it !in 0..2 }) return null
        if (cells.count { it == 1 } != 3 || cells.count { it == 2 } != 1) return null
        if (houndsSeat !in 0..1 || cur !in 0..1 || ply < 0) return null
        if (houndMoves !in 0..HoundsBoard.MAX_HOUND_MOVES) return null
        val mask = cells.indices.fold(0) { m, i -> if (cells[i] == 1) m or (1 shl i) else m }
        val pos = HoundsPosition(mask, cells.indexOf(2), if (cur == houndsSeat) HoundsRole.HOUNDS else HoundsRole.HARE, houndMoves)
        if (!boardOver && HoundsEngine.winner(pos) != HoundsRole.NONE) return null
        return pos
    }

    // --- Resume ------------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return houndsJson.encodeToString(
            HoundsSnapshot.serializer(),
            HoundsSnapshot(
                cells = s.cells,
                players = s.players.map { HoundsSnapshotPlayer(it.playerId, it.displayName, it.isBot) },
                houndsSeat = s.houndsSeat,
                currentPlayerIndex = s.currentPlayerIndex,
                houndMoves = s.houndMoves,
                ply = s.ply,
                lastMove = s.lastMove,
                lastMover = s.lastMover,
                lastAction = s.lastAction,
                boardOver = s.boardOver,
                winnerPlayerId = s.winnerPlayerId,
                endReason = s.endReason,
                sessionWins = sessionWins.value,
                sessionDraws = sessionDraws.value,
                nextHoundsSeat = nextHoundsSeat,
                difficulty = difficulty.name
            )
        )
    }

    private fun decodeSnapshot(text: String): Pair<HoundsSnapshot, HoundsPosition>? {
        return try {
            val snap = houndsJson.decodeFromString(HoundsSnapshot.serializer(), text)
            val roster = context.players
            val pos = validPosition(snap.cells, snap.houndsSeat, snap.currentPlayerIndex, snap.houndMoves, snap.ply, snap.boardOver)
                ?: return null
            val lm = snap.lastMove
            val ok = snap.players.size == 2 && roster.size == 2 &&
                snap.players.indices.all {
                    snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot
                } &&
                snap.nextHoundsSeat in 0..1 &&
                snap.lastMover in -1..1 &&
                (lm == null || (lm.from in 0 until HoundsBoard.POINTS && lm.to in 0 until HoundsBoard.POINTS)) &&
                snap.sessionDraws >= 0 &&
                snap.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } } &&
                (snap.winnerPlayerId == null || snap.players.any { it.playerId == snap.winnerPlayerId })
            if (ok) snap to pos else null
        } catch (_: Exception) {
            null
        }
    }

    private fun applySnapshot(snap: HoundsSnapshot, pos: HoundsPosition, restoreDifficulty: Boolean) {
        state.value = stateFrom(
            pos, snap.players.map { HoundsPlayerState(it.playerId, it.displayName, it.isBot) }, snap.houndsSeat,
            snap.lastMove, snap.lastMover, snap.ply, snap.lastAction, snap.boardOver, snap.winnerPlayerId, snap.endReason
        )
        sessionWins.value = snap.sessionWins
        sessionDraws.value = snap.sessionDraws
        nextHoundsSeat = snap.nextHoundsSeat
        if (restoreDifficulty) difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        hintMove.value = null
    }

    override fun snapshot(): String? {
        return try {
            if (matchOver.value || state.value == null) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT &&
                context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
            ) return null
            captureSnapshot()
        } catch (_: Exception) {
            null
        }
    }

    override fun restore(snapshot: String): Boolean {
        val (snap, pos) = decodeSnapshot(snapshot) ?: return false
        matchOver.value = false
        applySnapshot(snap, pos, restoreDifficulty = true)
        history.clear()
        return true
    }

    // --- Undo / Hint -------------------------------------------------------------------------

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value && !isNetworked

    override fun undo() {
        if (matchOver.value || isNetworked) return
        val previous = history.pop() ?: return
        val (snap, pos) = decodeSnapshot(previous) ?: return
        applySnapshot(snap, pos, restoreDifficulty = false)
    }

    private fun hintAllowed(s: HoundsState): Boolean {
        if (isNetworked || matchOver.value || s.boardOver || s.legalMoves.isEmpty()) return false
        return !s.players[s.currentPlayerIndex].isBot
    }

    fun hint(): HoundsMove? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val best = HoundsEngine.bestMove(toPosition(s))
        hintMove.value = best
        return best
    }

    suspend fun requestHint(): HoundsMove? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val pos = toPosition(s)
        val best = withContext(Dispatchers.Default) { HoundsEngine.bestMove(pos) }
        if (state.value !== s || matchOver.value) return null
        hintMove.value = best
        return best
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    // --- Lifecycle ---------------------------------------------------------------------------

    override fun startMatch() {
        if (isNetworked && !isHost) return
        val players = context.players.map { HoundsPlayerState(it.playerId, it.displayName, it.isBot) }
        matchOver.value = false
        history.clear()
        hintMove.value = null
        val houndsSeat = nextHoundsSeat.coerceIn(0, 1)
        commit(
            stateFrom(
                HoundsEngine.initial(), players, houndsSeat, null, -1, 0, "Game started", false, null, null
            )
        )
        // Pass and play and networked rematches swap sides; against the CPU the person keeps theirs.
        if (players.none { it.isBot }) nextHoundsSeat = 1 - houndsSeat
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

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

    fun playAgain() {
        if (matchOver.value) return
        if (isNetworked) {
            if (opponentLeft.value || state.value?.boardOver != true) return
            if (isHost) startMatch() else sendToHost(HoundsNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    /** Plays one step for the current player; a no-op unless it is one of the current legal moves. */
    fun play(move: HoundsMove) {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver) return
        if (isNetworked) {
            if (opponentLeft.value || s.currentPlayerIndex != context.localPlayerIndex) return
            if (move !in s.legalMoves) return
            if (!isHost) {
                sendToHost(HoundsNetMessage.PlayMove(move.from, move.to))
                return
            }
        }
        applyMove(move)
    }

    private fun applyMove(move: HoundsMove) {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver || move !in s.legalMoves) return
        val mover = s.players[s.currentPlayerIndex]
        if (!mover.isBot && !isNetworked) captureSnapshot()?.let { history.push(it) }
        hintMove.value = null

        val next = HoundsEngine.apply(toPosition(s), move)
        val won = HoundsEngine.winner(next)
        val over = won != HoundsRole.NONE
        var winnerId: String? = null
        var reason: String? = null
        var text = if (s.currentRole == HoundsRole.HOUNDS) "${mover.displayName} moves a hound" else "${mover.displayName} moves the hare"
        if (over) {
            val winSeat = if (won == HoundsRole.HOUNDS) s.houndsSeat else s.hareSeat
            winnerId = s.players[winSeat].playerId
            reason = HoundsEngine.reasonOf(next)
            text = "${s.players[winSeat].displayName} wins"
            sessionWins.value = sessionWins.value + (winnerId to (sessionWins.value[winnerId] ?: 0) + 1)
        }
        commit(stateFrom(next, s.players, s.houndsSeat, move, s.currentPlayerIndex, s.ply + 1, text, over, winnerId, reason))
    }

    // ---- Networked internals ----

    private fun commit(newState: HoundsState) {
        state.value = newState
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
    }

    private fun toNetState(s: HoundsState) = HoundsNetState(
        cells = s.cells,
        players = s.players.map { HoundsNetPlayer(it.playerId, it.displayName) },
        houndsSeat = s.houndsSeat,
        currentPlayerIndex = s.currentPlayerIndex,
        houndMoves = s.houndMoves,
        ply = s.ply,
        lastMove = s.lastMove,
        lastMover = s.lastMover,
        lastAction = s.lastAction,
        boardOver = s.boardOver,
        winnerPlayerId = s.winnerPlayerId,
        endReason = s.endReason,
        sessionWins = sessionWins.value,
        sessionDraws = sessionDraws.value
    )

    private fun broadcastState() {
        val s = state.value ?: return
        val guestId = context.players.getOrNull(1)?.playerId ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val payload = Json.encodeToString(
            HoundsNetMessage.serializer(), HoundsNetMessage.StateSync(stateVersion, toNetState(s))
        ).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = hostId, toPlayerId = guestId, payload = payload)
    }

    private fun sendToHost(message: HoundsNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = Json.encodeToString(HoundsNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            com.gamesuite.core.NetJson.decodeFromString(HoundsNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val guestId = context.players.getOrNull(1)?.playerId
        when (message) {
            is HoundsNetMessage.StateSync ->
                if (!isHost && fromPlayerId == hostId) applyRemoteState(message.version, message.state)
            is HoundsNetMessage.RequestState ->
                if (isHost && fromPlayerId == guestId) broadcastState()
            is HoundsNetMessage.PlayMove ->
                if (isHost && fromPlayerId == guestId) applyGuestMove(HoundsMove(message.from, message.to))
            is HoundsNetMessage.RequestPlayAgain ->
                if (isHost && fromPlayerId == guestId && !opponentLeft.value && state.value?.boardOver == true && !matchOver.value) {
                    startMatch()
                }
        }
    }

    private fun applyGuestMove(move: HoundsMove) {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver || opponentLeft.value) return
        if (s.currentPlayerIndex != 1) return
        if (move !in s.legalMoves) return
        applyMove(move)
    }

    private fun applyRemoteState(version: Int, net: HoundsNetState) {
        if (version <= lastAppliedRemoteVersion) return
        val roster = context.players
        if (net.players.size != 2 || roster.size != 2) return
        if (!net.players.indices.all { net.players[it].playerId == roster[it].playerId }) return
        if (net.winnerPlayerId != null && net.players.none { it.playerId == net.winnerPlayerId }) return
        if (net.lastMover !in -1..1) return
        val lm = net.lastMove
        if (lm != null && (lm.from !in 0 until HoundsBoard.POINTS || lm.to !in 0 until HoundsBoard.POINTS)) return
        val pos = validPosition(net.cells, net.houndsSeat, net.currentPlayerIndex, net.houndMoves, net.ply, net.boardOver) ?: return
        if (!com.gamesuite.core.sessionTalliesValid(net.sessionWins, net.sessionDraws, context.players)) return
        lastAppliedRemoteVersion = version
        state.value = stateFrom(
            pos, net.players.mapIndexed { i, p -> HoundsPlayerState(p.playerId, p.displayName, roster[i].isBot) },
            net.houndsSeat, net.lastMove, net.lastMover, net.ply, net.lastAction, net.boardOver, net.winnerPlayerId, net.endReason
        )
        sessionWins.value = net.sessionWins
        sessionDraws.value = net.sessionDraws
        hintMove.value = null
    }

    private fun botToMove(s: HoundsState): Boolean {
        if (matchOver.value || s.boardOver) return false
        return s.players[s.currentPlayerIndex].isBot
    }

    fun playBotTurn(rng: Random = Random.Default) {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val move = HoundsEngine.chooseMove(toPosition(s), difficulty, rng) ?: return
        play(move)
    }

    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val level = difficulty
        val pos = toPosition(s)
        val move = withContext(Dispatchers.Default) { HoundsEngine.chooseMove(pos, level, Random.Default) } ?: return
        if (state.value !== s || matchOver.value) return
        play(move)
    }
}
