package com.gamesuite.games.mancala

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.MultiplayerTransport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything needed to put a Mancala session back exactly as it was: the board, whose turn it is
 * (an owed extra turn is simply "same player still on move"), the last sow's path/capture (so the
 * status line and extra-turn prompt read the same), the session tally and the difficulty. One
 * shape serves both Resume (disk) and Undo (memory). Mancala has no hidden information.
 */
@Serializable
private data class MancalaSnapshot(
    val pits: List<Int>,
    val currentPlayerIndex: Int,
    val lastAction: String,
    val roundOver: Boolean,
    val winnerPlayerId: String?,
    val lastSowPath: List<Int>,
    val lastCapture: MancalaSnapshotCapture?,
    val players: List<MancalaSnapshotPlayer>,
    val scoreP1: Int,
    val scoreP2: Int,
    val draws: Int,
    val difficulty: String,
    /** Seat that opened the session's first board; defaulted so saves written before it existed still decode. */
    val openingSeatIndex: Int = 0,
    /** Variant settings; defaulted so saves written before the Hub options existed decode as the standard game. */
    val pitsPerSide: Int = 6,
    val stonesPerPit: Int = 4,
    val captureRule: String = "opposite"
)

@Serializable
private data class MancalaSnapshotCapture(val landingPit: Int, val oppositePit: Int, val totalSwept: Int)

@Serializable
private data class MancalaSnapshotPlayer(val playerId: String, val isBot: Boolean)

private val mancalaSnapshotJson = Json { ignoreUnknownKeys = true }

/**
 * Board layout for the standard game (14 pits, index 0-13); with n pits per side ([MancalaRules])
 * the layout is the same shape with 2n+2 entries:
 *   0-5   = player 0's pits (left to right from player 0's perspective)
 *   6     = player 0's store
 *   7-12  = player 1's pits
 *   13    = player 1's store
 * Standard Kalah rules: sow counter-clockwise, land in your own empty pit
 * with the last stone -> capture that pit + the opposite pit into your
 * store; land in any store you own -> extra turn; game ends when one
 * side's pits are all empty -> remaining stones on the other side go to
 * that side's store.
 */
data class MancalaState(
    val pits: List<Int>,
    val currentPlayerIndex: Int,
    val lastAction: String,
    /**
     * True once the current game has ended (one side's pits are all empty)
     * -- distinct from [MancalaGame.matchOver], which only flips once the
     * whole session ends (the player leaves via the round-over panel's
     * "Back to Menu" rather than "Play Again").
     */
    val roundOver: Boolean = false,
    val winnerPlayerId: String? = null,
    /**
     * Pure instrumentation of the sow that produced this state -- animation/physics
     * pitch, Mancala section: MancalaScreen's seed-hop cascade needs to know which
     * pits the stones actually visited, in order, to animate a seed hopping
     * pit-to-pit rather than just snapping to the final board. Element 0 is the pit
     * the stones were picked up from ([sow]'s own `pitIndex`, captured before its
     * loop starts); every element after that is a cursor position appended inside
     * that same loop, in the exact order the loop already visits them (opponent-store
     * hops excluded, since the loop itself never places a stone there) -- this never
     * changes what sow() actually does, only records it. A hop is (path[i], path[i+1])
     * for each consecutive pair, so path always has at least 2 entries after any real
     * sow (sow() rejects an empty starting pit before the loop can even run).
     * Empty for the initial deal (startMatch()) since no sow has happened yet.
     */
    val lastSowPath: List<Int> = emptyList(),
    /**
     * Non-null only when the sow that produced this state ended in a capture (see
     * [sow]'s capture branch) -- lets MancalaScreen replay a "sweep into store" arc
     * for the swept seeds without re-deriving the capture rule itself. Reset to null
     * on every sow that doesn't capture, so a later non-capturing move never replays
     * a stale flourish.
     */
    val lastCapture: MancalaCapture? = null,
    /**
     * True when this state was put back by Resume or Undo rather than produced by a sow. The
     * screen uses it to show the final position straight away instead of replaying the seed-hop
     * cascade (and its haptics/sounds) for a sow that is not happening now.
     */
    val restored: Boolean = false
)

/**
 * Structured capture info for [MancalaState.lastCapture] -- see [MancalaGame.sow]'s
 * capture branch, the only place one of these is ever created. [landingPit] and
 * [oppositePit] are both zeroed by that same branch (into [totalSwept], which is
 * their combined stone count before the sweep) and swept into the mover's store.
 */
data class MancalaCapture(val landingPit: Int, val oppositePit: Int, val totalSwept: Int)

/** Capture variant: the classic opposite-pit capture, or none at all (only the extra-turn rule). */
enum class MancalaCaptureRule(val key: String) {
    OPPOSITE("opposite"),
    NONE("none");

    companion object {
        /** Null for an unknown key, so a corrupt save or message is rejected rather than guessed at. */
        fun fromKey(key: String?): MancalaCaptureRule? = values().firstOrNull { it.key == key }
    }
}

/**
 * The board shape and rules of one Mancala session. Board indexes: seat 0's pits are
 * `0 until pitsPerSide`, then its store at `pitsPerSide`, seat 1's pits next and seat 1's store
 * last (`2 * pitsPerSide + 1`); the pit opposite pit p is `2 * pitsPerSide - p`. The defaults are
 * the standard game (6 pits, 4 stones, opposite-pit capture).
 */
data class MancalaRules(
    val pitsPerSide: Int = 6,
    val stonesPerPit: Int = 4,
    val capture: MancalaCaptureRule = MancalaCaptureRule.OPPOSITE
) {
    val boardSize: Int get() = 2 * pitsPerSide + 2
    val store0: Int get() = pitsPerSide
    val store1: Int get() = 2 * pitsPerSide + 1

    /** The stones on the whole board, constant for the life of a game. */
    val totalStones: Int get() = 2 * pitsPerSide * stonesPerPit

    /** Only the combinations the Hub offers (and the screen can lay out) are playable. */
    val isSupported: Boolean
        get() = pitsPerSide in SUPPORTED_PITS && stonesPerPit in SUPPORTED_STONES

    /** Longest sow path a legal board can hold: the picked-up pit plus at most every stone. */
    val maxSowPathLength: Int get() = maxOf(60, totalStones + 1)

    fun pitsOf(playerIndex: Int): IntRange =
        if (playerIndex == 0) 0 until pitsPerSide else (pitsPerSide + 1)..(2 * pitsPerSide)

    fun storeOf(playerIndex: Int): Int = if (playerIndex == 0) store0 else store1

    fun oppositeOf(pit: Int): Int = 2 * pitsPerSide - pit

    /** The opening board: [stonesPerPit] in every pit, empty stores. */
    fun initialPits(): List<Int> = List(boardSize) { if (it == store0 || it == store1) 0 else stonesPerPit }

    internal fun isTerminal(pits: IntArray): Boolean =
        pitsOf(0).all { pits[it] == 0 } || pitsOf(1).all { pits[it] == 0 }

    /**
     * A pure copy of the sow rules (same capture / extra-turn logic as [MancalaGame]'s real sow, no
     * state touched), used by the bot's search, the hint and the capture preview. [MancalaSim.landingCursor]
     * is the pit the last stone landed in.
     */
    internal fun simulate(pits: IntArray, playerIndex: Int, pitIndex: Int): MancalaSim {
        val size = boardSize
        val ownPits = pitsOf(playerIndex)
        val ownStore = storeOf(playerIndex)
        val opponentStore = storeOf(1 - playerIndex)

        val p = pits.copyOf()
        var stones = p[pitIndex]
        p[pitIndex] = 0
        var cursor = pitIndex
        while (stones > 0) {
            cursor = (cursor + 1) % size
            if (cursor == opponentStore) continue
            p[cursor]++
            stones--
        }

        var extraTurn = false
        if (capture == MancalaCaptureRule.OPPOSITE && cursor in ownPits && p[cursor] == 1) {
            val oppositeIndex = oppositeOf(cursor)
            if (p[oppositeIndex] > 0) {
                val captured = p[oppositeIndex] + p[cursor]
                p[oppositeIndex] = 0
                p[cursor] = 0
                p[ownStore] += captured
            }
        } else if (cursor == ownStore) {
            extraTurn = true
        }
        return MancalaSim(p, extraTurn, cursor)
    }

    companion object {
        val SUPPORTED_PITS: List<Int> = listOf(5, 6)
        val SUPPORTED_STONES: IntRange = 3..6

        /** Reads the Hub's `pits` / `stones` / `capture` extras; anything missing or unsupported falls back to the standard game. */
        fun fromExtras(extras: Map<String, String>): MancalaRules {
            val pits = extras["pits"]?.toIntOrNull()?.takeIf { it in SUPPORTED_PITS } ?: 6
            val stones = extras["stones"]?.toIntOrNull()?.takeIf { it in SUPPORTED_STONES } ?: 4
            val capture = MancalaCaptureRule.fromKey(extras["capture"]) ?: MancalaCaptureRule.OPPOSITE
            return MancalaRules(pits, stones, capture)
        }
    }
}

/** Result of a hypothetical sow; [landingCursor] is where the last stone landed. */
internal class MancalaSim(val pits: IntArray, val extraTurn: Boolean, val landingCursor: Int)

/** Hard cap on search depth (plies) and on visited positions, so a move takes well under a second on any board. */
internal const val MANCALA_SEARCH_DEPTH = 8
internal const val MANCALA_NODE_BUDGET = 1_500_000

/**
 * Depth-limited minimax with alpha-beta pruning over [MancalaRules.simulate], deepened one ply at a
 * time until [MANCALA_SEARCH_DEPTH] or until the node budget is spent; the answer is always that
 * of the deepest COMPLETED iteration. Pure: it reads plain int arrays, never any game state.
 */
internal class MancalaSearch(private val rules: MancalaRules, private val nodeBudget: Int = MANCALA_NODE_BUDGET) {
    private var nodes = 0
    private var aborted = false

    private fun finalScore(pits: IntArray, maximizer: Int): Int {
        val p = pits.copyOf()
        val side0 = rules.pitsOf(0)
        val side1 = rules.pitsOf(1)
        if (side0.all { p[it] == 0 }) { for (i in side1) { p[rules.store1] += p[i]; p[i] = 0 } }
        if (side1.all { p[it] == 0 }) { for (i in side0) { p[rules.store0] += p[i]; p[i] = 0 } }
        val myStore = if (maximizer == 0) p[rules.store0] else p[rules.store1]
        val oppStore = if (maximizer == 0) p[rules.store1] else p[rules.store0]
        return myStore - oppStore
    }

    private fun minimax(pits: IntArray, playerIndex: Int, depth: Int, maximizer: Int, alpha: Int, beta: Int): Int {
        if (aborted) return 0
        nodes++
        if (nodes > nodeBudget) {
            aborted = true
            return 0
        }
        if (depth == 0 || rules.isTerminal(pits)) return finalScore(pits, maximizer)

        var a = alpha
        var b = beta
        var any = false
        val maximizing = playerIndex == maximizer
        var best = if (maximizing) Int.MIN_VALUE else Int.MAX_VALUE
        for (m in rules.pitsOf(playerIndex)) {
            if (pits[m] == 0) continue
            any = true
            val sim = rules.simulate(pits, playerIndex, m)
            val nextPlayer = if (sim.extraTurn) playerIndex else 1 - playerIndex
            val score = minimax(sim.pits, nextPlayer, depth - 1, maximizer, a, b)
            if (maximizing) {
                best = maxOf(best, score)
                a = maxOf(a, best)
            } else {
                best = minOf(best, score)
                b = minOf(b, best)
            }
            if (a >= b) break
        }
        return if (any) best else finalScore(pits, maximizer)
    }

    private fun searchRoot(pits: IntArray, playerIndex: Int, depth: Int): Int? {
        var bestMove: Int? = null
        var bestScore = Int.MIN_VALUE
        for (m in rules.pitsOf(playerIndex)) {
            if (pits[m] == 0) continue
            val sim = rules.simulate(pits, playerIndex, m)
            val nextPlayer = if (sim.extraTurn) playerIndex else 1 - playerIndex
            val score = minimax(sim.pits, nextPlayer, depth - 1, playerIndex, Int.MIN_VALUE, Int.MAX_VALUE)
            if (aborted) return null
            if (score > bestScore) {
                bestScore = score
                bestMove = m
            }
        }
        return bestMove
    }

    /** The best legal pit for [playerIndex], or null when there is none. */
    fun bestMove(pits: IntArray, playerIndex: Int, maxDepth: Int = MANCALA_SEARCH_DEPTH): Int? {
        var best: Int? = rules.pitsOf(playerIndex).firstOrNull { pits[it] > 0 } ?: return null
        for (depth in 1..maxDepth) {
            val found = searchRoot(pits, playerIndex, depth)
            if (aborted) break
            if (found != null) best = found
        }
        return best
    }
}

/**
 * One bot move or hint, split so the (possibly slow) search can run off the main thread: take the
 * plan on the main thread ([MancalaGame.planBotTurn] / [MancalaGame.planHint]), call [chooseMove]
 * anywhere (it reads only the copied position), then hand the answer back to
 * [MancalaGame.applyBotMove] / [MancalaGame.applyHint], which drop it if the position has moved on.
 */
class MancalaMovePlan internal constructor(
    internal val position: MancalaState,
    val playerIndex: Int,
    private val rules: MancalaRules,
    private val difficulty: CpuDifficulty
) {
    /** The pit (absolute board index) to sow, or null when the seat has no legal pit. Thread-safe. */
    fun chooseMove(): Int? {
        val pits = position.pits.toIntArray()
        val ownPits = rules.pitsOf(playerIndex)
        return when (difficulty) {
            CpuDifficulty.EASY -> ownPits.filter { pits[it] > 0 }.randomOrNull()
            CpuDifficulty.MEDIUM -> {
                // Prefer a move landing exactly in the store (extra turn); else first non-empty pit.
                val ownStore = rules.storeOf(playerIndex)
                val bonusMove = ownPits.firstOrNull { pit -> pits[pit] > 0 && (pit + pits[pit]) % rules.boardSize == ownStore }
                bonusMove ?: ownPits.firstOrNull { pits[it] > 0 }
            }
            CpuDifficulty.HARD -> MancalaSearch(rules).bestMove(pits, playerIndex)
        }
    }
}

class MancalaGame : GameModule, ResumableGame, UndoableGame {
    override val gameId = "mancala"
    override val displayName = "Mancala"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<MancalaState?>(null)

    /**
     * Running session score across games -- mirrors TicTacToeGame's
     * scoreP1/scoreP2/draws (see its KDoc): a running score is what gives
     * repeated Mancala play a reason to continue past a single game, the
     * same way Play Again does for Tic-Tac-Toe.
     */
    val scoreP1 = mutableStateOf(0)
    val scoreP2 = mutableStateOf(0)
    val draws = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via the round-over panel), not per-game. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /**
     * The roster seat (0 or 1) that takes the first sowing of the session's FIRST board. Set by the
     * UI from the Hub's first-mover choice before [startMatch]. Values outside 0..1 count as 0.
     * Boards dealt later by [playAgain] always open with seat 0 (the engine's existing behaviour,
     * no alternation). The board layout never changes: seat 0's pits stay on the near side.
     */
    var openingSeatIndex: Int = 0

    /**
     * The board shape and rules (pits per side, stones per pit, capture rule). Set by the UI from the
     * Hub's options before [init]/[startMatch]; a restored save replaces it with the save's own. An
     * unsupported value is replaced by the standard game. Defaults to the standard 6 x 4 game.
     */
    var rules: MancalaRules = MancalaRules()
        set(value) {
            field = if (value.isSupported) value else MancalaRules()
        }

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    private val player0Pits: IntRange get() = rules.pitsOf(0)
    private val player0Store: Int get() = rules.store0
    private val player1Pits: IntRange get() = rules.pitsOf(1)
    private val player1Store: Int get() = rules.store1

    /** Snapshots taken just BEFORE each human sowing, newest last; popping one is Undo. Per game: a new board starts empty. */
    private val history = SnapshotHistory()

    /** The pit (absolute board index 0-12) the last [hint] suggested, shown ringed until the next sow, undo or new board. */
    val hintPit = mutableStateOf<Int?>(null)

    // ---- Networked play (LOCAL_AD_HOC / ONLINE, two seats: host = 0, guest = 1) -----------------
    // Host-authoritative: only the host runs the rules ([applySow]); a guest's tap becomes a
    // MancalaNetMessage.SowRequest and the guest's own engine only ever adopts the host's StateSync.

    /** True when this session is played over a transport (Nearby or Online). False before [init]. */
    val isNetworkedPlay: Boolean
        get() = ::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)

    private val isHost: Boolean get() = context.localPlayerIndex == 0

    /**
     * The seat whose pits the screen draws at the bottom: the local player's own seat when
     * networked (so the guest's board is flipped), always seat 0 in local play.
     */
    val viewSeat: Int get() = if (isNetworkedPlay) context.localPlayerIndex.coerceIn(0, 1) else 0

    /** Set when the other seat's transport reported it left (cleared when it is heard from again). Never records a result. */
    val opponentDisconnected = mutableStateOf(false)

    private var stateVersion = 0
    private var lastAppliedRemoteVersion = 0
    private var registeredTransport: MultiplayerTransport? = null

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        scoreP1.value = 0
        scoreP2.value = 0
        draws.value = 0
        history.clear()
        hintPit.value = null
        stateVersion = 0
        lastAppliedRemoteVersion = 0
        opponentDisconnected.value = false
        if (isNetworkedPlay) {
            // init can run again for the same session (the screen re-keys on its context); a second
            // set of listeners would apply every request twice.
            if (registeredTransport !== context.transport) {
                registeredTransport = context.transport
                registerNetworkListeners(context.transport)
            }
            // Covers the startup race where the host dealt before this device was listening.
            if (!isHost) sendToHost(MancalaNetMessage.RequestState)
        }
    }

    private fun registerNetworkListeners(transport: MultiplayerTransport) {
        transport.onMessageReceived { fromPlayerId, payload -> handleNetworkMessage(fromPlayerId, payload) }
        transport.onReconnected {
            if (isNetworkedPlay && !matchOver.value) {
                // A guest may have missed broadcasts; the host re-sends (its own link may be what dropped).
                if (isHost) {
                    if (state.value != null) sendStateToGuest()
                } else {
                    sendToHost(MancalaNetMessage.RequestState)
                }
            }
        }
        transport.onPlayerLeft { playerId ->
            if (isNetworkedPlay && !matchOver.value && playerId == opponentPlayerId()) opponentDisconnected.value = true
        }
        transport.onPlayerJoined { playerId ->
            if (isNetworkedPlay && !matchOver.value && playerId == opponentPlayerId()) {
                opponentDisconnected.value = false
                if (isHost && state.value != null) sendStateToGuest()
            }
        }
    }

    private fun opponentPlayerId(): String? =
        context.players.getOrNull(1 - context.localPlayerIndex.coerceIn(0, 1))?.playerId

    private fun seatOf(playerId: String): Int = context.players.indexOfFirst { it.playerId == playerId }

    private fun sendToHost(message: MancalaNetMessage) {
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = MancalaNetCodec.encode(message))
    }

    private fun sendStateTo(guestPlayerId: String) {
        val s = state.value ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val message = MancalaNetMessage.StateSync(stateVersion, toNetState(s))
        context.transport.send(fromPlayerId = hostId, toPlayerId = guestPlayerId, payload = MancalaNetCodec.encode(message))
    }

    private fun sendStateToGuest() {
        context.players.getOrNull(1)?.playerId?.let { sendStateTo(it) }
    }

    /** Host only: stamps the next version on the current board and sends it to the guest. */
    private fun commitAndBroadcast() {
        if (!isNetworkedPlay || !isHost) return
        stateVersion++
        sendStateToGuest()
    }

    private fun toNetState(s: MancalaState) = MancalaNetState(
        pits = s.pits,
        currentPlayerIndex = s.currentPlayerIndex,
        lastAction = s.lastAction,
        roundOver = s.roundOver,
        winnerPlayerId = s.winnerPlayerId,
        lastSowPath = s.lastSowPath,
        lastCapture = s.lastCapture?.let { MancalaNetCapture(it.landingPit, it.oppositePit, it.totalSwept) },
        scoreP1 = scoreP1.value,
        scoreP2 = scoreP2.value,
        draws = draws.value,
        pitsPerSide = rules.pitsPerSide,
        stonesPerPit = rules.stonesPerPit,
        captureRule = rules.capture.key
    )

    /** A board is only adopted if it has THIS game's shape and rules (a mismatched host is ignored, not half-shown). */
    private fun isValidNetState(n: MancalaNetState): Boolean {
        val capture = n.lastCapture
        val r = rules
        val last = r.boardSize - 1
        return n.pitsPerSide == r.pitsPerSide &&
            n.stonesPerPit == r.stonesPerPit &&
            n.captureRule == r.capture.key &&
            n.pits.size == r.boardSize &&
            n.pits.all { it >= 0 } &&
            n.pits.sum() == r.totalStones &&
            n.currentPlayerIndex in 0..1 &&
            (n.winnerPlayerId == null || context.players.any { it.playerId == n.winnerPlayerId }) &&
            n.lastSowPath.size <= r.maxSowPathLength && n.lastSowPath.all { it in 0..last } &&
            (capture == null || (capture.landingPit in 0..last && capture.oppositePit in 0..last && capture.totalSwept >= 0)) &&
            n.scoreP1 >= 0 && n.scoreP2 >= 0 && n.draws >= 0
    }

    /** Guest only: adopts the host's board if it is newer than what is already shown. */
    private fun applyRemoteState(version: Int, n: MancalaNetState) {
        if (version <= 0 || version <= lastAppliedRemoteVersion) return
        if (!isValidNetState(n)) return
        // Not the very next board: this device missed moves (late join, reconnect), so show the
        // position as it is instead of replaying a sow that is not happening now.
        val restored = version != lastAppliedRemoteVersion + 1
        lastAppliedRemoteVersion = version
        opponentDisconnected.value = false
        val incoming = MancalaState(
            pits = n.pits,
            currentPlayerIndex = n.currentPlayerIndex,
            lastAction = n.lastAction,
            roundOver = n.roundOver,
            winnerPlayerId = n.winnerPlayerId,
            lastSowPath = n.lastSowPath,
            lastCapture = n.lastCapture?.let { MancalaCapture(it.landingPit, it.oppositePit, it.totalSwept) },
            restored = restored
        )
        val current = state.value
        // An identical board must not produce a new state object: the screen replays the hop
        // animation for every new object.
        if (current == null || current.copy(restored = false) != incoming.copy(restored = false)) {
            state.value = incoming
        }
        scoreP1.value = n.scoreP1
        scoreP2.value = n.scoreP2
        draws.value = n.draws
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        if (!isNetworkedPlay || matchOver.value) return
        val message = MancalaNetCodec.decode(payload) ?: return
        when (message) {
            // Only the host is authoritative: a StateSync from any other seat is a forgery.
            is MancalaNetMessage.StateSync -> {
                if (!isHost && fromPlayerId == context.players.getOrNull(0)?.playerId) {
                    applyRemoteState(message.version, message.state)
                }
            }
            is MancalaNetMessage.RequestState -> {
                if (isHost && seatOf(fromPlayerId) > 0) sendStateTo(fromPlayerId)
            }
            is MancalaNetMessage.SowRequest -> {
                if (isHost) handleSowRequest(fromPlayerId, message)
            }
            is MancalaNetMessage.PlayAgain -> {
                if (isHost) handlePlayAgainRequest(fromPlayerId)
            }
        }
    }

    /**
     * Host only. [fromPlayerId] is the transport's real sender; the seat it maps to must be the seat
     * on move (and not the host's own), the request must be for the version the guest saw, and the
     * pit must be one of that seat's non-empty pits. Anything else changes nothing.
     */
    private fun handleSowRequest(fromPlayerId: String, request: MancalaNetMessage.SowRequest) {
        val s = state.value ?: return
        val seat = seatOf(fromPlayerId)
        if (seat < 0 || seat == context.localPlayerIndex) return
        if (s.roundOver || seat != s.currentPlayerIndex) return
        if (request.baseVersion != stateVersion) {
            // The guest acted on an out-of-date board: ignore the tap, hand it the real one.
            sendStateTo(fromPlayerId)
            return
        }
        val ownPits = if (seat == 0) player0Pits else player1Pits
        if (request.pit !in ownPits || s.pits[request.pit] == 0) return
        opponentDisconnected.value = false
        applySow(seat, request.pit)
    }

    private fun handlePlayAgainRequest(fromPlayerId: String) {
        val seat = seatOf(fromPlayerId)
        if (seat < 0 || seat == context.localPlayerIndex) return
        if (state.value?.roundOver != true) return
        dealBoard(0)
    }

    // --- Resume -----------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return mancalaSnapshotJson.encodeToString(
            MancalaSnapshot.serializer(),
            MancalaSnapshot(
                pits = s.pits,
                currentPlayerIndex = s.currentPlayerIndex,
                lastAction = s.lastAction,
                roundOver = s.roundOver,
                winnerPlayerId = s.winnerPlayerId,
                lastSowPath = s.lastSowPath,
                lastCapture = s.lastCapture?.let { MancalaSnapshotCapture(it.landingPit, it.oppositePit, it.totalSwept) },
                players = context.players.map { MancalaSnapshotPlayer(it.playerId, it.isBot) },
                scoreP1 = scoreP1.value,
                scoreP2 = scoreP2.value,
                draws = draws.value,
                difficulty = difficulty.name,
                openingSeatIndex = openingSeatIndex,
                pitsPerSide = rules.pitsPerSide,
                stonesPerPit = rules.stonesPerPit,
                captureRule = rules.capture.key
            )
        )
    }

    /** The rules a snapshot was saved under; null when it names an unknown capture rule or an unsupported board. */
    private fun snapshotRules(snap: MancalaSnapshot): MancalaRules? {
        val capture = MancalaCaptureRule.fromKey(snap.captureRule) ?: return null
        val r = MancalaRules(snap.pitsPerSide, snap.stonesPerPit, capture)
        return if (r.isSupported) r else null
    }

    /**
     * Decodes and sanity-checks a snapshot against its OWN saved board shape (a bad stone count or
     * pit count is rejected) and THIS game's roster; null if unusable.
     */
    private fun decodeSnapshot(text: String): MancalaSnapshot? {
        return try {
            val snap = mancalaSnapshotJson.decodeFromString(MancalaSnapshot.serializer(), text)
            val r = snapshotRules(snap) ?: return null
            val roster = context.players
            val capture = snap.lastCapture
            val last = r.boardSize - 1
            val valid = snap.pits.size == r.boardSize &&
                snap.pits.all { it >= 0 } &&
                snap.pits.sum() == r.totalStones &&
                snap.players.size == roster.size &&
                snap.players.indices.all {
                    snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot
                } &&
                snap.currentPlayerIndex in 0..1 &&
                (snap.winnerPlayerId == null || roster.any { it.playerId == snap.winnerPlayerId }) &&
                snap.lastSowPath.size <= r.maxSowPathLength && snap.lastSowPath.all { it in 0..last } &&
                (capture == null || (capture.landingPit in 0..last && capture.oppositePit in 0..last && capture.totalSwept >= 0)) &&
                snap.scoreP1 >= 0 && snap.scoreP2 >= 0 && snap.draws >= 0 &&
                (snap.roundOver || (r.pitsOf(0).any { snap.pits[it] > 0 } && r.pitsOf(1).any { snap.pits[it] > 0 }))
            if (valid) snap else null
        } catch (_: Exception) {
            null
        }
    }

    private fun applySnapshot(snap: MancalaSnapshot, keepCounters: Boolean = false) {
        snapshotRules(snap)?.let { rules = it }
        state.value = MancalaState(
            pits = snap.pits,
            currentPlayerIndex = snap.currentPlayerIndex,
            lastAction = snap.lastAction,
            roundOver = snap.roundOver,
            winnerPlayerId = snap.winnerPlayerId,
            lastSowPath = snap.lastSowPath,
            lastCapture = snap.lastCapture?.let { MancalaCapture(it.landingPit, it.oppositePit, it.totalSwept) },
            restored = true
        )
        scoreP1.value = snap.scoreP1
        scoreP2.value = snap.scoreP2
        draws.value = snap.draws
        if (!keepCounters) {
            difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        }
        hintPit.value = null
    }

    /** Null when there is nothing to save: no board yet, the session already ended, or a mode whose state lives with other players. */
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
        applySnapshot(snap)
        openingSeatIndex = if (snap.openingSeatIndex in 0..1) snap.openingSeatIndex else 0
        history.clear()
        return true
    }

    // --- Undo -------------------------------------------------------------------------------

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value && !isNetworkedPlay

    /**
     * Takes back the last human sowing. Against the CPU that also takes back the CPU's reply (the
     * snapshot was taken before the human's sow), so you are back on your own turn. After an extra
     * turn each human sowing is its own step. Undoing a game-ending sow re-opens the board and
     * takes its win off the session tally. Mancala has no mistake/hint counters to protect, and
     * the difficulty is left as it is.
     */
    override fun undo() {
        if (matchOver.value || isNetworkedPlay) return
        val previous = history.pop() ?: return
        val snap = decodeSnapshot(previous) ?: return
        applySnapshot(snap, keepCounters = true)
    }

    // --- Hint -------------------------------------------------------------------------------

    /**
     * Suggests a pit for the player to move: the best pit by the HARD bot's search, regardless of
     * the CPU difficulty setting. Highlights it ([hintPit]) and returns it (absolute board index);
     * null when it is a bot's turn, the game is over or there is no legal pit. Mancala keeps no
     * records, so a hint costs nothing.
     */
    fun hint(): Int? {
        val plan = planHint() ?: return null
        return applyHint(plan, plan.chooseMove())
    }

    /**
     * First half of an off-main-thread hint: a plan for the player to move (null in the cases where
     * [hint] returns null). Run `plan.chooseMove()` on any thread, then pass the answer to [applyHint].
     */
    fun planHint(): MancalaMovePlan? {
        if (isNetworkedPlay) return null
        val s = state.value ?: return null
        if (matchOver.value || s.roundOver) return null
        if (context.players.getOrNull(s.currentPlayerIndex)?.isBot != false) return null
        return MancalaMovePlan(s, s.currentPlayerIndex, rules, CpuDifficulty.HARD)
    }

    /** Second half: rings [pit] and returns it, unless the position changed since [planHint] (then nothing happens and null comes back). */
    fun applyHint(plan: MancalaMovePlan, pit: Int?): Int? {
        if (matchOver.value || state.value !== plan.position) return null
        hintPit.value = pit
        return pit
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        if (isNetworkedPlay) {
            // Networked play: the host deals (seat 0 first, openingSeatIndex does not apply); a
            // guest waits for the host's StateSync.
            if (isHost) dealBoard(0)
            return
        }
        dealBoard(if (openingSeatIndex in 0..1) openingSeatIndex else 0)
    }

    private fun dealBoard(openingSeat: Int) {
        val pits = rules.initialPits()
        // A new board starts a new undo history: undo never reaches back into the previous board.
        history.clear()
        hintPit.value = null
        state.value = MancalaState(pits = pits, currentPlayerIndex = openingSeat, lastAction = "Game started")
        commitAndBroadcast()
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /**
     * Called from the round-over panel's "Back to Menu" button -- ends the
     * whole session (not just the current game), reporting the cumulative
     * score. Mirrors TicTacToeGame.leaveSession().
     */
    fun leaveSession() {
        if (matchOver.value) return
        val scores = mutableListOf<PlayerScore>()
        context.players.getOrNull(0)?.let {
            scores += PlayerScore(playerId = it.playerId, score = scoreP1.value, isWinner = scoreP1.value > scoreP2.value)
        }
        context.players.getOrNull(1)?.let {
            scores += PlayerScore(playerId = it.playerId, score = scoreP2.value, isWinner = scoreP2.value > scoreP1.value)
        }
        endMatch(GameResult(scores = scores))
    }

    /**
     * Called from the round-over panel's "Play Again" button -- keeps the
     * running score and deals a fresh board within the same session. Reuses
     * [startMatch] itself rather than duplicating its setup, since "a new
     * game" and "the first game of the match" are the same operation.
     * Mirrors TicTacToeGame.playAgain(). Later boards open with seat 0 (see [openingSeatIndex]).
     */
    fun playAgain() {
        if (matchOver.value) return
        if (isNetworkedPlay) {
            if (state.value?.roundOver != true) return
            // Either seat may ask; only the host deals.
            if (!isHost) {
                sendToHost(MancalaNetMessage.PlayAgain)
                return
            }
        }
        dealBoard(0)
    }

    /**
     * A local tap on pit [pitIndex] for seat [playerIndex]. Networked: only the local seat may sow;
     * the host applies it, a guest just asks the host and waits for the resulting StateSync.
     */
    fun sow(playerIndex: Int, pitIndex: Int) {
        if (matchOver.value) return
        if (isNetworkedPlay) {
            val me = context.localPlayerIndex
            if (playerIndex != me) return
            if (!isHost) {
                val s = state.value ?: return
                if (s.roundOver || s.currentPlayerIndex != me) return
                if (pitIndex !in player1Pits || s.pits[pitIndex] == 0) return
                sendToHost(MancalaNetMessage.SowRequest(pit = pitIndex, baseVersion = lastAppliedRemoteVersion))
                return
            }
        }
        applySow(playerIndex, pitIndex)
    }

    /** The rules engine: validates and applies one sowing to [state] (and, on a networked host, broadcasts it). */
    private fun applySow(playerIndex: Int, pitIndex: Int) {
        // Once the session has ended (leaveSession / abortMatch) a stray tap or an in-flight bot
        // turn must not mutate the board or tally another win into the already-reported scores.
        if (matchOver.value) return
        val s = state.value ?: return
        if (s.roundOver || playerIndex != s.currentPlayerIndex) return

        val ownPits = if (playerIndex == 0) player0Pits else player1Pits
        val ownStore = if (playerIndex == 0) player0Store else player1Store
        val opponentStore = if (playerIndex == 0) player1Store else player0Store

        if (pitIndex !in ownPits || s.pits[pitIndex] == 0) return

        // Undo step: the position just BEFORE a human's sowing (never a bot's), taken only once the
        // move is known to be legal. Against the CPU, popping it also removes the CPU's reply.
        if (!isNetworkedPlay && context.players.getOrNull(playerIndex)?.isBot == false) {
            captureSnapshot()?.let { history.push(it) }
        }
        hintPit.value = null

        val pits = s.pits.toMutableList()
        var stones = pits[pitIndex]
        pits[pitIndex] = 0
        var cursor = pitIndex

        // sowPath: pure instrumentation for MancalaScreen's seed-hop animation (see
        // MancalaState.lastSowPath's KDoc) -- element 0 is the pit the stones were
        // picked up from; the loop below is completely unchanged, it just also
        // appends each cursor it already visits.
        val sowPath = mutableListOf(pitIndex)
        val boardSize = rules.boardSize
        while (stones > 0) {
            cursor = (cursor + 1) % boardSize
            if (cursor == opponentStore) continue // skip opponent's store
            pits[cursor]++
            stones--
            sowPath.add(cursor)
        }

        var extraTurn = false
        var captureMsg = ""
        var capture: MancalaCapture? = null

        // Landed in own empty pit (was 0 before this sow, now 1) -> capture (unless the variant has no captures).
        if (rules.capture == MancalaCaptureRule.OPPOSITE && cursor in ownPits && pits[cursor] == 1) {
            val oppositeIndex = rules.oppositeOf(cursor)
            if (pits[oppositeIndex] > 0) {
                val captured = pits[oppositeIndex] + pits[cursor]
                pits[oppositeIndex] = 0
                pits[cursor] = 0
                pits[ownStore] += captured
                captureMsg = " and captured $captured"
                capture = MancalaCapture(landingPit = cursor, oppositePit = oppositeIndex, totalSwept = captured)
            }
        } else if (cursor == ownStore) {
            extraTurn = true
        }

        val player0Empty = player0Pits.all { pits[it] == 0 }
        val player1Empty = player1Pits.all { pits[it] == 0 }
        var roundOver = false
        var winnerId: String? = null

        if (player0Empty || player1Empty) {
            if (player0Empty) { for (i in player1Pits) { pits[player1Store] += pits[i]; pits[i] = 0 } }
            if (player1Empty) { for (i in player0Pits) { pits[player0Store] += pits[i]; pits[i] = 0 } }
            roundOver = true
            winnerId = when {
                pits[player0Store] > pits[player1Store] -> context.players.getOrNull(0)?.playerId
                pits[player1Store] > pits[player0Store] -> context.players.getOrNull(1)?.playerId
                else -> null // tie
            }
        }

        val nextPlayer = if (extraTurn && !roundOver) playerIndex else (playerIndex + 1) % 2
        val player = context.players[playerIndex]

        state.value = s.copy(
            pits = pits,
            currentPlayerIndex = nextPlayer,
            lastAction = "${player.displayName} sowed from pit ${pitIndex + 1}$captureMsg",
            roundOver = roundOver,
            winnerPlayerId = winnerId,
            lastSowPath = sowPath,
            lastCapture = capture,
            restored = false
        )

        // Tally into the running session score (see scoreP1/scoreP2/draws' KDoc) rather
        // than ending the match here -- a game ending only ends the current round; the
        // whole session only ends via leaveSession(), so Play Again can deal a fresh
        // board without disconnecting the transport or losing the running score.
        if (roundOver) {
            when {
                winnerId != null && winnerId == context.players.getOrNull(0)?.playerId -> scoreP1.value += 1
                winnerId != null && winnerId == context.players.getOrNull(1)?.playerId -> scoreP2.value += 1
                else -> draws.value += 1
            }
        }
        commitAndBroadcast()
    }

    /**
     * Mancala, unlike Dominoes, has zero hidden information — both players'
     * pits are visible to everyone, always — so a real look-ahead search is
     * fair game for HARD rather than a strategy-sourced heuristic. EASY
     * moves uniformly at random among legal pits, not even taking the
     * free-extra-turn bonus MEDIUM already knew about — genuinely weaker,
     * not just relabeled. MEDIUM is the original bot, untouched: prefer a
     * move landing exactly in the store (extra turn), else the first
     * non-empty pit.
     */
    fun playBotTurn() {
        val plan = planBotTurn() ?: return
        applyBotMove(plan, plan.chooseMove())
    }

    /**
     * First half of an off-main-thread bot turn: a plan for the bot on move (null when it is not a
     * bot's turn, the board is over or the session ended). The screen calls `plan.chooseMove()` inside
     * `withContext(Dispatchers.Default)` and hands the pit to [applyBotMove].
     */
    fun planBotTurn(): MancalaMovePlan? {
        if (matchOver.value) return null
        val s = state.value ?: return null
        if (s.roundOver) return null
        val botIndex = s.currentPlayerIndex
        if (context.players.getOrNull(botIndex)?.isBot != true) return null
        return MancalaMovePlan(s, botIndex, rules, difficulty)
    }

    /** Second half: sows [move] unless the position changed since the plan was made (undo, a new board, the session ending). */
    fun applyBotMove(plan: MancalaMovePlan, move: Int?) {
        if (move == null || matchOver.value || state.value !== plan.position) return
        sow(plan.playerIndex, move)
    }

    // ---- HARD: the search itself lives in MancalaSearch / MancalaRules.simulate above, a pure copy
    // of the sow rules over plain int arrays, so exploring hypothetical moves never touches (or risks
    // corrupting) `state`, and it can run on any thread.

    /**
     * Read-only helper for MancalaScreen's capture-preview highlighting:
     * which of [playerIndex]'s currently legal pits would land the last
     * stone in an empty pit of theirs (a capture). Reuses the exact same
     * [legalMoves]/[simulateSow] pair the HARD bot's minimax search already
     * relies on, so this preview can never drift from the real capture rule
     * in [sow] -- it only ever reads the board via simulateSow's pure copy,
     * never touching [state]. A capture always zeroes both the landing pit
     * and its opposite pit (see sow()'s capture branch); landing in a
     * non-empty own pit or the store never reads back as zero, so checking
     * the landing pit alone is enough to recognize one, without needing to
     * separately inspect the opposite pit here.
     */
    fun captureCandidates(playerIndex: Int): Set<Int> {
        val s = state.value ?: return emptySet()
        // The no-capture variant never highlights anything.
        if (rules.capture != MancalaCaptureRule.OPPOSITE) return emptySet()
        val pits = s.pits.toIntArray()
        val ownPits = rules.pitsOf(playerIndex)
        return ownPits.filter { pit ->
            pits[pit] > 0 && rules.simulate(pits, playerIndex, pit).let { result ->
                result.landingCursor in ownPits && result.pits[result.landingCursor] == 0
            }
        }.toSet()
    }
}
