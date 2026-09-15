package com.gamesuite.games.kakuro

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/** BLACK cells never hold a player value -- they optionally carry [KakuroCell.acrossClue]/[KakuroCell.downClue]. WHITE cells hold the player's own entry. */
enum class KakuroCellType { BLACK, WHITE }

/**
 * One cell of a Kakuro board, row-major (index = row * cols + col).
 *
 * A BLACK cell's [acrossClue] (non-null iff a horizontal run of white cells
 * starts immediately to its right) and [downClue] (non-null iff a vertical
 * run starts immediately below it) are the sum targets for those runs -- the
 * standard Kakuro grid convention. A single BLACK cell can carry both at
 * once (the classic "corner" clue cell). A WHITE cell's [value]/[notes]
 * mirror SudokuCell exactly: [value] is the player's entered digit (1-9),
 * [notes] are pencil marks, and a cell holding a value never also holds
 * notes.
 */
data class KakuroCell(
    val type: KakuroCellType,
    val value: Int? = null,
    val notes: Set<Int> = emptySet(),
    val acrossClue: Int? = null,
    val downClue: Int? = null
)

/**
 * [cells] and [solution] are both row-major length `rows*cols` lists.
 * [solution] holds the one true digit for every WHITE cell and `null` for
 * every BLACK cell -- kept alongside [cells] (not hidden) for the same
 * non-concern SudokuState's own KDoc gives (an offline single-player local
 * game has no "the server hides this from the client" boundary to defend).
 */
data class KakuroState(
    val rows: Int,
    val cols: Int,
    val cells: List<KakuroCell>,
    val solution: List<Int?>,
    val selectedIndex: Int? = null,
    /** Every wrong entry ever placed, counted at the moment it's placed -- never decrements, same "session stat, not a live enforced limit" idiom as SudokuState.mistakes -- see docs/KAKURO_DESIGN.md's "Mistakes & feedback" section. */
    val mistakes: Int = 0,
    val won: Boolean = false
) {
    val isOver: Boolean get() = won
}

/**
 * A fixed, hand-authored run-topology: which cells of a `rows x cols`
 * bounding box are BLACK vs WHITE. NEVER generated at runtime -- see
 * docs/KAKURO_DESIGN.md's "Topology & generation" section for why
 * procedural topology generation is explicitly out of scope for this MVP
 * (a genuinely separate, much larger combinatorial problem from digit
 * generation). Row 0 and column 0 are always entirely BLACK by convention in
 * every template this file ships (the classic Kakuro "border" layout) --
 * this guarantees every white run's sum clue always has a BLACK cell
 * immediately to its left (across) or above (down) to live on, so
 * [KakuroGame]'s clue-derivation never needs to special-case a run that
 * starts at the grid edge. [KakuroGameTest] independently re-validates, for
 * every template in [KakuroTemplates], that this border convention holds
 * AND that the two hard authoring constraints docs/KAKURO_DESIGN.md calls
 * for are met: every WHITE cell belongs to a run of length >= 2 in at least
 * one direction, and no run exceeds length 9 -- so a hand-authoring mistake
 * fails loudly in a test rather than silently shipping a broken template.
 */
data class KakuroTemplate(val rows: Int, val cols: Int, val layout: List<KakuroCellType>) {
    init {
        require(layout.size == rows * cols) { "layout size ${layout.size} != rows*cols ${rows * cols}" }
    }
}

/**
 * The fixed template sets docs/KAKURO_DESIGN.md's "Topology & generation"
 * section calls for: 3 per difficulty tier (within the doc's own "3-4"
 * range), bounding boxes of roughly 6x6/9x9/12x12 for EASY/MEDIUM/HARD.
 * Each is a hand-authored `#`/`.` grid literal (row 0 and column 0 always
 * entirely `#`, the border convention [KakuroTemplate]'s KDoc explains) --
 * fixed here as literal data, never regenerated at runtime.
 * [KakuroGameTest] is the actual authority that these are valid -- this
 * comment describes intent, the test enforces it.
 *
 * SHAPE, AND WHY: every template here is a periodic diagonal-stripe pattern
 * (`#` at interior position (r,c) unless `(a*r + b*c) mod 3` hits a fixed
 * offset) where EVERY run is exactly length 2, most white cells are
 * crossed in both directions, and no run ever gets close to length 9 --
 * this was NOT the first design tried, and the reason it's the one that
 * shipped is itself an important finding (see [KakuroGame]'s own KDoc's
 * GENERATION section): an earlier attempt used sparser, more irregular
 * hand/randomly-scattered layouts with several length-4/5 runs, which
 * still satisfied docs/KAKURO_DESIGN.md's two literal hard constraints
 * ("every white cell in a run >= 2 in some direction," "no run > 9") but
 * turned out, when actually measured, to have a near-ZERO empirical chance
 * of a random digit fill's derived sums ever being uniquely solvable --
 * long runs have far too many valid AllDifferent digit-sets for a given
 * sum, so [KakuroGame.generatePuzzle]'s reject-and-retry loop essentially
 * never terminated within any reasonable attempt budget. Kakuro's sum
 * clues are a much weaker per-cell signal than Sudoku's direct-value
 * givens, so unlike Sudoku (where a random full grid always trivially
 * exists and only the CARVED-DOWN puzzle needs a uniqueness check), Kakuro
 * needs the TOPOLOGY itself to be tightly interlocked for uniqueness to be
 * findable at all by this technique. All-length-2 runs are the tightest
 * (fewest-degrees-of-freedom) run shape possible, which empirically raised
 * the per-attempt hit rate from "effectively 0 in hundreds of attempts" to
 * roughly 0.2%-2% across every tier's own size (measured via an offline
 * script mirroring this file's own fill/verify algorithm exactly) -- solid
 * odds for [MAX_GENERATION_ATTEMPTS]' own budget, and [KakuroGameTest]'s
 * own timing test is what actually confirms this holds up in the real
 * compiled engine, not just the offline estimate.
 */
object KakuroTemplates {
    private fun parse(vararg rows: String): KakuroTemplate {
        val height = rows.size
        val width = rows[0].length
        require(rows.all { it.length == width }) { "all template rows must share the same length" }
        val layout = rows.flatMap { row -> row.map { ch -> if (ch == '#') KakuroCellType.BLACK else KakuroCellType.WHITE } }
        return KakuroTemplate(height, width, layout)
    }

    val EASY: List<KakuroTemplate> = listOf(
        parse(
            "######",
            "#..#..",
            "#.#..#",
            "##..#.",
            "#..#..",
            "#.#..#"
        ),
        parse(
            "######",
            "##..#.",
            "#..#..",
            "#.#..#",
            "##..#.",
            "#..#.."
        ),
        parse(
            "######",
            "#..#..",
            "##..#.",
            "#.#..#",
            "#..#..",
            "##..#."
        )
    )

    val MEDIUM: List<KakuroTemplate> = listOf(
        parse(
            "#########",
            "#..#..#..",
            "#.#..#..#",
            "##..#..#.",
            "#..#..#..",
            "#.#..#..#",
            "##..#..#.",
            "#..#..#..",
            "#.#..#..#"
        ),
        parse(
            "#########",
            "##..#..#.",
            "#..#..#..",
            "#.#..#..#",
            "##..#..#.",
            "#..#..#..",
            "#.#..#..#",
            "##..#..#.",
            "#..#..#.."
        ),
        parse(
            "#########",
            "#..#..#..",
            "##..#..#.",
            "#.#..#..#",
            "#..#..#..",
            "##..#..#.",
            "#.#..#..#",
            "#..#..#..",
            "##..#..#."
        )
    )

    val HARD: List<KakuroTemplate> = listOf(
        parse(
            "############",
            "#..#..#..#..",
            "#.#..#..#..#",
            "##..#..#..#.",
            "#..#..#..#..",
            "#.#..#..#..#",
            "##..#..#..#.",
            "#..#..#..#..",
            "#.#..#..#..#",
            "##..#..#..#.",
            "#..#..#..#..",
            "#.#..#..#..#"
        ),
        parse(
            "############",
            "##..#..#..#.",
            "#..#..#..#..",
            "#.#..#..#..#",
            "##..#..#..#.",
            "#..#..#..#..",
            "#.#..#..#..#",
            "##..#..#..#.",
            "#..#..#..#..",
            "#.#..#..#..#",
            "##..#..#..#.",
            "#..#..#..#.."
        ),
        parse(
            "############",
            "#..#..#..#..",
            "##..#..#..#.",
            "#.#..#..#..#",
            "#..#..#..#..",
            "##..#..#..#.",
            "#.#..#..#..#",
            "#..#..#..#..",
            "##..#..#..#.",
            "#.#..#..#..#",
            "#..#..#..#..",
            "##..#..#..#."
        )
    )

    fun forTier(tier: CpuDifficulty): List<KakuroTemplate> = when (tier) {
        CpuDifficulty.EASY -> EASY
        CpuDifficulty.MEDIUM -> MEDIUM
        CpuDifficulty.HARD -> HARD
    }
}

/** One horizontal or vertical maximal white run of length >= 2 (see [KakuroGame.computeRuns]'s KDoc for why shorter "runs" are never tracked). [clueCellIndex] is the BLACK cell immediately before [cells]' first entry, guaranteed to exist by every template's border convention. */
private data class KakuroRun(val cells: List<Int>, val clueCellIndex: Int, val horizontal: Boolean)

private data class KakuroGenerated(val template: KakuroTemplate, val solution: IntArray, val cells: List<KakuroCell>)

/**
 * Kakuro: BLACK cells carry a down-sum and/or across-sum clue; WHITE cells
 * fill with digits 1-9 such that every contiguous run of white cells
 * (bounded by black cells or the board edge) sums to its own clue AND
 * contains no repeated digit within that run. Select a cell, then either
 * enter a value or toggle a pencil mark -- see [selectCell]/[setValue]/
 * [toggleNote]'s own KDocs, the exact `SudokuGame` input shape per
 * docs/KAKURO_DESIGN.md's "Input model" section.
 *
 * GENERATION (the genuinely hard part -- see docs/KAKURO_DESIGN.md's
 * "Topology & generation" section for the full reasoning this file
 * implements):
 *  1. Pick one of the difficulty tier's fixed [KakuroTemplates] (topology
 *     never varies procedurally -- that's a deliberately separate, much
 *     larger, explicitly out-of-scope problem).
 *  2. [fillDigits]: randomized backtracking fill of the template's white
 *     cells with 1-9, enforcing AllDifferent per run (both directions) --
 *     the same technique/shape `SudokuGame.generateSolvedGrid` uses for its
 *     own row/col/box AllDifferent constraints, applied here to per-run
 *     AllDifferent instead. No sum target is involved yet.
 *  3. Each run's sum clue is derived directly from the filled solution.
 *  4. [countCompletions]: a REAL backtracking solver (sum-target + distinct-
 *     digit pruning, MRV-ordered, capped at 2 solutions, under a hard call-
 *     budget ceiling) verifies those derived clues describe EXACTLY ONE
 *     valid grid -- the same shape/reasoning `SudokuGame.countSolutions`/
 *     `NonogramGame`'s own solver already use, because a valid AllDifferent
 *     fill's DERIVED sums don't automatically guarantee only that one fill
 *     produces them. Inconclusive (budget exhausted) is treated identically
 *     to "not unique" -- never guess past an unverified result.
 *  5. Not unique/inconclusive -> discard this digit fill and retry, up to
 *     [MAX_GENERATION_ATTEMPTS] (re-rolling against the SAME template most
 *     of the time, occasionally re-picking a different template from the
 *     tier's set -- see [generatePuzzle]'s own KDoc), calling `error(...)`
 *     loudly if every attempt fails rather than ever shipping an unverified
 *     puzzle.
 *
 * STATS/DAILY-SEED/SESSION SHAPE: time-only stats ([KakuroStatsStore]),
 * `startMatch(dailySeed)` seeds BOTH template choice and digit-fill
 * randomness from the same `Random` instance (matching every other solo
 * puzzle's exact `startMatch(dailySeed)` shape) -- see docs/KAKURO_DESIGN.md's
 * "Stats, daily seed, session shape" section.
 *
 * MISTAKES & FEEDBACK: Sudoku's model, not Nonogram's -- a wrong digit is
 * accepted (not blocked), compared directly against the cell's own known-
 * solution value, and counted in [KakuroState.mistakes] (never decrements).
 * No live per-run conflict highlighting -- see docs/KAKURO_DESIGN.md's
 * "Mistakes & feedback" section for why (same reasoning SudokuGame's own
 * KDoc gives for its equivalent choice).
 *
 * DELIBERATE SCOPE CUTS (see docs/KAKURO_DESIGN.md's own section): no
 * procedural topology generation, no hint/solver exposed to the player, no
 * live per-run conflict highlighting. KenKen is explicitly NOT covered here.
 */
class KakuroGame(private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() }) : GameModule {
    override val gameId = "kakuro"
    override val displayName = "Kakuro"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<KakuroState?>(null)
    val puzzlesSolved = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via "Back to Menu"), not per-board. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** Same "stopwatch starts on the first real move" idiom as SudokuGame's own field. */
    val timerStartElapsedRealtime = mutableStateOf<Long?>(null)

    /** Total time for the current board, frozen the instant [setValue] detects a win; null until then. */
    val finishedElapsedMillis = mutableStateOf<Long?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** [pause]'s own nowMillis() reading, or null while not currently paused -- see [pause]/[resume]'s KDoc. */
    private var pausedAtElapsedRealtime: Long? = null

    /** Total time spent paused during the CURRENT board, subtracted out in [freezeTimer] -- see [pause]/[resume]'s KDoc. */
    private var totalPausedMillis: Long = 0L

    override fun init(context: GameContext) {
        this.context = context
        puzzlesSolved.value = 0
        matchOver.value = false
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() = startMatch(dailySeed = null)

    /** See this class's KDoc / SudokuGame.startMatch(dailySeed)'s KDoc for what this is for. */
    fun startMatch(dailySeed: Long?) {
        val random = dailySeed?.let { Random(it) } ?: Random.Default
        val generated = generatePuzzle(difficulty, random)

        timerStartElapsedRealtime.value = null
        finishedElapsedMillis.value = null
        pausedAtElapsedRealtime = null
        totalPausedMillis = 0L
        // A fresh board is always playable, regardless of whether a PRIOR
        // board's endMatch() left matchOver stuck true -- the consolidated
        // fix every solo puzzle in this batch needed after adversarial
        // review, built in here from the start rather than rediscovered.
        matchOver.value = false

        val size = generated.template.rows * generated.template.cols
        state.value = KakuroState(
            rows = generated.template.rows,
            cols = generated.template.cols,
            cells = generated.cells,
            solution = (0 until size).map { i ->
                if (generated.template.layout[i] == KakuroCellType.WHITE) generated.solution[i] else null
            }
        )
    }

    /** Idempotent -- a second [pause] call with no [resume] in between is a no-op, same guard every solo puzzle in this batch needed after adversarial review (see SudokuGame.pause()'s KDoc for the full story). Built in here from day one per docs/KAKURO_DESIGN.md's own explicit call-out. */
    override fun pause() {
        if (pausedAtElapsedRealtime == null && timerStartElapsedRealtime.value != null && state.value?.isOver != true) {
            pausedAtElapsedRealtime = nowMillis()
        }
    }

    /** Accumulates the just-finished pause's duration into [totalPausedMillis] -- see [pause]'s KDoc. */
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

    /** Selects [index] as the target of the next setValue/clearValue/toggleNote call. Only a WHITE cell is selectable -- a BLACK cell never accepts player input, per docs/KAKURO_DESIGN.md's "Input model" section ("tap a white cell to select it"). Out-of-range indices are ignored rather than stored, same guarded-no-op treatment every invalid state gets in this batch. */
    fun selectCell(index: Int) {
        val s = state.value ?: return
        if (s.isOver) return
        if (index !in s.cells.indices) return
        if (s.cells[index].type != KakuroCellType.WHITE) return
        state.value = s.copy(selectedIndex = index)
    }

    /**
     * Sets the selected cell's value (1-9). No-op if nothing is selected,
     * the selected cell is BLACK, the board is already won, or the whole
     * session has already ended via [leaveSession]/[endMatch] (checked
     * FIRST, not just the per-board [KakuroState.isOver] flag -- the exact
     * bug pattern found missing in ColorFloodGame.pick()'s own adversarial
     * review and proactively closed here from day one, per
     * docs/KAKURO_DESIGN.md's own explicit call-out). A wrong entry is
     * still accepted (not blocked) and counted in [KakuroState.mistakes] --
     * a running total that never decrements, exactly SudokuState.mistakes'
     * own shape; see this class's KDoc for why.
     */
    fun setValue(value: Int) {
        val s = state.value ?: return
        val index = s.selectedIndex ?: return
        if (matchOver.value || s.isOver) return
        val cell = s.cells[index]
        if (cell.type != KakuroCellType.WHITE) return

        if (timerStartElapsedRealtime.value == null) timerStartElapsedRealtime.value = nowMillis()

        val cells = s.cells.toMutableList()
        cells[index] = cell.copy(value = value, notes = emptySet())

        val mistakes = if (value != s.solution[index]) s.mistakes + 1 else s.mistakes
        // BLACK cells' own value stays null forever (setValue only ever
        // touches WHITE cells, guarded above) and s.solution is also null
        // for every BLACK cell -- so this single equality check is already
        // correct for the whole board without special-casing cell type,
        // exactly mirroring SudokuGame.setValue's own win-check line.
        val won = cells.indices.all { cells[it].value == s.solution[it] }
        state.value = s.copy(cells = cells, mistakes = mistakes, won = won)
        if (won) {
            puzzlesSolved.value += 1
            freezeTimer()
        }
    }

    /** Clears the selected cell's value only (notes are untouched). No-op on a BLACK cell, an already-empty cell, an already-won board, or once the whole session has already ended (see [setValue]'s KDoc for why). */
    fun clearValue() {
        val s = state.value ?: return
        val index = s.selectedIndex ?: return
        if (matchOver.value || s.isOver) return
        val cell = s.cells[index]
        if (cell.type != KakuroCellType.WHITE || cell.value == null) return
        val cells = s.cells.toMutableList()
        cells[index] = cell.copy(value = null)
        state.value = s.copy(cells = cells)
    }

    /** Toggles a pencil mark on the selected cell. No-op on a BLACK cell, a cell that already holds a value, an already-won board, or once the whole session has already ended (see [setValue]'s KDoc for why). */
    fun toggleNote(value: Int) {
        val s = state.value ?: return
        val index = s.selectedIndex ?: return
        if (matchOver.value || s.isOver) return
        val cell = s.cells[index]
        if (cell.type != KakuroCellType.WHITE || cell.value != null) return
        val cells = s.cells.toMutableList()
        cells[index] = cell.copy(notes = if (value in cell.notes) cell.notes - value else cell.notes + value)
        state.value = s.copy(cells = cells)
    }

    private fun freezeTimer() {
        val start = timerStartElapsedRealtime.value ?: nowMillis()
        finishedElapsedMillis.value = (nowMillis() - start) - totalPausedMillis
    }

    /** Called from the finished-board panel's "New Puzzle" button -- keeps the running tally. */
    fun playAgain() {
        if (matchOver.value) return
        startMatch()
    }

    /** Called from the finished-board panel's (or in-progress screen's) "Back to Menu" button -- ends the whole session. */
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

    // -------------------------------------------------------------------
    // Generation
    // -------------------------------------------------------------------

    /**
     * Picks a template, fills it, verifies uniqueness, and retries on
     * failure -- see this class's own KDoc for the overall pipeline.
     * [MAX_GENERATION_ATTEMPTS] retries mostly re-roll [fillDigits] against
     * the SAME template (cheap, and the common case succeeds almost
     * immediately -- see [KakuroGameTest]'s own measured timing); every
     * [TEMPLATE_SWITCH_INTERVAL]th attempt re-picks a different template
     * from the tier's set instead, widening retry diversity if one
     * template's run structure keeps failing to verify unique -- exactly
     * the reject-and-retry idiom docs/KAKURO_DESIGN.md's own "Topology &
     * generation" section calls for. `error(...)` loudly if every attempt
     * fails rather than ever silently shipping an unverified puzzle --
     * should be unreachable in practice (see the class KDoc's pipeline and
     * the timing/success-rate test), but never trusted blindly.
     */
    private fun generatePuzzle(tier: CpuDifficulty, random: Random): KakuroGenerated {
        val templates = KakuroTemplates.forTier(tier)
        var template = templates[random.nextInt(templates.size)]
        repeat(MAX_GENERATION_ATTEMPTS) { attempt ->
            if (attempt > 0 && attempt % TEMPLATE_SWITCH_INTERVAL == 0 && templates.size > 1) {
                template = templates[random.nextInt(templates.size)]
            }
            val runs = computeRuns(template)
            val cellRunIds = buildCellRunIds(template, runs)
            val whiteCells = (0 until template.rows * template.cols).filter { template.layout[it] == KakuroCellType.WHITE }

            val filled = fillDigits(cellRunIds, whiteCells, runs.size, random, FILL_CALL_BUDGET) ?: return@repeat
            val runSums = IntArray(runs.size) { i -> runs[i].cells.sumOf { filled[it] } }

            val uniqueCount = countCompletions(
                size = template.rows * template.cols,
                whiteCells = whiteCells,
                cellRunIds = cellRunIds,
                runs = runs,
                runSums = runSums,
                cap = 2,
                callBudget = SOLVE_CALL_BUDGET
            )
            if (uniqueCount == 1) {
                return KakuroGenerated(template = template, solution = filled, cells = buildCells(template, runs, runSums))
            }
        }
        error("Kakuro generation failed to find a uniquely-solvable puzzle for tier=$tier after $MAX_GENERATION_ATTEMPTS attempts")
    }

    /**
     * Every maximal horizontal/vertical run of white cells with length >= 2
     * -- a length-1 "run" is never tracked here (no clue is ever generated
     * for one; see [KakuroTemplate]'s KDoc / docs/KAKURO_DESIGN.md's own
     * "a length-1 run is degenerate" reasoning), so a white cell whose only
     * neighbor-free direction is length 1 relies entirely on its OTHER
     * direction's run for both its clue and its constraint -- exactly what
     * template validity (checked by [KakuroGameTest]) guarantees exists.
     * [KakuroRun.clueCellIndex] is the BLACK cell immediately before the
     * run's first cell, which every template's border convention guarantees
     * exists (checked defensively below rather than assumed).
     */
    private fun computeRuns(template: KakuroTemplate): List<KakuroRun> {
        val rows = template.rows
        val cols = template.cols
        val runs = mutableListOf<KakuroRun>()
        for (r in 0 until rows) {
            var c = 0
            while (c < cols) {
                if (template.layout[r * cols + c] == KakuroCellType.WHITE) {
                    val start = c
                    while (c < cols && template.layout[r * cols + c] == KakuroCellType.WHITE) c++
                    val length = c - start
                    if (length >= 2) {
                        check(start > 0) { "invalid template: horizontal run starts at the grid's left edge (row=$r)" }
                        val cells = (start until c).map { r * cols + it }
                        runs += KakuroRun(cells = cells, clueCellIndex = r * cols + (start - 1), horizontal = true)
                    }
                } else {
                    c++
                }
            }
        }
        for (c in 0 until cols) {
            var r = 0
            while (r < rows) {
                if (template.layout[r * cols + c] == KakuroCellType.WHITE) {
                    val start = r
                    while (r < rows && template.layout[r * cols + c] == KakuroCellType.WHITE) r++
                    val length = r - start
                    if (length >= 2) {
                        check(start > 0) { "invalid template: vertical run starts at the grid's top edge (col=$c)" }
                        val cells = (start until r).map { it * cols + c }
                        runs += KakuroRun(cells = cells, clueCellIndex = (start - 1) * cols + c, horizontal = false)
                    }
                } else {
                    r++
                }
            }
        }
        return runs
    }

    /** For each cell index, the ids (indices into [runs]) of every run it belongs to (0, 1, or 2 -- a WHITE cell belongs to its horizontal run if any and its vertical run if any; a BLACK cell belongs to none). */
    private fun buildCellRunIds(template: KakuroTemplate, runs: List<KakuroRun>): Array<IntArray> {
        val perCell = Array(template.rows * template.cols) { mutableListOf<Int>() }
        runs.forEachIndexed { i, run -> for (cell in run.cells) perCell[cell] += i }
        return Array(perCell.size) { perCell[it].toIntArray() }
    }

    /** Builds the final [KakuroCell] list: BLACK cells get whichever of [KakuroCell.acrossClue]/[KakuroCell.downClue] apply (derived from [runSums], keyed by each run's own [KakuroRun.clueCellIndex]); WHITE cells start empty. */
    private fun buildCells(template: KakuroTemplate, runs: List<KakuroRun>, runSums: IntArray): List<KakuroCell> {
        val across = HashMap<Int, Int>()
        val down = HashMap<Int, Int>()
        runs.forEachIndexed { i, run ->
            if (run.horizontal) across[run.clueCellIndex] = runSums[i] else down[run.clueCellIndex] = runSums[i]
        }
        return (0 until template.rows * template.cols).map { i ->
            if (template.layout[i] == KakuroCellType.BLACK) {
                KakuroCell(type = KakuroCellType.BLACK, acrossClue = across[i], downClue = down[i])
            } else {
                KakuroCell(type = KakuroCellType.WHITE)
            }
        }
    }

    /**
     * Randomized backtracking fill of every white cell with 1-9, enforcing
     * ONLY per-run AllDifferent (no sum target yet -- clues are derived
     * from whatever this settles on) -- the same technique/shape
     * `SudokuGame.generateSolvedGrid` uses for its own row/col/box
     * AllDifferent constraints, applied here to per-run AllDifferent
     * instead. Plain cell order (not MRV) is deliberate, matching
     * `generateSolvedGrid`'s own precedent: a pure AllDifferent fill with no
     * sum constraint is a far easier search than [countCompletions]' sum-
     * pruned uniqueness check below, the same reason Sudoku's OWN full-grid
     * fill never needed MRV while its uniqueness solver does. [callBudget]
     * is still a hard, unconditional ceiling regardless -- if exhausted,
     * this returns null (never a partial/unverified fill) and
     * [generatePuzzle] retries.
     */
    private fun fillDigits(cellRunIds: Array<IntArray>, whiteCells: List<Int>, runCount: Int, random: Random, callBudget: Int): IntArray? {
        val grid = IntArray(cellRunIds.size)
        val runMask = IntArray(runCount)
        var calls = 0
        var budgetExceeded = false

        fun backtrack(pos: Int): Boolean {
            calls++
            if (calls > callBudget) {
                budgetExceeded = true
                return false
            }
            if (pos == whiteCells.size) return true
            val cell = whiteCells[pos]
            val ids = cellRunIds[cell]
            for (d in (1..9).shuffled(random)) {
                val bit = 1 shl d
                if (ids.any { runMask[it] and bit != 0 }) continue
                for (id in ids) runMask[id] = runMask[id] or bit
                grid[cell] = d
                if (backtrack(pos + 1)) return true
                grid[cell] = 0
                for (id in ids) runMask[id] = runMask[id] and bit.inv()
            }
            return false
        }

        val solved = backtrack(0)
        return if (solved && !budgetExceeded) grid else null
    }

    /**
     * Counts how many completions of [whiteCells] satisfy EVERY run in
     * [runs] summing exactly to its own [runSums] entry with no repeated
     * digit within that run, stopping as soon as [cap] is reached --
     * [generatePuzzle] only ever needs to distinguish "exactly one" from
     * "more than one." Uses a minimum-remaining-values (MRV) heuristic
     * (branch on the unassigned white cell with the FEWEST legal digits
     * left, where "legal" already folds in both the AllDifferent and the
     * sum-feasibility check below) plus a hard [callBudget] ceiling on total
     * backtracking steps -- the exact same shape/reasoning
     * `SudokuGame.countSolutions`/`NonogramGame`'s own solver already use,
     * for the same reason: a plain fixed-order search has no worst-case
     * bound of its own, and adversarial review already found that exact gap
     * a real ANR risk in this codebase once (see `SudokuGame.countSolutions`'
     * own KDoc for the full story). If [callBudget] is exhausted before a
     * verdict, returns [INCONCLUSIVE] rather than guessing --
     * [generatePuzzle] discards and retries on that outcome exactly like a
     * confirmed non-unique result.
     *
     * Per-digit legality folds in sum-range pruning: placing digit [d] in a
     * cell belonging to run `r` is legal only if `d` isn't already used in
     * `r`, AND (if `r` isn't yet full) the new partial sum still leaves room
     * to reach `r`'s target using its remaining cells -- bounded by the
     * absolute cheapest possible remaining sum (`1+2+...+remaining`) and the
     * absolute priciest (`9+8+...`) as safe (if slightly loose) lower/upper
     * bounds, the standard technique real Kakuro solvers use. This pruning
     * is what keeps this tractable rather than a brute-force digit
     * assignment search.
     */
    private fun countCompletions(
        size: Int,
        whiteCells: List<Int>,
        cellRunIds: Array<IntArray>,
        runs: List<KakuroRun>,
        runSums: IntArray,
        cap: Int,
        callBudget: Int
    ): Int {
        val grid = IntArray(size)
        val isAssigned = BooleanArray(size)
        val runSumSoFar = IntArray(runs.size)
        val runCountSoFar = IntArray(runs.size)
        val runMask = IntArray(runs.size)

        var calls = 0
        var count = 0
        var budgetExceeded = false

        fun isLegal(cell: Int, d: Int): Boolean {
            val bit = 1 shl d
            for (rid in cellRunIds[cell]) {
                if (runMask[rid] and bit != 0) return false
                val newCount = runCountSoFar[rid] + 1
                val newSum = runSumSoFar[rid] + d
                val remaining = runs[rid].cells.size - newCount
                val target = runSums[rid]
                if (remaining == 0) {
                    if (newSum != target) return false
                } else {
                    val minRemaining = remaining * (remaining + 1) / 2 // 1+2+...+remaining
                    val maxRemaining = remaining * (19 - remaining) / 2 // 9+8+...+(10-remaining)
                    if (newSum + minRemaining > target || newSum + maxRemaining < target) return false
                }
            }
            return true
        }

        fun place(cell: Int, d: Int) {
            val bit = 1 shl d
            for (rid in cellRunIds[cell]) {
                runMask[rid] = runMask[rid] or bit
                runSumSoFar[rid] += d
                runCountSoFar[rid] += 1
            }
            grid[cell] = d
            isAssigned[cell] = true
        }

        fun unplace(cell: Int, d: Int) {
            val bit = 1 shl d
            for (rid in cellRunIds[cell]) {
                runMask[rid] = runMask[rid] and bit.inv()
                runSumSoFar[rid] -= d
                runCountSoFar[rid] -= 1
            }
            grid[cell] = 0
            isAssigned[cell] = false
        }

        fun solve(): Boolean {
            calls++
            if (calls > callBudget) {
                budgetExceeded = true
                return true
            }

            var bestCell = -1
            var bestMask = 0
            var bestCandidateCount = 10
            for (cell in whiteCells) {
                if (isAssigned[cell]) continue
                var mask = 0
                for (d in 1..9) if (isLegal(cell, d)) mask = mask or (1 shl d)
                val n = Integer.bitCount(mask)
                if (n == 0) return false // dead end: an unassigned white cell with no legal digit at all
                if (n < bestCandidateCount) {
                    bestCandidateCount = n
                    bestCell = cell
                    bestMask = mask
                    if (n == 1) break // can't beat a forced cell
                }
            }
            if (bestCell == -1) {
                // Every white cell is filled and every run's sum/AllDifferent constraint held throughout.
                count++
                return count >= cap
            }

            var remaining = bestMask
            while (remaining != 0) {
                val bit = remaining and (-remaining)
                remaining = remaining and (remaining - 1)
                val d = Integer.numberOfTrailingZeros(bit)
                place(bestCell, d)
                if (solve()) return true
                unplace(bestCell, d)
            }
            return false
        }

        solve()
        return if (budgetExceeded) INCONCLUSIVE else count
    }

    private companion object {
        /**
         * See [generatePuzzle]'s KDoc for why this exists and how it's used
         * across retries. Set much higher than SudokuGame's/NonogramGame's
         * own 500 for a deliberate reason: even with [KakuroTemplates]' own
         * tightly-interlocked all-length-2-run design, a random digit fill's
         * derived clues are only uniquely solvable roughly 0.2%-2% of the
         * time per attempt (measured offline -- see [KakuroTemplates]' own
         * KDoc) -- an intrinsically much lower per-attempt hit rate than
         * Sudoku's carve-based approach ever needs, since Kakuro's sum
         * clues are a far weaker per-cell signal than direct given values.
         * 20,000 attempts at a worst-case-observed 0.2% per-attempt rate
         * still succeeds with overwhelming probability
         * (1-0.998^20000 ~= 1, i.e. failure chance around 1 in 10^17), while
         * each individual attempt is cheap (bounded by [FILL_CALL_BUDGET]/
         * [SOLVE_CALL_BUDGET]) -- [KakuroGameTest]'s own timing test is what
         * actually confirms the realistic (not just worst-case) attempt
         * count stays fast in practice.
         */
        const val MAX_GENERATION_ATTEMPTS = 20_000

        /** Every this-many-th failed attempt, [generatePuzzle] re-picks a different template rather than re-rolling the same one again. */
        const val TEMPLATE_SWITCH_INTERVAL = 200

        /** Hard ceiling on [fillDigits]' own backtracking steps -- see that method's KDoc for why this search is expected to be cheap in practice regardless. */
        const val FILL_CALL_BUDGET = 200_000

        /** See [countCompletions]'s KDoc for why this exists and how the number was chosen (mirroring SudokuGame.SOLVE_CALL_BUDGET's own reasoning). */
        const val SOLVE_CALL_BUDGET = 500_000

        /** Sentinel [countCompletions] result meaning "ran out of budget before reaching a verdict," never a real solution count. */
        const val INCONCLUSIVE = -1
    }
}
