package com.gamesuite.games.mancala

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
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
    val difficulty: String
)

@Serializable
private data class MancalaSnapshotCapture(val landingPit: Int, val oppositePit: Int, val totalSwept: Int)

@Serializable
private data class MancalaSnapshotPlayer(val playerId: String, val isBot: Boolean)

private val mancalaSnapshotJson = Json { ignoreUnknownKeys = true }

/**
 * Board layout (14 pits, index 0-13):
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

/**
 * Pure result of a hypothetical sow, used both by the HARD bot's minimax
 * search ([MancalaGame.simulateSow]) and by [MancalaGame.captureCandidates]'s
 * capture preview. [landingCursor] is the pit the last stone landed in --
 * exposing it lets a caller recognize a capture (see captureCandidates'
 * KDoc) without re-deriving the sow loop's own cursor math.
 */
private data class SowResult(val pits: List<Int>, val extraTurn: Boolean, val landingCursor: Int)

class MancalaGame : GameModule, ResumableGame, UndoableGame {
    override val gameId = "mancala"
    override val displayName = "Mancala"
    override val category = GameCategory.BOARD
    override val minPlayers = 2
    override val maxPlayers = 2
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT
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

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    private val player0Pits = 0..5
    private val player0Store = 6
    private val player1Pits = 7..12
    private val player1Store = 13

    /** Snapshots taken just BEFORE each human sowing, newest last; popping one is Undo. Per game: a new board starts empty. */
    private val history = SnapshotHistory()

    /** The pit (absolute board index 0-12) the last [hint] suggested, shown ringed until the next sow, undo or new board. */
    val hintPit = mutableStateOf<Int?>(null)

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        scoreP1.value = 0
        scoreP2.value = 0
        draws.value = 0
        history.clear()
        hintPit.value = null
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
                difficulty = difficulty.name
            )
        )
    }

    /** Decodes and sanity-checks a snapshot against THIS game's board shape and roster; null if unusable. */
    private fun decodeSnapshot(text: String): MancalaSnapshot? {
        return try {
            val snap = mancalaSnapshotJson.decodeFromString(MancalaSnapshot.serializer(), text)
            val roster = context.players
            val capture = snap.lastCapture
            val valid = snap.pits.size == 14 &&
                snap.pits.all { it >= 0 } &&
                snap.pits.sum() == 48 &&
                snap.players.size == roster.size &&
                snap.players.indices.all {
                    snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot
                } &&
                snap.currentPlayerIndex in 0..1 &&
                (snap.winnerPlayerId == null || roster.any { it.playerId == snap.winnerPlayerId }) &&
                snap.lastSowPath.size <= 60 && snap.lastSowPath.all { it in 0..13 } &&
                (capture == null || (capture.landingPit in 0..13 && capture.oppositePit in 0..13 && capture.totalSwept >= 0)) &&
                snap.scoreP1 >= 0 && snap.scoreP2 >= 0 && snap.draws >= 0 &&
                (snap.roundOver || (player0Pits.any { snap.pits[it] > 0 } && player1Pits.any { snap.pits[it] > 0 }))
            if (valid) snap else null
        } catch (_: Exception) {
            null
        }
    }

    private fun applySnapshot(snap: MancalaSnapshot, keepCounters: Boolean = false) {
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
        history.clear()
        return true
    }

    // --- Undo -------------------------------------------------------------------------------

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value

    /**
     * Takes back the last human sowing. Against the CPU that also takes back the CPU's reply (the
     * snapshot was taken before the human's sow), so you are back on your own turn. After an extra
     * turn each human sowing is its own step. Undoing a game-ending sow re-opens the board and
     * takes its win off the session tally. Mancala has no mistake/hint counters to protect, and
     * the difficulty is left as it is.
     */
    override fun undo() {
        if (matchOver.value) return
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
        val s = state.value ?: return null
        if (matchOver.value || s.roundOver) return null
        if (context.players.getOrNull(s.currentPlayerIndex)?.isBot != false) return null
        val pit = minimaxBestMove(s.pits, s.currentPlayerIndex, depth = HARD_SEARCH_DEPTH)
        hintPit.value = pit
        return pit
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        val pits = MutableList(14) { 0 }
        for (i in player0Pits) pits[i] = 4
        for (i in player1Pits) pits[i] = 4
        // A new board starts a new undo history: undo never reaches back into the previous board.
        history.clear()
        hintPit.value = null
        state.value = MancalaState(pits = pits, currentPlayerIndex = 0, lastAction = "Game started")
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
     * Mirrors TicTacToeGame.playAgain().
     */
    fun playAgain() {
        if (matchOver.value) return
        startMatch()
    }

    fun sow(playerIndex: Int, pitIndex: Int) {
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
        if (context.players.getOrNull(playerIndex)?.isBot == false) {
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
        while (stones > 0) {
            cursor = (cursor + 1) % 14
            if (cursor == opponentStore) continue // skip opponent's store
            pits[cursor]++
            stones--
            sowPath.add(cursor)
        }

        var extraTurn = false
        var captureMsg = ""
        var capture: MancalaCapture? = null

        // Landed in own empty pit (was 0 before this sow, now 1) -> capture.
        if (cursor in ownPits && pits[cursor] == 1) {
            val oppositeIndex = 12 - cursor
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
        if (matchOver.value) return
        val s = state.value ?: return
        if (s.roundOver) return
        val botIndex = s.currentPlayerIndex
        if (context.players.getOrNull(botIndex)?.isBot != true) return

        val ownPits = if (botIndex == 0) player0Pits else player1Pits
        val ownStore = if (botIndex == 0) player0Store else player1Store

        val move = when (difficulty) {
            CpuDifficulty.EASY -> ownPits.filter { s.pits[it] > 0 }.randomOrNull()
            CpuDifficulty.MEDIUM -> {
                // Prefer a move landing exactly in the store (extra turn); else first non-empty pit.
                val bonusMove = ownPits.firstOrNull { pit -> s.pits[pit] > 0 && (pit + s.pits[pit]) % 14 == ownStore }
                bonusMove ?: ownPits.firstOrNull { s.pits[it] > 0 }
            }
            CpuDifficulty.HARD -> minimaxBestMove(s.pits, botIndex, depth = HARD_SEARCH_DEPTH)
        }
        if (move != null) sow(botIndex, move)
    }

    // ---- HARD: depth-limited minimax with alpha-beta pruning over a pure (side-effect-free)
    // copy of the sow rules. Kept entirely separate from sow()/the real Compose state — this
    // only ever reads/returns plain List<Int> boards, so exploring thousands of hypothetical
    // moves per turn never touches (or risks corrupting) `state`.

    private fun legalMoves(pits: List<Int>, playerIndex: Int): List<Int> {
        val ownPits = if (playerIndex == 0) player0Pits else player1Pits
        return ownPits.filter { pits[it] > 0 }
    }

    private fun isTerminal(pits: List<Int>): Boolean =
        player0Pits.all { pits[it] == 0 } || player1Pits.all { pits[it] == 0 }

    /** Same end-of-game sweep as sow()'s roundOver branch, applied to a hypothetical board. */
    private fun finalScoreFor(pits: List<Int>, maximizer: Int): Int {
        val p = pits.toMutableList()
        if (player0Pits.all { p[it] == 0 }) { for (i in player1Pits) { p[player1Store] += p[i]; p[i] = 0 } }
        if (player1Pits.all { p[it] == 0 }) { for (i in player0Pits) { p[player0Store] += p[i]; p[i] = 0 } }
        val myStore = if (maximizer == 0) p[player0Store] else p[player1Store]
        val oppStore = if (maximizer == 0) p[player1Store] else p[player0Store]
        return myStore - oppStore
    }

    /** A pure copy of sow()'s rules — same capture/extra-turn logic, no Compose state touched. */
    private fun simulateSow(pits: List<Int>, playerIndex: Int, pitIndex: Int): SowResult {
        val ownPits = if (playerIndex == 0) player0Pits else player1Pits
        val ownStore = if (playerIndex == 0) player0Store else player1Store
        val opponentStore = if (playerIndex == 0) player1Store else player0Store

        val p = pits.toMutableList()
        var stones = p[pitIndex]
        p[pitIndex] = 0
        var cursor = pitIndex
        while (stones > 0) {
            cursor = (cursor + 1) % 14
            if (cursor == opponentStore) continue
            p[cursor]++
            stones--
        }

        var extraTurn = false
        if (cursor in ownPits && p[cursor] == 1) {
            val oppositeIndex = 12 - cursor
            if (p[oppositeIndex] > 0) {
                val captured = p[oppositeIndex] + p[cursor]
                p[oppositeIndex] = 0
                p[cursor] = 0
                p[ownStore] += captured
            }
        } else if (cursor == ownStore) {
            extraTurn = true
        }
        return SowResult(p, extraTurn, cursor)
    }

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
        val ownPits = if (playerIndex == 0) player0Pits else player1Pits
        return legalMoves(s.pits, playerIndex).filter { pit ->
            val result = simulateSow(s.pits, playerIndex, pit)
            result.landingCursor in ownPits && result.pits[result.landingCursor] == 0
        }.toSet()
    }

    private fun minimax(pits: List<Int>, playerIndex: Int, depth: Int, maximizer: Int, alpha: Int, beta: Int): Int {
        if (depth == 0 || isTerminal(pits)) return finalScoreFor(pits, maximizer)
        val moves = legalMoves(pits, playerIndex)
        if (moves.isEmpty()) return finalScoreFor(pits, maximizer)

        var a = alpha
        var b = beta
        if (playerIndex == maximizer) {
            var best = Int.MIN_VALUE
            for (m in moves) {
                val (nextPits, extraTurn) = simulateSow(pits, playerIndex, m)
                val nextPlayer = if (extraTurn) playerIndex else 1 - playerIndex
                best = maxOf(best, minimax(nextPits, nextPlayer, depth - 1, maximizer, a, b))
                a = maxOf(a, best)
                if (a >= b) break
            }
            return best
        } else {
            var best = Int.MAX_VALUE
            for (m in moves) {
                val (nextPits, extraTurn) = simulateSow(pits, playerIndex, m)
                val nextPlayer = if (extraTurn) playerIndex else 1 - playerIndex
                best = minOf(best, minimax(nextPits, nextPlayer, depth - 1, maximizer, a, b))
                b = minOf(b, best)
                if (a >= b) break
            }
            return best
        }
    }

    private fun minimaxBestMove(pits: List<Int>, playerIndex: Int, depth: Int): Int? {
        val moves = legalMoves(pits, playerIndex)
        var bestMove: Int? = null
        var bestScore = Int.MIN_VALUE
        for (m in moves) {
            val (nextPits, extraTurn) = simulateSow(pits, playerIndex, m)
            val nextPlayer = if (extraTurn) playerIndex else 1 - playerIndex
            val score = minimax(nextPits, nextPlayer, depth - 1, playerIndex, Int.MIN_VALUE, Int.MAX_VALUE)
            if (score > bestScore) {
                bestScore = score
                bestMove = m
            }
        }
        return bestMove
    }

    companion object {
        /**
         * 8 plies deep. Kalah's branching factor is at most 6 (fewer as pits
         * empty), so even fully unpruned this is ~6^8 ≈ 1.7M leaf boards in
         * the worst case — alpha-beta cuts that drastically in practice —
         * comfortably fast enough to run synchronously on the UI thread
         * during the same bot-turn delay every other game's bot already
         * uses, with no separate background dispatch needed.
         */
        private const val HARD_SEARCH_DEPTH = 8
    }
}
