package com.gamesuite.games.colorflood

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

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
class ColorFloodGame(private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() }) : GameModule {
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

    override fun init(context: GameContext) {
        this.context = context
        puzzlesSolved.value = 0
        matchOver.value = false
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
     */
    override fun pause() {
        if (timerStartElapsedRealtime.value != null && state.value?.isOver != true) {
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

    /**
     * Repaints the current territory [colorIndex] and re-floods from the
     * origin. No-op if [colorIndex] is out of range, already the
     * territory's current color (picking your own color grows nothing, so
     * this correctly doesn't count as a move — same "an action that
     * changes nothing isn't a move" idiom MinesweeperGame's own guard
     * clauses follow), or the board is already won.
     */
    fun pick(colorIndex: Int) {
        val s = state.value ?: return
        if (s.isOver) return
        if (colorIndex !in 0 until s.colorCount) return
        if (colorIndex == s.currentColor) return

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
