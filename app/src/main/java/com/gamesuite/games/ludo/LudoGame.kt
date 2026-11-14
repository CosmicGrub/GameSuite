package com.gamesuite.games.ludo

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** What the last change to the table was; the screen words, animates and announces it. */
object LudoEvent {
    const val START = "start"
    const val ROLL = "roll"
    const val NO_MOVE = "no_move"
    const val THREE_SIXES = "three_sixes"
    const val MOVE = "move"
    const val ENTER = "enter"
    const val CAPTURE = "capture"
    const val HOME = "home"
    const val WIN = "win"
    val ALL = setOf(START, ROLL, NO_MOVE, THREE_SIXES, MOVE, ENTER, CAPTURE, HOME, WIN)
}

enum class LudoReject { NONE, BOARD_OVER, NOT_YOUR_TURN, ROLL_FIRST, ALREADY_ROLLED, NOT_YOUR_TOKEN, NEED_SIX, EXACT_ROLL, FINISHED }

data class LudoPlayerState(val playerId: String, val displayName: String, val isBot: Boolean, val color: Int)

/**
 * [tokens] is `seat * 4 + token` progress values (see [LudoBoard]). [dice] is the most recent roll
 * (0 before the first); [awaitingMove] is true while the seat on turn has rolled and must pick a
 * token. [rollSerial] / [moveSerial] only ever grow and key the screen's dice and slide animations.
 * [eventFrom]/[eventTo] are the progress values the last moved token went between.
 */
data class LudoState(
    val tokens: List<Int>,
    val players: List<LudoPlayerState>,
    val currentPlayerIndex: Int,
    val dice: Int = 0,
    val awaitingMove: Boolean = false,
    val sixesInRow: Int = 0,
    val needSix: Boolean = true,
    val exactFinish: Boolean = true,
    val event: String = LudoEvent.START,
    val eventSeat: Int? = null,
    val eventToken: Int? = null,
    val eventFrom: Int = LudoBoard.YARD,
    val eventTo: Int = LudoBoard.YARD,
    val captured: Int = 0,
    val rollSerial: Int = 0,
    val moveSerial: Int = 0,
    val boardOver: Boolean = false,
    val winnerPlayerId: String? = null
)

@Serializable
private data class LudoSnapshot(
    val tokens: List<Int>,
    val players: List<LudoSnapshotPlayer>,
    val currentPlayerIndex: Int,
    val dice: Int,
    val awaitingMove: Boolean,
    val sixesInRow: Int,
    val needSix: Boolean,
    val exactFinish: Boolean,
    val event: String,
    val eventSeat: Int? = null,
    val eventToken: Int? = null,
    val eventFrom: Int = -1,
    val eventTo: Int = -1,
    val captured: Int = 0,
    val rollSerial: Int = 0,
    val moveSerial: Int = 0,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    val sessionWins: Map<String, Int>,
    val nextStartingPlayerIndex: Int,
    val difficulty: String,
    val rollLog: List<Int> = emptyList()
)

@Serializable
private data class LudoSnapshotPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

private val ludoSnapshotJson = Json { ignoreUnknownKeys = true }

/**
 * Ludo for 2 to 4 seats with four tokens each. Roll the die, move one token by the roll; a six moves
 * again (three sixes in a row forfeit the turn). Options: [needSix] (a six is required to leave the
 * yard, default true) and [exactFinish] (the last step needs the exact roll, default true).
 *
 * THE DICE come only from the [random] passed to the constructor, one `nextInt(1, 7)` per roll, and
 * every roll is appended to [rollLog] and saved in the snapshot, so a seeded Random (or the log)
 * replays a game exactly. In a networked game only the host rolls; a guest asks.
 *
 * Undo (offline only) takes back the last human token move and everything the computer did after
 * it, returning to the same roll with the choice of token still open, so it can never be used to
 * re-roll. A networked game has no undo: a roll is shared the moment it is made.
 */
class LudoGame(private val random: Random = Random.Default) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "ludo"
    override val displayName = "Ludo"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<LudoState?>(null)

    /** Rules for the NEXT board (the Hub's options); a restored save or a host sync carries its own. */
    var needSix: Boolean = true
    var exactFinish: Boolean = true

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    private var stateVersion = com.gamesuite.core.SyncEpoch.base()
    private var lastAppliedRemoteVersion = 0

    val hostLeft = mutableStateOf(false)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val matchOver = mutableStateOf(false)

    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var firstOpenerIndex: Int = 0

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var nextStartingPlayerIndex = 0
    private val history = SnapshotHistory()

    private val rolls = ArrayList<Int>()

    /** Every die roll of the current board, in order (host-side; restored from a save). */
    val rollLog: List<Int> get() = rolls.toList()

    /** The token the last hint suggested. */
    val hintToken = mutableStateOf<Int?>(null)

    override fun init(context: GameContext) {
        require(LudoBoard.validSeatCount(context.players.size)) { "Ludo needs 2 to 4 seats" }
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        nextStartingPlayerIndex =
            if (!isNetworked && firstOpenerIndex in context.players.indices) firstOpenerIndex else 0
        history.clear()
        hintToken.value = null
        hostLeft.value = false
        stateVersion = com.gamesuite.core.SyncEpoch.base()
        lastAppliedRemoteVersion = 0
        rolls.clear()
        if (isNetworked) registerNetworkListeners()
    }

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { fromPlayerId, payload -> handleNetworkMessage(fromPlayerId, payload) }
        if (!isHost) sendToHost(LudoNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcastState() else sendToHost(LudoNetMessage.RequestState) }
        transport.onPlayerLeft { handlePlayerLeft(it) }
        transport.onPlayerJoined { handlePlayerJoined(it) }
    }

    private fun handlePlayerLeft(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) {
            if (seat == 0) hostLeft.value = true
            return
        }
        val s = state.value ?: return
        if (seat == 0 || s.players[seat].isBot) return
        commit(s.copy(players = s.players.mapIndexed { i, p -> if (i == seat) p.copy(isBot = true) else p }))
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) {
            if (seat == 0 && hostLeft.value) {
                hostLeft.value = false
                sendToHost(LudoNetMessage.RequestState)
            }
            return
        }
        val s = state.value ?: return
        if (context.players[seat].isBot || !s.players[seat].isBot) return
        commit(s.copy(players = s.players.mapIndexed { i, p -> if (i == seat) p.copy(isBot = false) else p }))
    }

    // --- Queries ---------------------------------------------------------------------------

    private fun positions(s: LudoState) = IntArray(s.tokens.size) { s.tokens[it] }
    private fun colors(s: LudoState) = s.players.map { it.color }

    fun isMyTurn(): Boolean = state.value?.let { isMyTurn(it) } ?: false

    fun isMyTurn(s: LudoState): Boolean {
        if (matchOver.value || s.boardOver) return false
        if (s.players[s.currentPlayerIndex].isBot) return false
        return if (isNetworked) !hostLeft.value && s.currentPlayerIndex == context.localPlayerIndex else true
    }

    /** The tokens of the seat on turn that the current roll lets move (empty before a roll). */
    fun legalTokens(): List<Int> {
        val s = state.value ?: return emptyList()
        return legalTokens(s)
    }

    private fun legalTokens(s: LudoState): List<Int> =
        if (s.boardOver || !s.awaitingMove) emptyList()
        else LudoRules.legalTokens(positions(s), s.currentPlayerIndex, s.dice, s.needSix, s.exactFinish)

    fun explainToken(token: Int): LudoReject {
        val s = state.value ?: return LudoReject.BOARD_OVER
        if (matchOver.value || s.boardOver) return LudoReject.BOARD_OVER
        if (!isMyTurn(s)) return LudoReject.NOT_YOUR_TURN
        if (!s.awaitingMove) return LudoReject.ROLL_FIRST
        if (token !in 0 until LudoBoard.TOKENS) return LudoReject.NOT_YOUR_TOKEN
        val p = s.tokens[s.currentPlayerIndex * LudoBoard.TOKENS + token]
        return when {
            p == LudoBoard.FINISHED -> LudoReject.FINISHED
            p == LudoBoard.YARD && s.needSix && s.dice != 6 -> LudoReject.NEED_SIX
            token !in legalTokens(s) -> LudoReject.EXACT_ROLL
            else -> LudoReject.NONE
        }
    }

    /** Why a roll request would do nothing right now, or NONE if it will roll. */
    fun explainRoll(): LudoReject {
        val s = state.value ?: return LudoReject.BOARD_OVER
        return when {
            matchOver.value || s.boardOver -> LudoReject.BOARD_OVER
            !isMyTurn(s) -> LudoReject.NOT_YOUR_TURN
            s.awaitingMove -> LudoReject.ALREADY_ROLLED
            else -> LudoReject.NONE
        }
    }

    // --- Resume ----------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return ludoSnapshotJson.encodeToString(
            LudoSnapshot.serializer(),
            LudoSnapshot(
                tokens = s.tokens,
                players = s.players.map { LudoSnapshotPlayer(it.playerId, it.displayName, it.isBot) },
                currentPlayerIndex = s.currentPlayerIndex, dice = s.dice, awaitingMove = s.awaitingMove,
                sixesInRow = s.sixesInRow, needSix = s.needSix, exactFinish = s.exactFinish,
                event = s.event, eventSeat = s.eventSeat, eventToken = s.eventToken,
                eventFrom = s.eventFrom, eventTo = s.eventTo, captured = s.captured,
                rollSerial = s.rollSerial, moveSerial = s.moveSerial,
                boardOver = s.boardOver, winnerPlayerId = s.winnerPlayerId,
                sessionWins = sessionWins.value, nextStartingPlayerIndex = nextStartingPlayerIndex,
                difficulty = difficulty.name, rollLog = rolls.toList()
            )
        )
    }

    /** The last move's progress values drive the screen's hop animation, so they must be real progress values
     *  (-1 = in the yard ... FINISHED) and never run backwards; an unbounded range once meant a multi-billion-step loop. */
    private fun eventRangeValid(from: Int, to: Int): Boolean =
        from in LudoBoard.YARD..LudoBoard.FINISHED && to in LudoBoard.YARD..LudoBoard.FINISHED && to >= from

    private fun decodeSnapshot(text: String): LudoSnapshot? {
        return try {
            val snap = ludoSnapshotJson.decodeFromString(LudoSnapshot.serializer(), text)
            val roster = context.players
            val n = roster.size
            val colors = LudoBoard.colorsFor(n)
            val positions = snap.tokens.toIntArray()
            val seatOk = { x: Int? -> x == null || x in 0 until n }
            val valid = snap.players.size == n &&
                snap.players.indices.all {
                    snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot
                } &&
                LudoRules.isConsistent(positions, colors) &&
                snap.currentPlayerIndex in 0 until n &&
                snap.nextStartingPlayerIndex in 0 until n &&
                snap.dice in 0..6 && snap.sixesInRow in 0..2 &&
                snap.event in LudoEvent.ALL &&
                seatOk(snap.eventSeat) && (snap.eventToken == null || snap.eventToken in 0 until LudoBoard.TOKENS) &&
                snap.captured >= 0 && snap.rollSerial >= 0 && snap.moveSerial >= 0 &&
                eventRangeValid(snap.eventFrom, snap.eventTo) &&
                snap.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } } &&
                (snap.winnerPlayerId == null || roster.any { it.playerId == snap.winnerPlayerId }) &&
                snap.rollLog.all { it in 1..6 } &&
                (!snap.awaitingMove || (!snap.boardOver && snap.dice in 1..6 &&
                    LudoRules.legalTokens(positions, snap.currentPlayerIndex, snap.dice, snap.needSix, snap.exactFinish).isNotEmpty())) &&
                (snap.boardOver == (snap.winnerPlayerId != null)) &&
                (!snap.boardOver || (0 until n).any { LudoRules.hasWon(positions, it) })
            if (valid) snap else null
        } catch (_: Exception) {
            null
        }
    }

    private fun applySnapshot(snap: LudoSnapshot, restoreDifficulty: Boolean) {
        val colors = LudoBoard.colorsFor(snap.players.size)
        state.value = LudoState(
            tokens = snap.tokens,
            players = snap.players.mapIndexed { i, p -> LudoPlayerState(p.playerId, p.displayName, p.isBot, colors[i]) },
            currentPlayerIndex = snap.currentPlayerIndex, dice = snap.dice, awaitingMove = snap.awaitingMove,
            sixesInRow = snap.sixesInRow, needSix = snap.needSix, exactFinish = snap.exactFinish,
            event = snap.event, eventSeat = snap.eventSeat, eventToken = snap.eventToken,
            eventFrom = snap.eventFrom, eventTo = snap.eventTo, captured = snap.captured,
            rollSerial = snap.rollSerial, moveSerial = snap.moveSerial,
            boardOver = snap.boardOver, winnerPlayerId = snap.winnerPlayerId
        )
        sessionWins.value = snap.sessionWins
        nextStartingPlayerIndex = snap.nextStartingPlayerIndex
        rolls.clear(); rolls.addAll(snap.rollLog)
        needSix = snap.needSix
        exactFinish = snap.exactFinish
        if (restoreDifficulty) {
            difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        }
        hintToken.value = null
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
        val snap = decodeSnapshot(snapshot) ?: return false
        matchOver.value = false
        applySnapshot(snap, restoreDifficulty = true)
        history.clear()
        return true
    }

    // --- Undo / hint -----------------------------------------------------------------------

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value && !isNetworked

    override fun undo() {
        if (matchOver.value || isNetworked) return
        val previous = history.pop() ?: return
        val snap = decodeSnapshot(previous) ?: return
        applySnapshot(snap, restoreDifficulty = false)
    }

    private fun hintAllowed(s: LudoState) =
        !isNetworked && !matchOver.value && !s.boardOver && s.awaitingMove && !s.players[s.currentPlayerIndex].isBot

    private fun searchHint(s: LudoState): Int? = LudoBot.choose(
        positions(s), colors(s), s.currentPlayerIndex, s.dice, s.needSix, s.exactFinish,
        CpuDifficulty.HARD, Random(s.moveSerial.toLong() * 7 + s.rollSerial)
    )

    fun hint(): Int? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        return searchHint(s).also { hintToken.value = it }
    }

    suspend fun requestHint(): Int? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val best = withContext(Dispatchers.Default) { searchHint(s) }
        if (state.value !== s || matchOver.value) return null
        hintToken.value = best
        return best
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    // --- Lifecycle -------------------------------------------------------------------------

    override fun startMatch() {
        if (isNetworked && !isHost) return
        val colors = LudoBoard.colorsFor(context.players.size)
        val players = context.players.mapIndexed { i, p -> LudoPlayerState(p.playerId, p.displayName, p.isBot, colors[i]) }
        matchOver.value = false
        history.clear()
        hintToken.value = null
        rolls.clear()
        val starter = nextStartingPlayerIndex % players.size
        commit(
            LudoState(
                tokens = List(players.size * LudoBoard.TOKENS) { LudoBoard.YARD },
                players = players, currentPlayerIndex = starter,
                needSix = needSix, exactFinish = exactFinish
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
        if (!com.gamesuite.core.LaunchPolicy.requestNewRound()) return
        if (matchOver.value) return
        if (isNetworked) {
            if (hostLeft.value || state.value?.boardOver != true) return
            if (isHost) startMatch() else sendToHost(LudoNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    // --- Rolling and moving ----------------------------------------------------------------

    /** The local player's roll. True when accepted (rolled here, or asked of the host). */
    fun roll(): Boolean {
        val s = state.value ?: return false
        if (explainRoll() != LudoReject.NONE) return false
        if (isNetworked && !isHost) {
            sendToHost(LudoNetMessage.RollRequest)
            return true
        }
        return doRoll()
    }

    private fun nextSeat(s: LudoState) = (s.currentPlayerIndex + 1) % s.players.size

    private fun doRoll(): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.boardOver || s.awaitingMove) return false
        val roll = random.nextInt(1, 7)
        rolls += roll
        hintToken.value = null
        val seat = s.currentPlayerIndex
        val sixes = if (roll == 6) s.sixesInRow + 1 else 0
        val base = s.copy(dice = roll, rollSerial = s.rollSerial + 1, eventSeat = seat, eventToken = null, captured = 0)
        val legal = LudoRules.legalTokens(positions(s), seat, roll, s.needSix, s.exactFinish)
        val next = when {
            sixes >= 3 -> base.copy(event = LudoEvent.THREE_SIXES, currentPlayerIndex = nextSeat(s), sixesInRow = 0)
            legal.isEmpty() -> base.copy(event = LudoEvent.NO_MOVE, currentPlayerIndex = nextSeat(s), sixesInRow = 0)
            else -> base.copy(event = LudoEvent.ROLL, awaitingMove = true, sixesInRow = sixes)
        }
        commit(next)
        return true
    }

    /** The local player's token choice. True when accepted. */
    fun moveToken(token: Int): Boolean {
        val s = state.value ?: return false
        if (explainToken(token) != LudoReject.NONE) return false
        if (isNetworked && !isHost) {
            sendToHost(LudoNetMessage.MoveRequest(token))
            return true
        }
        return doMove(token)
    }

    private fun doMove(token: Int): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.boardOver || !s.awaitingMove) return false
        if (token !in legalTokens(s)) return false
        val seat = s.currentPlayerIndex
        val mover = s.players[seat]
        if (!mover.isBot && !isNetworked) captureSnapshot()?.let { history.push(it) }
        hintToken.value = null

        val result = LudoRules.apply(positions(s), colors(s), seat, token, s.dice, s.needSix, s.exactFinish)
        val won = LudoRules.hasWon(result.positions, seat)
        val event = when {
            won -> LudoEvent.WIN
            result.captured.isNotEmpty() -> LudoEvent.CAPTURE
            result.finished -> LudoEvent.HOME
            result.leftYard -> LudoEvent.ENTER
            else -> LudoEvent.MOVE
        }
        val sameSeatAgain = s.dice == 6 && !won
        val next = s.copy(
            tokens = result.positions.toList(),
            currentPlayerIndex = if (sameSeatAgain) seat else if (won) seat else nextSeat(s),
            awaitingMove = false,
            sixesInRow = if (sameSeatAgain) s.sixesInRow else 0,
            event = event, eventSeat = seat, eventToken = token,
            eventFrom = result.from, eventTo = result.to, captured = result.captured.size,
            moveSerial = s.moveSerial + 1,
            boardOver = won, winnerPlayerId = if (won) mover.playerId else null
        )
        if (won) sessionWins.value = sessionWins.value + (mover.playerId to (sessionWins.value[mover.playerId] ?: 0) + 1)
        commit(next)
        return true
    }

    // --- Bot -------------------------------------------------------------------------------

    fun botToMove(): Boolean = state.value?.let { botToMove(it) } ?: false

    fun botToMove(s: LudoState): Boolean {
        if (matchOver.value || s.boardOver) return false
        if (isNetworked && !isHost) return false
        return s.players[s.currentPlayerIndex].isBot
    }

    private fun chooseBot(s: LudoState): Int? = LudoBot.choose(
        positions(s), colors(s), s.currentPlayerIndex, s.dice, s.needSix, s.exactFinish, difficulty, random
    )

    /** One bot action: roll when it has not rolled yet, otherwise move (the choice runs off the main thread). */
    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        if (!s.awaitingMove) {
            doRoll()
            return
        }
        val token = withContext(Dispatchers.Default) { chooseBot(s) } ?: return
        if (state.value !== s || matchOver.value) return
        doMove(token)
    }

    /** Synchronous [playBotTurnAsync] for tests and headless play. */
    fun playBotStep() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        if (!s.awaitingMove) { doRoll(); return }
        chooseBot(s)?.let { doMove(it) }
    }

    // --- Networked internals ---------------------------------------------------------------

    private fun commit(newState: LudoState) {
        state.value = newState
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
    }

    private fun toNetState(s: LudoState) = LudoNetState(
        tokens = s.tokens,
        players = s.players.map { LudoNetPlayer(it.playerId, it.displayName, it.isBot) },
        currentPlayerIndex = s.currentPlayerIndex, dice = s.dice, awaitingMove = s.awaitingMove,
        sixesInRow = s.sixesInRow, needSix = s.needSix, exactFinish = s.exactFinish,
        event = s.event, eventSeat = s.eventSeat, eventToken = s.eventToken,
        eventFrom = s.eventFrom, eventTo = s.eventTo, captured = s.captured,
        rollSerial = s.rollSerial, moveSerial = s.moveSerial,
        boardOver = s.boardOver, winnerPlayerId = s.winnerPlayerId, sessionWins = sessionWins.value
    )

    private fun syncPayload(): ByteArray? {
        val s = state.value ?: return null
        return Json.encodeToString(
            LudoNetMessage.serializer(), LudoNetMessage.StateSync(stateVersion, toNetState(s))
        ).toByteArray(Charsets.UTF_8)
    }

    private fun broadcastState() {
        val payload = syncPayload() ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        context.players.drop(1).filter { !it.isBot }.forEach {
            context.transport.send(fromPlayerId = hostId, toPlayerId = it.playerId, payload = payload)
        }
    }

    private fun sendStateTo(playerId: String) {
        val payload = syncPayload() ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        context.transport.send(fromPlayerId = hostId, toPlayerId = playerId, payload = payload)
    }

    private fun sendToHost(message: LudoNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = Json.encodeToString(LudoNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            com.gamesuite.core.NetJson.decodeFromString(LudoNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val senderSeat = context.players.indexOfFirst { it.playerId == fromPlayerId }
        val s = state.value
        when (message) {
            is LudoNetMessage.StateSync ->
                if (!isHost && fromPlayerId == hostId) applyRemoteState(message.version, message.state)
            is LudoNetMessage.RequestState ->
                if (isHost && senderSeat > 0) sendStateTo(fromPlayerId)
            is LudoNetMessage.RollRequest ->
                if (isHost && s != null && senderSeat > 0 && s.currentPlayerIndex == senderSeat &&
                    !s.players[senderSeat].isBot && !s.awaitingMove
                ) doRoll()
            is LudoNetMessage.MoveRequest ->
                if (isHost && s != null && senderSeat > 0 && s.currentPlayerIndex == senderSeat &&
                    !s.players[senderSeat].isBot
                ) doMove(message.token)
            is LudoNetMessage.RequestPlayAgain ->
                if (isHost && senderSeat > 0 && s?.boardOver == true && !matchOver.value) startMatch()
        }
    }

    private fun applyRemoteState(version: Int, net: LudoNetState) {
        if (version <= lastAppliedRemoteVersion) return
        val roster = context.players
        val n = roster.size
        val colors = LudoBoard.colorsFor(n)
        val positions = net.tokens.toIntArray()
        val valid = net.players.size == n &&
            net.players.indices.all { net.players[it].playerId == roster[it].playerId } &&
            LudoRules.isConsistent(positions, colors) &&
            net.currentPlayerIndex in 0 until n &&
            net.dice in 0..6 && net.sixesInRow in 0..2 &&
            net.event in LudoEvent.ALL &&
            (net.eventSeat == null || net.eventSeat in 0 until n) &&
            (net.eventToken == null || net.eventToken in 0 until LudoBoard.TOKENS) &&
            net.captured >= 0 && net.rollSerial >= 0 && net.moveSerial >= 0 &&
            eventRangeValid(net.eventFrom, net.eventTo) &&
            net.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } } &&
            (net.winnerPlayerId == null || roster.any { it.playerId == net.winnerPlayerId }) &&
            (net.boardOver == (net.winnerPlayerId != null)) &&
            (!net.boardOver || (0 until n).any { LudoRules.hasWon(positions, it) }) &&
            (!net.awaitingMove || (!net.boardOver && net.dice in 1..6 &&
                LudoRules.legalTokens(positions, net.currentPlayerIndex, net.dice, net.needSix, net.exactFinish).isNotEmpty()))
        if (!valid) return
        lastAppliedRemoteVersion = version
        state.value = LudoState(
            tokens = net.tokens,
            players = net.players.mapIndexed { i, p -> LudoPlayerState(p.playerId, p.displayName, p.isBot, colors[i]) },
            currentPlayerIndex = net.currentPlayerIndex, dice = net.dice, awaitingMove = net.awaitingMove,
            sixesInRow = net.sixesInRow, needSix = net.needSix, exactFinish = net.exactFinish,
            event = net.event, eventSeat = net.eventSeat, eventToken = net.eventToken,
            eventFrom = net.eventFrom, eventTo = net.eventTo, captured = net.captured,
            rollSerial = net.rollSerial, moveSerial = net.moveSerial,
            boardOver = net.boardOver, winnerPlayerId = net.winnerPlayerId
        )
        sessionWins.value = net.sessionWins
        hintToken.value = null
    }
}
