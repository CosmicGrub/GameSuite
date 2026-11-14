package com.gamesuite.games.shogi

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** One position in the repetition ledger: its hash, the side that moved into it (-1 = start) and whether that move gave check. */
@Serializable
data class ShogiRepEntry(val key: Long, val mover: Int, val check: Boolean)

@Serializable
private data class ShogiSnapshot(
    val variant: String,
    val cells: List<Int>,
    val hands: List<Int>,
    val sideToMove: Int,
    val senteSeat: Int,
    val players: List<ShogiSnapshotPlayer>,
    val lastFrom: Int,
    val lastTo: Int,
    val lastCaptured: Int,
    val ply: Int,
    val gameOver: Boolean,
    val winnerPlayerId: String?,
    val endReason: String,
    val rep: List<ShogiRepEntry>,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int,
    val nextStartingPlayerIndex: Int,
    val difficulty: String
)

@Serializable
private data class ShogiSnapshotPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

private val shogiJson = Json { ignoreUnknownKeys = true }

data class ShogiPlayerState(val playerId: String, val displayName: String, val isBot: Boolean)

enum class ShogiEnd { NONE, CHECKMATE, REPETITION, PERPETUAL_CHECK, IMPASSE, MOVE_LIMIT }

/** CPU strength per level: search depth cap, time cap and the Easy "human-feeling" slack in centipawns. */
data class ShogiSearchParams(val maxDepth: Int, val timeMs: Long, val noiseCp: Int)

fun shogiSearchParams(variant: ShogiVariant, difficulty: CpuDifficulty): ShogiSearchParams =
    if (variant == ShogiVariant.STANDARD) {
        when (difficulty) {
            CpuDifficulty.EASY -> ShogiSearchParams(1, 400, 140)
            CpuDifficulty.HARD -> ShogiSearchParams(5, 3000, 0)
            else -> ShogiSearchParams(3, 1500, 0)
        }
    } else {
        when (difficulty) {
            CpuDifficulty.EASY -> ShogiSearchParams(1, 400, 140)
            CpuDifficulty.HARD -> ShogiSearchParams(7, 2500, 0)
            else -> ShogiSearchParams(4, 1200, 0)
        }
    }

/**
 * Cells are `side * 16 + type` codes (see [ShogiPiece]), row-major, row 0 at the top of the Sente-view
 * board. [hands] is `side * 7 + (type - 1)` counts. [legalMoves] is the side to move's complete legal set
 * in [ShogiMove] encoding, so the screen highlights from it without recomputing anything.
 */
data class ShogiState(
    val variant: ShogiVariant,
    val cells: List<Int>,
    val hands: List<Int>,
    val players: List<ShogiPlayerState>,
    val senteSeat: Int,
    val sideToMove: Int,
    val lastFrom: Int = -1,
    val lastTo: Int = -1,
    val lastCaptured: Int = 0,
    val inCheck: Boolean = false,
    val legalMoves: List<Int> = emptyList(),
    val canDeclareImpasse: Boolean = false,
    val ply: Int = 0,
    val gameOver: Boolean = false,
    val winnerPlayerId: String? = null,
    val endReason: ShogiEnd = ShogiEnd.NONE
) {
    val n: Int get() = variant.n
    fun seatOfSide(side: Int): Int = if (side == 0) senteSeat else 1 - senteSeat
    fun sideOfSeat(seat: Int): Int = if (seat == senteSeat) 0 else 1
    val currentPlayerIndex: Int get() = seatOfSide(sideToMove)
    fun handCount(side: Int, type: Int): Int = hands[side * 7 + type - 1]
}

/**
 * Shogi (9x9) and Mini Shogi (5x5): one engine, two variants (see [ShogiRules]). Mirrors ReversiGame's
 * session shape: sessionWins/draws, leaveSession, playAgain, Resume snapshot, Undo history, Hint, async CPU
 * and the host-authoritative network handlers. The player who opens a board plays Sente (moves first, sits at
 * the bottom); the opener alternates every board.
 */
class ShogiGame(val variant: ShogiVariant = ShogiVariant.STANDARD) : GameModule, ResumableGame, UndoableGame {
    override val gameId = variant.gameId
    override val displayName = if (variant == ShogiVariant.STANDARD) "Shogi" else "Mini Shogi"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT, PlayMode.LOCAL_AD_HOC, PlayMode.ONLINE
    )

    private val rules = ShogiRules.of(variant)
    val state = mutableStateOf<ShogiState?>(null)
    val hintMove = mutableStateOf<Int?>(null)
    val opponentLeft = mutableStateOf(false)
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val sessionDraws = mutableStateOf(0)
    val matchOver = mutableStateOf(false)
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** Computer Arena only (null otherwise): per-seat CPU levels and pausing. */
    var arena: com.gamesuite.core.ArenaHook? = null
    var firstOpenerIndex: Int = 0

    /** Seeds the Easy CPU's choice among near-best moves; tests fix it. */
    var random: Random = Random.Default

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var nextStartingPlayerIndex = 0
    private var pos: ShogiPosition = rules.startPosition()
    private var rep = ArrayList<ShogiRepEntry>()
    private val history = SnapshotHistory()

    private var stateVersion = com.gamesuite.core.SyncEpoch.base()
    private var lastAppliedRemoteVersion = 0
    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0

    private val moveLimit = if (variant == ShogiVariant.STANDARD) 512 else 256

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

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    // ---- lifecycle ----

    override fun startMatch() {
        if (isNetworked && !isHost) return
        val players = context.players.map { ShogiPlayerState(it.playerId, it.displayName, it.isBot) }
        matchOver.value = false
        history.clear()
        hintMove.value = null
        pos = rules.startPosition()
        rep = arrayListOf(ShogiRepEntry(pos.keyHash(), -1, false))
        val sente = nextStartingPlayerIndex % players.size
        commit(buildState(players, sente, lastFrom = -1, lastTo = -1, lastCaptured = 0, ply = 0, mover = -1))
        nextStartingPlayerIndex = (nextStartingPlayerIndex + 1) % players.size
    }

    /** Test hook: starts a board from a hand-built [p] (Sente is seat [sente]); repetition history starts fresh. */
    internal fun loadPosition(p: ShogiPosition, sente: Int = 0) {
        val players = context.players.map { ShogiPlayerState(it.playerId, it.displayName, it.isBot) }
        matchOver.value = false
        history.clear()
        hintMove.value = null
        pos = p.copy()
        rep = arrayListOf(ShogiRepEntry(pos.keyHash(), -1, false))
        commit(buildState(players, sente, -1, -1, 0, 0, -1))
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
        val best = wins.values.maxOrNull() ?: 0
        val leaders = context.players.count { (wins[it.playerId] ?: 0) == best }
        endMatch(GameResult(scores = context.players.map {
            val w = wins[it.playerId] ?: 0
            PlayerScore(it.playerId, w, best > 0 && w == best && leaders == 1)
        }))
    }

    /** Computer Arena: deals the next board with [seat] playing Sente (first). */
    fun playAgainOpenedBy(seat: Int) {
        if (isNetworked) return
        nextStartingPlayerIndex = seat
        playAgain()
    }

    fun playAgain() {
        if (matchOver.value) return
        if (isNetworked) {
            if (opponentLeft.value || state.value?.gameOver != true) return
            if (isHost) startMatch() else sendToHost(ShogiNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    // ---- state building ----

    /**
     * Derives the full [ShogiState] for [pos] (after [mover] moved): legal moves, check, and any end
     * condition (checkmate, fourth repetition / perpetual check, move limit). Appends to [rep] first.
     */
    private fun buildState(
        players: List<ShogiPlayerState>, sente: Int, lastFrom: Int, lastTo: Int, lastCaptured: Int, ply: Int, mover: Int
    ): ShogiState {
        val side = pos.side
        val check = rules.inCheck(pos, side)
        val legal = rules.legalMoves(pos).toList()
        var end = ShogiEnd.NONE
        var winnerSide: Int? = null
        if (legal.isEmpty()) {
            end = ShogiEnd.CHECKMATE
            winnerSide = 1 - side
        } else if (mover >= 0) {
            val entry = ShogiRepEntry(pos.keyHash(), mover, check)
            rep.add(entry)
            val sameAt = rep.indices.filter { rep[it].key == entry.key }
            if (sameAt.size >= 4) {
                val cycle = rep.subList(sameAt[sameAt.size - 4] + 1, rep.size)
                val perpetual = (0..1).filter { s ->
                    val mine = cycle.filter { it.mover == s }
                    mine.isNotEmpty() && mine.all { it.check }
                }
                if (perpetual.size == 1) {
                    end = ShogiEnd.PERPETUAL_CHECK
                    winnerSide = 1 - perpetual[0] // the checking side loses
                } else {
                    end = ShogiEnd.REPETITION
                }
            } else if (ply >= moveLimit) {
                end = ShogiEnd.MOVE_LIMIT
            }
        }
        val senteSeat = sente
        fun seatOf(s: Int) = if (s == 0) senteSeat else 1 - senteSeat
        return ShogiState(
            variant = variant,
            cells = pos.board.toList(),
            hands = pos.hands.toList(),
            players = players,
            senteSeat = senteSeat,
            sideToMove = side,
            lastFrom = lastFrom,
            lastTo = lastTo,
            lastCaptured = lastCaptured,
            inCheck = check,
            legalMoves = if (end == ShogiEnd.NONE) legal else emptyList(),
            canDeclareImpasse = end == ShogiEnd.NONE && rules.canDeclareImpasse(pos, side),
            ply = ply,
            gameOver = end != ShogiEnd.NONE,
            winnerPlayerId = winnerSide?.let { players[seatOf(it)].playerId },
            endReason = end
        )
    }

    private fun tallyIfOver(s: ShogiState) {
        if (!s.gameOver) return
        if (s.winnerPlayerId != null) {
            sessionWins.value = sessionWins.value + (s.winnerPlayerId to (sessionWins.value[s.winnerPlayerId] ?: 0) + 1)
        } else {
            sessionDraws.value += 1
        }
    }

    private fun commit(s: ShogiState) {
        state.value = s
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
    }

    // ---- playing ----

    /** Plays [move] for the side to move if it is legal and the caller may move; a no-op otherwise. */
    fun playMove(move: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.gameOver) return
        if (isNetworked) {
            if (opponentLeft.value || s.currentPlayerIndex != context.localPlayerIndex) return
            if (move !in s.legalMoves) return
            if (!isHost) {
                sendToHost(ShogiNetMessage.PlayMove(move))
                return
            }
        }
        applyMove(move)
    }

    private fun applyMove(move: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.gameOver || move !in s.legalMoves) return
        val mover = s.players[s.currentPlayerIndex]
        if (!mover.isBot && !isNetworked) captureSnapshot()?.let { history.push(it) }
        hintMove.value = null
        val captured = rules.make(pos, move)
        val from = ShogiMove.from(move)
        val ns = buildState(
            s.players, s.senteSeat,
            lastFrom = if (from >= rules.cellCount) -1 else from,
            lastTo = ShogiMove.to(move),
            lastCaptured = captured,
            ply = s.ply + 1,
            mover = s.sideToMove
        )
        tallyIfOver(ns)
        commit(ns)
    }

    /** The side to move wins by the 27-point impasse declaration (standard board; only when the state offers it). */
    fun declareImpasse() {
        val s = state.value ?: return
        if (matchOver.value || s.gameOver || !s.canDeclareImpasse) return
        if (isNetworked) {
            if (opponentLeft.value || s.currentPlayerIndex != context.localPlayerIndex) return
            if (!isHost) {
                sendToHost(ShogiNetMessage.DeclareImpasse)
                return
            }
        }
        applyImpasse()
    }

    private fun applyImpasse() {
        val s = state.value ?: return
        if (matchOver.value || s.gameOver || !s.canDeclareImpasse) return
        val mover = s.players[s.currentPlayerIndex]
        if (!mover.isBot && !isNetworked) captureSnapshot()?.let { history.push(it) }
        hintMove.value = null
        val ns = s.copy(
            legalMoves = emptyList(), canDeclareImpasse = false, gameOver = true,
            winnerPlayerId = mover.playerId, endReason = ShogiEnd.IMPASSE
        )
        tallyIfOver(ns)
        commit(ns)
    }

    /**
     * Why tapping [from]->[to] (or a drop of [dropType] when [from] < 0) is not a legal move for the side to
     * move: one of "notyours", "cannot", "incheck", "exposes", "occupied", "nifu", "dead", "uchifuzume",
     * "nohand"; null if it is legal.
     */
    fun whyIllegal(from: Int, to: Int, dropType: Int = 0): String? {
        val s = state.value ?: return "cannot"
        if (s.gameOver) return "cannot"
        val p = pos.copy()
        if (from < 0) return rules.dropRefusal(p, dropType, to)
        val code = p.board[from]
        if (code == 0 || ShogiPiece.side(code) != p.side) return "notyours"
        val pseudo = rules.pseudoLegal(p)
        var reachable = false
        for (i in 0 until pseudo.size) if (ShogiMove.from(pseudo[i]) == from && ShogiMove.to(pseudo[i]) == to) reachable = true
        if (!reachable) return "cannot"
        return if (s.inCheck) "incheck" else "exposes"
    }

    // ---- CPU ----

    private fun botToMove(s: ShogiState): Boolean =
        !matchOver.value && !s.gameOver && s.players[s.currentPlayerIndex].isBot

    private fun searchBot(position: ShogiPosition, canDeclare: Boolean, level: CpuDifficulty): Int? {
        val params = shogiSearchParams(variant, level)
        if (canDeclare) return DECLARE
        return rules.chooseMove(position, params.maxDepth, params.timeMs, params.noiseCp, random)
    }

    fun playBotTurn() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val m = searchBot(pos, s.canDeclareImpasse, arena?.levelFor(s.currentPlayerIndex, difficulty) ?: difficulty) ?: return
        if (m == DECLARE) declareImpasse() else playMove(m)
    }

    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val snapshot = pos.copy()
        val level = arena?.levelFor(s.currentPlayerIndex, difficulty) ?: difficulty
        val m = withContext(Dispatchers.Default) { searchBot(snapshot, s.canDeclareImpasse, level) } ?: return
        if (state.value !== s || matchOver.value) return
        if (arena?.deferIfPaused { if (state.value === s && !matchOver.value) { if (m == DECLARE) declareImpasse() else playMove(m) } } == true) return
        if (m == DECLARE) declareImpasse() else playMove(m)
    }

    // ---- hint ----

    private fun hintAllowed(s: ShogiState): Boolean =
        !isNetworked && !matchOver.value && !s.gameOver && s.legalMoves.isNotEmpty() &&
            !s.players[s.currentPlayerIndex].isBot

    private fun searchHint(position: ShogiPosition): Int? {
        val depth = if (variant == ShogiVariant.STANDARD) 4 else 6
        return rules.chooseMove(position, depth, 1200)
    }

    fun hint(): Int? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val best = searchHint(pos.copy())
        hintMove.value = best
        return best
    }

    suspend fun requestHint(): Int? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val snapshot = pos.copy()
        val best = withContext(Dispatchers.Default) { searchHint(snapshot) }
        if (state.value !== s || matchOver.value) return null
        hintMove.value = best
        return best
    }

    // ---- resume / undo ----

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return shogiJson.encodeToString(
            ShogiSnapshot.serializer(),
            ShogiSnapshot(
                variant = variant.gameId, cells = s.cells, hands = s.hands, sideToMove = s.sideToMove,
                senteSeat = s.senteSeat,
                players = s.players.map { ShogiSnapshotPlayer(it.playerId, it.displayName, it.isBot) },
                lastFrom = s.lastFrom, lastTo = s.lastTo, lastCaptured = s.lastCaptured, ply = s.ply,
                gameOver = s.gameOver, winnerPlayerId = s.winnerPlayerId, endReason = s.endReason.name,
                rep = rep.toList(), sessionWins = sessionWins.value, sessionDraws = sessionDraws.value,
                nextStartingPlayerIndex = nextStartingPlayerIndex, difficulty = difficulty.name
            )
        )
    }

    private class Decoded(val snap: ShogiSnapshot, val position: ShogiPosition)

    /** Decodes and fully validates a snapshot against this variant and roster; null if unusable. */
    private fun decodeSnapshot(text: String): Decoded? = try {
        val snap = shogiJson.decodeFromString(ShogiSnapshot.serializer(), text)
        val roster = context.players
        val n = variant.n
        val position = positionFrom(n, snap.cells, snap.hands, snap.sideToMove)
        val ok = position != null && snap.variant == variant.gameId &&
            snap.players.size == 2 && roster.size == 2 &&
            snap.players.indices.all {
                snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot
            } &&
            snap.senteSeat in 0..1 && snap.nextStartingPlayerIndex in 0..1 &&
            lastMoveValid(snap.cells, snap.lastFrom, snap.lastTo, snap.lastCaptured) &&
            snap.sessionDraws >= 0 && snap.sessionWins.all { (id, w) -> w >= 0 && roster.any { it.playerId == id } } &&
            (snap.winnerPlayerId == null || roster.any { it.playerId == snap.winnerPlayerId }) &&
            ShogiEnd.values().any { it.name == snap.endReason } &&
            snap.ply >= 0 && snap.rep.size <= 5000 &&
            (snap.gameOver || rules.hasLegalMove(position!!))
        if (ok) Decoded(snap, position!!) else null
    } catch (_: Exception) {
        null
    }

    /**
     * The last-move fields drive the board highlight and the TalkBack announcement (`cells[lastTo]`), so a
     * snapshot or host state naming a square off the board, an empty destination, or a nonsense captured code
     * is rejected whole. lastTo -1 = no board move yet (new game, or an impasse ending); lastFrom -1 = a drop.
     */
    private fun lastMoveValid(cells: List<Int>, lastFrom: Int, lastTo: Int, lastCaptured: Int): Boolean {
        val squares = variant.n * variant.n
        if (cells.size != squares) return false
        if (lastFrom !in -1 until squares || lastTo !in -1 until squares) return false
        if (lastTo < 0) return lastFrom < 0 && lastCaptured == 0
        if (cells[lastTo] == 0) return false
        if (lastCaptured == 0) return true
        val t = ShogiPiece.type(lastCaptured)
        return lastCaptured > 0 && ShogiPiece.side(lastCaptured) in 0..1 && t in 1..15 && t != 13
    }

    /** Builds a [ShogiPosition] from raw arrays, or null if they are not a plausible shogi position. */
    private fun positionFrom(n: Int, cells: List<Int>, hands: List<Int>, side: Int): ShogiPosition? {
        if (cells.size != n * n || hands.size != 14 || side !in 0..1) return null
        if (hands.any { it < 0 || it > 40 }) return null
        val p = ShogiPosition(n)
        val kings = intArrayOf(0, 0)
        for (i in cells.indices) {
            val c = cells[i]
            if (c == 0) continue
            val sd = ShogiPiece.side(c)
            val t = ShogiPiece.type(c)
            if (c < 0 || sd !in 0..1 || t !in 1..15 || t == 13) return null
            if (variant == ShogiVariant.MINI && (t == ShogiPiece.KNIGHT || t == ShogiPiece.LANCE || t == 10 || t == 11)) return null
            if (t == ShogiPiece.KING) kings[sd]++
            p.board[i] = c
        }
        if (kings[0] != 1 || kings[1] != 1) return null
        for (i in 0 until 14) {
            val type = i % 7 + 1
            if (variant == ShogiVariant.MINI && (type == ShogiPiece.KNIGHT || type == ShogiPiece.LANCE) && hands[i] != 0) return null
            p.hands[i] = hands[i]
        }
        p.side = side
        p.recomputeKings()
        if (rules.inCheck(p, 1 - side)) return null // the side that just moved cannot have left itself in check
        return p
    }

    private fun applySnapshot(d: Decoded, restoreDifficulty: Boolean) {
        val snap = d.snap
        pos = d.position
        rep = ArrayList(snap.rep)
        if (rep.isEmpty()) rep.add(ShogiRepEntry(pos.keyHash(), -1, false))
        val players = snap.players.map { ShogiPlayerState(it.playerId, it.displayName, it.isBot) }
        val check = rules.inCheck(pos, pos.side)
        val over = snap.gameOver
        state.value = ShogiState(
            variant = variant, cells = pos.board.toList(), hands = pos.hands.toList(), players = players,
            senteSeat = snap.senteSeat, sideToMove = pos.side, lastFrom = snap.lastFrom, lastTo = snap.lastTo,
            lastCaptured = snap.lastCaptured, inCheck = check,
            legalMoves = if (over) emptyList() else rules.legalMoves(pos).toList(),
            canDeclareImpasse = !over && rules.canDeclareImpasse(pos, pos.side),
            ply = snap.ply, gameOver = over, winnerPlayerId = snap.winnerPlayerId,
            endReason = ShogiEnd.values().first { it.name == snap.endReason }
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
        val d = decodeSnapshot(snapshot) ?: return false
        matchOver.value = false
        applySnapshot(d, restoreDifficulty = true)
        history.clear()
        return true
    }

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value && !isNetworked

    override fun undo() {
        if (matchOver.value || isNetworked) return
        val previous = history.pop() ?: return
        val d = decodeSnapshot(previous) ?: return
        applySnapshot(d, restoreDifficulty = false)
    }

    // ---- networking ----

    private fun registerNetworkListeners() {
        val transport = context.transport
        transport.onMessageReceived { from, payload -> handleNetworkMessage(from, payload) }
        if (!isHost) sendToHost(ShogiNetMessage.RequestState)
        transport.onReconnected { if (isHost) broadcastState() else sendToHost(ShogiNetMessage.RequestState) }
        transport.onPlayerLeft { id -> handlePlayerLeft(id) }
        transport.onPlayerJoined { id -> handlePlayerJoined(id) }
    }

    private fun otherSeatId(): String? =
        context.players.getOrNull(1 - context.localPlayerIndex.coerceIn(0, 1))?.playerId

    private fun handlePlayerLeft(id: String) {
        if (!isNetworked || id != otherSeatId()) return
        opponentLeft.value = true
        val s = state.value ?: return
        commit(s)
    }

    private fun handlePlayerJoined(id: String) {
        if (!isNetworked || !opponentLeft.value || id != otherSeatId()) return
        opponentLeft.value = false
        val s = state.value ?: return
        if (isHost) commit(s)
    }

    private fun toNetState(s: ShogiState) = ShogiNetState(
        n = variant.n, cells = s.cells, hands = s.hands, sideToMove = s.sideToMove, senteSeat = s.senteSeat,
        players = s.players.map { ShogiNetPlayer(it.playerId, it.displayName) },
        lastFrom = s.lastFrom, lastTo = s.lastTo, lastCaptured = s.lastCaptured, ply = s.ply,
        gameOver = s.gameOver, winnerPlayerId = s.winnerPlayerId, endReason = s.endReason.name,
        sessionWins = sessionWins.value, sessionDraws = sessionDraws.value
    )

    private fun broadcastState() {
        val s = state.value ?: return
        val guestId = context.players.getOrNull(1)?.playerId ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val payload = Json.encodeToString(
            ShogiNetMessage.serializer(), ShogiNetMessage.StateSync(stateVersion, toNetState(s))
        ).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = hostId, toPlayerId = guestId, payload = payload)
    }

    private fun sendToHost(message: ShogiNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = Json.encodeToString(ShogiNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun handleNetworkMessage(from: String, payload: ByteArray) {
        val message = try {
            com.gamesuite.core.NetJson.decodeFromString(ShogiNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val guestId = context.players.getOrNull(1)?.playerId
        when (message) {
            is ShogiNetMessage.StateSync ->
                if (!isHost && from == hostId) applyRemoteState(message.version, message.state)
            is ShogiNetMessage.RequestState ->
                if (isHost && from == guestId) broadcastState()
            is ShogiNetMessage.PlayMove -> if (isHost && from == guestId) {
                val s = state.value
                if (s != null && !matchOver.value && !s.gameOver && !opponentLeft.value &&
                    s.currentPlayerIndex == 1 && message.move in s.legalMoves
                ) applyMove(message.move)
            }
            is ShogiNetMessage.DeclareImpasse -> if (isHost && from == guestId) {
                val s = state.value
                if (s != null && s.currentPlayerIndex == 1 && !opponentLeft.value) applyImpasse()
            }
            is ShogiNetMessage.RequestPlayAgain ->
                if (isHost && from == guestId && !opponentLeft.value && state.value?.gameOver == true && !matchOver.value) startMatch()
        }
    }

    private fun applyRemoteState(version: Int, net: ShogiNetState) {
        if (version <= lastAppliedRemoteVersion) return
        val roster = context.players
        if (net.n != variant.n || net.players.size != 2 || roster.size != 2) return
        if (!net.players.indices.all { net.players[it].playerId == roster[it].playerId }) return
        if (net.senteSeat !in 0..1) return
        val end = ShogiEnd.values().firstOrNull { it.name == net.endReason } ?: return
        if (net.winnerPlayerId != null && roster.none { it.playerId == net.winnerPlayerId }) return
        val p = positionFrom(net.n, net.cells, net.hands, net.sideToMove) ?: return
        if (!lastMoveValid(net.cells, net.lastFrom, net.lastTo, net.lastCaptured)) return
        if (net.sessionDraws < 0 || net.sessionWins.any { (id, w) -> w < 0 || roster.none { it.playerId == id } }) return
        lastAppliedRemoteVersion = version
        pos = p
        val players = net.players.mapIndexed { i, pl -> ShogiPlayerState(pl.playerId, pl.displayName, roster[i].isBot) }
        val over = net.gameOver
        state.value = ShogiState(
            variant = variant, cells = p.board.toList(), hands = p.hands.toList(), players = players,
            senteSeat = net.senteSeat, sideToMove = p.side, lastFrom = net.lastFrom, lastTo = net.lastTo,
            lastCaptured = net.lastCaptured, inCheck = rules.inCheck(p, p.side),
            legalMoves = if (over) emptyList() else rules.legalMoves(p).toList(),
            canDeclareImpasse = !over && rules.canDeclareImpasse(p, p.side),
            ply = net.ply, gameOver = over, winnerPlayerId = net.winnerPlayerId, endReason = end
        )
        sessionWins.value = net.sessionWins
        sessionDraws.value = net.sessionDraws
        hintMove.value = null
    }

    private companion object {
        const val DECLARE = -1
    }
}
