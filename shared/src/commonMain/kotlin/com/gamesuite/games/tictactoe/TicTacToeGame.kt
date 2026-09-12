package com.gamesuite.games.tictactoe

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
class TicTacToeGame : GameModule {
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

    // 0 = empty, 1 = P1, 2 = P2 — Compose state so UI recomposes on change.
    val board = mutableStateOf(IntArray(9))
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

    override fun init(context: GameContext) {
        this.context = context
        board.value = IntArray(9)
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

        if (isNetworked) {
            context.transport.onMessageReceived { fromPlayerId, payload -> handleNetworkMessage(fromPlayerId, payload) }
            // Covers the same startup race TicTacToeNetMessage.RequestState's own KDoc
            // documents: this guest's listener above might register after the host has
            // already broadcast (or will broadcast before this guest is ready to receive).
            if (!isHost) sendToHost(TicTacToeNetMessage.RequestState)
        }
    }

    override fun startMatch() {
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
        if (isNetworked && !isHost) {
            sendToHost(TicTacToeIntentPayload.PlayAgain)
            return
        }
        applyPlayAgain()
    }

    private fun applyPlayAgain() {
        if (matchOver.value) return
        startingPlayer = if (startingPlayer == 1) 2 else 1
        roundNumber.value += 1
        board.value = IntArray(9)
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
    private fun broadcastState() {
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
            selectedSymbol = selectedSymbol.value
        )
        sendMessage(TicTacToeNetMessage.StateSync(stateVersion, snapshot), toPlayerId = null)
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
                if (message.version <= lastAppliedStateVersion) return
                lastAppliedStateVersion = message.version
                applyNetState(message.state)
            }
            is TicTacToeNetMessage.Intent -> {
                if (!isHost) return // only the host ever applies a peer's intent
                val senderIndex = context.players.indexOfFirst { it.playerId == fromPlayerId }
                if (senderIndex == -1) return // unknown sender -- ignore rather than trust a bare claimed identity
                when (val intent = message.intent) {
                    is TicTacToeIntentPayload.CellClicked -> {
                        // Not this sender's actual turn -- ignore rather than trust the
                        // intent's own cell index blindly. applyCellClicked's own
                        // roundOver/matchOver/occupied-cell guards still apply on top of
                        // this, exactly as they do for a local tap.
                        if (senderIndex != currentPlayer.value - 1) return
                        applyCellClicked(intent.index)
                    }
                    TicTacToeIntentPayload.PlayAgain -> applyPlayAgain()
                }
            }
            TicTacToeNetMessage.RequestState -> {
                if (isHost) broadcastState()
            }
        }
    }

    /**
     * Called from the UI's X/O toggle under [wild], before the player taps a
     * cell — see [selectedSymbol]. No-op under standard rules since the UI
     * doesn't show the toggle then, but harmless either way since standard
     * [cellClicked] never reads [selectedSymbol].
     */
    fun chooseSymbol(symbol: Int) {
        selectedSymbol.value = symbol
    }

    /** Call this from the UI when a cell is tapped.
     *  Networked + not host: forwarded to the host as a [TicTacToeIntentPayload.CellClicked]
     *  intent instead of applied locally -- the host validates and applies it, then
     *  broadcasts the result back (see [handleNetworkMessage]/[applyCellClicked]). */
    fun cellClicked(index: Int) {
        if (isNetworked && !isHost) {
            sendToHost(TicTacToeIntentPayload.CellClicked(index))
            return
        }
        applyCellClicked(index)
    }

    /** The real move logic -- called directly for local play (pass-and-play/vs-bot), by
     *  [cellClicked] when this instance IS the host (its own local tap), and by
     *  [handleNetworkMessage] when the host applies a validated guest intent. */
    private fun applyCellClicked(index: Int) {
        if (roundOver.value || matchOver.value || board.value[index] != 0) return

        val newBoard = board.value.copyOf()
        // Standard: the cell records the mover's own fixed symbol. Wild: the
        // mover chose a symbol independently of who they are — see the
        // class KDoc's [wild] paragraph — so it comes from [selectedSymbol].
        newBoard[index] = if (wild) selectedSymbol.value else currentPlayer.value
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
            val loserIsMover = misere
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
        if (roundOver.value || matchOver.value) return
        val botIndex = currentPlayer.value - 1
        if (context.players.getOrNull(botIndex)?.isBot != true) return

        val (cell, symbol) = chooseBotMove(board.value) ?: return
        // Route through the same [selectedSymbol] the human's toggle writes
        // to, so cellClicked() doesn't need a bot-specific placement path.
        selectedSymbol.value = symbol
        cellClicked(cell)
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
    private fun chooseBotMove(b: IntArray): Pair<Int, Int>? = when (difficulty) {
        CpuDifficulty.HARD -> minimaxBestMove(b, currentPlayer.value)
        CpuDifficulty.MEDIUM -> heuristicMove(b)
        CpuDifficulty.EASY -> if (Random.nextFloat() < 0.35f) heuristicMove(b) else randomMove(b)
    }

    /**
     * Symbols `mover` may legally place this turn: only their own player
     * number under standard rules (a fixed X-or-O assignment baked into the
     * board), or either symbol under [wild]. Every search/heuristic function
     * below branches over this instead of assuming "symbol == mover", which
     * is what lets one code path serve both rule sets.
     */
    private fun availableSymbols(mover: Int): List<Int> = if (wild) listOf(1, 2) else listOf(mover)

    private fun randomMove(b: IntArray): Pair<Int, Int>? {
        val cell = b.indices.filter { b[it] == 0 }.randomOrNull() ?: return null
        // EASY doesn't strategize even in standard rules, so under [wild] its
        // symbol choice is just as arbitrary as its cell choice.
        val symbol = if (wild) listOf(1, 2).random() else currentPlayer.value
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
    private fun heuristicMove(b: IntArray): Pair<Int, Int>? {
        val bot = currentPlayer.value
        val opponent = if (bot == 1) 2 else 1
        val symbols = availableSymbols(bot)

        if (misere) return misereHeuristicMove(b, bot, opponent, symbols)

        for (symbol in symbols) {
            winningMove(b, symbol)?.let { return it to symbol }
        }
        if (!wild) {
            winningMove(b, opponent)?.let { return it to bot }
        }

        val preferredOrder = listOf(4, 0, 2, 6, 8, 1, 3, 5, 7)
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
                val score = minimax(next, depth = 1, isMaximizing = false, maximizer = player, minimizer = opponent)
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
        for (line in WIN_LINES) {
            val cells = line.map { b[it] }
            if (cells.count { it == player } == 2 && cells.count { it == 0 } == 1) {
                moves += line[cells.indexOf(0)]
            }
        }
        return moves
    }

    /** Returns the 3 indices of the completed line, if any, regardless of which player completed it. */
    private fun findWinningLine(b: IntArray): List<Int>? {
        for (line in WIN_LINES) {
            val (a, c, d) = line
            if (b[a] != 0 && b[a] == b[c] && b[c] == b[d]) return line.toList()
        }
        return null
    }

    private fun isBoardFull(b: IntArray) = b.none { it == 0 }

    companion object {
        private val WIN_LINES = listOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)
        )
    }
}
