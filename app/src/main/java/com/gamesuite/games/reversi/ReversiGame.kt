package com.gamesuite.games.reversi

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty

/** A player's identity + bot flag — mirrors ConnectFourPlayerState's own shape (see that class's
 *  KDoc for why this is a list even though Reversi, like Connect Four, is always exactly 2 players). */
data class ReversiPlayerState(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean
)

/**
 * [cells] is 64 entries, row-major on an 8x8 board (index = row*8+col); each entry is the index
 * into [players] who owns that disc, or null if empty. [legalMoves] is always the CURRENT
 * player's own legal destination cells (recomputed by [ReversiGame.buildStateAfterMove] on every
 * move, including through a pass — see that function's own KDoc), so the UI can highlight them
 * directly without recomputing anything itself. [scores] is disc count per player index, live
 * for the whole board (not just at game end). [justPassed] is true only on the ply immediately
 * after a real pass happened (the OTHER player had no legal move and was skipped) — see
 * [ReversiGame.buildStateAfterMove] — so the UI can show a one-time "Player X passes" moment
 * rather than silently reassigning the turn.
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
    val winnerPlayerId: String? = null
)

/**
 * Reversi/Othello: players alternate placing a disc on any cell that FLANKS at least one line of
 * the opponent's discs (traps them between the new disc and an existing disc of the mover's own
 * color), flipping every trapped disc to the mover's color. Standard 8x8 board, standard opening
 * (see [startMatch]). The fifth board game in this batch (after Dots and Boxes/Connect Four/
 * Checkers/Chess) — reuses that established `sessionWins`/`sessionDraws`/`matchOver`/
 * `nextStartingPlayerIndex`/`leaveSession`/`playAgain` session shape verbatim (see
 * [ConnectFourGame][com.gamesuite.games.connectfour.ConnectFourGame]'s own KDoc for the
 * precedent), since this is another fixed-board, `difficulty`-tunes-the-bot 2-player game, not a
 * scaled solo puzzle.
 *
 * DISC IDENTITY: unlike real Othello's fixed black/white discs, this engine represents ownership
 * purely by player index (0/1) — there is no engine-level "black"/"white" concept, since which
 * player is 0 vs 1 depends on session setup, not a fixed color. The UI layer (ReversiScreen) picks
 * a consistent on-screen dark/light disc pair for the two indices.
 *
 * A REAL PASS, NOT A SKIP: Reversi's genre-defining wrinkle over Connect Four/Checkers/Chess is
 * that a player can be completely unable to move — [buildStateAfterMove] computes the actual
 * legal-move set for whoever's up next after every placement and, when that set is empty but the
 * player who just moved still has a move, hands the turn straight back to them with
 * `justPassed=true` (see that function's own KDoc for the full three-way branch). The bot's own
 * [minimax] search has to handle the exact same rule mid-tree (see that function's KDoc) — a pass
 * consumes a ply but places no disc, and true game-over is reached only once NEITHER player has a
 * legal move at a node, not merely the player-to-move.
 *
 * BOT STRATEGY: real minimax with alpha-beta pruning, scaled by SEARCH DEPTH per [CpuDifficulty]
 * tier (`EASY`=2 ply, `MEDIUM`=4, `HARD`=6 — see [difficultySearchDepth], the exact same tier
 * shape ConnectFourGame uses) rather than a full solve — Othello's full game tree, while smaller
 * than Connect Four's, is still far too large to solve at shallow tiers. The non-terminal
 * evaluation (see [evaluate]) is a real, well-known classic Othello heuristic rather than raw disc
 * count: a static POSITIONAL WEIGHT table (corners strongly positive, the diagonally-adjacent
 * "X-squares" and orthogonally-adjacent "C-squares" strongly negative while their corner is still
 * takeable — see [positionalWeight]'s own KDoc for citation) plus a MOBILITY term (having more
 * legal moves than the opponent is itself a real Othello advantage, independent of the current
 * disc count — disc count early/mid-game is close to irrelevant and can even be misleading, which
 * is exactly why this evaluation is NOT plain disc-count-based). A genuine board-over leaf is
 * scored by the real final disc-count difference instead (see [terminalScore]), scaled well above
 * the heuristic's own range so a confirmed forced win always outranks a merely-good-looking
 * heuristic position — same "prefer an actual win over a good-looking position" principle
 * ConnectFourGame's own `WIN_SCORE` embodies.
 *
 * Legal cells are searched CORNER-FIRST, then other-edge, then interior, then X/C-squares last
 * (see [moveOrderPriority]) purely as alpha-beta MOVE ORDERING — trying the moves most likely to
 * be strong first lets pruning cut off more of the tree sooner, not a gameplay rule of its own,
 * same disclaimer ConnectFourGame's own `columnSearchOrder` KDoc makes about its center-first order.
 */
class ReversiGame : GameModule {
    override val gameId = "reversi"
    override val displayName = "Reversi"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT
    )

    val state = mutableStateOf<ReversiState?>(null)

    /** Boards won this session, keyed by playerId; a drawn board increments nobody (see
     *  [sessionDraws] instead). Mirrors ConnectFourGame's own shape exactly. */
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())
    val sessionDraws = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via the board-over panel), not per-board. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** Alternates who opens each new board (including across Play Again rounds), for fairness —
     *  same idiom ConnectFourGame's own Play Again uses. */
    private var nextStartingPlayerIndex = 0

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        sessionDraws.value = 0
        nextStartingPlayerIndex = 0
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    /**
     * Deals the standard Othello opening: the four center cells filled in the real game's own
     * D4/E4/D5/E5 pattern (here, board indices 27/28/35/36 on an 8-wide row-major board), with
     * the two diagonal pairs split between the two players so each starts with exactly 2 discs on
     * the board and 4 legal opening moves. Which player is "starting" alternates via
     * [nextStartingPlayerIndex] the same way ConnectFourGame's own startMatch() does; a fresh
     * board is always playable regardless of whether a PRIOR board's endMatch() left matchOver
     * stuck true (same fix ConnectFourGame/Minesweeper/Sudoku/... all needed, built in from the
     * start here rather than rediscovered).
     */
    override fun startMatch() {
        val players = context.players.map { ReversiPlayerState(it.playerId, it.displayName, it.isBot) }
        matchOver.value = false

        val startingPlayerIndex = nextStartingPlayerIndex % players.size
        val otherPlayerIndex = 1 - startingPlayerIndex // safe: this game is always exactly 2 players
        val cells = MutableList<Int?>(64) { null }
        cells[27] = otherPlayerIndex     // row 3, col 3 -- real Othello's D4
        cells[36] = otherPlayerIndex     // row 4, col 4 -- real Othello's E5
        cells[28] = startingPlayerIndex  // row 3, col 4 -- real Othello's E4
        cells[35] = startingPlayerIndex  // row 4, col 3 -- real Othello's D5

        state.value = ReversiState(
            cells = cells,
            players = players,
            currentPlayerIndex = startingPlayerIndex,
            legalMoves = legalMovesFor(cells, startingPlayerIndex),
            scores = computeScores(cells, players.size),
            lastAction = "Game started"
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
        val scores = context.players.map {
            val w = wins[it.playerId] ?: 0
            PlayerScore(playerId = it.playerId, score = w, isWinner = bestWins > 0 && w == bestWins)
        }
        endMatch(GameResult(scores = scores))
    }

    /** Called from the board-over panel's "Play Again" button — keeps the running session tally
     *  and deals a fresh board within the same session. Mirrors ConnectFourGame.playAgain(). */
    fun playAgain() {
        if (matchOver.value) return
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
        if (index !in s.legalMoves) return

        val mover = s.players[s.currentPlayerIndex]
        val newCells = applyMove(s.cells, index, s.currentPlayerIndex)
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

        state.value = newState
    }

    /**
     * Bounded bot: choose one legal cell via [chooseBotMove] and play it. No-op if the board/match
     * is already over or the current player isn't actually a bot, so it's safe to call speculatively
     * — same contract as ConnectFourGame.playBotTurn().
     */
    fun playBotTurn() {
        val s = state.value ?: return
        if (matchOver.value || s.boardOver) return
        val bot = s.players[s.currentPlayerIndex]
        if (!bot.isBot) return

        val index = chooseBotMove(s) ?: return
        placeDisc(index)
    }

    /** Search depth (in plies) per [CpuDifficulty] tier — see this class's own KDoc for why this
     *  is depth-scaled rather than a full solve. Identical tier shape to ConnectFourGame's own. */
    private val difficultySearchDepth: Map<CpuDifficulty, Int> = mapOf(
        CpuDifficulty.EASY to 2,
        CpuDifficulty.MEDIUM to 4,
        CpuDifficulty.HARD to 6
    )

    /**
     * Runs [minimax] from [s]'s current position for every one of the mover's own legal cells and
     * returns the single best-scoring one (ties keep the FIRST found — since candidates are sorted
     * corner-first via [moveOrderPriority], a tie is broken toward the strongest-looking cell, a
     * reasonable positional default). Null only if [s.legalMoves] is somehow empty, which shouldn't
     * be reachable: [playBotTurn] only calls this while `!s.boardOver`, and [buildStateAfterMove]
     * never leaves a non-board-over state with an empty legal-move set for the player to move.
     *
     * Threads the running [alpha] forward across SIBLING top-level moves, the same root-level
     * move-ordering technique ConnectFourGame's own `chooseBotMove` KDoc explains in full (provably
     * can't change which move is ultimately chosen, only tightens pruning).
     */
    private fun chooseBotMove(s: ReversiState): Int? {
        val depth = difficultySearchDepth[difficulty] ?: difficultySearchDepth.getValue(CpuDifficulty.MEDIUM)
        val bot = s.currentPlayerIndex
        val opponent = 1 - bot

        var alpha = Int.MIN_VALUE
        var bestScore = Int.MIN_VALUE
        var bestIndex: Int? = null
        for (index in s.legalMoves.sortedByDescending(::moveOrderPriority)) {
            val newCells = applyMove(s.cells, index, bot)
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

    /**
     * Alpha-beta minimax, always evaluated from [bot]'s own perspective (positive is good for
     * [bot], regardless of whose ply it actually is — `maximizing` tracks whose turn it is, [bot]
     * itself never changes across one top-level [chooseBotMove] search, same convention
     * ConnectFourGame.minimax uses).
     *
     * PASS HANDLING (the one real difference from ConnectFourGame's own minimax, which never needs
     * it): [toMove]'s legal moves are computed FIRST. If empty, this checks whether the OTHER
     * player has a move — if so, [toMove] is passed over entirely: the search recurses to the other
     * player at `depth - 1` WITHOUT placing a disc (no cell is ever chosen for a pass — there is
     * none to choose). This deliberately consumes one ply per pass, same as a real move, rather
     * than searching a pass "for free" — a documented simplification (a real engine might not
     * charge a ply for a forced pass) that keeps the recursion trivially guaranteed to terminate
     * without any special-casing, since every recursive call still strictly decreases [depth]. True
     * game-over is reached only once NEITHER player has a legal move at the same node — that node
     * is scored by [terminalScore] (the real final disc difference), never treated as merely another
     * depth-limited cutoff.
     */
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
        for (index in toMoveLegal.sortedByDescending(::moveOrderPriority)) {
            val newCells = applyMove(cells, index, toMove)
            val score = minimax(newCells, depth - 1, a, b, !maximizing, bot, opponent, 1 - toMove)
            if (maximizing) {
                best = maxOf(best, score)
                a = maxOf(a, best)
            } else {
                best = minOf(best, score)
                b = minOf(b, best)
            }
            // Alpha-beta cutoff: the side above us in the tree already has a better option
            // elsewhere, so it will never let play reach this branch -- no need to keep exploring it.
            if (b <= a) break
        }
        return best
    }

    /** A genuine board-over leaf's score, always from [bot]'s own perspective: the real final disc
     *  count difference, offset by [WIN_SCORE] in the winning direction so it always dominates
     *  [evaluate]'s own heuristic range (bounded by a small constant times 64 cells, comfortably
     *  under [WIN_SCORE]) -- the exact same "a confirmed outcome always beats a merely-good-looking
     *  heuristic position" principle ConnectFourGame's own WIN_SCORE embodies. The disc-difference
     *  term ALSO naturally prefers a bigger margin win over a narrower one (and a narrower loss over
     *  a bigger one), the same "prefer the best of the same class of outcome" idea ConnectFourGame's
     *  `WIN_SCORE + depth` achieves via depth instead. */
    private fun terminalScore(cells: List<Int?>, bot: Int, opponent: Int): Int {
        val botDiscs = cells.count { it == bot }
        val opponentDiscs = cells.count { it == opponent }
        val diff = botDiscs - opponentDiscs
        return when {
            diff > 0 -> WIN_SCORE + diff
            diff < 0 -> -WIN_SCORE + diff
            else -> 0
        }
    }

    /**
     * Non-terminal position estimate for [minimax]'s depth cutoff, always from [bot]'s own
     * perspective: the sum of every occupied cell's [positionalWeight] (positive when [bot] owns
     * it, negated when [opponent] does), plus a MOBILITY term -- `(botLegalMoveCount -
     * opponentLegalMoveCount) * MOBILITY_WEIGHT`. Mobility matters independently of the positional
     * table: having more available moves than the opponent is a real, well-known Othello advantage
     * (it constrains the opponent's own options and tends to force them into weaker cells later),
     * not captured by static per-cell weights alone.
     */
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

    /**
     * Classic Othello static positional weight for [index], per the well-established
     * corner/X-square/C-square convention described throughout Othello strategy literature and
     * reproduced by many published Othello AI heuristics (e.g. Kartik Kukreja's widely-cited
     * "Heuristic Function for Reversi/Othello" write-up uses this exact corner-strongly-positive,
     * X-/C-square-strongly-negative shape, just at a different overall scale) -- the specific
     * magnitudes here (corner=120, X-square=-20, C-square=-40/+10) are this project's own chosen
     * scale within that same well-known family, not a novel scheme:
     * - CORNERS (index in [CORNERS]): the single best cell in Othello -- once taken it can never be
     *   flipped back, so it anchors a stable edge. Highest weight, [CORNER_WEIGHT].
     * - X-SQUARES (diagonally adjacent to a corner, index in [X_SQUARES]): the single most
     *   dangerous cell in the early/mid game -- playing one very often hands the adjacent corner
     *   straight to the opponent. Flatly negative regardless of the corner's own state (unlike
     *   C-squares below), since an X-square's danger isn't really about who owns the corner so much
     *   as the structural weakness of the disc itself.
     * - C-SQUARES (orthogonally adjacent to a corner along an edge, [C_SQUARE_TO_CORNER]'s keys):
     *   dangerous ONLY WHILE the corresponding corner is still empty and therefore still takeable --
     *   once a player actually OWNS that corner, a neighboring C-square stops being a liability (it
     *   can no longer be used to flank a corner-taking move against a corner that's already gone)
     *   and instead becomes an ordinary, mildly useful edge cell. This conditional is looked up live
     *   against [cells] on every call, not baked into a static table.
     * - OTHER EDGE cells (on the border, not a corner or C-square): modestly positive -- edge discs
     *   are harder to flank than interior ones (fewer of the 8 directions apply), with cells nearer
     *   a corner ([EDGE_NEAR_CORNER_WEIGHT]) worth a touch more than dead-center edge cells
     *   ([EDGE_MID_WEIGHT]).
     * - All other INTERIOR cells: a small positive value, [INTERIOR_CORE_WEIGHT] for the true center
     *   and [INTERIOR_RING_WEIGHT] for the ring just inside the edge (a shade lower, since those
     *   cells sit one step from the genuinely dangerous perimeter).
     */
    private fun positionalWeight(index: Int, cells: List<Int?>): Int {
        if (index in CORNERS) return CORNER_WEIGHT
        if (index in X_SQUARES) return X_SQUARE_WEIGHT

        val cornerForCSquare = C_SQUARE_TO_CORNER[index]
        if (cornerForCSquare != null) {
            return if (cells[cornerForCSquare] == null) C_SQUARE_WEIGHT_CORNER_OPEN else C_SQUARE_WEIGHT_CORNER_TAKEN
        }

        val row = index / 8
        val col = index % 8
        val onEdge = row == 0 || row == 7 || col == 0 || col == 7
        if (onEdge) {
            // Whichever coordinate actually varies along this edge (the other is pinned to 0/7);
            // corners/C-squares above already consumed coord values 0/1/6/7, so only 2..5 remain.
            val alongEdge = if (row == 0 || row == 7) col else row
            return if (alongEdge == 2 || alongEdge == 5) EDGE_NEAR_CORNER_WEIGHT else EDGE_MID_WEIGHT
        }

        val ring = row == 1 || row == 6 || col == 1 || col == 6
        return if (ring) INTERIOR_RING_WEIGHT else INTERIOR_CORE_WEIGHT
    }

    /** Corner-first, then other-edge, then interior, then X-/C-squares last -- pure alpha-beta
     *  MOVE-ORDERING (see this class's own KDoc), not a gameplay rule. Reuses the same
     *  corner/X-square/C-square/edge/interior classification [positionalWeight] does, but as a
     *  flat, state-independent priority rank (ordering doesn't need the live corner-taken nuance
     *  that scoring does -- a coarse rank is all pruning needs). */
    private fun moveOrderPriority(index: Int): Int = when {
        index in CORNERS -> 3
        index in X_SQUARES || index in C_SQUARE_TO_CORNER -> 0
        else -> {
            val row = index / 8
            val col = index % 8
            if (row == 0 || row == 7 || col == 0 || col == 7) 2 else 1
        }
    }

    /** Disc count per player index, live for the whole board (not just at game end). */
    private fun computeScores(cells: List<Int?>, playerCount: Int): List<Int> =
        (0 until playerCount).map { p -> cells.count { it == p } }

    /**
     * Walks one step at a time from ([row], [col]) in direction ([dr], [dc]) -- ALWAYS via real
     * row/col bounds checking on every single step, never a flat `index ± delta` shortcut (the
     * classic Othello-engine bug: e.g. `index - 1` from column 0 silently wraps into the previous
     * row's last column instead of walking off the board). Returns true iff it sees one-or-more
     * opponent discs immediately, followed by a disc belonging to [player], before hitting an empty
     * cell or the board edge -- the real, exact Othello flanking-capture rule. Used both to decide
     * whether ([row], [col]) is itself a legal move (any direction flanking is enough) and, in
     * [applyMove], to decide which directions actually get flipped.
     */
    private fun flanksInDirection(cells: List<Int?>, row: Int, col: Int, dr: Int, dc: Int, player: Int): Boolean {
        val opponent = 1 - player
        var r = row + dr
        var c = col + dc
        var sawOpponent = false
        while (r in 0..7 && c in 0..7) {
            when (cells[r * 8 + c]) {
                opponent -> {
                    sawOpponent = true
                    r += dr
                    c += dc
                }
                player -> return sawOpponent
                else -> return false // empty cell -- the line dead-ends before ever reaching player's own disc
            }
        }
        return false // ran off the board without ever reaching player's own disc
    }

    /** True iff [index] is currently empty AND [player] flanks the opponent in at least one of the
     *  8 [DIRECTIONS] from it -- the real Othello legal-move rule, shared verbatim between the
     *  actual move set exposed on [ReversiState.legalMoves] and the bot's own search. */
    private fun isLegalMove(cells: List<Int?>, index: Int, player: Int): Boolean {
        if (cells[index] != null) return false
        val row = index / 8
        val col = index % 8
        return DIRECTIONS.any { (dr, dc) -> flanksInDirection(cells, row, col, dr, dc, player) }
    }

    /** Every legal destination cell for [player] on [cells] -- used both for the actual move set
     *  the UI highlights and for the bot's own search (see this class's own KDoc). */
    private fun legalMovesFor(cells: List<Int?>, player: Int): Set<Int> =
        cells.indices.filterTo(mutableSetOf()) { isLegalMove(cells, it, player) }

    /** Places [player]'s disc at [index], then for every one of the 8 [DIRECTIONS] where
     *  [flanksInDirection] returns true, walks that same direction flipping every opponent disc to
     *  [player]'s own color until it reaches [player]'s own disc (exclusive) -- the real Othello
     *  capture rule. Flanking is decided against the ORIGINAL [cells] (before [index] is placed),
     *  which is always correct here: whether a direction flanks depends only on cells strictly
     *  beyond [index] in that direction, never on [index] itself. */
    private fun applyMove(cells: List<Int?>, index: Int, player: Int): List<Int?> {
        val newCells = cells.toMutableList()
        newCells[index] = player
        val row = index / 8
        val col = index % 8
        for ((dr, dc) in DIRECTIONS) {
            if (!flanksInDirection(cells, row, col, dr, dc, player)) continue
            var r = row + dr
            var c = col + dc
            while (newCells[r * 8 + c] != player) {
                newCells[r * 8 + c] = player
                r += dr
                c += dc
            }
        }
        return newCells
    }

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
        val opponent = 1 - justMoved
        val scores = computeScores(cells, players.size)

        val opponentMoves = legalMovesFor(cells, opponent)
        if (opponentMoves.isNotEmpty()) {
            return ReversiState(
                cells = cells,
                players = players,
                currentPlayerIndex = opponent,
                legalMoves = opponentMoves,
                scores = scores,
                lastAction = actionText
            )
        }

        val moverMoves = legalMovesFor(cells, justMoved)
        if (moverMoves.isNotEmpty()) {
            return ReversiState(
                cells = cells,
                players = players,
                currentPlayerIndex = justMoved,
                legalMoves = moverMoves,
                scores = scores,
                lastAction = "${players[opponent].displayName} has no legal move and passes",
                justPassed = true
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
            winnerPlayerId = winnerId
        )
    }

    private companion object {
        const val CORNER_WEIGHT = 120
        const val X_SQUARE_WEIGHT = -20
        const val C_SQUARE_WEIGHT_CORNER_OPEN = -40
        const val C_SQUARE_WEIGHT_CORNER_TAKEN = 10
        const val EDGE_NEAR_CORNER_WEIGHT = 10
        const val EDGE_MID_WEIGHT = 6
        const val INTERIOR_RING_WEIGHT = 1
        const val INTERIOR_CORE_WEIGHT = 3

        /** Small constant weighting how much a mobility ADVANTAGE (more legal moves than the
         *  opponent) is worth relative to the positional table above -- see [evaluate]'s own KDoc. */
        const val MOBILITY_WEIGHT = 12

        /** Magnitude of a confirmed win/loss score -- comfortably larger than any possible sum of
         *  [positionalWeight]/mobility heuristic scores (bounded by 64 cells times the highest
         *  single-cell weight, plus a small mobility term), so a real win is always preferred over
         *  any merely-good-looking heuristic position. Same value/role as ConnectFourGame's own
         *  WIN_SCORE. */
        const val WIN_SCORE = 1_000_000

        /** Explicit (dr, dc) pairs -- see [flanksInDirection]'s own KDoc for why this is never a
         *  flat index-delta approach. */
        val DIRECTIONS = listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)

        val CORNERS = setOf(0, 7, 56, 63)
        val X_SQUARES = setOf(9, 14, 49, 54)

        /** Every C-square index mapped to the corner it sits next to -- see [positionalWeight]'s
         *  own KDoc for why that corner's own occupancy is looked up live rather than baked in. */
        val C_SQUARE_TO_CORNER = mapOf(
            1 to 0, 8 to 0,
            6 to 7, 15 to 7,
            48 to 56, 57 to 56,
            55 to 63, 62 to 63
        )
    }
}
