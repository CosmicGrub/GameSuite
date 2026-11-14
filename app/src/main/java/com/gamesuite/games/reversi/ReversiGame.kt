package com.gamesuite.games.reversi

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything needed to put a Reversi session back exactly as it was: the board, whose turn it is,
 * the pass flag, the session tally, who opens the next board and the difficulty. One shape serves
 * both Resume (written to disk) and Undo (kept in memory). The legal-move set and the disc counts
 * are derived from the cells on restore, so they cannot disagree with the board.
 *
 * [size] is the board edge length; a save written before board sizes existed has no such field and
 * decodes as the standard 8. (It is also left out of the JSON when it is 8, since defaults are not
 * encoded, so standard saves stay byte-identical to the old format.)
 */
@Serializable
private data class ReversiSnapshot(
    val cells: List<Int?>,
    val players: List<ReversiSnapshotPlayer>,
    val currentPlayerIndex: Int,
    val lastAction: String,
    val justPassed: Boolean,
    val boardOver: Boolean,
    val winnerPlayerId: String?,
    val sessionWins: Map<String, Int>,
    val sessionDraws: Int,
    val nextStartingPlayerIndex: Int,
    val difficulty: String,
    val size: Int = 8
)

@Serializable
private data class ReversiSnapshotPlayer(val playerId: String, val displayName: String, val isBot: Boolean)

private val reversiSnapshotJson = Json { ignoreUnknownKeys = true }

/** The supported board edge lengths and the Hub's "board" option ids that select them. */
object ReversiBoardSizes {
    const val SMALL = 6
    const val STANDARD = 8
    const val LARGE = 10
    val ALL: List<Int> = listOf(SMALL, STANDARD, LARGE)

    /** Hub option id ("small" / "standard" / "large") to an edge length; anything else is standard. */
    fun fromOptionId(id: String?): Int = when (id) {
        "small" -> SMALL
        "large" -> LARGE
        else -> STANDARD
    }
}

/**
 * Search depth (in plies) for the bot per board size and [CpuDifficulty] tier. 6x6 and 8x8 keep the
 * long-standing 2/4/6; a 10x10 board has more cells to scan per node and more moves per position, so
 * it searches 2/3/4 to stay around a second per move on a phone.
 */
internal fun reversiSearchDepth(size: Int, difficulty: CpuDifficulty): Int =
    if (size >= ReversiBoardSizes.LARGE) {
        when (difficulty) {
            CpuDifficulty.EASY -> 2
            CpuDifficulty.HARD -> 4
            else -> 3
        }
    } else {
        when (difficulty) {
            CpuDifficulty.EASY -> 2
            CpuDifficulty.HARD -> 6
            else -> 4
        }
    }

/** A player's identity + bot flag — mirrors ConnectFourPlayerState's own shape (see that class's
 *  KDoc for why this is a list even though Reversi, like Connect Four, is always exactly 2 players). */
data class ReversiPlayerState(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean
)

/**
 * [cells] is size*size entries (64 on the standard 8x8 board), row-major (index = row*size+col);
 * each entry is the index into [players] who owns that disc, or null if empty. [legalMoves] is
 * always the CURRENT player's own legal destination cells (recomputed by
 * [ReversiGame.buildStateAfterMove] on every move, including through a pass — see that function's
 * own KDoc), so the UI can highlight them directly without recomputing anything itself. [scores] is
 * disc count per player index, live for the whole board (not just at game end). [justPassed] is
 * true only on the ply immediately after a real pass happened (the OTHER player had no legal move
 * and was skipped) — see [ReversiGame.buildStateAfterMove] — so the UI can show a one-time "Player
 * X passes" moment rather than silently reassigning the turn. [size] is the board edge length.
 */
data class ReversiState(
    val cells: List<Int?>,
    val players: List<ReversiPlayerState>,
    val currentPlayerIndex: Int,
    val legalMoves: Set<Int>,
    val scores: List<Int>,
    val lastAction: String,
    val justPassed: Boolean = false,
    val boardOver: Boolean = false,
    val winnerPlayerId: String? = null,
    val size: Int = 8
)

/**
 * The pure board rules and bot search for ONE board edge length. Immutable after construction, so a
 * search can run on a background thread against the engine it captured without touching the
 * (main-thread) game state: [ReversiGame] hands it a copy of the cells and applies the answer later.
 *
 * Flanking walks one step at a time with real row/col bounds checking in all 8 directions, never a
 * flat `index ± delta` shortcut (the classic Othello-engine bug: `index - 1` from column 0 silently
 * wraps into the previous row instead of walking off the board).
 *
 * BOT STRATEGY: alpha-beta minimax over the classic static positional table (corners strongly
 * positive; the diagonal "X-squares" and edge-adjacent "C-squares" negative while their corner is
 * still takeable) plus a mobility term; a genuine board-over leaf is scored by the real final disc
 * difference, offset by [WIN_SCORE] so a confirmed outcome always beats a heuristic one. A pass
 * consumes one ply but places no disc; true game over needs NEITHER player to have a move. Legal
 * cells are searched corner-first purely as alpha-beta move ordering. The table generalizes to any
 * even size >= 6: corners at the four ends, X-squares diagonal to a corner, C-squares beside it.
 */
internal class ReversiEngine private constructor(val n: Int) {
    val cellCount: Int = n * n

    /** For a C-square, the corner it sits next to; -1 for every other cell. */
    private val cSquareCorner = IntArray(cellCount) { -1 }

    /** Static weight of every cell that is not a C-square (a C-square's weight depends on its corner). */
    private val fixedWeight = IntArray(cellCount)

    /** Alpha-beta move-ordering rank per cell (higher is searched first). */
    private val orderRank = IntArray(cellCount)

    init {
        val last = n - 1
        for (index in 0 until cellCount) {
            val row = index / n
            val col = index % n
            val rowEdge = row == 0 || row == last
            val colEdge = col == 0 || col == last
            val nearRow = row == 1 || row == n - 2
            val nearCol = col == 1 || col == n - 2
            when {
                rowEdge && colEdge -> {
                    fixedWeight[index] = CORNER_WEIGHT
                    orderRank[index] = 3
                }
                nearRow && nearCol -> {
                    fixedWeight[index] = X_SQUARE_WEIGHT
                    orderRank[index] = 0
                }
                rowEdge && nearCol -> {
                    cSquareCorner[index] = row * n + (if (col == 1) 0 else last)
                    orderRank[index] = 0
                }
                colEdge && nearRow -> {
                    cSquareCorner[index] = (if (row == 1) 0 else last) * n + col
                    orderRank[index] = 0
                }
                rowEdge || colEdge -> {
                    // Whichever coordinate varies along this edge; 0/1 and last-1/last are corner or
                    // C-square cells handled above, so only the stretch between them remains.
                    val alongEdge = if (rowEdge) col else row
                    fixedWeight[index] =
                        if (alongEdge == 2 || alongEdge == n - 3) EDGE_NEAR_CORNER_WEIGHT else EDGE_MID_WEIGHT
                    orderRank[index] = 2
                }
                else -> {
                    val ring = row == 1 || row == n - 2 || col == 1 || col == n - 2
                    fixedWeight[index] = if (ring) INTERIOR_RING_WEIGHT else INTERIOR_CORE_WEIGHT
                    orderRank[index] = 1
                }
            }
        }
    }

    /** The standard opening: the four centre cells, the two diagonals split between the players. */
    fun openingCells(startingPlayer: Int): MutableList<Int?> {
        val cells = MutableList<Int?>(cellCount) { null }
        val h = n / 2
        val other = 1 - startingPlayer
        cells[(h - 1) * n + (h - 1)] = other
        cells[h * n + h] = other
        cells[(h - 1) * n + h] = startingPlayer
        cells[h * n + (h - 1)] = startingPlayer
        return cells
    }

    /** True iff walking from ([row], [col]) in ([dr], [dc]) sees 1+ opponent discs then one of [player]'s own. */
    private fun flanksInDirection(cells: List<Int?>, row: Int, col: Int, dr: Int, dc: Int, player: Int): Boolean {
        val opponent = 1 - player
        var r = row + dr
        var c = col + dc
        var sawOpponent = false
        while (r in 0 until n && c in 0 until n) {
            when (cells[r * n + c]) {
                opponent -> {
                    sawOpponent = true
                    r += dr
                    c += dc
                }
                player -> return sawOpponent
                else -> return false // empty cell -- the line dead-ends before reaching player's own disc
            }
        }
        return false // ran off the board without reaching player's own disc
    }

    fun isLegalMove(cells: List<Int?>, index: Int, player: Int): Boolean {
        if (index !in 0 until cellCount || cells[index] != null) return false
        val row = index / n
        val col = index % n
        return DIRECTIONS.any { (dr, dc) -> flanksInDirection(cells, row, col, dr, dc, player) }
    }

    fun legalMovesFor(cells: List<Int?>, player: Int): Set<Int> =
        cells.indices.filterTo(mutableSetOf()) { isLegalMove(cells, it, player) }

    /** Places [player]'s disc at [index] and flips every flanked line (decided against the original cells). */
    fun applyMove(cells: List<Int?>, index: Int, player: Int): List<Int?> {
        val newCells = cells.toMutableList()
        newCells[index] = player
        val row = index / n
        val col = index % n
        for ((dr, dc) in DIRECTIONS) {
            if (!flanksInDirection(cells, row, col, dr, dc, player)) continue
            var r = row + dr
            var c = col + dc
            while (newCells[r * n + c] != player) {
                newCells[r * n + c] = player
                r += dr
                c += dc
            }
        }
        return newCells
    }

    /**
     * Runs [minimax] for every cell in [legal] and returns the best-scoring one (ties keep the first
     * found; candidates are sorted corner-first). The running alpha is threaded across sibling root
     * moves, which tightens pruning without changing which move wins. When [deadlineNanos] passes
     * and at least one move has been scored, the remaining root moves are skipped.
     */
    fun chooseMove(
        cells: List<Int?>,
        legal: Set<Int>,
        bot: Int,
        depth: Int,
        deadlineNanos: Long = Long.MAX_VALUE
    ): Int? {
        val opponent = 1 - bot
        var alpha = Int.MIN_VALUE
        var bestScore = Int.MIN_VALUE
        var bestIndex: Int? = null
        for (index in legal.sortedByDescending { orderRank[it] }) {
            if (bestIndex != null && System.nanoTime() > deadlineNanos) break
            val newCells = applyMove(cells, index, bot)
            val score = minimax(
                newCells, depth = depth - 1, alpha = alpha, beta = Int.MAX_VALUE,
                maximizing = false, bot = bot, opponent = opponent, toMove = opponent
            )
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
                alpha = maxOf(alpha, bestScore)
            }
        }
        return bestIndex
    }

    private fun minimax(
        cells: List<Int?>, depth: Int, alpha: Int, beta: Int, maximizing: Boolean,
        bot: Int, opponent: Int, toMove: Int
    ): Int {
        val toMoveLegal = legalMovesFor(cells, toMove)
        if (toMoveLegal.isEmpty()) {
            val otherLegal = legalMovesFor(cells, 1 - toMove)
            if (otherLegal.isEmpty()) return terminalScore(cells, bot, opponent) // neither can move -- real game over
            if (depth == 0) return evaluate(cells, bot, opponent)
            return minimax(cells, depth - 1, alpha, beta, !maximizing, bot, opponent, 1 - toMove) // pass: no disc placed
        }
        if (depth == 0) return evaluate(cells, bot, opponent)

        var a = alpha
        var b = beta
        var best = if (maximizing) Int.MIN_VALUE else Int.MAX_VALUE
        for (index in toMoveLegal.sortedByDescending { orderRank[it] }) {
            val newCells = applyMove(cells, index, toMove)
            val score = minimax(newCells, depth - 1, a, b, !maximizing, bot, opponent, 1 - toMove)
            if (maximizing) {
                best = maxOf(best, score)
                a = maxOf(a, best)
            } else {
                best = minOf(best, score)
                b = minOf(b, best)
            }
            if (b <= a) break // alpha-beta cutoff
        }
        return best
    }

    /** A genuine board-over leaf from [bot]'s view: the real disc difference offset by [WIN_SCORE]. */
    private fun terminalScore(cells: List<Int?>, bot: Int, opponent: Int): Int {
        val diff = cells.count { it == bot } - cells.count { it == opponent }
        return when {
            diff > 0 -> WIN_SCORE + diff
            diff < 0 -> -WIN_SCORE + diff
            else -> 0
        }
    }

    /** Positional table plus mobility, from [bot]'s perspective. */
    private fun evaluate(cells: List<Int?>, bot: Int, opponent: Int): Int {
        var score = 0
        for (index in cells.indices) {
            val owner = cells[index] ?: continue
            val weight = positionalWeight(index, cells)
            score += if (owner == bot) weight else -weight
        }
        val botMoves = legalMovesFor(cells, bot).size
        val opponentMoves = legalMovesFor(cells, opponent).size
        score += (botMoves - opponentMoves) * MOBILITY_WEIGHT
        return score
    }

    /** A C-square is dangerous only while its corner is still empty (looked up live); the rest is static. */
    private fun positionalWeight(index: Int, cells: List<Int?>): Int {
        val corner = cSquareCorner[index]
        if (corner >= 0) return if (cells[corner] == null) C_SQUARE_WEIGHT_CORNER_OPEN else C_SQUARE_WEIGHT_CORNER_TAKEN
        return fixedWeight[index]
    }

    companion object {
        private val cache = ConcurrentHashMap<Int, ReversiEngine>()

        /** The shared engine for [size]; [size] must be one of [ReversiBoardSizes.ALL]. */
        fun of(size: Int): ReversiEngine {
            require(size in ReversiBoardSizes.ALL) { "unsupported Reversi board size $size" }
            return cache.getOrPut(size) { ReversiEngine(size) }
        }

        const val CORNER_WEIGHT = 120
        const val X_SQUARE_WEIGHT = -20
        const val C_SQUARE_WEIGHT_CORNER_OPEN = -40
        const val C_SQUARE_WEIGHT_CORNER_TAKEN = 10
        const val EDGE_NEAR_CORNER_WEIGHT = 10
        const val EDGE_MID_WEIGHT = 6
        const val INTERIOR_RING_WEIGHT = 1
        const val INTERIOR_CORE_WEIGHT = 3
        const val MOBILITY_WEIGHT = 12
        const val WIN_SCORE = 1_000_000

        /** Explicit (dr, dc) pairs -- see [flanksInDirection] for why this is never a flat index delta. */
        val DIRECTIONS = listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)
    }
}

/**
 * Reversi/Othello: players alternate placing a disc on any cell that FLANKS at least one line of
 * the opponent's discs (traps them between the new disc and an existing disc of the mover's own
 * color), flipping every trapped disc to the mover's color. Standard 8x8 board and opening (see
 * [startMatch]), or 6x6 / 10x10 via [boardSize]. Reuses the established
 * `sessionWins`/`sessionDraws`/`matchOver`/`nextStartingPlayerIndex`/`leaveSession`/`playAgain`
 * session shape verbatim (see [ConnectFourGame][com.gamesuite.games.connectfour.ConnectFourGame]'s
 * own KDoc for the precedent).
 *
 * DISC IDENTITY: unlike real Othello's fixed black/white discs, this engine represents ownership
 * purely by player index (0/1); the UI layer (ReversiScreen) picks a consistent dark/light pair.
 *
 * A REAL PASS, NOT A SKIP: [buildStateAfterMove] computes the actual legal-move set for whoever's
 * up next after every placement and, when that set is empty but the player who just moved still has
 * a move, hands the turn straight back to them with `justPassed=true`.
 *
 * BOT: the rules and the alpha-beta search live in [ReversiEngine] (see its KDoc), depth scaled by
 * [reversiSearchDepth]. [playBotTurnAsync] and [requestHint] run that search OFF the main thread on a
 * copy of the position and re-check that the position is unchanged before applying the answer;
 * [playBotTurn] and [hint] are the same thing synchronously.
 */
class ReversiGame : GameModule, ResumableGame, UndoableGame {
    override val gameId = "reversi"
    override val displayName = "Reversi"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    val state = mutableStateOf<ReversiState?>(null)

    /**
     * Board edge length for the NEXT board dealt (6, 8 or 10; anything else becomes 8). Set it
     * before [init] (the Hub's "board" option); a restored save or a guest's host sync overrides it
     * with the size of the position it carries. Networked hosts deal whatever this is set to.
     */
    var boardSize: Int = ReversiBoardSizes.STANDARD
        set(value) {
            field = if (value in ReversiBoardSizes.ALL) value else ReversiBoardSizes.STANDARD
            engine = ReversiEngine.of(field)
        }

    @Volatile
    private var engine: ReversiEngine = ReversiEngine.of(ReversiBoardSizes.STANDARD)

    // ---- Networked play bookkeeping (host-authoritative; see ReversiNetMessage) ----
    /** True in LOCAL_AD_HOC / ONLINE. Safe to read before [init] (false). */
    private val isNetworked: Boolean
        get() = this::context.isInitialized &&
            (context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE)
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    private var stateVersion = com.gamesuite.core.SyncEpoch.base()
    private var lastAppliedRemoteVersion = 0

    /** Networked only: the other seat left; no further moves or rematches. Local state, never synced. */
    val opponentLeft = mutableStateOf(false)

    /** Boards won this session, keyed by playerId; a drawn board increments nobody (see
     *  [sessionDraws] instead). Mirrors ConnectFourGame's own shape exactly. */
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val sessionDraws = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via the board-over panel), not per-board. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** Computer Arena only (null otherwise): per-seat CPU levels and pausing. */
    var arena: com.gamesuite.core.ArenaHook? = null

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** Alternates who opens each new board (including across Play Again rounds), for fairness —
     *  same idiom ConnectFourGame's own Play Again uses. */
    private var nextStartingPlayerIndex = 0

    /**
     * The roster seat that opens the FIRST board of a new match (the Hub's "who goes first"). Set it
     * before [init]; [init] consumes it as the first value of [nextStartingPlayerIndex], so later
     * boards keep alternating from there. Out-of-range values fall back to seat 0. A resumed game
     * ignores it (the snapshot restores its own alternation).
     */
    var firstOpenerIndex: Int = 0

    /** Snapshots taken just BEFORE each human move, newest last; popping one is Undo. Per board. */
    private val history = SnapshotHistory()

    /** The cell the last [hint] suggested, shown ringed until the next move, undo or new board. */
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
        // A guest that registers after the host already dealt asks for the state directly.
        if (!isHost) sendToHost(ReversiNetMessage.RequestState)
        transport.onReconnected {
            if (isHost) broadcastState() else sendToHost(ReversiNetMessage.RequestState)
        }
        transport.onPlayerLeft { playerId -> handlePlayerLeft(playerId) }
        transport.onPlayerJoined { playerId -> handlePlayerJoined(playerId) }
    }

    private fun otherSeatId(): String? = context.players.getOrNull(1 - context.localPlayerIndex.coerceIn(0, 1))?.playerId

    private fun handlePlayerLeft(playerId: String) {
        if (!isNetworked || playerId != otherSeatId()) return
        opponentLeft.value = true
        val s = state.value ?: return
        // Host: broadcast so a (still connected) guest sees it too. Guest: local only, the host is gone.
        commit(s.copy(lastAction = "Opponent disconnected"))
    }

    private fun handlePlayerJoined(playerId: String) {
        if (!isNetworked || !opponentLeft.value || playerId != otherSeatId()) return
        opponentLeft.value = false
        val s = state.value ?: return
        if (isHost) {
            commit(s.copy(lastAction = "Opponent reconnected"))
        }
    }

    // --- Resume -----------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return reversiSnapshotJson.encodeToString(
            ReversiSnapshot.serializer(),
            ReversiSnapshot(
                cells = s.cells,
                players = s.players.map { ReversiSnapshotPlayer(it.playerId, it.displayName, it.isBot) },
                currentPlayerIndex = s.currentPlayerIndex,
                lastAction = s.lastAction,
                justPassed = s.justPassed,
                boardOver = s.boardOver,
                winnerPlayerId = s.winnerPlayerId,
                sessionWins = sessionWins.value,
                sessionDraws = sessionDraws.value,
                nextStartingPlayerIndex = nextStartingPlayerIndex,
                difficulty = difficulty.name,
                size = s.size
            )
        )
    }

    /** Decodes and sanity-checks a snapshot against this game's board shape and roster; null if unusable. */
    private fun decodeSnapshot(text: String): ReversiSnapshot? {
        return try {
            val snap = reversiSnapshotJson.decodeFromString(ReversiSnapshot.serializer(), text)
            val roster = context.players
            val valid = snap.size in ReversiBoardSizes.ALL &&
                snap.cells.size == snap.size * snap.size &&
                snap.players.size == 2 &&
                snap.cells.all { it == null || it in snap.players.indices } &&
                snap.players.size == roster.size &&
                snap.players.indices.all {
                    snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot
                } &&
                snap.currentPlayerIndex in snap.players.indices &&
                snap.nextStartingPlayerIndex in snap.players.indices &&
                snap.sessionDraws >= 0 &&
                snap.sessionWins.all { (id, wins) -> wins >= 0 && roster.any { it.playerId == id } } &&
                (snap.winnerPlayerId == null || snap.players.any { it.playerId == snap.winnerPlayerId }) &&
                (snap.boardOver || ReversiEngine.of(snap.size).legalMovesFor(snap.cells, snap.currentPlayerIndex).isNotEmpty())
            if (valid) snap else null
        } catch (_: Exception) {
            null
        }
    }

    private fun applySnapshot(snap: ReversiSnapshot, restoreDifficulty: Boolean) {
        boardSize = snap.size
        val rules = engine
        state.value = ReversiState(
            cells = snap.cells,
            players = snap.players.map { ReversiPlayerState(it.playerId, it.displayName, it.isBot) },
            currentPlayerIndex = snap.currentPlayerIndex,
            legalMoves = if (snap.boardOver) emptySet() else rules.legalMovesFor(snap.cells, snap.currentPlayerIndex),
            scores = computeScores(snap.cells, snap.players.size),
            lastAction = snap.lastAction,
            justPassed = snap.justPassed,
            boardOver = snap.boardOver,
            winnerPlayerId = snap.winnerPlayerId,
            size = snap.size
        )
        sessionWins.value = snap.sessionWins
        sessionDraws.value = snap.sessionDraws
        nextStartingPlayerIndex = snap.nextStartingPlayerIndex
        if (restoreDifficulty) {
            difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        }
        hintMove.value = null
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
        applySnapshot(snap, restoreDifficulty = true)
        history.clear()
        return true
    }

    // --- Undo -------------------------------------------------------------------------------

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value && !isNetworked

    /**
     * Takes back the last human move. Against the CPU that also takes back the CPU's reply (and any
     * pass that followed), since the snapshot was taken before the human's move. Undoing a
     * board-ending move re-opens the board and takes its win/draw back off the session tally.
     * Reversi has no clock and no only-goes-up counters, so everything is restored as saved; the
     * CPU difficulty is left as currently set.
     */
    override fun undo() {
        if (matchOver.value || isNetworked) return
        val previous = history.pop() ?: return
        val snap = decodeSnapshot(previous) ?: return
        applySnapshot(snap, restoreDifficulty = false)
    }

    // --- Hint -------------------------------------------------------------------------------

    /** True when a hint makes sense for [s]: a human's turn on a live, non-networked board. */
    private fun hintAllowed(s: ReversiState): Boolean {
        if (isNetworked) return false
        if (matchOver.value || s.boardOver || s.legalMoves.isEmpty()) return false
        if (s.players[s.currentPlayerIndex].isBot) return false
        return true
    }

    /**
     * Iterative deepening through [hintDepthsFor] plies, stopping early once [HINT_TIME_BUDGET_MS]
     * has been spent. Pure: reads only its arguments, so it is safe to run off the main thread.
     */
    private fun searchHint(rules: ReversiEngine, s: ReversiState): Int? {
        val started = System.nanoTime()
        var best: Int? = null
        for (depth in hintDepthsFor(rules.n)) {
            best = rules.chooseMove(s.cells, s.legalMoves, s.currentPlayerIndex, depth) ?: best
            if ((System.nanoTime() - started) / 1_000_000L > HINT_TIME_BUDGET_MS) break
        }
        return best
    }

    /**
     * Suggests a cell for the player to move: the strongest move the bot's own search can find
     * regardless of the CPU difficulty setting. Highlights it ([hintMove]) and returns it; null when
     * it is not a human's turn or the board is over. Synchronous; the screen uses [requestHint].
     */
    fun hint(): Int? {
        val s = state.value ?: return null
        if (!hintAllowed(s)) return null
        val best = searchHint(engine, s)
        hintMove.value = best
        return best
    }

    /**
     * [hint] with the search on [Dispatchers.Default]. The suggestion is applied only if the
     * position is still exactly the one that was searched (no move, undo, restart or leave in the
     * meantime); otherwise it is dropped and null is returned.
     */
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

    /**
     * Deals the standard Othello opening: the four center cells filled in the real game's own
     * pattern (indices 27/28/35/36 on the 8x8 board; the centre of any even size), with the two
     * diagonal pairs split between the two players so each starts with exactly 2 discs on the board.
     * Which player is "starting" alternates via [nextStartingPlayerIndex] the same way
     * ConnectFourGame's own startMatch() does; a fresh board is always playable regardless of whether
     * a PRIOR board's endMatch() left matchOver stuck true.
     */
    override fun startMatch() {
        if (isNetworked && !isHost) return // guest: wait for the host's StateSync (or its RequestState reply)
        val players = context.players.map { ReversiPlayerState(it.playerId, it.displayName, it.isBot) }
        matchOver.value = false
        // A new board starts a new undo history: undo never reaches back into the previous board.
        history.clear()
        hintMove.value = null

        val rules = engine
        val startingPlayerIndex = nextStartingPlayerIndex % players.size
        val cells = rules.openingCells(startingPlayerIndex) // safe: this game is always exactly 2 players

        commit(
            ReversiState(
                cells = cells,
                players = players,
                currentPlayerIndex = startingPlayerIndex,
                legalMoves = rules.legalMovesFor(cells, startingPlayerIndex),
                scores = computeScores(cells, players.size),
                lastAction = "Game started",
                size = rules.n
            )
        )
        nextStartingPlayerIndex = (nextStartingPlayerIndex + 1) % players.size
    }

    /** No solve timer here (this is a 2-player game, not a solo puzzle — same as ConnectFourGame),
     *  so there's no pause/resume state to track. */
    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Called from the board-over panel's "Back to Menu" button — ends the whole session (not
     *  just the current board), reporting the session's cumulative boards-won tally. Mirrors
     *  ConnectFourGame.leaveSession() exactly. */
    fun leaveSession() {
        if (matchOver.value) return
        val wins = sessionWins.value
        val bestWins = wins.values.maxOrNull() ?: 0
        // Only a strict leader wins the session: boards split evenly are a tie, not a win for both.
        val leaders = context.players.count { (wins[it.playerId] ?: 0) == bestWins }
        val scores = context.players.map {
            val w = wins[it.playerId] ?: 0
            PlayerScore(playerId = it.playerId, score = w, isWinner = bestWins > 0 && w == bestWins && leaders == 1)
        }
        endMatch(GameResult(scores = scores))
    }

    /** Called from the board-over panel's "Play Again" button — keeps the running session tally
     *  and deals a fresh board within the same session. Mirrors ConnectFourGame.playAgain(). */
    fun playAgain() {
        if (!com.gamesuite.core.LaunchPolicy.requestNewRound()) return
        if (matchOver.value) return
        if (isNetworked) {
            // Only a finished board with both players present can be replayed. A guest asks the host;
            // the host re-deals (the engine alternates the opener) and broadcasts.
            if (opponentLeft.value || state.value?.boardOver != true) return
            if (isHost) startMatch() else sendToHost(ReversiNetMessage.RequestPlayAgain)
            return
        }
        startMatch()
    }

    /**
     * Places the current player's disc at [index], flips every disc it flanks, and advances the
     * turn (including through a pass or into a board-over — see [buildStateAfterMove]). A total
     * no-op if the board/session is already over or [index] is not one of the current player's own
     * [ReversiState.legalMoves] (covers an out-of-range index too, since legalMoves can never
     * contain one) — the same `matchOver`-not-checked gap ConnectFourGame's own KDoc documents,
     * built in here from the start.
     */
    fun placeDisc(index: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver) return
        if (isNetworked) {
            if (opponentLeft.value || s.currentPlayerIndex != context.localPlayerIndex) return // not my turn
            if (index !in s.legalMoves) return
            if (!isHost) {
                // A guest never touches its own engine: ask the host, then wait for its StateSync.
                sendToHost(ReversiNetMessage.PlaceDisc(index))
                return
            }
        }
        applyPlacement(index)
    }

    /** The engine step: validates [index] against the current player's legal moves, applies it and
     *  (host only) broadcasts. Shared by a local tap and a validated guest request. */
    private fun applyPlacement(index: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver) return
        if (index !in s.legalMoves) return

        val mover = s.players[s.currentPlayerIndex]
        // Undo step: taken before a HUMAN move is applied (never for the bot's), after the move
        // passed validation, so a rejected tap leaves nothing behind. Networked play has no undo.
        if (!mover.isBot && !isNetworked) captureSnapshot()?.let { history.push(it) }
        hintMove.value = null
        val newCells = engine.applyMove(s.cells, index, s.currentPlayerIndex)
        val flippedCount = s.cells.indices.count { newCells[it] == s.currentPlayerIndex && s.cells[it] != null && s.cells[it] != s.currentPlayerIndex }

        val newState = buildStateAfterMove(
            cells = newCells,
            players = s.players,
            justMoved = s.currentPlayerIndex,
            actionText = "${mover.displayName} places a disc, flipping $flippedCount"
        )

        if (newState.boardOver) {
            if (newState.winnerPlayerId != null) {
                sessionWins.value = sessionWins.value + (newState.winnerPlayerId to (sessionWins.value[newState.winnerPlayerId] ?: 0) + 1)
            } else {
                sessionDraws.value += 1
            }
        }

        commit(newState)
    }

    // ---- Networked internals ----

    /** Every state change funnels through here: sets the state and, for the networked host,
     *  bumps the version and broadcasts it. A guest's own calls (disconnect notice) stay local. */
    private fun commit(newState: ReversiState) {
        state.value = newState
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState()
        }
    }

    private fun toNetState(s: ReversiState) = ReversiNetState(
        cells = s.cells,
        players = s.players.map { ReversiNetPlayer(it.playerId, it.displayName) },
        currentPlayerIndex = s.currentPlayerIndex,
        lastAction = s.lastAction,
        justPassed = s.justPassed,
        boardOver = s.boardOver,
        winnerPlayerId = s.winnerPlayerId,
        sessionWins = sessionWins.value,
        sessionDraws = sessionDraws.value,
        size = s.size
    )

    /** Host: sends the authoritative state (current version) to the guest seat. */
    private fun broadcastState() {
        val s = state.value ?: return
        val guestId = context.players.getOrNull(1)?.playerId ?: return
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val payload = Json.encodeToString(
            ReversiNetMessage.serializer(),
            ReversiNetMessage.StateSync(stateVersion, toNetState(s))
        ).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = hostId, toPlayerId = guestId, payload = payload)
    }

    private fun sendToHost(message: ReversiNetMessage) {
        val hostId = context.players.getOrNull(0)?.playerId ?: return
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = Json.encodeToString(ReversiNetMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            com.gamesuite.core.NetJson.decodeFromString(ReversiNetMessage.serializer(), String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return // malformed or foreign payload: ignore rather than crash the match
        }
        val hostId = context.players.getOrNull(0)?.playerId
        val guestId = context.players.getOrNull(1)?.playerId
        when (message) {
            // Only the host is authoritative: a StateSync from any other sender is a forgery, and the host never adopts one.
            is ReversiNetMessage.StateSync ->
                if (!isHost && fromPlayerId == hostId) applyRemoteState(message.version, message.state)
            is ReversiNetMessage.RequestState ->
                if (isHost && fromPlayerId == guestId) broadcastState()
            is ReversiNetMessage.PlaceDisc ->
                if (isHost && fromPlayerId == guestId) applyGuestPlacement(message.cell)
            is ReversiNetMessage.RequestPlayAgain ->
                if (isHost && fromPlayerId == guestId && !opponentLeft.value && state.value?.boardOver == true && !matchOver.value) {
                    startMatch()
                }
        }
    }

    /** Host: a guest move request from the (already identity-checked) guest seat. */
    private fun applyGuestPlacement(cell: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver || opponentLeft.value) return
        if (s.currentPlayerIndex != 1) return // out of turn
        if (cell !in s.legalMoves) return // illegal, occupied or out of range
        applyPlacement(cell)
    }

    /** Guest: adopts a strictly newer, well-formed host state (including a valid board size). */
    private fun applyRemoteState(version: Int, net: ReversiNetState) {
        if (version <= lastAppliedRemoteVersion) return
        val roster = context.players
        val valid = net.size in ReversiBoardSizes.ALL &&
            net.cells.size == net.size * net.size &&
            net.cells.all { it == null || it == 0 || it == 1 } &&
            net.players.size == 2 && roster.size == 2 &&
            net.players.indices.all { net.players[it].playerId == roster[it].playerId } &&
            net.currentPlayerIndex in 0..1 &&
            (net.winnerPlayerId == null || net.players.any { it.playerId == net.winnerPlayerId })
        if (!valid) return
        if (!com.gamesuite.core.sessionTalliesValid(net.sessionWins, net.sessionDraws, context.players)) return
        lastAppliedRemoteVersion = version
        boardSize = net.size
        val rules = engine
        state.value = ReversiState(
            cells = net.cells,
            players = net.players.mapIndexed { i, p -> ReversiPlayerState(p.playerId, p.displayName, roster[i].isBot) },
            currentPlayerIndex = net.currentPlayerIndex,
            legalMoves = if (net.boardOver) emptySet() else rules.legalMovesFor(net.cells, net.currentPlayerIndex),
            scores = computeScores(net.cells, 2),
            lastAction = net.lastAction,
            justPassed = net.justPassed,
            boardOver = net.boardOver,
            winnerPlayerId = net.winnerPlayerId,
            size = net.size
        )
        sessionWins.value = net.sessionWins
        sessionDraws.value = net.sessionDraws
        hintMove.value = null
    }

    /** True when the bot should move in [s]: a live board whose player to move is a bot. */
    private fun botToMove(s: ReversiState): Boolean {
        if (matchOver.value || s.boardOver) return false
        return s.players[s.currentPlayerIndex].isBot
    }

    /**
     * Bounded bot: choose one legal cell and play it, synchronously. No-op if the board/match is
     * already over or the current player isn't actually a bot, so it's safe to call speculatively —
     * same contract as ConnectFourGame.playBotTurn(). The screen uses [playBotTurnAsync].
     */
    fun playBotTurn() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val rules = engine
        val index = rules.chooseMove(s.cells, s.legalMoves, s.currentPlayerIndex, reversiSearchDepth(rules.n, difficulty)) ?: return
        placeDisc(index)
    }

    /**
     * [playBotTurn] with the search on [Dispatchers.Default], capped at about [BOT_TIME_CAP_MS] per
     * move. The move is applied only if the position is still exactly the one that was searched and
     * the session is live; if the player undid, restarted or left meanwhile, the answer is dropped.
     */
    suspend fun playBotTurnAsync() {
        val s = state.value ?: return
        if (!botToMove(s)) return
        val rules = engine
        val depth = reversiSearchDepth(rules.n, arena?.levelFor(s.currentPlayerIndex, difficulty) ?: difficulty)
        val deadline = System.nanoTime() + BOT_TIME_CAP_MS * 1_000_000L
        val index = withContext(Dispatchers.Default) {
            rules.chooseMove(s.cells, s.legalMoves, s.currentPlayerIndex, depth, deadline)
        } ?: return
        if (state.value !== s || matchOver.value) return
        if (arena?.deferIfPaused { if (state.value === s && !matchOver.value) placeDisc(index) } == true) return
        placeDisc(index)
    }

    /** Disc count per player index, live for the whole board (not just at game end). */
    private fun computeScores(cells: List<Int?>, playerCount: Int): List<Int> =
        (0 until playerCount).map { p -> cells.count { it == p } }

    /**
     * Resolves what happens after [justMoved] places a disc on the already-updated [cells]:
     * computes the OPPONENT's legal moves on the new board first.
     * - If non-empty: turn passes to the opponent normally, [ReversiState.legalMoves] is the
     *   opponent's own move set, [actionText] is used as-is.
     * - Else if [justMoved] STILL has a legal move: the opponent is skipped entirely -- turn passes
     *   BACK to [justMoved] with `justPassed=true` and a dedicated "so-and-so passes" message (a
     *   real, correctly-implemented pass per official Othello rules: the passing player literally
     *   cannot move, this is never silently skipped with no feedback).
     * - Else (NEITHER player has a legal move): `boardOver=true`, winner decided by strictly more
     *   discs (an equal count is a tie, `winnerPlayerId=null`), with a dedicated final-score message.
     */
    private fun buildStateAfterMove(
        cells: List<Int?>,
        players: List<ReversiPlayerState>,
        justMoved: Int,
        actionText: String
    ): ReversiState {
        val rules = engine
        val opponent = 1 - justMoved
        val scores = computeScores(cells, players.size)

        val opponentMoves = rules.legalMovesFor(cells, opponent)
        if (opponentMoves.isNotEmpty()) {
            return ReversiState(
                cells = cells,
                players = players,
                currentPlayerIndex = opponent,
                legalMoves = opponentMoves,
                scores = scores,
                lastAction = actionText,
                size = rules.n
            )
        }

        val moverMoves = rules.legalMovesFor(cells, justMoved)
        if (moverMoves.isNotEmpty()) {
            return ReversiState(
                cells = cells,
                players = players,
                currentPlayerIndex = justMoved,
                legalMoves = moverMoves,
                scores = scores,
                lastAction = "${players[opponent].displayName} has no legal move and passes",
                justPassed = true,
                size = rules.n
            )
        }

        val winnerId = when {
            scores[justMoved] > scores[opponent] -> players[justMoved].playerId
            scores[opponent] > scores[justMoved] -> players[opponent].playerId
            else -> null
        }
        return ReversiState(
            cells = cells,
            players = players,
            currentPlayerIndex = justMoved,
            legalMoves = emptySet(),
            scores = scores,
            lastAction = if (winnerId != null) {
                "${players.first { it.playerId == winnerId }.displayName} wins ${scores[0]}-${scores[1]}!"
            } else {
                "It's a tie, ${scores[0]}-${scores[1]}!"
            },
            boardOver = true,
            winnerPlayerId = winnerId,
            size = rules.n
        )
    }

    private companion object {
        /** Hint search: iterative deepening through these plies, abandoning deeper passes once the
         *  budget in ms is spent. The 10x10 board stops at 4 for the same reason its bot does. */
        fun hintDepthsFor(size: Int): List<Int> =
            if (size >= ReversiBoardSizes.LARGE) listOf(2, 3, 4) else listOf(2, 4, 6)

        const val HINT_TIME_BUDGET_MS = 400L

        /** After this long into a bot move the remaining root candidates are skipped. */
        const val BOT_TIME_CAP_MS = 1_500L
    }
}
