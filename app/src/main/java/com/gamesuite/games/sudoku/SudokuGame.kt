package com.gamesuite.games.sudoku

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * One cell of a [SudokuSnapshot]. [value] 0 means empty. The hidden solution travels in the
 * snapshot's own [SudokuSnapshot.solution] list, so a resumed puzzle is the SAME puzzle.
 */
@Serializable
private data class SudokuSnapshotCell(val value: Int, val given: Boolean, val notes: List<Int>)

/**
 * Everything needed to put a Sudoku session back exactly as it was. One shape serves Resume (disk)
 * and Undo (memory). The clock is stored as ACTIVE elapsed milliseconds, never as an absolute clock
 * reading, so a resumed puzzle continues from where it was and does not count the time away.
 */
@Serializable
private data class SudokuSnapshot(
    val playerIds: List<String>,
    val cells: List<SudokuSnapshotCell>,
    val solution: List<Int>,
    val selectedIndex: Int?,
    val mistakes: Int,
    val puzzlesSolved: Int,
    val hintsUsed: Int,
    val hintedCells: List<Int>,
    val difficulty: String,
    val dailySeed: Long?,
    val timerStarted: Boolean,
    val elapsedMillis: Long,
    val finishedMillis: Long?
)

private val sudokuSnapshotJson = Json { ignoreUnknownKeys = true }

/**
 * One of 81 cells, row-major (index = row * 9 + col).
 *
 * [isGiven] cells are the puzzle's original clues and can never be edited —
 * every mutating engine method (setValue/clearValue/toggleNote) no-ops on
 * one. [notes] are pencil marks (1-9); a cell that already has a [value]
 * never carries notes (setValue clears them; toggleNote refuses to add one).
 */
data class SudokuCell(
    val value: Int? = null,
    val isGiven: Boolean = false,
    val notes: Set<Int> = emptySet()
)

/**
 * [cells] and [solution] are both row-major length-81 lists. [solution] is
 * the one true completion of this board — kept alongside [cells] (not
 * hidden) purely so [SudokuGame.setValue] can tell a right entry from a
 * wrong one without re-deriving it; nothing stops a determined player from
 * reading it off `state.value`, the same non-concern as `MinesweeperCell`
 * exposing `isMine` on hidden cells (an offline single-player local game has
 * no "the server hides this from the client" boundary to defend anyway).
 */
data class SudokuState(
    val cells: List<SudokuCell>,
    val solution: List<Int>,
    val selectedIndex: Int? = null,
    /** Every wrong entry ever placed, counted at the moment it's placed — see [SudokuGame.setValue]'s KDoc for why this never decrements. */
    val mistakes: Int = 0,
    val won: Boolean = false
) {
    val isOver: Boolean get() = won
}

/**
 * Classic 9x9 Sudoku: three overlapping constraints (each row, column, and
 * 3x3 box contains 1-9 exactly once) and a puzzle that's solvable by logic
 * alone from its given clues. Select a cell, then either enter a value or
 * (in notes mode, same "explicit mode toggle" idiom as Minesweeper's flag
 * mode, since this app has no long-press-vs-tap distinction to spare) toggle
 * a pencil mark.
 *
 * GENERATION is the one genuinely nontrivial piece of this engine — see
 * [generateSolvedGrid], [carvePuzzle], and [countSolutions]'s own KDocs. In
 * short: fill a complete valid grid via randomized backtracking, then remove
 * clues one at a time, keeping each removal only if the puzzle-so-far still
 * has EXACTLY ONE solution (verified by actually re-solving it via a
 * minimum-remaining-values backtracking solver under a hard search-effort
 * budget, not assumed) — the standard, well-established technique for
 * guaranteeing a puzzle is uniquely, logically solvable rather than merely
 * "looks like Sudoku." An earlier version of the solver used a naive
 * fixed-order search with no effort bound at all; adversarial review found
 * it could blow up combinatorially (multi-million-step searches, real risk
 * of an on-device ANR) for a real fraction of seeds at HARD's clue count —
 * [countSolutions]'s KDoc has the full story and the fix.
 *
 * Difficulty (no bot here either, so the lever is the puzzle itself, same
 * idiom as Minesweeper/Sliding Puzzle) scales target clue count: EASY 42,
 * MEDIUM 32, HARD 26 — real Sudoku-difficulty ranges, though see
 * [carvePuzzle]'s KDoc for why a given tier's actual clue count can land
 * slightly above its target rather than exactly on it.
 *
 * DELIBERATE SCOPE CUTS (honest MVP, same spirit as every other game's own
 * documented cuts):
 *   - No mistake limit / game-over — [SudokuState.mistakes] is shown to the
 *     player as feedback, not enforced as a fail condition. A "3 strikes"
 *     mode is a real, separable feature, not required for a correct,
 *     playable first version.
 *   - No real-time row/col/box conflict highlighting — a wrong entry is
 *     detected by comparing against the known [SudokuState.solution]
 *     (simple and always correct for a puzzle generated with a unique
 *     solution), not by independently re-deriving which cells conflict with
 *     which. Good enough to tell the player "that's wrong"; a future pass
 *     could highlight exactly which peer(s) it conflicts with.
 *   - Undo is a snapshot stack ([SnapshotHistory]) pushed before each entry,
 *     erase, note toggle or hint; it is per puzzle and closed once the
 *     puzzle is solved (the solve is already counted and recorded).
 *   - Daily-seed support mirrors MinesweeperGame/SlidingPuzzleGame's
 *     `startMatch(dailySeed)` exact shape/reasoning — this file never reads
 *     today's date itself.
 */
class SudokuGame(private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() }) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "sudoku"
    override val displayName = "Sudoku"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<SudokuState?>(null)
    val puzzlesSolved = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via "Back to Menu"), not per-board. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** Same "stopwatch starts on the first real move" idiom as MinesweeperGame's own field — see that class's KDoc. */
    val timerStartElapsedRealtime = mutableStateOf<Long?>(null)

    /** Total time for the current board, frozen the instant [setValue] detects a win; null until then. */
    val finishedElapsedMillis = mutableStateOf<Long?>(null)

    /** Hints used on the CURRENT puzzle; any hint makes the solve "assisted" (no best-time record). Never decreases within a puzzle, not even through undo. */
    val hintsUsed = mutableStateOf(0)

    /** Cells whose digit was filled in by a hint, so the screen can mark them with a non-colour cue. A cell leaves the set when the player erases or overwrites it. */
    val hintedCells = mutableStateOf<Set<Int>>(emptySet())

    /** Snapshots taken just BEFORE each human entry (digit, erase, note toggle) or hint; popping one is Undo. Per puzzle. */
    private val history = SnapshotHistory()

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var currentDailySeed: Long? = null

    /** The daily seed of the puzzle on screen, or null for a random puzzle; lets the screen re-derive its "Daily puzzle" tag after a resume. */
    val dailySeedInUse: Long? get() = currentDailySeed

    /** [pause]'s own nowMillis() reading, or null while not currently paused — see [pause]/[resume]'s KDoc. */
    private var pausedAtElapsedRealtime: Long? = null

    /** Total time spent paused during the CURRENT board, subtracted out in [freezeTimer] — see [pause]/[resume]'s KDoc. */
    private var totalPausedMillis: Long = 0L

    /** Target clue count per tier — a floor [carvePuzzle] tries to reach, not a guarantee; see that method's KDoc. */
    private val difficultyClueTarget: Map<CpuDifficulty, Int> = mapOf(
        CpuDifficulty.EASY to 42,
        CpuDifficulty.MEDIUM to 32,
        CpuDifficulty.HARD to 26
    )

    override fun init(context: GameContext) {
        this.context = context
        puzzlesSolved.value = 0
        matchOver.value = false
        hintsUsed.value = 0
        hintedCells.value = emptySet()
        history.clear()
    }

    // --- Resume -----------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        val roster = if (::context.isInitialized) context.players.map { it.playerId } else emptyList()
        return sudokuSnapshotJson.encodeToString(
            SudokuSnapshot.serializer(),
            SudokuSnapshot(
                playerIds = roster,
                cells = s.cells.map { SudokuSnapshotCell(it.value ?: 0, it.isGiven, it.notes.sorted()) },
                solution = s.solution,
                selectedIndex = s.selectedIndex,
                mistakes = s.mistakes,
                puzzlesSolved = puzzlesSolved.value,
                hintsUsed = hintsUsed.value,
                hintedCells = hintedCells.value.sorted(),
                difficulty = difficulty.name,
                dailySeed = currentDailySeed,
                timerStarted = timerStartElapsedRealtime.value != null,
                elapsedMillis = activeElapsedMillis() ?: 0L,
                finishedMillis = finishedElapsedMillis.value
            )
        )
    }

    /** Decodes and sanity-checks a snapshot against THIS game's roster and board shape; null if unusable. */
    private fun decodeSnapshot(text: String): SudokuSnapshot? {
        val snap = try {
            sudokuSnapshotJson.decodeFromString(SudokuSnapshot.serializer(), text)
        } catch (_: Exception) {
            return null
        }
        if (!::context.isInitialized) return null
        val roster = context.players.map { it.playerId }
        if (snap.playerIds != roster) return null
        if (snap.cells.size != 81 || snap.solution.size != 81) return null
        if (snap.solution.any { it !in 1..9 }) return null
        for (i in 0 until 81) {
            val c = snap.cells[i]
            if (c.value !in 0..9) return null
            if (c.notes.any { it !in 1..9 }) return null
            if (c.value != 0 && c.notes.isNotEmpty()) return null
            if (c.given && c.value != snap.solution[i]) return null
        }
        // The solution must itself be a valid grid: every row, column and box holds 1-9 once.
        for (u in 0 until 9) {
            var rowBits = 0
            var colBits = 0
            var boxBits = 0
            for (k in 0 until 9) {
                rowBits = rowBits or (1 shl snap.solution[u * 9 + k])
                colBits = colBits or (1 shl snap.solution[k * 9 + u])
                boxBits = boxBits or (1 shl snap.solution[((u / 3) * 3 + k / 3) * 9 + (u % 3) * 3 + k % 3])
            }
            if (rowBits != ALL_DIGITS_MASK || colBits != ALL_DIGITS_MASK || boxBits != ALL_DIGITS_MASK) return null
        }
        if (snap.selectedIndex != null && snap.selectedIndex !in 0 until 81) return null
        if (snap.mistakes < 0 || snap.puzzlesSolved < 0 || snap.hintsUsed < 0 || snap.elapsedMillis < 0L) return null
        if (snap.finishedMillis != null && snap.finishedMillis < 0L) return null
        if (snap.hintedCells.any { it !in 0 until 81 }) return null
        return snap
    }

    private fun snapshotCells(snap: SudokuSnapshot): List<SudokuCell> =
        snap.cells.map { SudokuCell(value = if (it.value == 0) null else it.value, isGiven = it.given, notes = it.notes.toSet()) }

    /** Null when there is nothing to save: no board yet, the session already ended, or a mode whose state lives with other players. */
    override fun snapshot(): String? {
        return try {
            if (matchOver.value || state.value == null || !::context.isInitialized) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            captureSnapshot()
        } catch (_: Exception) {
            null
        }
    }

    override fun restore(snapshot: String): Boolean {
        return try {
            val snap = decodeSnapshot(snapshot) ?: return false
            val cells = snapshotCells(snap)
            val won = cells.indices.all { cells[it].value == snap.solution[it] }
            // A solved board always has a running clock; one without would record a bogus 0 ms best time.
            if (won && !snap.timerStarted) return false
            val newState = SudokuState(
                cells = cells,
                solution = snap.solution,
                selectedIndex = snap.selectedIndex,
                mistakes = snap.mistakes,
                won = won
            )
            val tier = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
            // Everything validated and computed: assign.
            matchOver.value = false
            difficulty = tier
            currentDailySeed = snap.dailySeed
            puzzlesSolved.value = snap.puzzlesSolved
            hintsUsed.value = snap.hintsUsed
            hintedCells.value = snap.hintedCells.toSet()
            pausedAtElapsedRealtime = null
            totalPausedMillis = 0L
            timerStartElapsedRealtime.value = if (snap.timerStarted) nowMillis() - snap.elapsedMillis else null
            finishedElapsedMillis.value = if (won && snap.timerStarted) (snap.finishedMillis ?: snap.elapsedMillis) else null
            state.value = newState
            history.clear()
            true
        } catch (_: Exception) {
            false
        }
    }

    // --- Undo -------------------------------------------------------------------------------

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value && state.value?.isOver != true

    /**
     * Takes back the last cell entry or note toggle (or a hint's fill). Mistakes and hints used never
     * go down, and the clock keeps running. Not available once the puzzle is solved, because the
     * solve has already been counted and recorded.
     */
    override fun undo() {
        val s = state.value ?: return
        if (matchOver.value || s.isOver) return
        val previous = history.pop() ?: return
        val snap = decodeSnapshot(previous) ?: return
        if (snap.solution != s.solution) return
        state.value = SudokuState(
            cells = snapshotCells(snap),
            solution = s.solution,
            selectedIndex = snap.selectedIndex,
            mistakes = maxOf(s.mistakes, snap.mistakes),
            won = false
        )
        hintsUsed.value = maxOf(hintsUsed.value, snap.hintsUsed)
        hintedCells.value = snap.hintedCells.toSet()
    }

    private fun pushUndo() {
        captureSnapshot()?.let { history.push(it) }
    }

    // --- Hint -------------------------------------------------------------------------------

    /**
     * Fills one cell with its correct digit and returns its index, or null when there is nothing
     * to fill (board solved or session over). Picks the selected cell when it is empty, otherwise
     * the empty cell with the fewest candidates (lowest index on ties); a board with no empty cell
     * left but wrong entries gets the first wrong entry corrected. The cell is marked in
     * [hintedCells], [hintsUsed] goes up, and the puzzle becomes "assisted": its solve must not set
     * a best-time record. Never counts as a mistake.
     */
    fun hint(): Int? {
        val s = state.value ?: return null
        if (matchOver.value || s.isOver) return null
        val target = chooseHintCell(s) ?: return null
        pushUndo()
        hintsUsed.value += 1
        placeValue(s, target, s.solution[target], hinted = true)
        return target
    }

    private fun chooseHintCell(s: SudokuState): Int? {
        val selected = s.selectedIndex
        if (selected != null && selected in s.cells.indices) {
            val c = s.cells[selected]
            if (!c.isGiven && c.value == null) return selected
        }
        var best = -1
        var bestCount = 10
        for (i in 0 until 81) {
            val c = s.cells[i]
            if (c.isGiven || c.value != null) continue
            var used = 0
            for (p in peersOf(i)) {
                val v = s.cells[p].value
                if (v != null) used = used or (1 shl v)
            }
            val count = Integer.bitCount(ALL_DIGITS_MASK and used.inv())
            if (count < bestCount) {
                bestCount = count
                best = i
            }
        }
        if (best >= 0) return best
        return (0 until 81).firstOrNull { i ->
            val c = s.cells[i]
            !c.isGiven && c.value != null && c.value != s.solution[i]
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() = startMatch(dailySeed = null)

    /** See this class's KDoc / MinesweeperGame.startMatch(dailySeed)'s KDoc for what this is for. */
    fun startMatch(dailySeed: Long?) {
        val random = dailySeed?.let { Random(it) } ?: Random.Default
        currentDailySeed = dailySeed
        val solved = generateSolvedGrid(random)
        val target = difficultyClueTarget[difficulty] ?: difficultyClueTarget.getValue(CpuDifficulty.MEDIUM)
        val puzzle = carvePuzzle(solved, target, random)

        timerStartElapsedRealtime.value = null
        finishedElapsedMillis.value = null
        pausedAtElapsedRealtime = null
        totalPausedMillis = 0L
        // A new puzzle starts a new undo history and a fresh hint count: undo never reaches back into the previous puzzle.
        history.clear()
        hintsUsed.value = 0
        hintedCells.value = emptySet()
        // A fresh board is always playable, regardless of whether a PRIOR
        // board's endMatch() left matchOver stuck true (see the class KDoc's
        // note on this fix) -- found by adversarial review, see that note.
        matchOver.value = false
        state.value = SudokuState(
            cells = List(81) { i ->
                val v = puzzle[i]
                SudokuCell(value = if (v == 0) null else v, isGiven = v != 0)
            },
            solution = solved.toList()
        )
    }

    /**
     * Snapshots the current time so [resume] can measure how long the app
     * was actually paused. FOUND BY ADVERSARIAL REVIEW (during the Lights
     * Out pass, which shares this exact pattern) — [pause]/[resume] used to
     * be empty no-ops while the timer was a pure wall-clock delta
     * (`nowMillis() - start` in [freezeTimer]) — since
     * `SystemClock.elapsedRealtime()` keeps advancing while the app is
     * backgrounded (unlike `uptimeMillis()`), backgrounding mid-puzzle for
     * even a few minutes silently inflated the recorded solve time (and
     * therefore any "best time" record) by the ENTIRE background duration.
     * `GameSessionManager.pause()`/`resume()` really do forward from
     * `MainActivity.onPause()`/`onResume()` — this is not a
     * theoretical/unwired path, it fires on every real backgrounding.
     *
     * Guarded by `pausedAtElapsedRealtime == null` so a second [pause] call
     * with no [resume] in between is a no-op rather than silently moving the
     * anchor forward and losing the intervening interval — the same
     * asymmetry-with-[resume] gap found and fixed in ColorFloodGame.pause()
     * (see that class's KDoc), closed here defensively for the same reason.
     */
    override fun pause() {
        if (pausedAtElapsedRealtime == null && timerStartElapsedRealtime.value != null && state.value?.isOver != true) {
            pausedAtElapsedRealtime = nowMillis()
        }
    }

    /** Accumulates the just-finished pause's duration into [totalPausedMillis] — see [pause]'s KDoc. */
    override fun resume() {
        pausedAtElapsedRealtime?.let {
            totalPausedMillis += nowMillis() - it
            pausedAtElapsedRealtime = null
        }
    }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Selects [index] as the target of the next setValue/clearValue/toggleNote call. Any cell is selectable (including givens) purely for highlighting — only edits are blocked on a given. Out-of-range indices are ignored rather than stored, same guarded-no-op treatment every other invalid state gets here — found missing by adversarial review before this ever shipped. Also a no-op once the whole session has ended via [leaveSession]/[endMatch] (see [setValue]'s KDoc for why). */
    fun selectCell(index: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.isOver) return
        if (index !in s.cells.indices) return
        state.value = s.copy(selectedIndex = index)
    }

    /**
     * Sets the selected cell's value (1-9). No-op if nothing is selected,
     * the cell is a given, the board is already won, or the whole session
     * has already ended via [leaveSession]/[endMatch] — found missing by
     * ColorFloodGame.pick()'s own adversarial review (only the per-board
     * `isOver` was checked, never `matchOver`, so a call after the final
     * `GameResult` had already been delivered could still mutate state,
     * including a phantom win with no second result ever reported — see
     * that class's KDoc). Not currently reachable through this app's real
     * screens, but a real gap worth closing defensively regardless. A wrong
     * entry is still accepted (not blocked) and counted in
     * [SudokuState.mistakes] — that counter is a running total of every
     * wrong placement EVER made, not a live "currently wrong" count, so
     * correcting a mistake afterward does not decrement it. This matches how
     * most real Sudoku apps track mistakes (a session stat), not a
     * strict/enforced limit — see this class's own KDoc on why there's no
     * game-over here.
     *
     * Returns true if the entry was applied (a wrong digit still counts as applied) and false
     * if it was one of the no-ops above, so the screen can tell a real placement from a tap
     * that did nothing and say so instead of playing the placement feedback anyway.
     */
    fun setValue(value: Int): Boolean {
        val s = state.value ?: return false
        val index = s.selectedIndex ?: return false
        if (matchOver.value || s.isOver) return false
        val cell = s.cells[index]
        if (cell.isGiven) return false
        // Only digits 1-9 are real entries; anything else would produce a state no snapshot can describe.
        if (value !in 1..9) return false

        pushUndo()
        placeValue(s, index, value, hinted = false)
        return true
    }

    /** Applies a digit to [index]: starts the clock, clears peers' notes, counts a mistake (never for a hint) and detects the win. Callers have already validated and pushed undo. */
    private fun placeValue(s: SudokuState, index: Int, value: Int, hinted: Boolean) {
        val cell = s.cells[index]
        if (timerStartElapsedRealtime.value == null) timerStartElapsedRealtime.value = nowMillis()

        val cells = s.cells.toMutableList()
        cells[index] = cell.copy(value = value, notes = emptySet())
        // Placing a value clears that same digit from every peer's pencil
        // marks -- the standard "auto-clean notes" convenience real Sudoku
        // apps have; a pure bookkeeping nicety, not a rules requirement.
        for (p in peersOf(index)) {
            val peer = cells[p]
            if (value in peer.notes) cells[p] = peer.copy(notes = peer.notes - value)
        }

        val mistakes = if (!hinted && value != s.solution[index]) s.mistakes + 1 else s.mistakes
        val won = cells.indices.all { cells[it].value == s.solution[it] }
        hintedCells.value = if (hinted) hintedCells.value + index else hintedCells.value - index
        state.value = s.copy(
            cells = cells,
            mistakes = mistakes,
            won = won,
            selectedIndex = if (hinted) index else s.selectedIndex
        )
        if (won) {
            puzzlesSolved.value += 1
            freezeTimer()
        }
    }

    /** Clears the selected cell's value only (notes are untouched). No-op on a given cell, an already-empty cell, an already-won board, or once the whole session has already ended via [leaveSession]/[endMatch] (see [setValue]'s KDoc for why). Returns true only if a value was actually erased. */
    fun clearValue(): Boolean {
        val s = state.value ?: return false
        val index = s.selectedIndex ?: return false
        if (matchOver.value || s.isOver) return false
        val cell = s.cells[index]
        if (cell.isGiven || cell.value == null) return false
        pushUndo()
        val cells = s.cells.toMutableList()
        cells[index] = cell.copy(value = null)
        hintedCells.value = hintedCells.value - index
        state.value = s.copy(cells = cells)
        return true
    }

    /** Toggles a pencil mark on the selected cell. No-op on a given cell, a cell that already holds a value, an already-won board, or once the whole session has already ended via [leaveSession]/[endMatch] (see [setValue]'s KDoc for why). Returns true only if a mark was actually toggled. */
    fun toggleNote(value: Int): Boolean {
        val s = state.value ?: return false
        val index = s.selectedIndex ?: return false
        if (matchOver.value || s.isOver) return false
        val cell = s.cells[index]
        if (cell.isGiven || cell.value != null || value !in 1..9) return false
        pushUndo()
        val cells = s.cells.toMutableList()
        cells[index] = cell.copy(notes = if (value in cell.notes) cell.notes - value else cell.notes + value)
        state.value = s.copy(cells = cells)
        return true
    }

    private fun freezeTimer() {
        val start = timerStartElapsedRealtime.value ?: nowMillis()
        finishedElapsedMillis.value = (nowMillis() - start) - totalPausedMillis
    }

    /**
     * Pause-aware live reading of the current board's stopwatch, for the on-screen clock: null
     * before the first digit is placed, frozen at the pause instant while [pause]d (so
     * backgrounding never makes the display tick ahead and then jump back at the solve), and
     * exactly [finishedElapsedMillis] once the board is won (so the clock ends on the very
     * number that gets recorded). Read-only; never mutates the timer.
     */
    fun activeElapsedMillis(): Long? {
        val start = timerStartElapsedRealtime.value ?: return null
        finishedElapsedMillis.value?.let { return it }
        val upTo = pausedAtElapsedRealtime ?: nowMillis()
        return (upTo - start - totalPausedMillis).coerceAtLeast(0L)
    }

    /** Called from the finished-board panel's "New Puzzle" button — keeps the running tally. */
    fun playAgain() {
        if (matchOver.value) return
        startMatch()
    }

    /** Called from the finished-board panel's (or in-progress screen's) "Back to Menu" button — ends the whole session. */
    fun leaveSession() {
        if (matchOver.value) return
        val player = context.players.getOrNull(context.localPlayerIndex)
        val result = GameResult(
            scores = if (player != null) listOf(
                PlayerScore(playerId = player.playerId, score = puzzlesSolved.value, isWinner = puzzlesSolved.value > 0)
            ) else emptyList()
        )
        endMatch(result)
    }

    private fun boxIndex(row: Int, col: Int) = (row / 3) * 3 + (col / 3)

    /** Every OTHER index sharing [index]'s row, column, or 3x3 box. */
    private fun peersOf(index: Int): List<Int> {
        val row = index / 9
        val col = index % 9
        val box = boxIndex(row, col)
        return (0 until 81).filter { it != index && (it / 9 == row || it % 9 == col || boxIndex(it / 9, it % 9) == box) }
    }

    /**
     * Randomized backtracking fill of a complete, valid 81-cell grid (0-8
     * masks track which digits 1-9 are already used per row/column/box, so
     * placement legality is an O(1) bitwise check rather than a scan).
     * Tries digits 1..9 in random order per cell, backtracking on dead ends.
     * Always terminates with a valid grid — a full backtracking search over
     * Sudoku's constraint space cannot fail to find one — and does so fast
     * in practice (this is the standard technique; see [SudokuGameTest] for
     * an actual measured timing, not just an assumption).
     */
    private fun generateSolvedGrid(random: Random): IntArray {
        val grid = IntArray(81)
        val rowMask = IntArray(9)
        val colMask = IntArray(9)
        val boxMask = IntArray(9)

        fun fill(pos: Int): Boolean {
            if (pos == 81) return true
            val row = pos / 9
            val col = pos % 9
            val box = boxIndex(row, col)
            for (d in (1..9).shuffled(random)) {
                val bit = 1 shl d
                if (rowMask[row] and bit == 0 && colMask[col] and bit == 0 && boxMask[box] and bit == 0) {
                    rowMask[row] = rowMask[row] or bit
                    colMask[col] = colMask[col] or bit
                    boxMask[box] = boxMask[box] or bit
                    grid[pos] = d
                    if (fill(pos + 1)) return true
                    grid[pos] = 0
                    rowMask[row] = rowMask[row] and bit.inv()
                    colMask[col] = colMask[col] and bit.inv()
                    boxMask[box] = boxMask[box] and bit.inv()
                }
            }
            return false
        }

        check(fill(0)) { "Sudoku full-grid generation failed -- should be unreachable" }
        return grid
    }

    /**
     * Removes cells from [solution] one at a time (random order, single
     * pass) as long as doing so leaves a puzzle with EXACTLY ONE solution —
     * verified by actually re-solving the puzzle-so-far via [countSolutions]
     * (capped at 2, since "one" vs "more than one" is all that matters
     * here), not approximated. This is the standard, well-established
     * technique for guaranteeing a generated puzzle is uniquely, logically
     * solvable.
     *
     * [countSolutions] can come back [INCONCLUSIVE] (its own search-effort
     * budget ran out before reaching a verdict — see that method's KDoc);
     * that's treated IDENTICALLY to "not unique" here, i.e. the cell goes
     * back. Never removing a cell without a verified-unique result is what
     * keeps the "always uniquely, logically solvable" guarantee honest even
     * under that budget.
     *
     * Stops once [targetClues] is reached OR the single pass over all 81
     * cells completes (whichever first) — [targetClues] is therefore a
     * FLOOR this tries to reach, not a promise: a tier that can't be carved
     * all the way down to its nominal target ships with slightly more clues
     * than nominal rather than a non-unique (or unverified) puzzle. A fully
     * general "minimum possible clue count" search (multiple passes,
     * backtracking over which cells to remove) is a much harder, open
     * combinatorial problem this MVP doesn't attempt — an honest trade-off,
     * not a bug.
     */
    private fun carvePuzzle(solution: IntArray, targetClues: Int, random: Random): IntArray {
        val puzzle = solution.copyOf()
        var clues = 81
        for (i in (0 until 81).shuffled(random)) {
            if (clues <= targetClues) break
            val backup = puzzle[i]
            puzzle[i] = 0
            if (countSolutions(puzzle, cap = 2, callBudget = SOLVE_CALL_BUDGET) == 1) {
                clues--
            } else {
                puzzle[i] = backup
            }
        }
        return puzzle
    }

    /**
     * Counts how many completions [grid] (0 = empty) has, stopping as soon
     * as [cap] is reached — [carvePuzzle] only ever needs to distinguish
     * "exactly one" from "more than one," never the true count.
     *
     * Uses a minimum-remaining-values (MRV) heuristic: at each step, branch
     * on the EMPTY cell with the FEWEST legal candidates left (not a fixed
     * left-to-right scan) — the standard, well-known technique for keeping
     * Sudoku backtracking fast in practice, and a real fix, not a tweak: an
     * earlier fixed-order version of this method was found by adversarial
     * review to blow up combinatorially (multi-million-call searches, no
     * plateau found across hundreds of sampled seeds) for a real,
     * non-negligible fraction of puzzles at HARD's 26-clue target.
     *
     * WHY A CALL BUDGET ON TOP OF MRV: MRV dramatically shrinks the search
     * in the common case, but — like any backtracking heuristic — it has no
     * hard worst-case bound of its own; some adversarial grid could still
     * exist. [callBudget] is an unconditional ceiling on total backtracking
     * steps. If it's exhausted before a verdict is reached, this returns
     * [INCONCLUSIVE] rather than guessing — [carvePuzzle] then plays it safe
     * (puts the cell back) rather than ever risk calling a puzzle unique
     * when uniqueness was never actually verified. This bounds
     * [carvePuzzle]'s total worst-case work to (81 removal attempts) x
     * [callBudget], independent of how adversarial a given seed's puzzle
     * geometry turns out to be — the actual property the earlier version
     * was missing, not just a higher-probability-of-being-fast version of
     * the same unbounded algorithm.
     */
    private fun countSolutions(grid: IntArray, cap: Int, callBudget: Int): Int {
        val work = grid.copyOf()
        val rowMask = IntArray(9)
        val colMask = IntArray(9)
        val boxMask = IntArray(9)
        for (i in 0 until 81) {
            val d = work[i]
            if (d != 0) {
                val row = i / 9
                val col = i % 9
                val box = boxIndex(row, col)
                val bit = 1 shl d
                rowMask[row] = rowMask[row] or bit
                colMask[col] = colMask[col] or bit
                boxMask[box] = boxMask[box] or bit
            }
        }

        var count = 0
        var calls = 0
        var budgetExceeded = false

        // true return means "stop searching" -- either cap solutions were
        // found, or the call budget ran out; budgetExceeded distinguishes
        // the two for the final return value below.
        fun solve(): Boolean {
            calls++
            if (calls > callBudget) {
                budgetExceeded = true
                return true
            }

            // Find the empty cell with the fewest legal candidates (MRV).
            var bestPos = -1
            var bestMask = 0
            var bestCandidateCount = 10
            for (p in 0 until 81) {
                if (work[p] != 0) continue
                val row = p / 9
                val col = p % 9
                val box = boxIndex(row, col)
                val availableMask = ALL_DIGITS_MASK and (rowMask[row] or colMask[col] or boxMask[box]).inv()
                val candidateCount = Integer.bitCount(availableMask)
                if (candidateCount == 0) return false // dead end: an empty cell with no legal digit at all
                if (candidateCount < bestCandidateCount) {
                    bestCandidateCount = candidateCount
                    bestPos = p
                    bestMask = availableMask
                    if (candidateCount == 1) break // can't beat a forced cell
                }
            }
            if (bestPos == -1) {
                // No empty cells left -- every cell is filled and legal.
                count++
                return count >= cap
            }

            val row = bestPos / 9
            val col = bestPos % 9
            val box = boxIndex(row, col)
            var remaining = bestMask
            while (remaining != 0) {
                val bit = remaining and (-remaining) // lowest set bit
                remaining = remaining and (remaining - 1) // clear it
                val d = Integer.numberOfTrailingZeros(bit)
                rowMask[row] = rowMask[row] or bit
                colMask[col] = colMask[col] or bit
                boxMask[box] = boxMask[box] or bit
                work[bestPos] = d
                if (solve()) return true
                work[bestPos] = 0
                rowMask[row] = rowMask[row] and bit.inv()
                colMask[col] = colMask[col] and bit.inv()
                boxMask[box] = boxMask[box] and bit.inv()
            }
            return false
        }

        solve()
        return if (budgetExceeded) INCONCLUSIVE else count
    }

    private companion object {
        /** Bits 1..9 set (bit 0 unused, matching every mask in this file). */
        val ALL_DIGITS_MASK = (1..9).fold(0) { acc, d -> acc or (1 shl d) }

        /** See [countSolutions]'s KDoc for why this exists at all and how the number was chosen. */
        const val SOLVE_CALL_BUDGET = 200_000

        /** Sentinel [countSolutions] result meaning "ran out of budget before reaching a verdict," never a real solution count. */
        const val INCONCLUSIVE = -1
    }
}
