package com.gamesuite.games.minesweeper

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

enum class CellState { HIDDEN, REVEALED, FLAGGED }

/**
 * Everything needed to put a Minesweeper session back exactly as it was. Each cell is one Int
 * (state ordinal + 4 if mine + 8 * adjacent count) so a 16x30 board stays small. Carries the mine
 * layout and whether mines are placed yet (the hidden information), the daily seed (so a board that
 * has not had its first reveal still deals the same mines), the ACTIVE elapsed time rather than
 * clock readings, the session tally and this board's hint count.
 */
@Serializable
private data class MinesweeperSnapshot(
    val rows: Int,
    val cols: Int,
    val mineCount: Int,
    val cells: List<Int>,
    val flagCount: Int,
    val exploded: Boolean,
    val won: Boolean,
    val explodedIndex: Int?,
    val minesPlaced: Boolean,
    val dailySeed: Long?,
    val difficulty: String,
    val puzzlesSolved: Int,
    val puzzlesFailed: Int,
    val boardNumber: Int,
    val hintsUsed: Int,
    val timerStarted: Boolean,
    val activeElapsedMillis: Long,
    val finishedElapsedMillis: Long?,
    val players: List<MinesweeperSnapshotPlayer>
)

@Serializable
private data class MinesweeperSnapshotPlayer(val playerId: String, val isBot: Boolean)

private val minesweeperSnapshotJson = Json { ignoreUnknownKeys = true }

/**
 * A suggestion from [MinesweeperGame.hint]. [isGuess] false: [index] is PROVEN safe and the hint has
 * already revealed it. [isGuess] true: nothing is provable, [index] is only the lowest-risk hidden
 * square and has NOT been revealed.
 */
data class MinesweeperHint(val index: Int, val isGuess: Boolean)

/** One revealed number's constraint: exactly [mines] of the still-unknown [cells] are mines. */
private class MinesweeperConstraint(val cells: Set<Int>, val mines: Int)

private fun minesweeperNeighbors(index: Int, rows: Int, cols: Int): List<Int> {
    val row = index / cols
    val col = index % cols
    val result = mutableListOf<Int>()
    for (dr in -1..1) {
        for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val r = row + dr
            val c = col + dc
            if (r in 0 until rows && c in 0 until cols) result += r * cols + c
        }
    }
    return result
}

/**
 * Works out a hint from what the player can SEE only (which squares are revealed and their numbers;
 * hidden squares' mine flags and the player's own flags are never read, since flags may be wrong).
 * Deduction: every revealed number says "exactly N of these unknown squares are mines"; a constraint
 * with 0 left to find clears its squares, one with all squares mined marks them, and when one
 * constraint's squares are a subset of another's the difference is itself a constraint. Repeats to a
 * fixpoint. Returns the lowest-index proven-safe square; otherwise (a guess) the hidden square with
 * the lowest estimated mine risk; null when there is nothing hidden to point at.
 */
private fun minesweeperHintPlan(s: MinesweeperState): MinesweeperHint? {
    val knownMines = mutableSetOf<Int>()
    val knownSafe = mutableSetOf<Int>()
    var current: List<MinesweeperConstraint> = run {
        val list = ArrayList<MinesweeperConstraint>()
        for (i in s.cells.indices) {
            val cell = s.cells[i]
            if (cell.state != CellState.REVEALED) continue
            val unknown = minesweeperNeighbors(i, s.rows, s.cols).filter { s.cells[it].state != CellState.REVEALED }
            if (unknown.isEmpty()) continue
            list += MinesweeperConstraint(unknown.toSet(), cell.adjacentMines)
        }
        list
    }

    while (true) {
        var changed = false
        val reduced = ArrayList<MinesweeperConstraint>()
        for (c in current) {
            val rest = c.cells.filter { it !in knownMines && it !in knownSafe }
            val need = c.mines - c.cells.count { it in knownMines }
            if (rest.isEmpty()) continue
            if (need < 0 || need > rest.size) continue // contradicts what is known: ignore it
            if (need == 0) {
                knownSafe.addAll(rest)
                changed = true
            } else if (need == rest.size) {
                knownMines.addAll(rest)
                changed = true
            } else {
                reduced += MinesweeperConstraint(rest.toSet(), need)
            }
        }
        current = reduced
        if (changed) continue
        for (a in current) {
            for (b in current) {
                if (a === b || a.cells.size >= b.cells.size) continue
                if (!b.cells.containsAll(a.cells)) continue
                val diff = b.cells - a.cells
                val diffMines = b.mines - a.mines
                if (diffMines == 0) {
                    knownSafe.addAll(diff)
                    changed = true
                } else if (diffMines == diff.size) {
                    knownMines.addAll(diff)
                    changed = true
                }
            }
        }
        if (!changed) break
    }

    val safe = knownSafe.filter { s.cells[it].state != CellState.REVEALED }.minOrNull()
    if (safe != null) return MinesweeperHint(safe, isGuess = false)

    // No proof: point at the lowest-risk hidden square. A frontier square's risk is the worst
    // mine fraction among the constraints touching it; any other square is as risky as the
    // density of the mines still unaccounted for.
    val frontierRisk = HashMap<Int, Double>()
    for (c in current) {
        val p = c.mines.toDouble() / c.cells.size
        for (cell in c.cells) frontierRisk[cell] = maxOf(frontierRisk[cell] ?: 0.0, p)
    }
    val bestFrontier = frontierRisk.entries
        .filter { s.cells[it.key].state == CellState.HIDDEN }
        .minWithOrNull(compareBy<Map.Entry<Int, Double>>({ it.value }, { it.key }))
    val unknownCells = s.cells.indices.count { s.cells[it].state != CellState.REVEALED && it !in knownMines }
    val density = if (unknownCells > 0) {
        ((s.mineCount - knownMines.size).coerceAtLeast(0)).toDouble() / unknownCells
    } else {
        1.0
    }
    val bestOther = s.cells.indices.firstOrNull {
        s.cells[it].state == CellState.HIDDEN && it !in knownMines && it !in frontierRisk
    }
    return when {
        bestFrontier != null && (bestOther == null || bestFrontier.value <= density) ->
            MinesweeperHint(bestFrontier.key, isGuess = true)
        bestOther != null -> MinesweeperHint(bestOther, isGuess = true)
        else -> null
    }
}

data class MinesweeperCell(
    val isMine: Boolean = false,
    /** 0-8; meaningless (and never read) while [isMine] is true. */
    val adjacentMines: Int = 0,
    val state: CellState = CellState.HIDDEN
)

/**
 * [cells] is row-major ([rows] x [cols]). [exploded] (lost — a mine was
 * revealed) and [won] (every non-mine cell is revealed) are mutually
 * exclusive terminal outcomes for the CURRENT board only — distinct from
 * [MinesweeperGame.matchOver], which only flips once the whole session
 * ends, same split every other solo-puzzle game here (Hangman, Sliding
 * Puzzle) uses between a round's outcome and the session's.
 *
 * [explodedIndex] is the row-major index of the mine the player revealed to
 * lose (null on every board that has not been lost), so a lost board can
 * single out the mine that ended it from the other mines it reveals. On a
 * lost board a FLAGGED cell that is a mine keeps its flag (a correct flag
 * stays visible) and a FLAGGED cell that is not a mine is a wrong flag the
 * screen can mark; every unflagged mine is REVEALED.
 */
data class MinesweeperState(
    val rows: Int,
    val cols: Int,
    val cells: List<MinesweeperCell>,
    val mineCount: Int,
    val flagCount: Int = 0,
    val exploded: Boolean = false,
    val won: Boolean = false,
    val explodedIndex: Int? = null
) {
    val isOver: Boolean get() = exploded || won
}

/**
 * Classic Minesweeper. Reveal a cell; a 0 auto-floods its neighbors, a
 * number tells you how many of its 8 neighbors are mines, a mine ends the
 * board. Flag a suspected mine instead of revealing it (the screen offers a
 * Reveal/Flag mode toggle, and press-and-hold flags in either mode — see
 * MinesweeperScreen).
 *
 * FIRST-CLICK SAFETY: mines are NOT placed at [startMatch] — [minesPlaced]
 * stays false until the player's first [revealCell] call, which places
 * every mine avoiding that cell AND its neighbors (a small "safe zone", not
 * just the one cell) before proceeding. This is the standard modern
 * Minesweeper rule ("the first click is never a mine, and usually opens up
 * a decent-sized area") and also sidesteps ever needing to re-roll a board
 * that happened to be a mine under the first tap.
 *
 * Same solo-puzzle session pattern as SlidingPuzzleGame/HangmanGame: a
 * module-level [matchOver] distinct from the per-board [MinesweeperState.isOver];
 * finishing a board (won OR lost) does not end the match, just bumps
 * [puzzlesSolved] on a win or [puzzlesFailed] on a loss; [playAgain] deals a
 * fresh board keeping the tally; [leaveSession] builds the [GameResult] from
 * the tally and ends the match. Solved plus failed is the number of FINISHED
 * boards, which the screen reads to decide whether leaving mid-board may be a
 * pure abort (nothing finished yet) or must go through [leaveSession] so the
 * finished boards still count.
 *
 * Difficulty (there's no bot here either, so exactly as in Sliding Puzzle
 * the lever has to be the puzzle itself) scales board size and mine density:
 * EASY is the classic 9x9/10-mine beginner board, MEDIUM is 16x16/40 mines,
 * HARD is the classic 16x30/99-mine "expert" board.
 *
 * DELIBERATE SCOPE CUTS (honest MVP, same spirit as every other game's own
 * documented cuts — see SolitaireGame.kt/SpiderLogic.h's equivalents):
 *   - No "chord" (tapping an already-revealed number whose flag count
 *     matches it to auto-reveal its remaining neighbors) — a real
 *     convenience, but it lets a misplaced flag silently detonate a mine
 *     on behalf of the player, which needs its own careful confirmation UX
 *     to do safely. A clean follow-up, not required for a correct, playable
 *     first version.
 *   - Hint is a deduction solver ([hint], [minesweeperHintPlan]): it reveals a
 *     square the visible numbers PROVE safe, or when nothing is provable only
 *     highlights the lowest-risk square. No undo: taking back a reveal would
 *     leak where the mines are.
 *   - Daily-seed support mirrors SlidingPuzzleGame.startMatch(dailySeed)'s
 *     exact shape/reasoning (see that class's KDoc) — this file never reads
 *     today's date itself.
 */
/**
 * [nowMillis] defaults to the real `SystemClock.elapsedRealtime()` for
 * every real call site (`MinesweeperGame()`, matching every other game's
 * own no-arg construction) — overridable only so a plain JUnit test can
 * supply a deterministic fake instead. `SystemClock.elapsedRealtime()`
 * throws "not mocked" under plain JUnit (no Robolectric in this project),
 * which is exactly why SlidingPuzzleGameTest never calls `tapTile()` at
 * all — this seam avoids that same coverage gap here rather than
 * repeating it, without adding a new test dependency project-wide.
 */
class MinesweeperGame(private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() }) : GameModule, ResumableGame {
    override val gameId = "minesweeper"
    override val displayName = "Minesweeper"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<MinesweeperState?>(null)
    val puzzlesSolved = mutableStateOf(0)

    /** Boards lost to a mine this session; with [puzzlesSolved] this is the finished-board count. See the class KDoc. */
    val puzzlesFailed = mutableStateOf(0)

    /**
     * Counts every fresh board dealt (bumped by each [startMatch], never reset, never lowered). The
     * screen keys its per-board state on this rather than on the board's contents: two boards of the
     * same tier have the same shape, and re-dealing the same daily seed gives an identical layout.
     */
    val boardNumber = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via "Back to Menu"), not per-board. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /**
     * `SystemClock.elapsedRealtime()` reading taken on the CURRENT board's first reveal, or
     * null before that first reveal (or between boards) — same "stopwatch starts on the first
     * real move, not on board generation" idiom as SlidingPuzzleGame's own
     * `timerStartElapsedRealtime`, and for the same reason (sitting and staring at a fresh
     * board shouldn't count against the clock). The screen derives its live "elapsed time"
     * display from [activeElapsedMillis] (which subtracts paused intervals) rather than this
     * class ticking a timer itself.
     */
    val timerStartElapsedRealtime = mutableStateOf<Long?>(null)

    /** Total time for the current board, frozen the instant [revealCell] detects a win OR a loss; null until then. */
    val finishedElapsedMillis = mutableStateOf<Long?>(null)

    /** Hints taken on the CURRENT board (reset per board, snapshotted); any hint makes the board "assisted" and ineligible for a best-time record. */
    val hintsUsed = mutableStateOf(0)

    /** The square the last [hint] pointed at, shown until the next reveal, flag, new board or restore. */
    val hintCell = mutableStateOf<MinesweeperHint?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** True once mines have actually been placed for the CURRENT board — see the class KDoc's FIRST-CLICK SAFETY section. */
    private var minesPlaced = false
    private var currentDailySeed: Long? = null

    /** [pause]'s own nowMillis() reading, or null while not currently paused — see [pause]/[resume]'s KDoc. */
    private var pausedAtElapsedRealtime: Long? = null

    /** Total time spent paused during the CURRENT board, subtracted out in [freezeTimer] — see [pause]/[resume]'s KDoc. */
    private var totalPausedMillis: Long = 0L

    /** rows x cols x mineCount per difficulty — classic beginner/intermediate/expert board sizes. */
    private val difficultyConfig: Map<CpuDifficulty, Triple<Int, Int, Int>> = mapOf(
        CpuDifficulty.EASY to Triple(9, 9, 10),
        CpuDifficulty.MEDIUM to Triple(16, 16, 40),
        CpuDifficulty.HARD to Triple(16, 30, 99)
    )

    override fun init(context: GameContext) {
        this.context = context
        puzzlesSolved.value = 0
        puzzlesFailed.value = 0
        matchOver.value = false
        hintsUsed.value = 0
        hintCell.value = null
    }

    /** True while the board on screen is the seeded daily board (a later fresh board is random again). Lets the screen re-derive its "Daily board" tag after a resume. */
    val isDailyBoard: Boolean get() = currentDailySeed != null

    // --- Resume -----------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return minesweeperSnapshotJson.encodeToString(
            MinesweeperSnapshot.serializer(),
            MinesweeperSnapshot(
                rows = s.rows,
                cols = s.cols,
                mineCount = s.mineCount,
                cells = s.cells.map { it.state.ordinal + (if (it.isMine) 4 else 0) + it.adjacentMines * 8 },
                flagCount = s.flagCount,
                exploded = s.exploded,
                won = s.won,
                explodedIndex = s.explodedIndex,
                minesPlaced = minesPlaced,
                dailySeed = currentDailySeed,
                difficulty = difficulty.name,
                puzzlesSolved = puzzlesSolved.value,
                puzzlesFailed = puzzlesFailed.value,
                boardNumber = boardNumber.value,
                hintsUsed = hintsUsed.value,
                timerStarted = timerStartElapsedRealtime.value != null,
                // Pause-adjusted, so a paused clock is saved as it reads, not as the wall clock moves on.
                activeElapsedMillis = activeElapsedMillis() ?: 0L,
                finishedElapsedMillis = finishedElapsedMillis.value,
                players = context.players.map { MinesweeperSnapshotPlayer(it.playerId, it.isBot) }
            )
        )
    }

    /** Decodes and sanity-checks a snapshot against this game's board shapes and roster; null if unusable. */
    private fun decodeSnapshot(text: String): Pair<MinesweeperSnapshot, List<MinesweeperCell>>? {
        val snap = try {
            minesweeperSnapshotJson.decodeFromString(MinesweeperSnapshot.serializer(), text)
        } catch (_: Exception) {
            return null
        }
        val tier = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: return null
        val (rows, cols, mines) = difficultyConfig[tier] ?: return null
        val roster = context.players
        if (snap.rows != rows || snap.cols != cols || snap.mineCount != mines) return null
        if (snap.cells.size != rows * cols) return null
        if (snap.players.size != roster.size) return null
        if (!snap.players.indices.all { snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot }) return null
        if (snap.cells.any { it < 0 || (it and 3) > 2 || (it shr 3) > 8 }) return null
        val cells = snap.cells.map {
            MinesweeperCell(isMine = (it and 4) != 0, adjacentMines = it shr 3, state = CellState.values()[it and 3])
        }
        if (cells.count { it.state == CellState.FLAGGED } != snap.flagCount) return null
        if (snap.exploded && snap.won) return null
        if (snap.exploded != (snap.explodedIndex != null)) return null
        if (snap.explodedIndex != null && snap.explodedIndex !in cells.indices) return null
        val over = snap.exploded || snap.won
        if (over && !snap.minesPlaced) return null
        // Mines are placed on the first reveal, which is also when the stopwatch starts.
        if (snap.minesPlaced != snap.timerStarted) return null
        if (snap.minesPlaced && cells.count { it.isMine } != mines) return null
        if (!snap.minesPlaced && cells.any { it.isMine || it.adjacentMines != 0 || it.state == CellState.REVEALED }) return null
        if (snap.activeElapsedMillis < 0L) return null
        val finished = snap.finishedElapsedMillis
        if (over != (finished != null) || (finished != null && finished < 0L)) return null
        if (snap.puzzlesSolved < 0 || snap.puzzlesFailed < 0 || snap.boardNumber < 0 || snap.hintsUsed < 0) return null
        return snap to cells
    }

    /** Null when there is nothing to save: no board yet, the session already ended, or a mode whose state lives with other players. */
    override fun snapshot(): String? {
        return try {
            if (matchOver.value || state.value == null) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            captureSnapshot()
        } catch (_: Exception) {
            null
        }
    }

    override fun restore(snapshot: String): Boolean {
        return try {
            val (snap, cells) = decodeSnapshot(snapshot) ?: return false
            // Everything validated: assign. The clock continues from the saved ACTIVE time and does
            // not count the time the app was gone.
            val tier = CpuDifficulty.values().first { it.name == snap.difficulty }
            difficulty = tier
            minesPlaced = snap.minesPlaced
            currentDailySeed = snap.dailySeed
            pausedAtElapsedRealtime = null
            totalPausedMillis = 0L
            val finished = snap.finishedElapsedMillis
            timerStartElapsedRealtime.value = when {
                !snap.timerStarted -> null
                finished != null -> nowMillis() - finished
                else -> nowMillis() - snap.activeElapsedMillis
            }
            finishedElapsedMillis.value = finished
            puzzlesSolved.value = snap.puzzlesSolved
            puzzlesFailed.value = snap.puzzlesFailed
            boardNumber.value = snap.boardNumber
            hintsUsed.value = snap.hintsUsed
            hintCell.value = null
            matchOver.value = false
            state.value = MinesweeperState(
                rows = snap.rows,
                cols = snap.cols,
                cells = cells,
                mineCount = snap.mineCount,
                flagCount = snap.flagCount,
                exploded = snap.exploded,
                won = snap.won,
                explodedIndex = snap.explodedIndex
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    // --- Hint -------------------------------------------------------------------------------

    /**
     * Asks for help on the current board. If the visible numbers PROVE a square safe, that square is
     * revealed (a wrongly flagged one is unflagged first) and returned with isGuess false. If nothing
     * is provable, the lowest-risk hidden square is only highlighted and returned with isGuess true.
     * Either way [hintsUsed] goes up, which marks the board assisted (no best-time record; a win
     * still counts as solved). Null, and no cost, before the first reveal (no mines exist yet), once
     * the board is over, after the session ended, or with nothing hidden left.
     */
    fun hint(): MinesweeperHint? {
        val s = state.value ?: return null
        if (matchOver.value || s.isOver || !minesPlaced) return null
        val plan = minesweeperHintPlan(s) ?: return null
        // Asking again with nothing changed (the guess is still ringed) is not a second hint.
        if (plan.isGuess && hintCell.value == plan) return plan
        hintsUsed.value += 1
        if (!plan.isGuess) {
            if (s.cells[plan.index].state == CellState.FLAGGED) toggleFlag(plan.index)
            revealCell(plan.index)
        }
        hintCell.value = plan
        return plan
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() = startMatch(dailySeed = null)

    /** See MinesweeperGame's class KDoc / SlidingPuzzleGame.startMatch(dailySeed)'s KDoc for what this is for. */
    fun startMatch(dailySeed: Long?) {
        val (rows, cols, mines) = difficultyConfig[difficulty] ?: difficultyConfig.getValue(CpuDifficulty.MEDIUM)
        minesPlaced = false
        currentDailySeed = dailySeed
        timerStartElapsedRealtime.value = null
        finishedElapsedMillis.value = null
        pausedAtElapsedRealtime = null
        totalPausedMillis = 0L
        // A fresh board is always playable, regardless of whether a PRIOR
        // board's endMatch() left matchOver stuck true (see the class KDoc's
        // note on this fix) -- found by adversarial review, see that note.
        matchOver.value = false
        hintsUsed.value = 0
        hintCell.value = null
        boardNumber.value += 1
        state.value = MinesweeperState(
            rows = rows,
            cols = cols,
            cells = List(rows * cols) { MinesweeperCell() },
            mineCount = mines
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

    /**
     * The stopwatch reading a live clock should show right now, in the SAME terms
     * [finishedElapsedMillis] is computed in: wall time since the first reveal minus every paused
     * interval, including a pause that is still running (so the display freezes while the app is
     * backgrounded instead of ticking on and then snapping back when the board ends). Null before
     * the first reveal. Once the board is won or lost it is just [finishedElapsedMillis].
     * Read-only; never mutates the timer.
     */
    fun activeElapsedMillis(): Long? {
        val start = timerStartElapsedRealtime.value ?: return null
        finishedElapsedMillis.value?.let { return it }
        val upTo = pausedAtElapsedRealtime ?: nowMillis()
        return (upTo - start - totalPausedMillis).coerceAtLeast(0L)
    }

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /**
     * Reveal the cell at [index] (row-major). No-op if the board is already
     * decided, the cell isn't hidden (already revealed, or flagged — a
     * flagged cell must be unflagged first, the standard convention), or the
     * whole session has already ended via [leaveSession]/[endMatch] — found
     * missing by ColorFloodGame.pick()'s own adversarial review (only the
     * per-board `isOver` was checked, never `matchOver`, so a call after the
     * final `GameResult` had already been delivered could still mutate
     * state, including a phantom win with no second result ever reported —
     * see that class's KDoc). Not currently reachable through this app's
     * real screens, but a real gap worth closing defensively regardless.
     */
    fun revealCell(index: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.isOver) return
        if (s.cells[index].state != CellState.HIDDEN) return
        hintCell.value = null

        // Stopwatch starts on the first *actual* reveal, not on board generation --
        // a no-op tap above never reaches this line, so idle time before the
        // player's first real move is never counted. Same idiom as
        // SlidingPuzzleGame.tapTile()'s own timer start.
        if (timerStartElapsedRealtime.value == null) {
            timerStartElapsedRealtime.value = nowMillis()
        }

        var working = s
        if (!minesPlaced) {
            val random = currentDailySeed?.let { Random(it) } ?: Random
            working = placeMines(working, avoidIndex = index, random = random)
            minesPlaced = true
        }

        if (working.cells[index].isMine) {
            // Loss: reveal every unflagged mine so the board explains itself. Everything
            // else is left exactly as the player last saw it, and that includes flags: a
            // flagged mine keeps its (correct) flag, a flagged safe cell stays flagged so
            // the screen can mark it as a wrong flag, and flagCount keeps agreeing with the
            // flags on the board. [index] is always HIDDEN here (guarded above), so the mine
            // the player hit is among the revealed ones.
            val revealedMines = working.cells.map {
                if (it.isMine && it.state != CellState.FLAGGED) it.copy(state = CellState.REVEALED) else it
            }
            state.value = working.copy(cells = revealedMines, exploded = true, explodedIndex = index)
            puzzlesFailed.value += 1
            freezeTimer()
            return
        }

        working = floodReveal(working, index)
        val revealedCount = working.cells.count { it.state == CellState.REVEALED }
        val safeCellCount = working.rows * working.cols - working.mineCount
        val won = revealedCount == safeCellCount
        state.value = working.copy(won = won)
        if (won) {
            puzzlesSolved.value += 1
            freezeTimer()
        }
    }

    private fun freezeTimer() {
        val start = timerStartElapsedRealtime.value ?: nowMillis()
        finishedElapsedMillis.value = (nowMillis() - start) - totalPausedMillis
    }

    /** Toggles a flag on a still-hidden cell — a no-op on an already-revealed cell, once the board is decided, or once the whole session has already ended via [leaveSession]/[endMatch] (see [revealCell]'s KDoc for why). */
    fun toggleFlag(index: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.isOver) return
        val cell = s.cells[index]
        if (cell.state == CellState.REVEALED) return
        hintCell.value = null

        val flagging = cell.state != CellState.FLAGGED
        val cells = s.cells.toMutableList()
        cells[index] = cell.copy(state = if (flagging) CellState.FLAGGED else CellState.HIDDEN)
        state.value = s.copy(cells = cells, flagCount = s.flagCount + if (flagging) 1 else -1)
    }

    /** Called from the finished-board panel's "New Board" button — keeps the running tally. */
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

    private fun neighborsOf(index: Int, rows: Int, cols: Int): List<Int> {
        val row = index / cols
        val col = index % cols
        val result = mutableListOf<Int>()
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val r = row + dr
                val c = col + dc
                if (r in 0 until rows && c in 0 until cols) result += r * cols + c
            }
        }
        return result
    }

    /**
     * Places [MinesweeperState.mineCount] mines uniformly at random among
     * every cell EXCEPT [avoidIndex] and its own neighbors (the "safe zone"
     * — see the class KDoc's FIRST-CLICK SAFETY section), then computes
     * every non-mine cell's [MinesweeperCell.adjacentMines] count.
     * [random] is threaded in (rather than reaching for the global default)
     * so a daily-seeded board is reproducible, same idiom
     * SlidingPuzzleGame.scramble() uses.
     */
    private fun placeMines(s: MinesweeperState, avoidIndex: Int, random: Random): MinesweeperState {
        val safeZone = (neighborsOf(avoidIndex, s.rows, s.cols) + avoidIndex).toSet()
        val candidates = s.cells.indices.filter { it !in safeZone }
        val mineIndices = candidates.shuffled(random).take(s.mineCount.coerceAtMost(candidates.size)).toSet()

        val withMines = s.cells.mapIndexed { i, cell -> cell.copy(isMine = i in mineIndices) }
        val withCounts = withMines.mapIndexed { i, cell ->
            if (cell.isMine) cell else cell.copy(adjacentMines = neighborsOf(i, s.rows, s.cols).count { withMines[it].isMine })
        }
        return s.copy(cells = withCounts)
    }

    /**
     * Reveals [startIndex] and, if it has zero adjacent mines, floods
     * outward through every zero-adjacent-mines neighbor chain, revealing
     * (but not further expanding past) the numbered cells bordering that
     * chain — the classic "click an empty area, a whole region opens up"
     * behavior. Iterative (a plain queue, not recursion) so this can't blow
     * the call stack on the largest (16x30) board. Never reveals a flagged
     * cell, even if the flood would otherwise reach it — flags are the
     * player's own mark and this function must not silently clear them.
     */
    private fun floodReveal(s: MinesweeperState, startIndex: Int): MinesweeperState {
        val cells = s.cells.toMutableList()
        val queue = ArrayDeque<Int>()
        queue.add(startIndex)
        val visited = mutableSetOf<Int>()

        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            if (!visited.add(i)) continue
            val cell = cells[i]
            if (cell.state != CellState.HIDDEN) continue // already revealed, or flagged -- leave flags alone
            cells[i] = cell.copy(state = CellState.REVEALED)
            if (!cell.isMine && cell.adjacentMines == 0) {
                for (n in neighborsOf(i, s.rows, s.cols)) if (n !in visited) queue.add(n)
            }
        }
        return s.copy(cells = cells)
    }
}
