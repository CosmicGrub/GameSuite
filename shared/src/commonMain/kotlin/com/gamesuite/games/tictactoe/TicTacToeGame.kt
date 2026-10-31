package com.gamesuite.games.tictactoe

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Everything needed to put a local Tic-Tac-Toe session back exactly as it was: the board, whose
 * turn it is (and the Wild symbol picked so far), the round state, the session tally, who opens
 * the next round, the CPU difficulty and the rule variant. One shape serves Resume (on disk) and
 * Undo (in memory).
 */
@Serializable
private data class TicTacToeSnapshot(
    val board: List<Int>,
    val currentPlayer: Int,
    val selectedSymbol: Int,
    val roundOver: Boolean,
    val winningLine: List<Int>?,
    val scoreP1: Int,
    val scoreP2: Int,
    val draws: Int,
    val roundNumber: Int,
    val startingPlayer: Int,
    val difficulty: String,
    val misere: Boolean,
    val wild: Boolean,
    /** Board edge length (3, 4 or 5). Absent in saves from before board sizes existed, which were all 3x3. */
    val size: Int = 3,
    val players: List<TicTacToeSnapshotPlayer>
)

@Serializable
private data class TicTacToeSnapshotPlayer(val playerId: String, val isBot: Boolean)

private val ticTacToeSnapshotJson = Json { ignoreUnknownKeys = true }

/**
 * First proof-of-concept game module, since upgraded with a real research
 * pass (see README item 9a): a perfect minimax bot backing HARD difficulty,
 * difficulty scaling down from it, a winning-line callout, and session-long
 * score tracking across rounds (standard tic-tac-toe is a single fixed
 * board with nothing at stake round-to-round otherwise — a running score is
 * what gives repeated play a reason to continue, mirroring the "matches are
 * a stream of rounds" shape UNO already has). Pure logic, exposed as
 * Compose state so a TicTacToeScreen composable can render it reactively.
 *
 * Variants pass (README item 13s) added [misere]: identical rules except
 * completing 3-in-a-row *loses* for whoever completed it. Every place the
 * standard rules treat "line completed" as "mover wins" is mirrored to
 * treat it as "mover loses" instead when the flag is set — the human-move
 * scoring in [cellClicked], the minimax terminal scoring in [minimax], and
 * the MEDIUM/EASY heuristic's win-seeking in [heuristicMove]. The board
 * stays a fixed 3x3 (WIN_LINES unchanged) — only the win/loss meaning flips.
 *
 * A second, independent variant added alongside it: [wild]. Standard rules
 * bake "player 1 always places X, player 2 always places O" into the board
 * itself — a cell's stored value doubles as both "who played here" and
 * "what symbol is shown". Wild Tic-Tac-Toe breaks that: each mover picks
 * either symbol on their turn (see [selectedSymbol]/[chooseSymbol] for the
 * human, [availableSymbols] for the bot), so a cell's content stops
 * identifying its owner. [findWinningLine] already only compares cell
 * *content*, never player identity, so it needed no change at all — the
 * pieces that assumed content-equals-mover do: [cellClicked] now reads the
 * placed symbol from [selectedSymbol] instead of [currentPlayer], and
 * [minimax]'s terminal scoring keys off turn order (who *moved*) rather
 * than the symbol a line is made of, since under [wild] those two can
 * disagree. [misere] and [wild] compose freely — see [heuristicMove] and
 * [minimax] for how the two flags combine.
 */
class TicTacToeGame : GameModule, ResumableGame, UndoableGame {
    override val gameId = "tic-tac-toe"
    override val displayName = "Tic-Tac-Toe"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        // Real networked play (docs/ENGINE_DECISION.md Action Item 8's follow-up on wiring
        // the LAN transport into an actual playable session) -- see this class's own
        // isNetworked/isHost and TicTacToeNetMessage.kt's KDoc for the host-authoritative
        // design this mirrors from UnoGame.kt's own already-proven real-networked-play
        // pattern. ONLINE included too since the mechanism (host-authoritative Intent/
        // StateSync over any MultiplayerTransport) is transport-agnostic -- nothing here
        // is LAN-specific.
        PlayMode.LOCAL_AD_HOC,
        PlayMode.ONLINE
    )

    // 0 = empty, 1 = P1, 2 = P2 — Compose state so UI recomposes on change. Row-major, boardDim x boardDim.
    val board = mutableStateOf(IntArray(9))

    /**
     * Board edge length in cells: 3 (three in a row), 4 (four in a row) or 5 (four in a row). Set by
     * [init] from [boardSize]; a restored save may change it (see [restore]). Misere, Wild and every
     * networked game are always 3.
     */
    val boardDim = mutableStateOf(3)

    /**
     * Pre-set by the UI from the Hub's `board` choice before [init] (which reads it): 3, 4 or 5.
     * Ignored (treated as 3) under [misere], under [wild] and in networked play, and for any other value.
     */
    var boardSize: Int = 3
    val currentPlayer = mutableStateOf(1)

    /** True once the current board has a winner or is full — distinct from [matchOver] below. */
    val roundOver = mutableStateOf(false)

    /** The 3 winning cell indices, so the UI can highlight them. Null while in progress or on a draw. */
    val winningLine = mutableStateOf<List<Int>?>(null)

    val scoreP1 = mutableStateOf(0)
    val scoreP2 = mutableStateOf(0)
    val draws = mutableStateOf(0)
    val roundNumber = mutableStateOf(1)

    /** True only once the whole session ends (user leaves via the round-over panel), not per-round. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** Pre-set by the UI (see TicTacToeScreen's `misere` param) before startMatch(). Reverse-win rules: false = standard. */
    var misere: Boolean = false

    /** Pre-set by the UI (see TicTacToeScreen's `wild` param) before startMatch(). Each mover picks X or O per turn: false = standard (symbol fixed per player). */
    var wild: Boolean = false

    /**
     * Which symbol (1=X, 2=O) the next [cellClicked] call will place, under
     * [wild] — meaningless under standard rules, where the symbol is always
     * [currentPlayer]'s own number. Defaults to X at the start of every turn;
     * the UI shows a toggle so the human can switch it before tapping a cell,
     * and [playBotTurn] sets it itself so bot and human moves share the same
     * [cellClicked] path regardless of who's placing.
     */
    val selectedSymbol = mutableStateOf(1)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** Alternates who opens each round, for fairness — reset to 1 at the start of a fresh session. */
    private var startingPlayer = 1

    /**
     * Roster seat (0 = Player 1 / X, 1 = Player 2 / O) that opens the FIRST round. Pre-set by the UI
     * from the Hub's first-mover choice after [init] and before [startMatch], which consumes it.
     * Later rounds alternate as before. Symbols stay tied to seats (seat 0 is always X), so a
     * CPU-first game simply has the CPU (O) move first. A resumed game ignores it. Out-of-range
     * values are treated as seat 0.
     */
    var firstOpenerIndex: Int = 0

    /** True for real networked play (LAN or online) -- see TicTacToeNetMessage.kt's own
     *  KDoc for the host-authoritative design this gates. False (the default) for
     *  SINGLE_DEVICE_PASS_AND_PLAY/SINGLE_PLAYER_VS_BOT, where every mutation below applies
     *  directly with no network involvement at all, exactly as it always has. */
    private val isNetworked: Boolean
        get() = context.activeMode == PlayMode.LOCAL_AD_HOC || context.activeMode == PlayMode.ONLINE

    /** The host always sits at [GameContext.localPlayerIndex] == 0, by the same lobby
     *  convention UnoGame.kt's own isHost relies on. Meaningless (never read) unless
     *  [isNetworked]. */
    private val isHost: Boolean
        get() = context.localPlayerIndex == 0

    /** Host only: bumped on every broadcast [applyCellClicked]/[applyPlayAgain] triggers --
     *  see [TicTacToeNetMessage.StateSync]'s own KDoc for why this exists. */
    private var stateVersion = 0

    /** Guest only: the last [TicTacToeNetMessage.StateSync.version] actually applied, so a
     *  stray out-of-order delivery can never move state backward. */
    private var lastAppliedStateVersion = -1

    /** Networked play only: true once the other seat's player has left (the transport's
     *  onPlayerLeft). Input and Play Again are inert from then on, and [leaveSession] ends the
     *  match as an abort so the remaining player is never charged a loss. */
    val opponentLeft = mutableStateOf(false)

    /** Snapshots taken just BEFORE each human move, newest last; popping one is Undo. Per round. */
    private val history = SnapshotHistory()

    /** The (cell, symbol) the last [hint] suggested, shown until the next move, undo or new round. Symbol is 1=X, 2=O. */
    val hintMove = mutableStateOf<Pair<Int, Int>?>(null)

    /** True once [startMatch] or a successful [restore] has dealt a board; transient (never saved). */
    private var started = false

    override fun init(context: GameContext) {
        this.context = context
        started = false
        history.clear()
        hintMove.value = null
        boardDim.value = resolveBoardDim()
        board.value = IntArray(boardDim.value * boardDim.value)
        currentPlayer.value = 1
        selectedSymbol.value = 1
        startingPlayer = 1
        roundOver.value = false
        winningLine.value = null
        scoreP1.value = 0
        scoreP2.value = 0
        draws.value = 0
        roundNumber.value = 1
        matchOver.value = false
        stateVersion = 0
        lastAppliedStateVersion = -1
        opponentLeft.value = false

        if (isNetworked) {
            context.transport.onMessageReceived { fromPlayerId, payload -> handleNetworkMessage(fromPlayerId, payload) }
            // Covers the same startup race TicTacToeNetMessage.RequestState's own KDoc
            // documents: this guest's listener above might register after the host has
            // already broadcast (or will broadcast before this guest is ready to receive).
            if (!isHost) sendToHost(TicTacToeNetMessage.RequestState)
            // After a blip the transport recovered from: a guest asks again, the host
            // proactively re-broadcasts (a guest whose own link never dropped would otherwise
            // never think to ask).
            context.transport.onReconnected {
                if (isHost) {
                    broadcastState()
                } else {
                    sendToHost(TicTacToeNetMessage.RequestState)
                }
            }
            context.transport.onPlayerLeft { playerId -> handlePlayerLeft(playerId) }
            context.transport.onPlayerJoined { playerId -> handlePlayerJoined(playerId) }
        }
    }

    // --- Resume -----------------------------------------------------------------------------

    private fun captureSnapshot(): String = ticTacToeSnapshotJson.encodeToString(
        TicTacToeSnapshot.serializer(),
        TicTacToeSnapshot(
            board = board.value.toList(),
            currentPlayer = currentPlayer.value,
            selectedSymbol = selectedSymbol.value,
            roundOver = roundOver.value,
            winningLine = winningLine.value,
            scoreP1 = scoreP1.value,
            scoreP2 = scoreP2.value,
            draws = draws.value,
            roundNumber = roundNumber.value,
            startingPlayer = startingPlayer,
            difficulty = difficulty.name,
            misere = misere,
            wild = wild,
            size = boardDim.value,
            players = context.players.map { TicTacToeSnapshotPlayer(it.playerId, it.isBot) }
        )
    )

    /** Decodes and sanity-checks a snapshot against THIS engine's variant and roster; null if unusable. */
    private fun decodeSnapshot(text: String): TicTacToeSnapshot? {
        val snap = try {
            ticTacToeSnapshotJson.decodeFromString(TicTacToeSnapshot.serializer(), text)
        } catch (_: Exception) {
            return null
        }
        val roster = context.players
        val line = snap.winningLine
        // A save may carry a different board size than this engine was started with (the Hub
        // choice is remembered separately from the save): that is fine for classic rules, where
        // the save's own size wins; Misere and Wild saves are always 3x3.
        val dim = snap.size
        val cellCount = dim * dim
        val sizeOk = dim in 3..5 && (dim == 3 || (!misere && !wild))
        val valid = sizeOk && snap.misere == misere && snap.wild == wild &&
            snap.board.size == cellCount && snap.board.all { it in 0..2 } &&
            snap.currentPlayer in 1..2 && snap.selectedSymbol in 1..2 && snap.startingPlayer in 1..2 &&
            snap.scoreP1 >= 0 && snap.scoreP2 >= 0 && snap.draws >= 0 && snap.roundNumber >= 1 &&
            snap.players.size == roster.size &&
            snap.players.indices.all { snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot } &&
            (line == null || (snap.roundOver && line.size == winLength(dim) && line.all { it in 0 until cellCount && snap.board[it] != 0 }))
        return if (valid) snap else null
    }

    private fun applySnapshot(snap: TicTacToeSnapshot) {
        boardDim.value = snap.size
        board.value = snap.board.toIntArray()
        currentPlayer.value = snap.currentPlayer
        selectedSymbol.value = snap.selectedSymbol
        roundOver.value = snap.roundOver
        winningLine.value = snap.winningLine
        scoreP1.value = snap.scoreP1
        scoreP2.value = snap.scoreP2
        draws.value = snap.draws
        roundNumber.value = snap.roundNumber
        startingPlayer = snap.startingPlayer
        difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        hintMove.value = null
    }

    /** Null when there is nothing to save: no board yet, the session already ended, or a mode whose state lives with other players. */
    override fun snapshot(): String? = try {
        if (!started || matchOver.value) {
            null
        } else if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) {
            null
        } else {
            captureSnapshot()
        }
    } catch (_: Exception) {
        null
    }

    override fun restore(snapshot: String): Boolean = try {
        val mode = context.activeMode
        val snap = if (mode == PlayMode.SINGLE_PLAYER_VS_BOT || mode == PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) {
            decodeSnapshot(snapshot)
        } else {
            null
        }
        if (snap == null) {
            false
        } else {
            matchOver.value = false
            applySnapshot(snap)
            history.clear()
            started = true
            true
        }
    } catch (_: Exception) {
        false
    }

    // --- Undo -------------------------------------------------------------------------------

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value

    /**
     * Takes back the last human move. Against the CPU that also takes back the CPU's reply (the
     * snapshot was taken before the human's move), so you are back on your own turn. Undoing a
     * line-completing move re-opens the round and takes its point back off the tally. Tic-Tac-Toe
     * has no mistake/hint counters or clock, so nothing needs to stay "spent".
     */
    override fun undo() {
        if (matchOver.value) return
        val previous = history.pop() ?: return
        val snap = decodeSnapshot(previous) ?: return
        applySnapshot(snap)
    }

    // --- Hint -------------------------------------------------------------------------------

    /**
     * Suggests the (cell, symbol) the perfect minimax search would play for the player to move,
     * regardless of the CPU difficulty setting (symbol is only meaningful under [wild]; otherwise
     * it is the mover's own mark). Exposed as [hintMove] until the next move, undo or new round.
     * Null when it is not a local human's turn, the round is over, or in network play.
     */
    fun hint(): Pair<Int, Int>? {
        val request = beginHint() ?: return null
        return finishHint(request, searchHint(request))
    }

    /**
     * Step 1 of an off-thread hint (the screen runs the search on Dispatchers.Default so a 4x4 or
     * 5x5 board never freezes the UI): captures the position on the calling (main) thread, or null
     * under the same conditions [hint] returns null.
     */
    fun beginHint(): TicTacToeSearchRequest? {
        if (!started || matchOver.value || roundOver.value || isNetworked) return null
        if (context.players.getOrNull(currentPlayer.value - 1)?.isBot != false) return null
        return TicTacToeSearchRequest(board.value.copyOf(), currentPlayer.value, roundNumber.value)
    }

    /** Step 2: the pure search; safe on any thread. It only reads the request and the rule flags. */
    fun searchHint(request: TicTacToeSearchRequest): Pair<Int, Int>? = searchMove(request.board.copyOf(), request.player)

    /**
     * Step 3, back on the main thread: publishes [move] as [hintMove] and returns it, unless the
     * position changed while the search ran (a move, an undo, a new round), in which case it is dropped.
     */
    fun finishHint(request: TicTacToeSearchRequest, move: Pair<Int, Int>?): Pair<Int, Int>? {
        if (!isStillCurrent(request)) return null
        hintMove.value = move
        return move
    }

    /** True while the position the [request] was taken from is exactly the position on the board now. */
    private fun isStillCurrent(request: TicTacToeSearchRequest): Boolean =
        !roundOver.value && !matchOver.value &&
            currentPlayer.value == request.player &&
            roundNumber.value == request.roundNumber &&
            board.value.contentEquals(request.board)

    /** Perfect minimax on 3x3 (any variant); depth-limited alpha-beta with a line-count heuristic on 4x4 / 5x5. */
    private fun searchMove(b: IntArray, player: Int): Pair<Int, Int>? {
        val dim = dimOf(b.size)
        if (dim == 3) return minimaxBestMove(b, player)
        val cell = TicTacToeLineSearch(dim, linesOf(dim), winLength(dim), SEARCH_NODE_BUDGET).bestMove(b, player) ?: return null
        return cell to player
    }

    override fun startMatch() {
        started = true
        history.clear()
        hintMove.value = null
        // The named seat opens round 1 (alternation in applyPlayAgain continues from it).
        if (roundNumber.value == 1 && !roundOver.value && board.value.all { it == 0 }) {
            // Networked: the host (X) always opens round 1; there is no first-mover choice.
            startingPlayer = if (firstOpenerIndex == 1 && !isNetworked) 2 else 1
            currentPlayer.value = startingPlayer
            selectedSymbol.value = 1
        }
        // Nothing extra needed for local pass-and-play; the bot's moves are
        // driven from the UI via playBotTurn() when it becomes its turn —
        // see TicTacToeScreen's LaunchedEffect, mirroring MancalaScreen.
        //
        // Networked: the host's init() already built a real (deterministic, empty) board,
        // but a guest that joined late enough to miss init()'s own RequestState round-trip
        // (or one that requested before the host had actually reached startMatch()) still
        // needs a real broadcast to converge on -- harmless to also send this to an
        // already-converged guest, since applying an identical state is a no-op.
        if (isNetworked && isHost) broadcastState()
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /**
     * Idempotent: once the match has ended (leaving via [leaveSession], an earlier abort, or a
     * host-broadcast `matchOver`), a second abort -- a double-tapped "Leave", or back pressed
     * while the first leave is still propagating -- must not fire [onMatchEnd] a second time,
     * since that would run the shell's navigation/session teardown twice.
     */
    override fun abortMatch() {
        if (matchOver.value) return
        endMatch(GameResult(wasAborted = true))
    }

    /** Called when the shell wants to know when this match ends, e.g. to navigate back. */
    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    /**
     * Called from the round-over panel's "Back to Menu" button — ends the whole
     * session (not just the current round), reporting the cumulative score.
     */
    fun leaveSession() {
        if (matchOver.value) return
        // The other player vanished: nobody can be charged a win or a loss for that.
        if (isNetworked && opponentLeft.value) {
            endMatch(GameResult(wasAborted = true))
            return
        }
        val scores = mutableListOf<PlayerScore>()
        context.players.getOrNull(0)?.let {
            scores += PlayerScore(playerId = it.playerId, score = scoreP1.value, isWinner = scoreP1.value > scoreP2.value)
        }
        context.players.getOrNull(1)?.let {
            scores += PlayerScore(playerId = it.playerId, score = scoreP2.value, isWinner = scoreP2.value > scoreP1.value)
        }
        endMatch(GameResult(scores = scores))
    }

    /** Called from the round-over panel's "Play Again" button — keeps the running score, resets the board.
     *  Networked + not host: forwarded to the host as an [TicTacToeIntentPayload.PlayAgain]
     *  intent instead of applied locally, same reasoning as [cellClicked]. */
    fun playAgain() {
        // After the match has ended there is no next round; without this a networked guest
        // would still forward a PlayAgain intent to the host.
        if (matchOver.value) return
        if (isNetworked) {
            // Nothing to re-deal while the round is running, or with nobody left to play.
            if (opponentLeft.value || !roundOver.value) return
            if (!isHost) {
                sendToHost(TicTacToeIntentPayload.PlayAgain)
                return
            }
        }
        applyPlayAgain()
    }

    private fun applyPlayAgain() {
        if (matchOver.value) return
        history.clear()
        hintMove.value = null
        startingPlayer = if (startingPlayer == 1) 2 else 1
        roundNumber.value += 1
        board.value = IntArray(boardDim.value * boardDim.value)
        winningLine.value = null
        roundOver.value = false
        currentPlayer.value = startingPlayer
        selectedSymbol.value = 1
        if (isNetworked && isHost) broadcastState()
    }

    // ---- Networked play (see TicTacToeNetMessage.kt's own KDoc for the host-authoritative
    // design) -- everything below this point is only ever exercised when [isNetworked]. ----

    /** Host only: bundles the current visible state into a [TicTacToeNetMessage.StateSync]
     *  and broadcasts it -- called after every host-side mutation ([applyCellClicked],
     *  [applyPlayAgain]) and once from [startMatch]. */
    private fun broadcastState(toPlayerId: String? = null) {
        stateVersion++
        val snapshot = TicTacToeNetState(
            board = board.value.toList(),
            currentPlayer = currentPlayer.value,
            roundOver = roundOver.value,
            winningLine = winningLine.value,
            scoreP1 = scoreP1.value,
            scoreP2 = scoreP2.value,
            draws = draws.value,
            roundNumber = roundNumber.value,
            matchOver = matchOver.value,
            selectedSymbol = selectedSymbol.value,
            size = boardDim.value
        )
        sendMessage(TicTacToeNetMessage.StateSync(stateVersion, snapshot), toPlayerId = toPlayerId)
    }

    /** Guest only: replaces every visible field with the host's own values -- the guest
     *  never computes any of this itself, only ever displays the last [StateSync] it has,
     *  same as UnoGame's own non-host devices. */
    private fun applyNetState(state: TicTacToeNetState) {
        board.value = state.board.toIntArray()
        currentPlayer.value = state.currentPlayer
        roundOver.value = state.roundOver
        winningLine.value = state.winningLine
        scoreP1.value = state.scoreP1
        scoreP2.value = state.scoreP2
        draws.value = state.draws
        roundNumber.value = state.roundNumber
        matchOver.value = state.matchOver
        selectedSymbol.value = state.selectedSymbol
    }

    /** Non-host only: sends [intent] to whichever player is at [GameContext.players] index 0
     *  -- the host, by the same lobby convention [isHost] itself relies on. */
    private fun sendToHost(intent: TicTacToeIntentPayload) {
        sendMessage(TicTacToeNetMessage.Intent(intent), toPlayerId = context.players.getOrNull(0)?.playerId)
    }

    /** Non-host only: same as [sendToHost] but for [TicTacToeNetMessage.RequestState], which
     *  isn't wrapped in an [TicTacToeNetMessage.Intent] (it's a lobby/sync concern, not a
     *  game move) -- mirrors [TicTacToeNetMessage.RequestState] itself being a top-level
     *  variant rather than an intent payload. */
    private fun sendToHost(message: TicTacToeNetMessage) {
        sendMessage(message, toPlayerId = context.players.getOrNull(0)?.playerId)
    }

    /** [toPlayerId] null broadcasts to every other connected player (see
     *  [com.gamesuite.transport.MultiplayerTransport.send]'s own KDoc) -- correct either way
     *  for this game's fixed 2-player cap, where "everyone else" is exactly one recipient. */
    private fun sendMessage(message: TicTacToeNetMessage, toPlayerId: String?) {
        val localPlayerId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        val payload = Json.encodeToString(message).encodeToByteArray()
        context.transport.send(fromPlayerId = localPlayerId, toPlayerId = toPlayerId, payload = payload)
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = runCatching { Json.decodeFromString<TicTacToeNetMessage>(payload.decodeToString()) }.getOrNull() ?: return
        when (message) {
            is TicTacToeNetMessage.StateSync -> {
                // The host is always authoritative over its own state -- an inbound
                // StateSync would only ever arrive here due to a bug or a malicious peer,
                // never as part of this protocol's own intended flow.
                if (isHost) return
                // Game state is accepted only from the host seat (players[0]).
                if (fromPlayerId != context.players.getOrNull(0)?.playerId) return
                if (matchOver.value) return
                if (message.version <= lastAppliedStateVersion) return
                if (!isPlausibleNetState(message.state)) return
                lastAppliedStateVersion = message.version
                applyNetState(message.state)
            }
            is TicTacToeNetMessage.Intent -> {
                if (!isHost) return // only the host ever applies a peer's intent
                if (!started || matchOver.value || opponentLeft.value) return
                val senderIndex = context.players.indexOfFirst { it.playerId == fromPlayerId }
                if (senderIndex == -1) return // unknown sender -- ignore rather than trust a bare claimed identity
                if (senderIndex == context.localPlayerIndex) return // the host's own seat never arrives over the wire
                when (val intent = message.intent) {
                    is TicTacToeIntentPayload.CellClicked -> {
                        // Not this sender's actual turn -- ignore rather than trust the
                        // intent's own cell index blindly. applyCellClicked's own
                        // roundOver/matchOver/occupied-cell guards still apply on top of
                        // this, exactly as they do for a local tap.
                        if (senderIndex != currentPlayer.value - 1) return
                        applyCellClicked(intent.index)
                    }
                    TicTacToeIntentPayload.PlayAgain -> {
                        // Either player may ask, but only once the round is over; a duplicate
                        // (both tapped) arrives after the re-deal and is dropped here.
                        if (!roundOver.value) return
                        applyPlayAgain()
                    }
                }
            }
            TicTacToeNetMessage.RequestState -> {
                if (!isHost) return
                val askerIndex = context.players.indexOfFirst { it.playerId == fromPlayerId }
                if (askerIndex == -1 || askerIndex == context.localPlayerIndex) return
                // Straight back to the asker, not a broadcast: no need to bump every device.
                broadcastState(toPlayerId = fromPlayerId)
            }
        }
    }

    /** Sanity check on an inbound state so a corrupt message can't wedge the guest's board. */
    private fun isPlausibleNetState(s: TicTacToeNetState): Boolean {
        val line = s.winningLine
        // Networked games are always 3x3; a state describing any other size is not ours.
        return s.size == boardDim.value && s.board.size == boardDim.value * boardDim.value && s.board.all { it in 0..2 } &&
            s.currentPlayer in 1..2 && s.selectedSymbol in 1..2 &&
            s.scoreP1 >= 0 && s.scoreP2 >= 0 && s.draws >= 0 && s.roundNumber >= 1 &&
            (line == null || (s.roundOver && line.size == winLength(boardDim.value) && line.all { it in 0 until s.board.size }))
    }

    private fun handlePlayerLeft(playerId: String) {
        val index = context.players.indexOfFirst { it.playerId == playerId }
        if (index == -1 || index == context.localPlayerIndex || matchOver.value) return
        opponentLeft.value = true
    }

    private fun handlePlayerJoined(playerId: String) {
        val index = context.players.indexOfFirst { it.playerId == playerId }
        if (index == -1 || index == context.localPlayerIndex || matchOver.value) return
        opponentLeft.value = false
        if (isHost) broadcastState()
    }

    /**
     * Called from the UI's X/O toggle under [wild], before the player taps a
     * cell — see [selectedSymbol]. No-op under standard rules since the UI
     * doesn't show the toggle then, but harmless either way since standard
     * [cellClicked] never reads [selectedSymbol].
     */
    fun chooseSymbol(symbol: Int) {
        if (matchOver.value) return
        selectedSymbol.value = symbol
    }

    /** Call this from the UI when a cell is tapped.
     *  Networked + not host: forwarded to the host as a [TicTacToeIntentPayload.CellClicked]
     *  intent instead of applied locally -- the host validates and applies it, then
     *  broadcasts the result back (see [handleNetworkMessage]/[applyCellClicked]). */
    fun cellClicked(index: Int) {
        // Checked before the networked-guest forward below so a finished match never sends an
        // intent to the host (applyCellClicked has its own guard for the local/host path).
        if (matchOver.value) return
        if (isNetworked) {
            if (opponentLeft.value) return
            if (!isHost) {
                sendToHost(TicTacToeIntentPayload.CellClicked(index))
                return
            }
            // The host owns seat 0 (X) only: a tap on the guest's turn must not place the
            // guest's mark. (Guest moves reach applyCellClicked via handleNetworkMessage.)
            if (currentPlayer.value != 1) return
        }
        applyCellClicked(index)
    }

    /** The real move logic -- called directly for local play (pass-and-play/vs-bot), by
     *  [cellClicked] when this instance IS the host (its own local tap), and by
     *  [handleNetworkMessage] when the host applies a validated guest intent. */
    private fun applyCellClicked(index: Int) {
        if (index !in board.value.indices || roundOver.value || matchOver.value || board.value[index] != 0) return

        // Undo only ever lands on a HUMAN's turn: remember the position before each local human
        // move (not the bot's, not network play's), so undoing against the CPU also takes the
        // CPU's reply back. A rejected tap returned above and leaves no step.
        if (!isNetworked && context.players.getOrNull(currentPlayer.value - 1)?.isBot != true) {
            history.push(captureSnapshot())
        }
        hintMove.value = null

        val newBoard = board.value.copyOf()
        // Standard: the cell records the mover's own fixed symbol. Wild: the
        // mover chose a symbol independently of who they are — see the
        // class KDoc's [wild] paragraph — so it comes from [selectedSymbol].
        // Networked play is always the classic rules (seat 0 = X, seat 1 = O), whatever the
        // variant flags say: the guest never learns them.
        val classicOnly = isNetworked
        newBoard[index] = if (wild && !classicOnly) selectedSymbol.value else currentPlayer.value
        board.value = newBoard

        val line = findWinningLine(newBoard)
        if (line != null) {
            winningLine.value = line
            // Standard: the mover just completed a line, so the mover scores.
            // Misere: completing a line LOSES, so the point goes to whoever
            // didn't complete it — currentPlayer.value is still the mover here,
            // cellClicked() returns before it ever flips to the other player.
            // Wild doesn't change any of this: it only changes which symbol
            // ends up in the cell, never who gets credit/blame for placing it.
            val loserIsMover = misere && !classicOnly
            val scorerIsP1 = if (loserIsMover) currentPlayer.value != 1 else currentPlayer.value == 1
            if (scorerIsP1) scoreP1.value += 1 else scoreP2.value += 1
            roundOver.value = true
            if (isNetworked && isHost) broadcastState()
            return
        }
        if (isBoardFull(newBoard)) {
            draws.value += 1
            roundOver.value = true
            if (isNetworked && isHost) broadcastState()
            return
        }

        currentPlayer.value = if (currentPlayer.value == 1) 2 else 1
        selectedSymbol.value = 1
        if (isNetworked && isHost) broadcastState()
    }

    /**
     * Call this from the UI when it becomes a bot's turn (see TicTacToeScreen's
     * LaunchedEffect keyed on currentPlayer, mirroring MancalaScreen.playBotTurn()).
     * No-ops if the round/match is over or the current player isn't actually a
     * bot, so it's safe to call speculatively.
     */
    fun playBotTurn() {
        val request = beginBotTurn() ?: return
        val move = searchBotMove(request) ?: return
        finishBotTurn(request, move)
    }

    /**
     * Step 1 of an off-thread bot turn (see [beginHint]): captures the position on the main thread,
     * or null when the round/match is over or the player to move is not a bot.
     */
    fun beginBotTurn(): TicTacToeSearchRequest? {
        if (roundOver.value || matchOver.value) return null
        val botIndex = currentPlayer.value - 1
        if (context.players.getOrNull(botIndex)?.isBot != true) return null
        return TicTacToeSearchRequest(board.value.copyOf(), currentPlayer.value, roundNumber.value)
    }

    /** Step 2: picks the bot's (cell, symbol) for the captured position. Pure; safe on any thread. */
    fun searchBotMove(request: TicTacToeSearchRequest): Pair<Int, Int>? = chooseBotMove(request.board.copyOf(), request.player)

    /**
     * Step 3, on the main thread: plays [move] unless the position changed while the search ran
     * (then nothing happens and false is returned, so the caller can simply search again).
     */
    fun finishBotTurn(request: TicTacToeSearchRequest, move: Pair<Int, Int>): Boolean {
        if (!isStillCurrent(request)) return false
        // Route through the same [selectedSymbol] the human's toggle writes
        // to, so cellClicked() doesn't need a bot-specific placement path.
        selectedSymbol.value = move.second
        cellClicked(move.first)
        return true
    }

    /**
     * HARD plays perfect minimax (see [minimaxBestMove]) — genuinely
     * unbeatable, the best a human can force is a draw, matching the known
     * game-theoretic result that tic-tac-toe is a forced draw with optimal
     * play on both sides. MEDIUM keeps the original one-ply heuristic (win
     * if possible, else block, else positional preference) — solid but not
     * exhaustive, so it's beatable with a deliberate fork. EASY mostly
     * moves at random, only reaching for the heuristic move a third of the
     * time, so it still occasionally blocks/wins but loses far more often —
     * a real skill ladder rather than three re-skins of the same bot. Every
     * tier now returns a (cell, symbol) pair rather than just a cell, since
     * under [wild] the bot has to decide both — see [availableSymbols].
     */
    private fun chooseBotMove(b: IntArray, player: Int): Pair<Int, Int>? = when (difficulty) {
        CpuDifficulty.HARD -> searchMove(b, player)
        CpuDifficulty.MEDIUM -> heuristicMove(b, player)
        CpuDifficulty.EASY -> if (Random.nextFloat() < 0.35f) heuristicMove(b, player) else randomMove(b, player)
    }

    /**
     * Symbols `mover` may legally place this turn: only their own player
     * number under standard rules (a fixed X-or-O assignment baked into the
     * board), or either symbol under [wild]. Every search/heuristic function
     * below branches over this instead of assuming "symbol == mover", which
     * is what lets one code path serve both rule sets.
     */
    private fun availableSymbols(mover: Int): List<Int> = if (wild) listOf(1, 2) else listOf(mover)

    private fun randomMove(b: IntArray, player: Int): Pair<Int, Int>? {
        val cell = b.indices.filter { b[it] == 0 }.randomOrNull() ?: return null
        // EASY doesn't strategize even in standard rules, so under [wild] its
        // symbol choice is just as arbitrary as its cell choice.
        val symbol = if (wild) listOf(1, 2).random() else player
        return cell to symbol
    }

    /**
     * Win if possible, else block the opponent's win, else prefer center,
     * then corners, then edges. Under [misere] this naive win-seeking would
     * be actively self-destructive (the bot would race to complete its own
     * line, which loses), so that case is delegated to [misereHeuristicMove]
     * instead — see its KDoc.
     *
     * Under [wild] the "block" step disappears: [findWinningLine]/[winningMove]
     * only look at cell *content*, never at who placed it, so a line with two
     * matching cells and an empty third is already a winning move for
     * whichever symbol matches it — there's no separate "opponent's threat"
     * to block, because the mover can simply take that win for themselves
     * with the matching symbol. So the win-check loops over both symbols
     * first; only standard rules (where the bot's and opponent's symbols are
     * genuinely fixed and distinct) still need an explicit block step.
     */
    private fun heuristicMove(b: IntArray, bot: Int): Pair<Int, Int>? {
        val opponent = if (bot == 1) 2 else 1
        val symbols = availableSymbols(bot)

        if (misere) return misereHeuristicMove(b, bot, opponent, symbols)

        for (symbol in symbols) {
            winningMove(b, symbol)?.let { return it to symbol }
        }
        if (!wild) {
            winningMove(b, opponent)?.let { return it to bot }
        }

        // 3x3 keeps its hand-tuned centre, corners, edges order; bigger boards go centre outwards.
        val preferredOrder = if (b.size == 9) listOf(4, 0, 2, 6, 8, 1, 3, 5, 7) else centerOutOrder(dimOf(b.size))
        val cell = preferredOrder.firstOrNull { b[it] == 0 } ?: return null
        return cell to symbols.first()
    }

    /**
     * Misere one-ply heuristic. The standard heuristic's two priorities both
     * invert: completing a line is a loss (never volunteer for it, where a
     * safe alternative exists), and there is no "block the opponent's win" —
     * the opponent doesn't want to complete a line either, so instead this
     * prefers whichever safe move leaves the opponent with the fewest safe
     * replies of their own, nudging them toward eventually being the one
     * forced to complete a line. It's a proxy for real lookahead, not exact
     * play — HARD's minimax is what actually solves misere optimally.
     *
     * Generalized over `symbols` (see [availableSymbols]) so [wild] composes
     * with [misere] for free: a "move" is now a (cell, symbol) pair, and
     * "self-losing" means that specific pair would complete a line, not just
     * the cell in isolation — under [wild] the same cell can be safe with one
     * symbol and self-losing with the other.
     */
    private fun misereHeuristicMove(b: IntArray, bot: Int, opponent: Int, symbols: List<Int>): Pair<Int, Int>? {
        val empty = b.indices.filter { b[it] == 0 }
        val allMoves = empty.flatMap { cell -> symbols.map { symbol -> cell to symbol } }
        val selfLosing = allMoves.filter { (cell, symbol) -> cell in completingMoves(b, symbol) }.toSet()
        // If every legal (cell, symbol) pair would complete a line, a loss
        // this round is unavoidable — fall back to considering all of them.
        val candidates = (allMoves - selfLosing).ifEmpty { allMoves }

        val preferredOrder = listOf(4, 0, 2, 6, 8, 1, 3, 5, 7)
        return candidates.minByOrNull { (cell, symbol) ->
            val next = b.copyOf()
            next[cell] = symbol
            val opponentSymbols = availableSymbols(opponent)
            val opponentMoves = next.indices.filter { next[it] == 0 }.flatMap { c -> opponentSymbols.map { c to it } }
            val opponentSafeCount = opponentMoves.count { (oc, os) -> oc !in completingMoves(next, os) }
            // Primary key: fewer safe replies left for the opponent is better.
            // Tie-break with the same positional preference used elsewhere.
            opponentSafeCount * 10 + preferredOrder.indexOf(cell)
        }
    }

    /**
     * Full minimax over the game tree — standard tic-tac-toe is tiny enough
     * (at most 9! ≈ 362,880 nodes) that no pruning was needed. [wild] changes
     * that: the mover now also picks a symbol per move, doubling the
     * branching factor at every ply (worst case on the order of 2^9 × 9!
     * nodes), so alpha-beta pruning earns its keep here — it's guaranteed to
     * return the exact same result as unpruned minimax, just by skipping
     * subtrees a rational opponent would never allow to be reached. Depth is
     * factored into the score so the bot prefers a *faster* win and a
     * *slower* loss when several lines lead to the same outcome, matching
     * how a skilled human actually plays rather than winning "eventually" in
     * a way that looks careless.
     */
    private fun minimaxBestMove(b: IntArray, player: Int): Pair<Int, Int>? {
        val opponent = if (player == 1) 2 else 1
        var bestScore = Int.MIN_VALUE
        var bestMove: Pair<Int, Int>? = null
        for (i in b.indices) {
            if (b[i] != 0) continue
            for (symbol in availableSymbols(player)) {
                val next = b.copyOf()
                next[i] = symbol
                val score = minimax(next, depth = 1, isMaximizing = false, maximizer = player, minimizer = opponent, alpha = bestScore)
                if (score > bestScore) {
                    bestScore = score
                    bestMove = i to symbol
                }
            }
        }
        return bestMove
    }

    private fun minimax(
        b: IntArray,
        depth: Int,
        isMaximizing: Boolean,
        maximizer: Int,
        minimizer: Int,
        alpha: Int = Int.MIN_VALUE,
        beta: Int = Int.MAX_VALUE
    ): Int {
        findWinningLine(b)?.let {
            // Standard/misere alike used to read the winner off the line's
            // cell content (b[line[0]]), which worked because content and
            // mover were the same number. Under [wild] they can differ, so
            // this instead derives who *moved* last from turn order: this
            // call's isMaximizing already flipped past whoever just played,
            // so the last mover is the other side.
            val lastMover = if (isMaximizing) minimizer else maximizer
            // Standard: completing the line wins for whoever completed it.
            // Misere: completing the line LOSES for whoever completed it —
            // same depth-preference (faster win / slower loss), just with
            // which side "winning the game" maps to flipped.
            val goodForMaximizer = if (misere) lastMover != maximizer else lastMover == maximizer
            return if (goodForMaximizer) 10 - depth else depth - 10
        }
        if (isBoardFull(b)) return 0

        val player = if (isMaximizing) maximizer else minimizer
        var best = if (isMaximizing) Int.MIN_VALUE else Int.MAX_VALUE
        var a = alpha
        var bt = beta
        outer@ for (i in b.indices) {
            if (b[i] != 0) continue
            for (symbol in availableSymbols(player)) {
                val next = b.copyOf()
                next[i] = symbol
                val score = minimax(next, depth + 1, !isMaximizing, maximizer, minimizer, a, bt)
                if (isMaximizing) {
                    best = maxOf(best, score)
                    a = maxOf(a, best)
                } else {
                    best = minOf(best, score)
                    bt = minOf(bt, best)
                }
                // Alpha-beta cutoff: the side above us in the tree already has
                // a better option elsewhere, so it will never let play reach
                // this branch — no need to keep exploring it.
                if (bt <= a) break@outer
            }
        }
        return best
    }

    /** Returns the cell that completes a line for `player`, if one exists. */
    private fun winningMove(b: IntArray, player: Int): Int? = completingMoves(b, player).firstOrNull()

    /** Returns every empty cell that would complete a line for `player` if played there. */
    private fun completingMoves(b: IntArray, player: Int): List<Int> {
        val moves = mutableListOf<Int>()
        val lines = linesOf(dimOf(b.size))
        val needed = lines.firstOrNull()?.size?.minus(1) ?: return moves
        for (line in lines) {
            var mine = 0
            var empties = 0
            var emptyCell = -1
            for (cell in line) {
                val v = b[cell]
                if (v == player) {
                    mine++
                } else if (v == 0) {
                    empties++
                    emptyCell = cell
                }
            }
            if (mine == needed && empties == 1) moves += emptyCell
        }
        return moves
    }

    /** Returns the indices of the completed line (3 on a 3x3 board, 4 on 4x4 and 5x5), if any, regardless of which player completed it. */
    private fun findWinningLine(b: IntArray): List<Int>? {
        for (line in linesOf(dimOf(b.size))) {
            val first = b[line[0]]
            if (first == 0) continue
            var all = true
            for (i in 1 until line.size) {
                if (b[line[i]] != first) {
                    all = false
                    break
                }
            }
            if (all) return line.toList()
        }
        return null
    }

    private fun isBoardFull(b: IntArray) = b.none { it == 0 }

    /** The board edge this engine will use: [boardSize] for classic local play, else always 3. */
    private fun resolveBoardDim(): Int =
        if (misere || wild || isNetworked) 3 else if (boardSize in 3..5) boardSize else 3

    companion object {
        /** Positions the bot searches per move on the 4x4 / 5x5 boards; keeps a move well under a second. */
        private const val SEARCH_NODE_BUDGET = 300_000

        private val WIN_LINES_3 = listOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)
        )
        private val WIN_LINES_4 = buildLines(4, 4)
        private val WIN_LINES_5 = buildLines(5, 4)

        /** How many marks in a row win on a [dim] x [dim] board: 3 on 3x3, 4 on 4x4 and 5x5. */
        fun winLength(dim: Int): Int = if (dim == 3) 3 else 4

        /** Every winning line of a [dim] x [dim] board as row-major cell indices (empty for an unsupported [dim]). */
        fun winningLines(dim: Int): List<List<Int>> = linesOf(dim).map { it.toList() }

        internal fun linesOf(dim: Int): List<IntArray> = when (dim) {
            3 -> WIN_LINES_3
            4 -> WIN_LINES_4
            5 -> WIN_LINES_5
            else -> emptyList()
        }

        /** Edge length for a board with [cellCount] cells; anything unrecognised is the classic 3. */
        internal fun dimOf(cellCount: Int): Int = when (cellCount) {
            16 -> 4
            25 -> 5
            else -> 3
        }

        /** All cells of a [dim] x [dim] board, nearest the centre first (ties by index). */
        internal fun centerOutOrder(dim: Int): List<Int> =
            (0 until dim * dim).sortedWith(
                compareBy<Int>({ centerDistance(it, dim) }, { it })
            )

        internal fun centerDistance(cell: Int, dim: Int): Int {
            val dr = (cell / dim) * 2 - (dim - 1)
            val dc = (cell % dim) * 2 - (dim - 1)
            return dr * dr + dc * dc
        }

        /** Rows, columns and both diagonal directions, [length] cells long, never wrapping around an edge. */
        private fun buildLines(dim: Int, length: Int): List<IntArray> {
            val out = mutableListOf<IntArray>()
            val directions = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
            for (r in 0 until dim) {
                for (c in 0 until dim) {
                    for ((dr, dc) in directions) {
                        val endRow = r + dr * (length - 1)
                        val endCol = c + dc * (length - 1)
                        if (endRow !in 0 until dim || endCol !in 0 until dim) continue
                        out += IntArray(length) { step -> (r + dr * step) * dim + (c + dc * step) }
                    }
                }
            }
            return out
        }
    }
}

/**
 * A position captured on the main thread for an off-thread bot move or hint (see
 * [TicTacToeGame.beginBotTurn] / [TicTacToeGame.beginHint]). The board is a private copy, so the search
 * can run on any thread; applying the answer re-checks that the live position still matches.
 */
class TicTacToeSearchRequest internal constructor(
    internal val board: IntArray,
    internal val player: Int,
    internal val roundNumber: Int
)

/**
 * Bot search for the 4x4 and 5x5 boards (classic rules only, so a move is just a cell). A move that
 * wins now is taken; otherwise an opponent's winning cell is blocked; otherwise iterative-deepening
 * negamax with alpha-beta pruning runs until [nodeBudget] positions have been visited, scoring leaves
 * with a line-count heuristic (open lines weighted by how many marks they hold). A depth that did not
 * finish inside the budget is discarded, so the answer always comes from a fully searched depth.
 */
internal class TicTacToeLineSearch(
    private val dim: Int,
    private val lines: List<IntArray>,
    private val winLength: Int,
    private val nodeBudget: Int
) {
    private val cellCount = dim * dim
    private val order: IntArray = TicTacToeGame.centerOutOrder(dim).toIntArray()
    private val linesThrough: Array<IntArray> = Array(cellCount) { cell ->
        lines.indices.filter { i -> lines[i].contains(cell) }.toIntArray()
    }
    private var nodes = 0
    private var aborted = false

    fun bestMove(position: IntArray, me: Int): Int? {
        val b = position.copyOf()
        var empty = 0
        for (v in b) if (v == 0) empty++
        if (empty == 0) return null
        val opponent = 3 - me
        for (cell in order) if (b[cell] == 0 && completes(b, cell, me)) return cell
        for (cell in order) if (b[cell] == 0 && completes(b, cell, opponent)) return cell

        nodes = 0
        aborted = false
        var best = order.first { b[it] == 0 }
        var depth = 1
        while (depth <= MAX_DEPTH) {
            val (cell, score) = searchRoot(b, me, depth, best, empty)
            if (aborted) break
            best = cell
            // A forced win or loss will not change with more depth.
            if (score >= WIN - MAX_DEPTH - 1 || score <= -(WIN - MAX_DEPTH - 1)) break
            if (depth >= empty) break
            depth++
        }
        return best
    }

    private fun searchRoot(b: IntArray, me: Int, depth: Int, first: Int, empty: Int): Pair<Int, Int> {
        var bestCell = first
        var bestScore = -INF
        var alpha = -INF
        for (i in -1 until cellCount) {
            val cell = if (i == -1) first else order[i]
            if (i >= 0 && cell == first) continue
            if (b[cell] != 0) continue
            b[cell] = me
            val score = -negamax(b, depth - 1, -INF, -alpha, 3 - me, 1, empty - 1)
            b[cell] = 0
            if (aborted) return bestCell to bestScore
            if (score > bestScore) {
                bestScore = score
                bestCell = cell
                if (score > alpha) alpha = score
            }
        }
        return bestCell to bestScore
    }

    /** Score of the position for [player], who is to move; the position has no winner yet. */
    private fun negamax(b: IntArray, depth: Int, alphaIn: Int, beta: Int, player: Int, ply: Int, empty: Int): Int {
        if (empty == 0) return 0
        if (depth == 0) return evaluate(b, player)
        var alpha = alphaIn
        var best = -INF
        for (cell in order) {
            if (b[cell] != 0) continue
            nodes++
            if (nodes > nodeBudget) {
                aborted = true
                return 0
            }
            b[cell] = player
            val score = if (completes(b, cell, player)) {
                WIN - ply
            } else {
                -negamax(b, depth - 1, -beta, -alpha, 3 - player, ply + 1, empty - 1)
            }
            b[cell] = 0
            if (aborted) return 0
            if (score > best) best = score
            if (best > alpha) alpha = best
            if (alpha >= beta) break
        }
        return best
    }

    /** True if every other cell of some line through [cell] already holds [player]'s mark. */
    private fun completes(b: IntArray, cell: Int, player: Int): Boolean {
        for (li in linesThrough[cell]) {
            var all = true
            for (c in lines[li]) {
                if (c != cell && b[c] != player) {
                    all = false
                    break
                }
            }
            if (all) return true
        }
        return false
    }

    /** Open-line count heuristic from [player]'s side: lines only they occupy count for, lines only the other occupies count against. */
    private fun evaluate(b: IntArray, player: Int): Int {
        var score = 0
        for (line in lines) {
            var mine = 0
            var theirs = 0
            for (c in line) {
                val v = b[c]
                if (v == player) mine++ else if (v != 0) theirs++
            }
            if (theirs == 0 && mine > 0) {
                score += LINE_WEIGHT[mine]
            } else if (mine == 0 && theirs > 0) {
                score -= LINE_WEIGHT[theirs]
            }
        }
        return score
    }

    private companion object {
        const val INF = 1_000_000
        const val WIN = 100_000
        const val MAX_DEPTH = 8
        val LINE_WEIGHT = intArrayOf(0, 1, 6, 40, 300, 2000)
    }
}
