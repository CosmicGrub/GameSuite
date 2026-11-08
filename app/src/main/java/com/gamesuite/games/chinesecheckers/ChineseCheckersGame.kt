package com.gamesuite.games.chinesecheckers

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** Why a board ended: a player filled the goal, the move limit ran out, or nobody could move. */
object CCEnd {
    const val GOAL = "goal"
    const val LIMIT = "limit"
    const val STUCK = "stuck"
}

/** Why a tap did nothing, so the screen can say so instead of ignoring the player. */
enum class CCReject { NONE, BOARD_OVER, NOT_YOUR_TURN, OPPONENT_PIECE, BOXED_IN, UNREACHABLE, PICK_A_PIECE }

/** The Hub's "pieces" option ids: the full 10-piece game or a shorter 6-piece one. */
object CCPieceCounts {
    fun fromOptionId(id: String?): Int = if (id == "short") CCBoard.SHORT_PIECES else CCBoard.STANDARD_PIECES
}

data class CCPlayerState(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean,
    /** The star corner this seat starts in (its goal is the opposite corner). */
    val homeCorner: Int
)

/**
 * [cells] is the 121 holes (see [CCBoard]); each entry is the seat index of the piece on it or null.
 * [lastFrom]/[lastTo]/[lastMover]/[lastHops] describe the previous move for the UI's animation and
 * announcements (hops = 0 for a one-hole step). [skippedSeat] is a seat that was passed over because
 * it was completely boxed in. [previous] holds each seat's last move as from*121+to (-1 = none) so
 * the computer never just undoes its own last move.
 */
data class CCState(
    val cells: List<Int?>,
    val players: List<CCPlayerState>,
    val currentPlayerIndex: Int,
    val pieces: Int,
    val lastFrom: Int? = null,
    val lastTo: Int? = null,
    val lastMover: Int? = null,
    val lastHops: Int = 0,
    val skippedSeat: Int? = null,
    val endReason: String? = null,
    val turnCount: Int = 0,
    val boardOver: Boolean = false,
    val winnerPlayerId: String? = null,
    val previous: List<Int> = emptyList()
)

@Serializable
private data class CCSnapshot(
    val cells: List<Int?>,
    val players: List<CCSnapshotPlayer>,
    val currentPlayerIndex: Int,
    val pieces: Int,
    val lastFrom: Int? = null,
    val lastTo: Int? = null,
    val lastMover: Int? = null,
    val lastHops: Int = 0,
    val skippedSeat: Int? = null,
    val endReason: String? = null,
    val turnCount: Int = 0,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    val previous: List<Int> = emptyList(),
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int,
    val nextStartingPlayerIndex: Int,
    val difficulty: String
)

@Serializable
private data class CCSnapshotPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

private val ccSnapshotJson = Json { ignoreUnknownKeys = true }

/**
 * Chinese Checkers for 2 to 6 seats (2, 3, 4 and 6 are the classic tables; 5 plays as a six-corner
 * table with one corner empty). Each seat starts with its pieces in one corner of the star and races
 * them into the opposite corner by single steps or chains of hops over any piece; the first to fill
 * the goal wins. A seat with no move at all is passed over; after [TURNS_PER_SEAT] moves each the
 * board ends and whoever is closest to home wins (a lone leader, otherwise a draw), so a blocked
 * board can never run forever.
 *
 * Same session shape as the other board games (sessionWins/Draws, leaveSession, playAgain, resume,
 * undo, hint). Networked: the host runs the engine and every bot seat; a seat whose player leaves is
 * handed to the computer so the table keeps playing, and gets its owner back if they reconnect.
 *
 * [random] only feeds computer tie-breaks and Easy's slips; inject a seeded one for replays.
 */
class ChineseCheckersGame(private val random: Random = Random.Default) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "chinese-checkers"
    override val displayName = "Chinese Checkers"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 6
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<CCState?>(null)

    /** Pieces per seat for the NEXT board (10 or 6); a restored save or a host sync carries its own. */
    var piecesPerSeat: Int = CCBoard.STANDARD_PIECES
        set(value) {
            field = if (value == CCBoard.SHORT_PIECES) CCBoard.SHORT_PIECES else CCBoard.STANDARD_PIECES
        }

    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    private var stateVersion = com.gamesuite.core.SyncEpoch.base()
    private var lastAppliedRemoteVersion = 0

    /** Networked guest only: the host left, so nothing more will happen. Local state, never synced. */
    val hostLeft = mutableStateOf(false)

    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val sessionDraws = mutableStateOf(0)
    val matchOver = mutableStateOf(false)

    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    var firstOpenerIndex: Int = 0

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var nextStartingPlayerIndex = 0
    private val history = SnapshotHistory()

    /** The move the last [hint] suggested, shown until the next move, undo or new board. */
    val hintMove = mutableStateOf<CCMove?>(null)

    val seatCount: Int get() = if (this::context.isInitialized) context.players.size else 0

    override fun init(context: GameContext) {
        require(CCBoard.validSeatCount(context.players.size)) { "Chinese Checkers needs 2 to 6 seats" }
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        sessionDraws.value = 0
        nextStartingPlayerIndex =
            if (!isNetworked && firstOpenerIndex in context.players.indices) firstOpenerIndex else 0
        history.clear()
        hintMove.value = null
        hostLeft.value = false
        stateVersion = com.gamesuite.core.SyncEpoch.base()
        lastAppliedRemoteVersion = 0
        if (isNetworked) registerNetworkListeners()
    }

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { fromPlayerId, payload -> handleNetworkMessage(fromPlayerId, payload) }
        if (!isHost) sendToHost(CCNetMessage.RequestState)
        transport.onReconnected {
            if (isHost) broadcastState() else sendToHost(CCNetMessage.RequestState)
        }
        transport.onPlayerLeft { playerId -> handlePlayerLeft(playerId) }
        transport.onPlayerJoined { playerId -> handlePlayerJoined(playerId) }
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
        // The table keeps playing: the computer takes over the empty seat.
        commit(s.copy(players = s.players.mapIndexed { i, p -> if (i == seat) p.copy(isBot = true) else p }))
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked) return
        val seat = context.players.indexOfFirst { it.playerId == playerId }
        if (seat < 0) return
        if (!isHost) {
            if (seat == 0 && hostLeft.value) {
                hostLeft.value = false
                sendToHost(CCNetMessage.RequestState)
            }
            return
        }
        val s = state.value ?: return
        if (context.players[seat].isBot || !s.players[seat].isBot) return
        commit(s.copy(players = s.players.mapIndexed { i, p -> if (i == seat) p.copy(isBot = false) else p }))
    }

    // --- Queries ---------------------------------------------------------------------------

    private fun ownersOf(s: CCState): IntArray = IntArray(CCBoard.CELLS) { s.cells[it] ?: -1 }

    /** True when the local human may move now. */
    fun isMyTurn(): Boolean = state.value?.let { isMyTurn(it) } ?: false

    fun isMyTurn(s: CCState): Boolean {
        if (matchOver.value || s.boardOver) return false
        if (s.players[s.currentPlayerIndex].isBot) return false
        return if (isNetworked) !hostLeft.value && s.currentPlayerIndex == context.localPlayerIndex else true
    }

    /** The holes the piece on [cell] can move to; empty unless it belongs to the seat on turn. */
    fun destinationsFrom(cell: Int): Set<Int> {
        val s = state.value ?: return emptySet()
        if (s.boardOver || cell !in 0 until CCBoard.CELLS || s.cells[cell] != s.currentPlayerIndex) return emptySet()
        return CCRules.destinations(ownersOf(s), cell)
    }

    /** Which of the current seat's pieces can move at all (for highlighting). */
    fun movablePieces(): Set<Int> {
        val s = state.value ?: return emptySet()
        if (s.boardOver) return emptySet()
        val owners = ownersOf(s)
        return (0 until CCBoard.CELLS).filterTo(mutableSetOf()) {
            owners[it] == s.currentPlayerIndex && CCRules.destinations(owners, it).isNotEmpty()
        }
    }

    fun explainTap(selected: Int?, cell: Int): CCReject {
        val s = state.value ?: return CCReject.BOARD_OVER
        if (matchOver.value || s.boardOver) return CCReject.BOARD_OVER
        if (!isMyTurn(s)) return CCReject.NOT_YOUR_TURN
        val owner = s.cells.getOrNull(cell)
        return when {
            owner == s.currentPlayerIndex ->
                if (destinationsFrom(cell).isEmpty()) CCReject.BOXED_IN else CCReject.NONE
            owner != null -> CCReject.OPPONENT_PIECE
            selected == null -> CCReject.PICK_A_PIECE
            cell in destinationsFrom(selected) -> CCReject.NONE
            else -> CCReject.UNREACHABLE
        }
    }

    /** Seats' remaining distance to the goal tip, for the progress bars. Lower is further ahead. */
    fun remainingFor(seat: Int): Int {
        val s = state.value ?: return 0
        return CCRules.remaining(ownersOf(s), seat, s.players[seat].homeCorner)
    }

    // --- Resume ----------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return ccSnapshotJson.encodeToString(
            CCSnapshot.serializer(),
            CCSnapshot(
                cells = s.cells,
                players = s.players.map { CCSnapshotPlayer(it.playerId, it.displayName, it.isBot) },
                currentPlayerIndex = s.currentPlayerIndex,
                pieces = s.pieces,
                lastFrom = s.lastFrom, lastTo = s.lastTo, lastMover = s.lastMover, lastHops = s.lastHops,
                skippedSeat = s.skippedSeat, endReason = s.endReason, turnCount = s.turnCount,
                boardOver = s.boardOver, winnerPlayerId = s.winnerPlayerId, previous = s.previous,
                sessionWins = sessionWins.value, sessionDraws = sessionDraws.value,
                nextStartingPlayerIndex = nextStartingPlayerIndex, difficulty = difficulty.name
            )
        )
    }

    private fun decodeSnapshot(text: String): CCSnapshot? {
        return try {
            val snap = ccSnapshotJson.decodeFromString(CCSnapshot.serializer(), text)
            val roster = context.players
            val n = roster.size
            val cellOk = { c: Int? -> c == null || c in 0 until CCBoard.CELLS }
            val valid = snap.cells.size == CCBoard.CELLS &&
                snap.pieces in listOf(CCBoard.STANDARD_PIECES, CCBoard.SHORT_PIECES) &&
                snap.players.size == n &&
                snap.players.indices.all {
                    snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot
                } &&
                snap.cells.all { it == null || it in 0 until n } &&
                (0 until n).all { seat -> snap.cells.count { it == seat } == snap.pieces } &&
                snap.currentPlayerIndex in 0 until n &&
                snap.nextStartingPlayerIndex in 0 until n &&
                cellOk(snap.lastFrom) && cellOk(snap.lastTo) &&
                (snap.lastMover == null || snap.lastMover in 0 until n) &&
                (snap.skippedSeat == null || snap.skippedSeat in 0 until n) &&
                snap.lastHops >= 0 && snap.turnCount >= 0 &&
                (snap.previous.isEmpty() || (snap.previous.size == n && snap.previous.all { it in -1 until CCBoard.CELLS * CCBoard.CELLS })) &&
                snap.sessionDraws >= 0 &&
                snap.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } } &&
                (snap.winnerPlayerId == null || roster.any { it.playerId == snap.winnerPlayerId }) &&
                (snap.endReason == null || snap.endReason in listOf(CCEnd.GOAL, CCEnd.LIMIT, CCEnd.STUCK)) &&
                (snap.boardOver || CCRules.hasMove(IntArray(CCBoard.CELLS) { snap.cells[it] ?: -1 }, snap.currentPlayerIndex))
            if (valid) snap else null
        } catch (_: Exception) {
            null
        }
    }

    private fun applySnapshot(snap: CCSnapshot, restoreDifficulty: Boolean) {
        val homes = CCBoard.layout(snap.players.size)
        state.value = CCState(
            cells = snap.cells,
            players = snap.players.mapIndexed { i, p -> CCPlayerState(p.playerId, p.displayName, p.isBot, homes[i]) },
            currentPlayerIndex = snap.currentPlayerIndex,
            pieces = snap.pieces,
            lastFrom = snap.lastFrom, lastTo = snap.lastTo, lastMover = snap.lastMover, lastHops = snap.lastHops,
            skippedSeat = snap.skippedSeat, endReason = snap.endReason, turnCount = snap.turnCount,
            boardOver = snap.boardOver, winnerPlayerId = snap.winnerPlayerId, previous = snap.previous
        )
        sessionWins.value = snap.sessionWins
        sessionDraws.value = snap.sessionDraws
        nextStartingPlayerIndex = snap.nextStartingPlayerIndex
        if (restoreDifficulty) {
            difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        }
        piecesPerSeat = snap.pieces
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
        val snap = decodeSnapshot(snapshot) ?: return false
        matchOver.value = false
        applySnapshot(snap, restoreDifficulty = true)
        history.clear()
        return true
    }

    // --- Undo ------------------------------------------------------------------------------

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value && !isNetworked

    /** Takes back the last human move together with every computer reply that followed it. */
    override fun undo() {
        if (matchOver.value || isNetworked) return
        val previous = history.pop() ?: return
        val snap = decodeSnapshot(previous) ?: return
        applySnapshot(snap, restoreDifficulty = false)
    }

    // --- Hint ------------------------------------------------------------------------------

    private fun hintAllowed(s: CCState): Boolean =
        !isNetworked && !matchOver.value && !s.boardOver && !s.players[s.currentPlayerIndex].isBot

    private fun searchHint(s: CCState): CCMove? = CCBot.choose(
        ownersOf(s), s.currentPlayerIndex, s.players[s.currentPlayerIndex].homeCorner,
        CpuDifficulty.HARD, Random(s.turnCount.toLong() * 31 + s.currentPlayerIndex), reverseOf(s, s.currentPlayerIndex)
    )

    fun hint(): CCMove? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        return searchHint(s).also { hintMove.value = it }
    }

    suspend fun requestHint(): CCMove? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val best = withContext(Dispatchers.Default) { searchHint(s) }
        if (state.value !== s || matchOver.value) return null
        hintMove.value = best
        return best
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    // --- Lifecycle -------------------------------------------------------------------------

    override fun startMatch() {
        if (isNetworked && !isHost) return
        val homes = CCBoard.layout(context.players.size)
        val players = context.players.mapIndexed { i, p -> CCPlayerState(p.playerId, p.displayName, p.isBot, homes[i]) }
        matchOver.value = false
        history.clear()
        hintMove.value = null
        val owners = CCRules.initialOwners(homes, piecesPerSeat)
        val starter = nextStartingPlayerIndex % players.size
        commit(
            CCState(
                cells = owners.map { if (it < 0) null else it },
                players = players,
                currentPlayerIndex = starter,
                pieces = piecesPerSeat
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

    /** Ends the whole session, reporting boards won; only a strict leader is a winner. */
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
            if (hostLeft.value || state.value?.boardOver != true) return
            if (isHost) startMatch() else sendToHost(CCNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    // --- Moving ----------------------------------------------------------------------------

    /**
     * The local player's move request. Returns true when it was accepted (applied here, or sent to
     * the host for a guest). Rejected quietly otherwise; use [explainTap] for the reason.
     */
    fun requestMove(from: Int, to: Int): Boolean {
        val s = state.value ?: return false
        if (!isMyTurn(s)) return false
        if (to !in destinationsFrom(from)) return false
        if (isNetworked && !isHost) {
            sendToHost(CCNetMessage.MoveRequest(from, to))
            return true
        }
        return applyMove(from, to)
    }

    private fun reverseOf(s: CCState, seat: Int): CCMove? {
        val code = s.previous.getOrNull(seat) ?: return null
        if (code < 0) return null
        return CCMove(code / CCBoard.CELLS, code % CCBoard.CELLS)
    }

    private fun applyMove(from: Int, to: Int): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.boardOver) return false
        val seat = s.currentPlayerIndex
        if (from !in 0 until CCBoard.CELLS || to !in 0 until CCBoard.CELLS) return false
        if (s.cells[from] != seat) return false
        val owners = ownersOf(s)
        if (to !in CCRules.destinations(owners, from)) return false

        val mover = s.players[seat]
        if (!mover.isBot && !isNetworked) captureSnapshot()?.let { history.push(it) }
        hintMove.value = null

        val route = CCRules.path(owners, from, to)
        val adjacent = (0..5).any { CCBoard.neighbors[from * 6 + it] == to }
        val hops = if (adjacent) 0 else route.size - 1
        val after = CCRules.apply(owners, CCMove(from, to))
        val n = s.players.size
        val previous = (if (s.previous.size == n) s.previous else List(n) { -1 }).toMutableList()
        previous[seat] = from * CCBoard.CELLS + to
        val turns = s.turnCount + 1
        val base = s.copy(
            cells = after.map { if (it < 0) null else it },
            lastFrom = from, lastTo = to, lastMover = seat, lastHops = hops,
            skippedSeat = null, turnCount = turns, previous = previous
        )

        val next: CCState = when {
            CCRules.hasWon(after, seat, mover.homeCorner, s.pieces) ->
                base.copy(boardOver = true, winnerPlayerId = mover.playerId, endReason = CCEnd.GOAL)
            turns >= TURNS_PER_SEAT * n -> finishByProgress(base, after, CCEnd.LIMIT)
            else -> {
                var chosen = -1
                var skipped: Int? = null
                for (k in 1..n) {
                    val cand = (seat + k) % n
                    if (CCRules.hasMove(after, cand)) { chosen = cand; break }
                    if (skipped == null && cand != seat) skipped = cand
                }
                if (chosen < 0) finishByProgress(base, after, CCEnd.STUCK)
                else base.copy(currentPlayerIndex = chosen, skippedSeat = skipped)
            }
        }
        if (next.boardOver) {
            val w = next.winnerPlayerId
            if (w != null) sessionWins.value = sessionWins.value + (w to (sessionWins.value[w] ?: 0) + 1)
            else sessionDraws.value += 1
        }
        commit(next)
        return true
    }

    /** Ends the board on [reason]; the seat strictly closest to home wins, any tie is a draw. */
    private fun finishByProgress(base: CCState, owners: IntArray, reason: String): CCState {
        val rem = base.players.indices.map { CCRules.remaining(owners, it, base.players[it].homeCorner) }
        val best = rem.min()
        val leaders = rem.indices.filter { rem[it] == best }
        return base.copy(
            boardOver = true,
            endReason = reason,
            winnerPlayerId = if (leaders.size == 1) base.players[leaders[0]].playerId else null
        )
    }

    // --- Bot -------------------------------------------------------------------------------

    fun botToMove(): Boolean = state.value?.let { botToMove(it) } ?: false

    fun botToMove(s: CCState): Boolean {
        if (matchOver.value || s.boardOver) return false
        if (isNetworked && !isHost) return false
        return s.players[s.currentPlayerIndex].isBot
    }

    private fun chooseBot(s: CCState): CCMove? = CCBot.choose(
        ownersOf(s), s.currentPlayerIndex, s.players[s.currentPlayerIndex].homeCorner,
        difficulty, random, reverseOf(s, s.currentPlayerIndex)
    )

    fun playBotTurn() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val m = chooseBot(s) ?: return
        applyMove(m.from, m.to)
    }

    /** [playBotTurn] with the search on [Dispatchers.Default]; dropped if the position changed meanwhile. */
    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val m = withContext(Dispatchers.Default) { chooseBot(s) } ?: return
        if (state.value !== s || matchOver.value) return
        applyMove(m.from, m.to)
    }

    // --- Networked internals ---------------------------------------------------------------

    private fun commit(newState: CCState) {
        state.value = newState
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
    }

    private fun toNetState(s: CCState) = CCNetState(
        cells = s.cells,
        players = s.players.map { CCNetPlayer(it.playerId, it.displayName, it.isBot) },
        currentPlayerIndex = s.currentPlayerIndex,
        pieces = s.pieces,
        lastFrom = s.lastFrom, lastTo = s.lastTo, lastMover = s.lastMover, lastHops = s.lastHops,
        skippedSeat = s.skippedSeat, endReason = s.endReason, turnCount = s.turnCount,
        boardOver = s.boardOver, winnerPlayerId = s.winnerPlayerId, previous = s.previous,
        sessionWins = sessionWins.value, sessionDraws = sessionDraws.value
    )

    private fun broadcastState() {
        val s = state.value ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val payload = Json.encodeToString(
            CCNetMessage.serializer(), CCNetMessage.StateSync(stateVersion, toNetState(s))
        ).toByteArray(Charsets.UTF_8)
        context.players.drop(1).filter { !it.isBot }.forEach {
            context.transport.send(fromPlayerId = hostId, toPlayerId = it.playerId, payload = payload)
        }
    }

    private fun sendStateTo(playerId: String) {
        val s = state.value ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val payload = Json.encodeToString(
            CCNetMessage.serializer(), CCNetMessage.StateSync(stateVersion, toNetState(s))
        ).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = hostId, toPlayerId = playerId, payload = payload)
    }

    private fun sendToHost(message: CCNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = Json.encodeToString(CCNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            com.gamesuite.core.NetJson.decodeFromString(CCNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val senderSeat = context.players.indexOfFirst { it.playerId == fromPlayerId }
        when (message) {
            is CCNetMessage.StateSync ->
                if (!isHost && fromPlayerId == hostId) applyRemoteState(message.version, message.state)
            is CCNetMessage.RequestState ->
                if (isHost && senderSeat > 0) sendStateTo(fromPlayerId)
            is CCNetMessage.MoveRequest ->
                if (isHost && senderSeat > 0) applyGuestMove(senderSeat, message.from, message.to)
            is CCNetMessage.RequestPlayAgain ->
                if (isHost && senderSeat > 0 && state.value?.boardOver == true && !matchOver.value) startMatch()
        }
    }

    /** Host: a guest's move, accepted only from the seat that is on turn and is not computer-held. */
    private fun applyGuestMove(senderSeat: Int, from: Int, to: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver) return
        if (s.currentPlayerIndex != senderSeat || s.players[senderSeat].isBot) return
        applyMove(from, to)
    }

    private fun applyRemoteState(version: Int, net: CCNetState) {
        if (version <= lastAppliedRemoteVersion) return
        val roster = context.players
        val n = roster.size
        val valid = net.cells.size == CCBoard.CELLS &&
            net.pieces in listOf(CCBoard.STANDARD_PIECES, CCBoard.SHORT_PIECES) &&
            net.cells.all { it == null || it in 0 until n } &&
            net.players.size == n &&
            net.players.indices.all { net.players[it].playerId == roster[it].playerId } &&
            net.currentPlayerIndex in 0 until n &&
            (net.lastFrom == null || net.lastFrom in 0 until CCBoard.CELLS) &&
            (net.lastTo == null || net.lastTo in 0 until CCBoard.CELLS) &&
            (net.lastMover == null || net.lastMover in 0 until n) &&
            (net.skippedSeat == null || net.skippedSeat in 0 until n) &&
            net.lastHops >= 0 && net.turnCount >= 0 &&
            (net.winnerPlayerId == null || roster.any { it.playerId == net.winnerPlayerId }) &&
            (net.endReason == null || net.endReason in listOf(CCEnd.GOAL, CCEnd.LIMIT, CCEnd.STUCK)) &&
            (net.previous.isEmpty() || (net.previous.size == n && net.previous.all { it in -1 until CCBoard.CELLS * CCBoard.CELLS })) &&
            (0 until n).all { seat -> net.cells.count { it == seat } == net.pieces } &&
            (net.boardOver || CCRules.hasMove(IntArray(CCBoard.CELLS) { net.cells[it] ?: -1 }, net.currentPlayerIndex))
        if (!valid) return
        if (!com.gamesuite.core.sessionTalliesValid(net.sessionWins, net.sessionDraws, context.players)) return
        lastAppliedRemoteVersion = version
        val homes = CCBoard.layout(n)
        state.value = CCState(
            cells = net.cells,
            players = net.players.mapIndexed { i, p -> CCPlayerState(p.playerId, p.displayName, p.isBot, homes[i]) },
            currentPlayerIndex = net.currentPlayerIndex,
            pieces = net.pieces,
            lastFrom = net.lastFrom, lastTo = net.lastTo, lastMover = net.lastMover, lastHops = net.lastHops,
            skippedSeat = net.skippedSeat, endReason = net.endReason, turnCount = net.turnCount,
            boardOver = net.boardOver, winnerPlayerId = net.winnerPlayerId, previous = net.previous
        )
        sessionWins.value = net.sessionWins
        sessionDraws.value = net.sessionDraws
        hintMove.value = null
    }

    companion object {
        /** Each seat gets this many moves before the board ends on progress. */
        const val TURNS_PER_SEAT = 300
    }
}
