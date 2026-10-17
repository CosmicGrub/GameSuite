package com.gamesuite.games.colorflood

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Everything needed to put a Color Flood session back as it was. One shape serves Resume (disk)
 * and Undo (memory). The territory is NOT stored: it is always the flood from the origin over
 * [cellColors], so it is re-derived (and the saved [won] flag cross-checked against it). Times are
 * ACTIVE (pause-adjusted) milliseconds, never clock readings. The board is fully visible, so there
 * is no hidden information beyond [dailySeed] (kept so the "Daily board" tag survives).
 */
@Serializable
private data class ColorFloodSnapshot(
    val size: Int,
    val colorCount: Int,
    val cellColors: List<Int>,
    val moves: Int,
    val won: Boolean,
    val difficulty: String,
    val dailySeed: Long?,
    val puzzlesSolved: Int,
    val hintsUsed: Int,
    val activeElapsedMillis: Long?,
    val finishedElapsedMillis: Long?,
    val players: List<ColorFloodSnapshotPlayer>
)

@Serializable
private data class ColorFloodSnapshotPlayer(val playerId: String, val isBot: Boolean)

private val colorFloodSnapshotJson = Json { ignoreUnknownKeys = true }

/**
 * [cellColors] is row-major (size*size); each value is a color INDEX (0
 * until [colorCount]), not an actual color — the UI maps indices to its own
 * palette, same "engine stays presentation-agnostic" split every other
 * game's board state follows. [territory] is the set of cell indices
 * currently claimed (always includes the top-left corner, index 0, which
 * never leaves it) — every cell in [territory] is guaranteed to share the
 * same [cellColors] value, readable via `cellColors[0]`.
 */
data class ColorFloodState(
    val size: Int,
    val colorCount: Int,
    val cellColors: List<Int>,
    val territory: Set<Int>,
    val moves: Int = 0,
    val won: Boolean = false
) {
    val isOver: Boolean get() = won
    val currentColor: Int get() = cellColors[0]
}

/**
 * Color Flood (the concrete pick for the reference material's "Color
 * Puzzle" preview — see docs/NEW_GAMES_BRAINSTORM.md for why this specific
 * ruleset was chosen over a match-3 interpretation of that name). Your
 * territory starts as just the top-left cell; picking a color repaints your
 * WHOLE territory that color, and any board cell already that color
 * touching your territory (directly, or through a chain of same-colored
 * cells) joins it. Flood the entire board into one color to win.
 *
 * Reuses [MinesweeperGame]'s own iterative flood-fill BFS shape almost
 * exactly (see [floodFrom]) — the brainstorm doc's own prediction for this
 * game, confirmed true in the implementation.
 *
 * NO MOVE LIMIT, NO LOSS STATE — a deliberate design decision, not a scope
 * cut: unlike Minesweeper (where a wrong move ends the board) or a real
 * Flood-It variant with a hard move cap, EVERY random coloring here is
 * trivially solvable (repeatedly picking any color not yet in your
 * territory strictly grows it, so the board always floods eventually) —
 * there's no "you lost" outcome to build, and imposing an artificial hard
 * cap would just be inventing failure where the genre doesn't need one.
 * [ColorFloodState.moves] is tracked and recorded as a personal-best stat
 * (see [ColorFloodStatsStore]) purely as the skill metric this puzzle
 * actually has — "how few moves can YOU flood it in" — same "no
 * enforced fail condition, just an honest personal-best" spirit as Lights
 * Out's own move count.
 *
 * Difficulty (no bot here either, so the lever is the puzzle itself, same
 * idiom as every other solo puzzle in this app) scales BOTH board size and
 * color count: EASY 9x9/4 colors, MEDIUM 12x12/5 colors, HARD 16x16/6
 * colors — more cells AND more colors to coordinate both make optimal play
 * genuinely harder, not just longer.
 */
class ColorFloodGame(private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() }) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "color-flood"
    override val displayName = "Color Flood"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<ColorFloodState?>(null)
    val puzzlesSolved = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via "Back to Menu"), not per-board. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** Same "stopwatch starts on the first real move" idiom as every other solo puzzle's own field. */
    val timerStartElapsedRealtime = mutableStateOf<Long?>(null)

    /** Total time for the current board, frozen the instant [pick] detects a win; null until then. */
    val finishedElapsedMillis = mutableStateOf<Long?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** [pause]'s own nowMillis() reading, or null while not currently paused — see [pause]/[resume]'s KDoc. */
    private var pausedAtElapsedRealtime: Long? = null

    /** Total time spent paused during the CURRENT board, subtracted out in [freezeTimer] — see [pause]/[resume]'s KDoc. */
    private var totalPausedMillis: Long = 0L

    /** (board size, color count) per tier. */
    private val difficultyConfig: Map<CpuDifficulty, Pair<Int, Int>> = mapOf(
        CpuDifficulty.EASY to (9 to 4),
        CpuDifficulty.MEDIUM to (12 to 5),
        CpuDifficulty.HARD to (16 to 6)
    )

    /** The seed of the daily board on screen, or null for a random board. Compose state so the screen can show the "Daily board" tag after a resume. */
    val dailySeedInUse = mutableStateOf<Long?>(null)

    /** Hints taken on the CURRENT board (reset per board). Any hint makes the solve "assisted": it never sets a record. */
    val hintsUsed = mutableStateOf(0)

    /** The colour the last [hint] suggested, shown until the next pick, undo or new board. */
    val hintColor = mutableStateOf<Int?>(null)

    /** Snapshots taken just BEFORE each pick; popping one is Undo. Per board. */
    private val history = SnapshotHistory()

    override fun init(context: GameContext) {
        this.context = context
        puzzlesSolved.value = 0
        matchOver.value = false
        hintsUsed.value = 0
        hintColor.value = null
        dailySeedInUse.value = null
        history.clear()
    }

    // --- Resume -----------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return colorFloodSnapshotJson.encodeToString(
            ColorFloodSnapshot.serializer(),
            ColorFloodSnapshot(
                size = s.size,
                colorCount = s.colorCount,
                cellColors = s.cellColors,
                moves = s.moves,
                won = s.won,
                difficulty = difficulty.name,
                dailySeed = dailySeedInUse.value,
                puzzlesSolved = puzzlesSolved.value,
                hintsUsed = hintsUsed.value,
                activeElapsedMillis = activeElapsedMillis(),
                finishedElapsedMillis = finishedElapsedMillis.value,
                players = context.players.map { ColorFloodSnapshotPlayer(it.playerId, it.isBot) }
            )
        )
    }

    /** Decodes and sanity-checks a snapshot against THIS game's tiers and roster; null if unusable. Never throws. */
    private fun decodeSnapshot(text: String): ColorFloodSnapshot? {
        return try {
            val snap = colorFloodSnapshotJson.decodeFromString(ColorFloodSnapshot.serializer(), text)
            val tier = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: return null
            val config = difficultyConfig[tier] ?: return null
            val roster = context.players
            val cells = snap.size * snap.size
            val shapeOk = snap.size == config.first && snap.colorCount == config.second &&
                snap.cellColors.size == cells &&
                snap.cellColors.all { it in 0 until snap.colorCount } &&
                snap.moves >= 0 && snap.puzzlesSolved >= 0 && snap.hintsUsed >= 0 &&
                snap.players.size == roster.size &&
                snap.players.indices.all { snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot }
            if (!shapeOk) return null
            val timesOk = (snap.activeElapsedMillis ?: 0L) >= 0L && (snap.finishedElapsedMillis ?: 0L) >= 0L &&
                (snap.won == (snap.finishedElapsedMillis != null))
            if (!timesOk) return null
            val territory = floodFrom(snap.cellColors, ORIGIN, snap.size)
            if (snap.won != (territory.size == cells)) return null
            snap
        } catch (_: Exception) {
            null
        }
    }

    private fun stateOf(snap: ColorFloodSnapshot): ColorFloodState = ColorFloodState(
        size = snap.size,
        colorCount = snap.colorCount,
        cellColors = snap.cellColors,
        territory = floodFrom(snap.cellColors, ORIGIN, snap.size),
        moves = snap.moves,
        won = snap.won
    )

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
            val snap = decodeSnapshot(snapshot) ?: return false
            val tier = CpuDifficulty.values().first { it.name == snap.difficulty }
            val newState = stateOf(snap)
            val now = nowMillis()
            val finished = snap.finishedElapsedMillis
            val active = snap.activeElapsedMillis
            matchOver.value = false
            difficulty = tier
            pausedAtElapsedRealtime = null
            totalPausedMillis = 0L
            when {
                finished != null -> {
                    timerStartElapsedRealtime.value = now - finished
                    finishedElapsedMillis.value = finished
                }
                active != null -> {
                    timerStartElapsedRealtime.value = now - active
                    finishedElapsedMillis.value = null
                }
                else -> {
                    timerStartElapsedRealtime.value = null
                    finishedElapsedMillis.value = null
                }
            }
            puzzlesSolved.value = snap.puzzlesSolved
            hintsUsed.value = snap.hintsUsed
            dailySeedInUse.value = snap.dailySeed
            hintColor.value = null
            history.clear()
            state.value = newState
            true
        } catch (_: Exception) {
            false
        }
    }

    // --- Undo -------------------------------------------------------------------------------

    /** A flooded board cannot be un-won: its result is already on the record, so undo would let it be won (and recorded) again. */
    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value && state.value?.isOver == false

    /**
     * Takes back the last colour pick (one step each). Counters that only go up ([hintsUsed],
     * [puzzlesSolved]) keep the larger value and the clock keeps running, so undo cannot be used to
     * shed a hint or rewind time. No-op once the board is flooded or the session has ended.
     */
    override fun undo() {
        if (matchOver.value) return
        val current = state.value ?: return
        if (current.isOver) return
        val previous = history.pop() ?: return
        val snap = decodeSnapshot(previous) ?: return
        if (snap.size != current.size || snap.colorCount != current.colorCount) return
        state.value = stateOf(snap)
        puzzlesSolved.value = maxOf(puzzlesSolved.value, snap.puzzlesSolved)
        hintsUsed.value = maxOf(hintsUsed.value, snap.hintsUsed)
        hintColor.value = null
    }

    // --- Hint -------------------------------------------------------------------------------

    /**
     * Suggests the colour that absorbs the most cells right now (lowest index on a tie), shows it
     * via [hintColor] and returns it; null when the board is over, the session ended or nothing
     * would be absorbed. The first hint on a board counts toward [hintsUsed]; asking again while
     * the same suggestion is showing is free. A board solved with any hint never sets a record.
     */
    fun hint(): Int? {
        val s = state.value ?: return null
        if (matchOver.value || s.isOver) return null
        hintColor.value?.let { return it }
        var best = -1
        var bestCount = 0
        for (c in 0 until s.colorCount) {
            val n = absorbCount(c)
            if (n > bestCount) {
                best = c
                bestCount = n
            }
        }
        if (best < 0) return null
        hintsUsed.value += 1
        hintColor.value = best
        return best
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() = startMatch(dailySeed = null)

    /** See this class's KDoc / MinesweeperGame.startMatch(dailySeed)'s KDoc for what this is for. */
    fun startMatch(dailySeed: Long?) {
        val random = dailySeed?.let { Random(it) } ?: Random.Default
        val (size, colorCount) = difficultyConfig[difficulty] ?: difficultyConfig.getValue(CpuDifficulty.MEDIUM)

        timerStartElapsedRealtime.value = null
        finishedElapsedMillis.value = null
        pausedAtElapsedRealtime = null
        totalPausedMillis = 0L
        matchOver.value = false
        dailySeedInUse.value = dailySeed
        hintsUsed.value = 0
        hintColor.value = null
        history.clear()

        val colors = List(size * size) { random.nextInt(colorCount) }
        state.value = ColorFloodState(
            size = size,
            colorCount = colorCount,
            cellColors = colors,
            territory = floodFrom(colors, ORIGIN, size)
        )
    }

    /**
     * Snapshots the current time so [resume] can measure how long the app
     * was actually paused, so backgrounding mid-puzzle never inflates the
     * recorded solve time — the same fix Minesweeper/Sudoku/Lights Out/
     * SlidingPuzzle all needed after this exact pattern was found by
     * adversarial review; built in here from the start rather than waiting
     * to rediscover it a sixth time.
     *
     * Guarded by `pausedAtElapsedRealtime == null` so a second [pause] call
     * with no [resume] in between is a no-op rather than silently moving
     * the anchor forward and losing the intervening interval — found by
     * this game's own adversarial review as a latent asymmetry with
     * [resume] (which was already correctly idempotent). Not currently
     * reachable through this app's real lifecycle wiring (Android
     * guarantees onPause()/onResume() strictly alternate, and there's
     * exactly one call site of each), but a real code-level gap worth
     * closing defensively regardless, the same spirit as
     * SudokuGame.selectCell's own bounds check.
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
     * [finishedElapsedMillis] is computed in: wall time since the first pick minus every paused
     * interval, including a pause that is still running (so the display freezes while the app is
     * backgrounded instead of ticking on and then jumping back at the win). Null before the first
     * pick. Once the board is won it is just [finishedElapsedMillis]. Read-only; never mutates the
     * timer.
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
     * Repaints the current territory [colorIndex] and re-floods from the
     * origin. No-op if [colorIndex] is out of range, already the
     * territory's current color (picking your own color grows nothing, so
     * this correctly doesn't count as a move — same "an action that
     * changes nothing isn't a move" idiom MinesweeperGame's own guard
     * clauses follow), the board is already won, or the whole session has
     * already ended via [leaveSession]/[endMatch] — found missing by this
     * game's own adversarial review (only [s]'s per-board `isOver` was
     * checked, not [matchOver], so a pick() after the final `GameResult`
     * had already been delivered could still mutate state, including
     * setting `won = true` with no second result ever reported). Not
     * currently reachable through this app's real screens (leaveSession()
     * is only ever wired to the finished-board panel's own "Back to Menu"
     * button, which only renders once the board is already over), but a
     * real gap worth closing defensively regardless.
     */
    fun pick(colorIndex: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.isOver) return
        if (colorIndex !in 0 until s.colorCount) return
        if (colorIndex == s.currentColor) return

        captureSnapshot()?.let { history.push(it) }
        hintColor.value = null
        if (timerStartElapsedRealtime.value == null) timerStartElapsedRealtime.value = nowMillis()

        val repainted = s.cellColors.mapIndexed { i, color -> if (i in s.territory) colorIndex else color }
        val newTerritory = floodFrom(repainted, ORIGIN, s.size)
        val won = newTerritory.size == s.size * s.size

        state.value = s.copy(cellColors = repainted, territory = newTerritory, moves = s.moves + 1, won = won)
        if (won) {
            puzzlesSolved.value += 1
            freezeTimer()
        }
    }

    /**
     * How many cells picking [colorIndex] would ADD to the territory right now: the same
     * repaint-and-flood [pick] performs, without committing it. 0 for the territory's current
     * color, an out-of-range index, a won board, an ended session, or before the first board.
     * Lets the screen label each swatch with what it would absorb (a pick that touches nothing
     * still costs a move, so "+0" is worth knowing). Read-only; agrees with [pick] by test.
     */
    fun absorbCount(colorIndex: Int): Int {
        val s = state.value ?: return 0
        if (matchOver.value || s.isOver) return 0
        if (colorIndex !in 0 until s.colorCount || colorIndex == s.currentColor) return 0
        val repainted = s.cellColors.mapIndexed { i, color -> if (i in s.territory) colorIndex else color }
        return floodFrom(repainted, ORIGIN, s.size).size - s.territory.size
    }

    private fun freezeTimer() {
        val start = timerStartElapsedRealtime.value ?: nowMillis()
        finishedElapsedMillis.value = (nowMillis() - start) - totalPausedMillis
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

    private fun neighborsOf(index: Int, size: Int): List<Int> {
        val row = index / size
        val col = index % size
        val result = mutableListOf<Int>()
        if (row > 0) result += index - size
        if (row < size - 1) result += index + size
        if (col > 0) result += index - 1
        if (col < size - 1) result += index + 1
        return result
    }

    /**
     * Iterative BFS (a plain queue, not recursion, so this can't blow the
     * call stack on the largest board) from [origin], following only
     * cells matching `colors[origin]`'s color — the exact "connected
     * same-value region" shape [MinesweeperGame.floodReveal] already uses
     * for its own zero-adjacent-cell flood, applied here to color equality
     * instead of "is a zero."
     */
    private fun floodFrom(colors: List<Int>, origin: Int, size: Int): Set<Int> {
        val targetColor = colors[origin]
        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque<Int>()
        queue.add(origin)
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            if (i in visited) continue
            if (colors[i] != targetColor) continue
            visited.add(i)
            for (n in neighborsOf(i, size)) if (n !in visited) queue.add(n)
        }
        return visited
    }

    private companion object {
        const val ORIGIN = 0 // top-left corner
    }
}
