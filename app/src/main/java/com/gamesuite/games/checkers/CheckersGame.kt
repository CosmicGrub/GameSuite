package com.gamesuite.games.checkers

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty

/**
 * Port of esp32-tictactoe/TicTacToeESP32/CheckersLogic.h/.cpp (American/English
 * draughts, 8x8, mandatory capture with multi-jump chaining) -- see that file's
 * own header for the rules this was verified against. Board coordinates are
 * row/col, each 0..7; only the 32 dark squares ((row+col) is odd) are ever
 * occupied. [PieceKind]/[CheckersPiece]/[CheckersMove] replace the C++ side's
 * `enum class CheckersPiece`/`CheckersMove` struct; a nullable
 * `List<CheckersPiece?>` of size 64 (index = row*8+col) replaces the raw
 * `CheckersPiece board[8][8]` array.
 *
 * The C++ engine hardcodes one fixed human seat and one fixed AI seat.
 * GameSuite's [GameContext] instead has two [PlayerInfo] slots where EITHER
 * can be the bot (or neither, in pass-and-play) -- so rather than "human" vs
 * "AI", the two fixed sides here are named by the same row geometry the C++
 * side used: side 0 starts at rows 0-2 and promotes on reaching row 7 (this
 * is exactly the C++ "AI" side, direction-for-direction); side 1 starts at
 * rows 5-7 and promotes on reaching row 0 (exactly the C++ "human" side).
 * [CheckersState.currentPlayerIndex] is just whichever of [GameContext.players]
 * is seated on that side -- the rules never care whether that seat is a human
 * or the bot, matching how [playMove] and [playBotTurn] are separate public
 * entry points the same way `playHuman`/`playAi` were in the source.
 */
enum class PieceKind { MAN, KING }

data class CheckersPiece(val owner: Int, val kind: PieceKind)

/**
 * One hop (a plain step, or one link of a capture chain) for [playMove], or
 * the overall (first hop's source) -> (last hop's destination) of an entire
 * [CheckersGame.playBotTurn] call -- mirrors the C++ side's per-hop
 * `CheckersMove` for the former and its `lastAiMove()` accessor for the
 * latter (see that method's KDoc: [CheckersState.lastMove] itself stays this
 * same collapsed one-slide-from-start-to-end summary for a compound bot
 * turn, matching the source's own simplification -- not a bug). [isCapture]
 * is true if any hop of the turn captured a piece; [capRow]/[capCol] are
 * only meaningful for a single hop (a real, single call to
 * [CheckersGame.playMove], or one entry of [CheckersState.lastTurnHops]) --
 * on THIS collapsed overall-move shape (as opposed to one of
 * [CheckersState.lastTurnHops]'s individual entries) they're left at -1
 * since a compound move erases which of possibly several captured squares
 * to report. See [CheckersState.lastTurnHops] for where the individual hops
 * of a compound turn ARE still available, added purely so a UI can animate
 * a multi-jump chain's captures one at a time instead of all at once --
 * this doesn't change the rules-level collapsing [lastMove] itself does.
 */
data class CheckersMove(
    val fromRow: Int,
    val fromCol: Int,
    val toRow: Int,
    val toCol: Int,
    val isCapture: Boolean,
    val capRow: Int = -1,
    val capCol: Int = -1
)

/**
 * [forcedRow]/[forcedCol] mirror `CheckersBoard::inForcedContinuation` --
 * (-1,-1) when no forced continuation is active, else the square whose piece
 * must jump again before the turn can pass (see [CheckersGame.generateMoves]).
 * [lastMove] is this project's answer to the port task's request for "a
 * clean, directly-assertable public field for what move the engine/bot just
 * made" in place of Mancala's fragile `lastAction`-string-parsing: it's the
 * single hop [CheckersGame.playMove] just applied, or the whole-turn overall
 * move [CheckersGame.playBotTurn] just applied, and is null only right after
 * [CheckersGame.startMatch] before any move has been played this round --
 * that nullness doubles as the screen's/tests' signal "this is a fresh
 * board," matching Mancala's `roundOver` flag's role of being read by both
 * gameplay and round-transition logic.
 *
 * [lastTurnHops] is additive, UI-animation-facing detail sitting alongside
 * [lastMove] rather than replacing it: the individual hop(s) that made up
 * whatever [commit] just happened, in play order -- a single-element list
 * equal to `[lastMove]` for a real, single [CheckersGame.playMove] call
 * (including one link of a human's forced-continuation chain, since the UI
 * already re-renders once per hop there), or every link of a compound
 * [CheckersGame.playBotTurn] chain when that's what just committed, each
 * with its own accurate [CheckersMove.capRow]/[CheckersMove.capCol] --
 * exactly the "what actually happened, hop by hop" a move-animation needs
 * to fade out each captured piece as the slide actually reaches it, instead
 * of every captured piece disappearing in the same frame the whole compound
 * move commits. Empty only when [lastMove] is null (the fresh-board case).
 */
data class CheckersState(
    val board: List<CheckersPiece?>,
    val currentPlayerIndex: Int,
    val forcedRow: Int = -1,
    val forcedCol: Int = -1,
    val lastMove: CheckersMove? = null,
    val lastTurnHops: List<CheckersMove> = emptyList(),
    val gameOver: Boolean = false,
    val winnerPlayerId: String? = null
) {
    val inForcedContinuation: Boolean get() = forcedRow >= 0
    fun pieceAt(row: Int, col: Int): CheckersPiece? = board[row * 8 + col]
}

/**
 * Pure position used only by move generation/[CheckersGame.applyMove]/the
 * HARD bot's search -- the internal analog of the C++ `CheckersBoard`'s
 * private fields, kept separate from the Compose-facing [CheckersState] so
 * exploring thousands of hypothetical positions per HARD-tier move never
 * touches (or risks corrupting) the real `state`, exactly as
 * MancalaGame.kt's own KDoc explains for its private `SowResult`/board-copy
 * helpers.
 */
private data class Position(
    val board: List<CheckersPiece?>,
    val sideToMove: Int,
    val forcedRow: Int = -1,
    val forcedCol: Int = -1
)

class CheckersGame : GameModule {
    override val gameId = "checkers"
    override val displayName = "Checkers"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT
    )

    val state = mutableStateOf<CheckersState?>(null)

    /** Running session score across rounds -- mirrors MancalaGame's scoreP1/scoreP2.
     *  No `draws` field: unlike Mancala, the ported ruleset has no draw outcome at all
     *  (see [terminalWinner] -- a side either has a move or it has lost), matching the
     *  C++ `CheckersResult` enum, which only ever reports IN_PROGRESS/HUMAN_WINS/AI_WINS. */
    val scoreP1 = mutableStateOf(0)
    val scoreP2 = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via the round-over panel), not per-round. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        scoreP1.value = 0
        scoreP2.value = 0
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    /**
     * Standard 8x8 setup: 12 men each on the dark squares of the 3 rows
     * closest to each side, the middle 2 rows empty -- CheckersLogic.cpp's
     * `reset()`. Side 1 (the C++ "human" side) moves first, matching
     * `reset()`'s `humanTurn = true` default exactly -- an arbitrary but
     * verified starting condition, kept as-is rather than "fixed" to favor
     * whichever seat a caller might expect to go first.
     */
    override fun startMatch() {
        val cells = MutableList<CheckersPiece?>(64) { null }
        for (row in 0..2) for (col in 0..7) if ((row + col) % 2 == 1) cells[row * 8 + col] = CheckersPiece(0, PieceKind.MAN)
        for (row in 5..7) for (col in 0..7) if ((row + col) % 2 == 1) cells[row * 8 + col] = CheckersPiece(1, PieceKind.MAN)
        state.value = CheckersState(board = cells, currentPlayerIndex = 1)
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Mirrors MancalaGame.leaveSession() -- ends the whole session, reporting cumulative score. */
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

    /** Mirrors MancalaGame.playAgain() -- keeps the running score, deals a fresh board. */
    fun playAgain() {
        if (matchOver.value) return
        startMatch()
    }

    // ---- Pure rules engine (mirrors CheckersLogic.cpp's private methods) -----------------

    private fun idx(row: Int, col: Int) = row * 8 + col

    private fun directionsFor(piece: CheckersPiece): List<Pair<Int, Int>> = when {
        piece.kind == PieceKind.KING -> listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
        piece.owner == 0 -> listOf(1 to -1, 1 to 1) // side 0 promotes at row 7
        else -> listOf(-1 to -1, -1 to 1)           // side 1 promotes at row 0
    }

    private fun isEnemy(piece: CheckersPiece?, side: Int) = piece != null && piece.owner != side

    private fun squareHasJump(board: List<CheckersPiece?>, row: Int, col: Int): Boolean {
        val piece = board[idx(row, col)] ?: return false
        for ((dr, dc) in directionsFor(piece)) {
            val landR = row + 2 * dr
            val landC = col + 2 * dc
            if (landR !in 0..7 || landC !in 0..7) continue
            val midR = row + dr
            val midC = col + dc
            if (!isEnemy(board[idx(midR, midC)], piece.owner)) continue
            if (board[idx(landR, landC)] != null) continue
            return true
        }
        return false
    }

    private fun sideHasAnyCapture(board: List<CheckersPiece?>, side: Int): Boolean {
        for (row in 0..7) for (col in 0..7) {
            if (board[idx(row, col)]?.owner == side && squareHasJump(board, row, col)) return true
        }
        return false
    }

    private fun jumpsFromSquare(board: List<CheckersPiece?>, row: Int, col: Int): List<CheckersMove> {
        val piece = board[idx(row, col)] ?: return emptyList()
        val moves = mutableListOf<CheckersMove>()
        for ((dr, dc) in directionsFor(piece)) {
            val landR = row + 2 * dr
            val landC = col + 2 * dc
            if (landR !in 0..7 || landC !in 0..7) continue
            val midR = row + dr
            val midC = col + dc
            if (!isEnemy(board[idx(midR, midC)], piece.owner)) continue
            if (board[idx(landR, landC)] != null) continue
            moves += CheckersMove(row, col, landR, landC, isCapture = true, capRow = midR, capCol = midC)
        }
        return moves
    }

    private fun simpleMovesFromSquare(board: List<CheckersPiece?>, row: Int, col: Int): List<CheckersMove> {
        val piece = board[idx(row, col)] ?: return emptyList()
        val moves = mutableListOf<CheckersMove>()
        for ((dr, dc) in directionsFor(piece)) {
            val r = row + dr
            val c = col + dc
            if (r !in 0..7 || c !in 0..7) continue
            if (board[idx(r, c)] != null) continue
            moves += CheckersMove(row, col, r, c, isCapture = false)
        }
        return moves
    }

    /**
     * The single place mandatory-capture and forced-continuation are decided
     * -- mirrors `CheckersBoard::generateMoves`. Unlike the C++ side (which
     * takes an explicit `humanSide` so a query can ask about either side
     * independent of whose turn it actually is), every real call site there
     * only ever passes the side that's actually to move -- so this generalizes
     * that by always generating for [pos]'s own `sideToMove`, which is exactly
     * the side any forced continuation on [pos] belongs to.
     */
    private fun generateMoves(pos: Position): List<CheckersMove> {
        if (pos.forcedRow >= 0) return jumpsFromSquare(pos.board, pos.forcedRow, pos.forcedCol)
        val anyCapture = sideHasAnyCapture(pos.board, pos.sideToMove)
        val moves = mutableListOf<CheckersMove>()
        for (row in 0..7) for (col in 0..7) {
            if (pos.board[idx(row, col)]?.owner != pos.sideToMove) continue
            moves += if (anyCapture) jumpsFromSquare(pos.board, row, col) else simpleMovesFromSquare(pos.board, row, col)
        }
        return moves
    }

    /**
     * Mirrors `CheckersBoard::applyMove`: moves the piece, clears a captured
     * piece if any, promotes on reaching the far row, and hands the turn
     * off -- UNLESS the move was a non-promoting capture that leaves the same
     * piece with a further jump, in which case [Position.sideToMove] stays
     * put and [Position.forcedRow]/[forcedCol] point at the landing square.
     * A piece that kings mid-chain stops there for the turn even if a
     * further jump exists -- this exact rule is deliberately kept from the
     * C++ source (see that method's own comment) since it was the one
     * explicitly exercised by the source's own promotion-mid-chain test.
     */
    private fun applyMove(pos: Position, move: CheckersMove): Position {
        val board = pos.board.toMutableList()
        val moving = board[idx(move.fromRow, move.fromCol)]!!
        board[idx(move.fromRow, move.fromCol)] = null
        if (move.isCapture) board[idx(move.capRow, move.capCol)] = null

        var landing = moving
        var promoted = false
        if (moving.kind == PieceKind.MAN) {
            if (moving.owner == 0 && move.toRow == 7) { landing = moving.copy(kind = PieceKind.KING); promoted = true }
            else if (moving.owner == 1 && move.toRow == 0) { landing = moving.copy(kind = PieceKind.KING); promoted = true }
        }
        board[idx(move.toRow, move.toCol)] = landing

        return if (move.isCapture && !promoted && squareHasJump(board, move.toRow, move.toCol)) {
            pos.copy(board = board, forcedRow = move.toRow, forcedCol = move.toCol)
        } else {
            pos.copy(board = board, sideToMove = 1 - pos.sideToMove, forcedRow = -1, forcedCol = -1)
        }
    }

    private fun pieceCountFor(board: List<CheckersPiece?>, side: Int): Int = board.count { it?.owner == side }

    /** Mirrors `CheckersBoard::result()`: the side to move loses (no pieces, or pieces but no
     *  legal moves) -- returns the WINNING playerIndex, or null while still in progress. */
    private fun terminalWinner(pos: Position): Int? {
        if (pieceCountFor(pos.board, pos.sideToMove) == 0) return 1 - pos.sideToMove
        if (generateMoves(pos).isEmpty()) return 1 - pos.sideToMove
        return null
    }

    private fun positionOf(s: CheckersState) = Position(s.board, s.currentPlayerIndex, s.forcedRow, s.forcedCol)

    /** [hops] defaults to just [move] itself -- correct as-is for [playMove]'s
     *  always-single-hop commits; [playBotTurn] passes its own full per-hop
     *  list explicitly since [move] there is already the collapsed overall
     *  first->last summary, not one hop. See [CheckersState.lastTurnHops]. */
    private fun commit(pos: Position, move: CheckersMove, hops: List<CheckersMove> = listOf(move)) {
        val winner = terminalWinner(pos)
        state.value = CheckersState(
            board = pos.board,
            currentPlayerIndex = pos.sideToMove,
            forcedRow = pos.forcedRow,
            forcedCol = pos.forcedCol,
            lastMove = move,
            lastTurnHops = hops,
            gameOver = winner != null,
            winnerPlayerId = winner?.let { context.players.getOrNull(it)?.playerId }
        )
        when (winner) {
            0 -> scoreP1.value += 1
            1 -> scoreP2.value += 1
        }
    }

    // ---- Public queries (mirror CheckersLogic.h's isLegalMove/hasLegalMoveFrom/
    // legalDestinationsFrom) -- pure, safe to call on every touch event/recomposition,
    // already fold in mandatory-capture and forced-continuation so the screen's tap
    // handling needs no rules knowledge of its own.

    fun hasLegalMoveFrom(row: Int, col: Int): Boolean {
        val s = state.value ?: return false
        return generateMoves(positionOf(s)).any { it.fromRow == row && it.fromCol == col }
    }

    fun legalDestinationsFrom(row: Int, col: Int): Set<Pair<Int, Int>> {
        val s = state.value ?: return emptySet()
        return generateMoves(positionOf(s)).filter { it.fromRow == row && it.fromCol == col }
            .map { it.toRow to it.toCol }.toSet()
    }

    /**
     * Plays one hop for [playerIndex] -- mirrors `CheckersBoard::playHuman`,
     * generalized to whichever seat is actually asking (pass-and-play has two
     * human seats, either of which calls this directly from a touch handler).
     * Silently rejects an illegal call (wrong player's turn, or a hop that
     * doesn't match a currently generated legal move) with no state change,
     * the same convention as every other GameModule in this suite.
     */
    fun playMove(playerIndex: Int, fromRow: Int, fromCol: Int, toRow: Int, toCol: Int) {
        val s = state.value ?: return
        if (s.gameOver || playerIndex != s.currentPlayerIndex) return
        val pos = positionOf(s)
        val move = generateMoves(pos).firstOrNull {
            it.fromRow == fromRow && it.fromCol == fromCol && it.toRow == toRow && it.toCol == toCol
        } ?: return
        commit(applyMove(pos, move), move)
    }

    /**
     * Plays the bot's entire turn -- every hop of a forced multi-jump chain
     * included -- mirroring `CheckersBoard::playAi`'s do-while loop, then
     * commits exactly once with the overall (first hop's source) -> (last
     * hop's destination) move, matching `lastAiMove()`'s documented
     * simplification (see [CheckersMove]'s KDoc). EASY/MEDIUM/HARD select
     * moves via [pickRandomMove]/[pickGreedyMove]/[pickMinimaxMove]
     * respectively -- see those methods' KDoc for what each tier actually
     * does. A no-op if it isn't currently the bot's turn, matching
     * `playAi()`'s own early-return.
     */
    fun playBotTurn() {
        val s = state.value ?: return
        if (s.gameOver) return
        val botIndex = s.currentPlayerIndex
        if (context.players.getOrNull(botIndex)?.isBot != true) return

        var pos = positionOf(s)
        var firstMove: CheckersMove? = null
        var lastMove: CheckersMove? = null
        var anyCapture = false
        // Every individual hop played this turn, in order -- see
        // CheckersState.lastTurnHops' KDoc for why this is tracked separately
        // from the collapsed first->last summary committed below.
        val hops = mutableListOf<CheckersMove>()

        while (pos.sideToMove == botIndex && terminalWinner(pos) == null) {
            val move = when (difficulty) {
                CpuDifficulty.EASY -> pickRandomMove(pos)
                CpuDifficulty.MEDIUM -> pickGreedyMove(pos, botIndex)
                CpuDifficulty.HARD -> pickMinimaxMove(pos, botIndex)
            } ?: break
            if (firstMove == null) firstMove = move
            lastMove = move
            anyCapture = anyCapture || move.isCapture
            hops += move
            pos = applyMove(pos, move)
        }

        val first = firstMove ?: return
        val last = lastMove!!
        commit(pos, CheckersMove(first.fromRow, first.fromCol, last.toRow, last.toCol, isCapture = anyCapture), hops)
    }

    // ---- EASY: a genuinely weak/mostly-random legal move -- still obeys mandatory
    // capture/forced-continuation (those are RULES, not a difficulty choice), just
    // picks uniformly at random among whatever generateMoves() actually offers.
    private fun pickRandomMove(pos: Position): CheckersMove? = generateMoves(pos).randomOrNull()

    // ---- MEDIUM: a real one-ply greedy heuristic -- score every immediate candidate
    // hop via evaluateHeuristic() (the exact same static evaluation the HARD bot's
    // search uses at its leaves) with no look-ahead into the opponent's reply, and
    // take the best-scoring one. Meaningfully stronger than EASY's coin flip without
    // paying minimax's search cost.
    private fun pickGreedyMove(pos: Position, botIndex: Int): CheckersMove? =
        generateMoves(pos).maxByOrNull { move ->
            val next = applyMove(pos, move)
            evaluateHeuristic(next, botIndex, generateMoves(next).size)
        }

    // ---- HARD: depth-limited minimax with alpha-beta pruning, ported from
    // CheckersLogic.cpp's minimaxSearch/findBestAiMove/evaluateHeuristic. Kept
    // entirely over pure Position copies -- state is never touched mid-search.

    private fun evaluateHeuristic(pos: Position, maximizer: Int, sideToMoveMobility: Int): Int {
        var score = 0
        for (row in 0..7) for (col in 0..7) {
            val piece = pos.board[idx(row, col)] ?: continue
            val mine = piece.owner == maximizer
            val contribution = when (piece.kind) {
                PieceKind.KING -> KING_VALUE
                PieceKind.MAN -> {
                    // Advancement/back-row terms carried over byte-for-byte from
                    // evaluateHeuristic() in the C++ source (side 0 = that file's
                    // AI_MAN branch, side 1 = its HUMAN_MAN branch) rather than
                    // re-derived from the header's own prose description of intent:
                    // that function's inline comments describe the OPPOSITE of what
                    // the same file's reset()/applyMove() actually do for which row
                    // is home vs. promotion for each side, so the literal arithmetic
                    // (not the comments) is the real, verified behavior being ported.
                    // Keeping the exact numbers keeps this tier's playing strength
                    // identical to the source engine; it is not a claim that the
                    // weighting is optimal play.
                    val advancement = if (piece.owner == 0) (7 - row) else row
                    val backRow = if (piece.owner == 0) 7 else 0
                    MAN_VALUE + ADVANCEMENT_WEIGHT * advancement + if (row == backRow) BACK_ROW_BONUS else 0
                }
            }
            score += if (mine) contribution else -contribution
        }
        val mobilityTerm = MOBILITY_WEIGHT * sideToMoveMobility
        score += if (pos.sideToMove == maximizer) mobilityTerm else -mobilityTerm
        return score
    }

    private fun minimax(pos: Position, depthRemaining: Int, plyFromRoot: Int, maximizer: Int, alpha: Int, beta: Int): Int {
        val moves = generateMoves(pos)
        if (pieceCountFor(pos.board, pos.sideToMove) == 0 || moves.isEmpty()) {
            val score = WIN_SCORE - plyFromRoot
            return if (pos.sideToMove == maximizer) -score else score
        }
        if (depthRemaining == 0) return evaluateHeuristic(pos, maximizer, moves.size)

        var a = alpha
        var b = beta
        return if (pos.sideToMove == maximizer) {
            var best = -SCORE_INF
            for (m in moves) {
                val score = minimax(applyMove(pos, m), depthRemaining - 1, plyFromRoot + 1, maximizer, a, b)
                if (score > best) best = score
                if (best > a) a = best
                if (a >= b) break
            }
            best
        } else {
            var best = SCORE_INF
            for (m in moves) {
                val score = minimax(applyMove(pos, m), depthRemaining - 1, plyFromRoot + 1, maximizer, a, b)
                if (score < best) best = score
                if (best < b) b = best
                if (a >= b) break
            }
            best
        }
    }

    private fun pickMinimaxMove(pos: Position, botIndex: Int): CheckersMove? {
        val moves = generateMoves(pos)
        var bestMove: CheckersMove? = null
        var bestScore = -SCORE_INF
        for (m in moves) {
            val score = minimax(applyMove(pos, m), HARD_SEARCH_DEPTH - 1, 1, botIndex, -SCORE_INF, SCORE_INF)
            if (bestMove == null || score > bestScore) {
                bestScore = score
                bestMove = m
            }
        }
        return bestMove
    }

    companion object {
        private const val MAN_VALUE = 100
        private const val KING_VALUE = 175
        private const val ADVANCEMENT_WEIGHT = 4
        private const val BACK_ROW_BONUS = 12
        private const val MOBILITY_WEIGHT = 2
        private const val WIN_SCORE = 1_000_000
        private const val SCORE_INF = 2_000_000

        /**
         * 9 plies deep, where a ply is one HOP (a plain step or one link of a
         * capture chain) rather than one full turn -- same convention as the
         * C++ source's `AI_SEARCH_DEPTH`, chosen there at 6 for an ESP32-32E
         * at 240MHz based on its own native_test timing harness. This port's
         * analog is CheckersGameTest's "HARD bot turn timing" test, which
         * times real end-to-end [playBotTurn] calls (HARD vs. HARD
         * self-play, multi-jump chains included) over 60 turns; run by hand
         * at each candidate depth on this port's JVM unit-test host before
         * settling on the shipped value:
         *   - depth 8:  avg 29.6ms/turn,  worst 163ms
         *   - depth 9:  avg 49.5ms/turn,  worst 323ms   <- shipped
         *   - depth 10: avg 276.8ms/turn, worst 1865ms
         * The jump from depth 9 to 10 is much steeper than 8 to 9 (deeper
         * search reaches more long forced-capture sequences, where the
         * "a ply is one hop" convention spends several plies of budget
         * without an intervening opponent reply -- see the C++ source's own
         * comment on this same convention); depth 10's worst case eats a
         * meaningful slice of a second even on this desktop-class host,
         * before accounting for an Android phone's ART runtime typically
         * running interpreted/branchy recursion like this slower than a
         * desktop JVM. Depth 9 keeps its worst case comfortably under the
         * ~700ms bot-turn delay budget UI screens in this suite already use
         * (see CheckersScreen's LaunchedEffect) with real headroom for that
         * phone-vs-desktop gap, while still searching noticeably deeper than
         * the source engine's depth 6, since phones can afford to.
         */
        private const val HARD_SEARCH_DEPTH = 9
    }
}
