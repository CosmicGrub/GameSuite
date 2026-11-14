package com.gamesuite.games.morris

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
private data class MorrisSnapshot(
    val points: List<Int?>,
    val players: List<MorrisSnapshotPlayer>,
    val currentPlayerIndex: Int,
    val hand: List<Int>,
    val quietPlies: Int,
    val ply: Int,
    val lastMove: MorrisMove?,
    val lastMover: Int,
    val lastAction: String,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    val endReason: String?,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int,
    val nextStartingPlayerIndex: Int,
    val difficulty: String,
    val men: Int,
    val flying: Boolean
)

@Serializable
private data class MorrisSnapshotPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

private val morrisJson = Json { ignoreUnknownKeys = true }

data class MorrisPlayerState(val playerId: String, val displayName: String, val isBot: Boolean)

/** What the player to move is doing right now. */
enum class MorrisPhase { PLACING, MOVING, FLYING }

/**
 * [points] holds the owner (player index) of each of the 24 points or null. [legalMoves] are the
 * current player's complete turns (including the removal pick when a move closes a mill).
 * [endReason] is "reduced" (a player is down to two men), "blocked" (a player cannot move) or
 * "drawn" (too long without a mill) once [boardOver]; a null [winnerPlayerId] then means a draw.
 */
data class MorrisState(
    val points: List<Int?>,
    val players: List<MorrisPlayerState>,
    val currentPlayerIndex: Int,
    val hand: List<Int>,
    val onBoard: List<Int>,
    val legalMoves: List<MorrisMove>,
    val lastMove: MorrisMove?,
    val lastMover: Int,
    val ply: Int,
    val quietPlies: Int,
    val lastAction: String,
    val boardOver: Boolean = false,
    val winnerPlayerId: String? = null,
    val endReason: String? = null,
    val men: Int = 9,
    val flying: Boolean = true
) {
    val phase: MorrisPhase
        get() = when {
            hand[currentPlayerIndex] > 0 -> MorrisPhase.PLACING
            flying && onBoard[currentPlayerIndex] == 3 -> MorrisPhase.FLYING
            else -> MorrisPhase.MOVING
        }

    /** Points whose man can move this turn. */
    fun movablePoints(): Set<Int> = legalMoves.filter { it.from >= 0 }.mapTo(mutableSetOf()) { it.from }

    /** Empty points a man on [from] (or a new man when from = -1) can go to. */
    fun destinations(from: Int): Set<Int> = legalMoves.filter { it.from == from }.mapTo(mutableSetOf()) { it.to }

    /** The men that may be removed by the move [from] to [to]; empty when that move closes no mill. */
    fun removals(from: Int, to: Int): List<Int> =
        legalMoves.filter { it.from == from && it.to == to && it.remove >= 0 }.map { it.remove }

    fun move(from: Int, to: Int, remove: Int = -1): MorrisMove? =
        legalMoves.firstOrNull { it.from == from && it.to == to && it.remove == remove }

    /**
     * Why tapping [tapped] does nothing given the current half-made turn, or null when the tap is
     * useful. [selected] is the man already picked up (-1 for none); [mine] is whether the local
     * player may act. (Choosing the man to remove after a mill is [whyNotRemovable].)
     */
    fun whyIllegal(tapped: Int, selected: Int, mine: Boolean, botTurn: Boolean): MorrisWhy? {
        if (boardOver) return MorrisWhy.GAME_OVER
        if (!mine) return if (botTurn) MorrisWhy.CPU_THINKING else MorrisWhy.NOT_YOUR_TURN
        val owner = points.getOrNull(tapped)
        val me = currentPlayerIndex
        if (phase == MorrisPhase.PLACING) {
            return if (owner != null) MorrisWhy.POINT_TAKEN else null
        }
        if (selected >= 0 && owner == null) {
            return if (tapped in destinations(selected)) null else MorrisWhy.TOO_FAR
        }
        if (owner == null) return MorrisWhy.PICK_OWN_MAN
        if (owner != me) return MorrisWhy.ENEMY_MAN
        return if (tapped in movablePoints()) null else MorrisWhy.BLOCKED_MAN
    }

    /** The removal tap [tapped] is wrong for the half-made move: protected men give a reason. */
    fun whyNotRemovable(tapped: Int, from: Int, to: Int): MorrisWhy? {
        val owner = points.getOrNull(tapped) ?: return MorrisWhy.NOTHING_THERE
        if (owner == currentPlayerIndex) return MorrisWhy.REMOVE_OWN_MAN
        return if (tapped in removals(from, to)) null else MorrisWhy.PROTECTED_MAN
    }
}

/**
 * Nine Men's Morris (also 6 men, flying on or off). Three phases: players alternate PLACING their
 * men on empty points, then slide them along the lines, and a player left with exactly three men
 * may FLY anywhere when flying is on. Three men in a row on a line is a mill: the player who just
 * closed one removes an opponent's man, preferring men outside a mill. A player left with two men,
 * or unable to move, loses; 100 movement plies without a mill or placement is a draw.
 *
 * Same session shape as [com.gamesuite.games.reversi.ReversiGame] (tally, Play Again, Undo, Hint,
 * host-authoritative networking).
 */
class NineMensMorrisGame : GameModule, ResumableGame, UndoableGame {
    override val gameId = "nine-mens-morris"
    override val displayName = "Nine Men's Morris"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<MorrisState?>(null)

    /** Men each player starts with for the NEXT board (6 or 9); a restored save or a host sync overrides it. */
    var men: Int = MorrisMen.NINE
        set(value) {
            field = if (value in MorrisMen.ALL) value else MorrisMen.NINE
            engine = MorrisEngine(field, flying)
        }

    /** Whether a player with three men may fly. */
    var flying: Boolean = true
        set(value) {
            field = value
            engine = MorrisEngine(men, field)
        }

    @Volatile
    private var engine: MorrisEngine = MorrisEngine(MorrisMen.NINE, true)

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
    private var nextStartingPlayerIndex = 0

    /** The roster seat that opens the first board; consumed by [init]. */
    var firstOpenerIndex: Int = 0

    private val history = SnapshotHistory()

    /** The turn the last hint suggested, shown until the next move, undo or new board. */
    val hintMove = mutableStateOf<MorrisMove?>(null)

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
        if (!isHost) sendToHost(MorrisNetMessage.RequestState)
        transport.onReconnected {
            if (isHost) broadcastState() else sendToHost(MorrisNetMessage.RequestState)
        }
        transport.onPlayerLeft { playerId -> handlePlayerLeft(playerId) }
        transport.onPlayerJoined { playerId -> handlePlayerJoined(playerId) }
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

    internal fun toPosition(s: MorrisState): MorrisPosition = MorrisPosition(
        IntArray(MorrisBoard.POINTS) { s.points[it] ?: -1 },
        intArrayOf(s.hand[0], s.hand[1]),
        s.currentPlayerIndex,
        s.quietPlies
    )

    private fun counts(points: List<Int?>) = listOf(points.count { it == 0 }, points.count { it == 1 })

    private fun stateFrom(
        rules: MorrisEngine,
        pos: MorrisPosition,
        players: List<MorrisPlayerState>,
        lastMove: MorrisMove?,
        lastMover: Int,
        ply: Int,
        lastAction: String,
        boardOver: Boolean,
        winnerPlayerId: String?,
        endReason: String?
    ): MorrisState {
        val points = pos.points.map { if (it < 0) null else it }
        return MorrisState(
            points = points,
            players = players,
            currentPlayerIndex = pos.side,
            hand = pos.hand.toList(),
            onBoard = counts(points),
            legalMoves = if (boardOver) emptyList() else rules.legalMoves(pos),
            lastMove = lastMove,
            lastMover = lastMover,
            ply = ply,
            quietPlies = pos.quiet,
            lastAction = lastAction,
            boardOver = boardOver,
            winnerPlayerId = winnerPlayerId,
            endReason = endReason,
            men = rules.men,
            flying = rules.flying
        )
    }

    /** A structurally consistent, legal-to-continue position, or null. */
    private fun validPosition(
        rules: MorrisEngine, points: List<Int?>, hand: List<Int>, cur: Int, quiet: Int, ply: Int, boardOver: Boolean
    ): MorrisPosition? {
        if (points.size != MorrisBoard.POINTS || points.any { it != null && it != 0 && it != 1 }) return null
        if (hand.size != 2 || hand.any { it < 0 || it > rules.men }) return null
        if (cur !in 0..1 || quiet < 0 || ply < 0) return null
        val c = counts(points)
        if (c[0] + hand[0] > rules.men || c[1] + hand[1] > rules.men) return null
        if (hand[cur] - hand[1 - cur] !in 0..1) return null
        val pos = MorrisPosition(IntArray(MorrisBoard.POINTS) { points[it] ?: -1 }, intArrayOf(hand[0], hand[1]), cur, quiet)
        if (!boardOver && rules.outcome(pos) != MorrisEngine.NONE) return null
        return pos
    }

    // --- Resume ------------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return morrisJson.encodeToString(
            MorrisSnapshot.serializer(),
            MorrisSnapshot(
                points = s.points,
                players = s.players.map { MorrisSnapshotPlayer(it.playerId, it.displayName, it.isBot) },
                currentPlayerIndex = s.currentPlayerIndex,
                hand = s.hand,
                quietPlies = s.quietPlies,
                ply = s.ply,
                lastMove = s.lastMove,
                lastMover = s.lastMover,
                lastAction = s.lastAction,
                boardOver = s.boardOver,
                winnerPlayerId = s.winnerPlayerId,
                endReason = s.endReason,
                sessionWins = sessionWins.value,
                sessionDraws = sessionDraws.value,
                nextStartingPlayerIndex = nextStartingPlayerIndex,
                difficulty = difficulty.name,
                men = s.men,
                flying = s.flying
            )
        )
    }

    private fun decodeSnapshot(text: String): Pair<MorrisSnapshot, MorrisPosition>? {
        return try {
            val snap = morrisJson.decodeFromString(MorrisSnapshot.serializer(), text)
            val roster = context.players
            if (snap.men !in MorrisMen.ALL) return null
            val rules = MorrisEngine(snap.men, snap.flying)
            val pos = validPosition(rules, snap.points, snap.hand, snap.currentPlayerIndex, snap.quietPlies, snap.ply, snap.boardOver)
                ?: return null
            val ok = snap.players.size == 2 && snap.players.size == roster.size &&
                snap.players.indices.all {
                    snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot
                } &&
                snap.nextStartingPlayerIndex in 0..1 &&
                snap.lastMover in -1..1 &&
                (snap.lastMove == null || (snap.lastMove.to in 0 until MorrisBoard.POINTS && snap.lastMove.from < MorrisBoard.POINTS &&
                    snap.lastMove.remove < MorrisBoard.POINTS)) &&
                snap.sessionDraws >= 0 &&
                snap.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } } &&
                (snap.winnerPlayerId == null || snap.players.any { it.playerId == snap.winnerPlayerId })
            if (ok) snap to pos else null
        } catch (_: Exception) {
            null
        }
    }

    private fun applySnapshot(snap: MorrisSnapshot, pos: MorrisPosition, restoreDifficulty: Boolean) {
        men = snap.men
        flying = snap.flying
        state.value = stateFrom(
            engine, pos, snap.players.map { MorrisPlayerState(it.playerId, it.displayName, it.isBot) },
            snap.lastMove, snap.lastMover, snap.ply, snap.lastAction, snap.boardOver, snap.winnerPlayerId, snap.endReason
        )
        sessionWins.value = snap.sessionWins
        sessionDraws.value = snap.sessionDraws
        nextStartingPlayerIndex = snap.nextStartingPlayerIndex
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

    // --- Undo --------------------------------------------------------------------------------

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value && !isNetworked

    override fun undo() {
        if (matchOver.value || isNetworked) return
        val previous = history.pop() ?: return
        val (snap, pos) = decodeSnapshot(previous) ?: return
        applySnapshot(snap, pos, restoreDifficulty = false)
    }

    // --- Hint --------------------------------------------------------------------------------

    private fun hintAllowed(s: MorrisState): Boolean {
        if (isNetworked || matchOver.value || s.boardOver || s.legalMoves.isEmpty()) return false
        return !s.players[s.currentPlayerIndex].isBot
    }

    fun hint(): MorrisMove? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val best = engine.hint(toPosition(s))
        hintMove.value = best
        return best
    }

    suspend fun requestHint(): MorrisMove? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val rules = engine
        val pos = toPosition(s)
        val best = withContext(Dispatchers.Default) { rules.hint(pos) }
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
        val players = context.players.map { MorrisPlayerState(it.playerId, it.displayName, it.isBot) }
        matchOver.value = false
        history.clear()
        hintMove.value = null
        val first = nextStartingPlayerIndex % players.size
        val rules = engine
        commit(
            stateFrom(
                rules, rules.initial(first), players, lastMove = null, lastMover = -1, ply = 0,
                lastAction = "Game started", boardOver = false, winnerPlayerId = null, endReason = null
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
            if (isHost) startMatch() else sendToHost(MorrisNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    /**
     * Plays one whole turn for the current player. A no-op unless [move] is exactly one of the
     * current legal turns (so an out-of-range point, a wrong removal or a move after the board
     * ended does nothing). A networked guest forwards it to the host instead of touching its engine.
     */
    fun play(move: MorrisMove) {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver) return
        if (isNetworked) {
            if (opponentLeft.value || s.currentPlayerIndex != context.localPlayerIndex) return
            if (move !in s.legalMoves) return
            if (!isHost) {
                sendToHost(MorrisNetMessage.PlayMove(move.from, move.to, move.remove))
                return
            }
        }
        applyMove(move)
    }

    private fun describe(name: String, m: MorrisMove): String = when {
        m.from < 0 && m.remove >= 0 -> "$name places a man and forms a mill"
        m.from < 0 -> "$name places a man"
        m.remove >= 0 -> "$name moves a man and forms a mill"
        else -> "$name moves a man"
    }

    private fun applyMove(move: MorrisMove) {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver || move !in s.legalMoves) return
        val mover = s.players[s.currentPlayerIndex]
        if (!mover.isBot && !isNetworked) captureSnapshot()?.let { history.push(it) }
        hintMove.value = null

        val rules = engine
        val next = rules.apply(toPosition(s), move)
        val out = rules.outcome(next)
        val over = out != MorrisEngine.NONE
        var winner: String? = null
        var reason: String? = null
        var text = describe(mover.displayName, move)
        if (over) {
            if (out == MorrisEngine.DRAW) {
                reason = "drawn"
                text = "Draw: no mill for too long"
                sessionDraws.value += 1
            } else {
                winner = s.players[out].playerId
                val loser = 1 - out
                reason = if (rules.total(next, loser) < 3) "reduced" else "blocked"
                text = "${s.players[out].displayName} wins"
                sessionWins.value = sessionWins.value + (winner to (sessionWins.value[winner] ?: 0) + 1)
            }
        }
        commit(
            stateFrom(
                rules, next, s.players, move, s.currentPlayerIndex, s.ply + 1,
                text, over, winner, reason
            )
        )
    }

    // ---- Networked internals ----

    private fun commit(newState: MorrisState) {
        state.value = newState
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
    }

    private fun toNetState(s: MorrisState) = MorrisNetState(
        points = s.points,
        players = s.players.map { MorrisNetPlayer(it.playerId, it.displayName) },
        currentPlayerIndex = s.currentPlayerIndex,
        hand = s.hand,
        quietPlies = s.quietPlies,
        ply = s.ply,
        men = s.men,
        flying = s.flying,
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
            MorrisNetMessage.serializer(), MorrisNetMessage.StateSync(stateVersion, toNetState(s))
        ).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = hostId, toPlayerId = guestId, payload = payload)
    }

    private fun sendToHost(message: MorrisNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = Json.encodeToString(MorrisNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            com.gamesuite.core.NetJson.decodeFromString(MorrisNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val guestId = context.players.getOrNull(1)?.playerId
        when (message) {
            is MorrisNetMessage.StateSync ->
                if (!isHost && fromPlayerId == hostId) applyRemoteState(message.version, message.state)
            is MorrisNetMessage.RequestState ->
                if (isHost && fromPlayerId == guestId) broadcastState()
            is MorrisNetMessage.PlayMove ->
                if (isHost && fromPlayerId == guestId) applyGuestMove(MorrisMove(message.from, message.to, message.remove))
            is MorrisNetMessage.RequestPlayAgain ->
                if (isHost && fromPlayerId == guestId && !opponentLeft.value && state.value?.boardOver == true && !matchOver.value) {
                    startMatch()
                }
        }
    }

    private fun applyGuestMove(move: MorrisMove) {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver || opponentLeft.value) return
        if (s.currentPlayerIndex != 1) return
        if (move !in s.legalMoves) return
        applyMove(move)
    }

    /** Guest: adopts a strictly newer, well-formed host state. */
    private fun applyRemoteState(version: Int, net: MorrisNetState) {
        if (version <= lastAppliedRemoteVersion) return
        val roster = context.players
        if (net.men !in MorrisMen.ALL) return
        if (net.players.size != 2 || roster.size != 2) return
        if (!net.players.indices.all { net.players[it].playerId == roster[it].playerId }) return
        if (net.winnerPlayerId != null && net.players.none { it.playerId == net.winnerPlayerId }) return
        if (net.lastMover !in -1..1) return
        val lm = net.lastMove
        if (lm != null && (lm.to !in 0 until MorrisBoard.POINTS || lm.from >= MorrisBoard.POINTS || lm.remove >= MorrisBoard.POINTS)) return
        val rules = MorrisEngine(net.men, net.flying)
        val pos = validPosition(rules, net.points, net.hand, net.currentPlayerIndex, net.quietPlies, net.ply, net.boardOver) ?: return
        if (!com.gamesuite.core.sessionTalliesValid(net.sessionWins, net.sessionDraws, context.players)) return
        lastAppliedRemoteVersion = version
        men = net.men
        flying = net.flying
        state.value = stateFrom(
            engine, pos, net.players.mapIndexed { i, p -> MorrisPlayerState(p.playerId, p.displayName, roster[i].isBot) },
            net.lastMove, net.lastMover, net.ply, net.lastAction, net.boardOver, net.winnerPlayerId, net.endReason
        )
        sessionWins.value = net.sessionWins
        sessionDraws.value = net.sessionDraws
        hintMove.value = null
    }

    private fun botToMove(s: MorrisState): Boolean {
        if (matchOver.value || s.boardOver) return false
        return s.players[s.currentPlayerIndex].isBot
    }

    /** Synchronous bot turn (tests); the screen uses [playBotTurnAsync]. */
    fun playBotTurn(rng: Random = Random.Default) {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val move = engine.chooseMove(toPosition(s), difficulty, rng, BOT_TIME_CAP_MS) ?: return
        play(move)
    }

    /** The search runs on [Dispatchers.Default]; the answer is dropped if the position changed meanwhile. */
    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val rules = engine
        val level = difficulty
        val pos = toPosition(s)
        val move = withContext(Dispatchers.Default) { rules.chooseMove(pos, level, Random.Default, BOT_TIME_CAP_MS) } ?: return
        if (state.value !== s || matchOver.value) return
        play(move)
    }

    private companion object {
        const val BOT_TIME_CAP_MS = 1_400L
    }
}
