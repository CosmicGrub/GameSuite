package com.gamesuite.games.kenken

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Everything needed to put a KenKen session back exactly as it was: the puzzle (cages AND the hidden
 * solution it was dealt with), the entries and pencil marks, the selection, the mistake and hint
 * counts, the session's solved tally, the tier, the daily seed and the ACTIVE (pause-adjusted)
 * elapsed time. One shape serves both Resume (written to disk) and Undo (kept in memory), so a
 * board and its tally can never be restored out of step with each other.
 */
@Serializable
private data class KenKenSnapshot(
    val size: Int,
    /** Row-major; null = empty cell. */
    val cellValues: List<Int?>,
    val cellNotes: List<List<Int>>,
    val cellCageIds: List<Int>,
    val solution: List<Int>,
    val cages: List<KenKenSnapshotCage>,
    val selectedIndex: Int?,
    val mistakes: Int,
    val won: Boolean,
    val hintsUsed: Int,
    val puzzlesSolved: Int,
    val difficulty: String,
    /** Non-null while the puzzle on the board is a daily (seeded) one. */
    val dailySeed: Long?,
    /** Pause-adjusted time on the board's clock; null if the clock has not started. */
    val activeElapsedMillis: Long?,
    /** Frozen solve time once the board is won; null before. */
    val finishedElapsedMillis: Long?,
    val players: List<KenKenSnapshotPlayer>
)

@Serializable
private data class KenKenSnapshotCage(val id: Int, val cellIndices: List<Int>, val operator: String?, val target: Int)

@Serializable
private data class KenKenSnapshotPlayer(val playerId: String, val isBot: Boolean)

private val kenKenSnapshotJson = Json { ignoreUnknownKeys = true }

/**
 * The four clue operators a cage can carry. `null` (not a value of this enum)
 * represents a single-cell cage, which carries no operator at all — see
 * [KenKenCage.operator]'s KDoc.
 */
enum class KenKenOperator { ADD, SUB, MUL, DIV }

/**
 * One cage: a set of orthogonally-connected cells (see [KenKenGame]'s class
 * KDoc, "CAGE TOPOLOGY", for how [cellIndices] is chosen) plus the
 * arithmetic clue that constrains them. [cellIndices] is always sorted
 * ascending, so `cellIndices.first()` is always the cage's top-left-most
 * cell (row-major index order IS top-left-to-bottom-right reading order) —
 * the UI relies on exactly this to know which single cell should display the
 * clue label.
 *
 * [operator] is `null` only for a single-cell cage, in which case [target]
 * IS that cell's solution value directly (the clue simply reveals it — see
 * this class's KDoc, "OPERATOR SELECTION", point 1). For every other cage,
 * [operator] is one of [KenKenOperator] and [target] is the result of
 * combining every cell in [cellIndices] via that operator, per the exact
 * per-operator rules [KenKenGame]'s KDoc spells out.
 */
data class KenKenCage(
    val id: Int,
    val cellIndices: List<Int>,
    val operator: KenKenOperator?,
    val target: Int
)

/**
 * One of a KenKen board's `size * size` cells, row-major (index = row *
 * size + col). Unlike [com.gamesuite.games.sudoku.SudokuCell], there is no
 * `isGiven` flag here at all — see [KenKenGame]'s class KDoc, "NO GIVEN
 * CELLS," for why real KenKen never pre-fills any cell, including
 * single-cell cages.
 */
data class KenKenCell(
    val value: Int? = null,
    val notes: Set<Int> = emptySet(),
    /** Which [KenKenCage.id] this cell belongs to — every cell belongs to exactly one cage. */
    val cageId: Int
)

/**
 * [cells] and [solution] are both row-major length-(size*size) lists;
 * [solution] is kept alongside [cells] for the same non-concern
 * [com.gamesuite.games.sudoku.SudokuState]'s own KDoc documents (an offline
 * single-player local game has no server-side "hide this from the client"
 * boundary to defend). [cages] is fixed for the lifetime of one board —
 * regenerated only by a fresh [KenKenGame.startMatch].
 */
data class KenKenState(
    val size: Int,
    val cells: List<KenKenCell>,
    val solution: List<Int>,
    val cages: List<KenKenCage>,
    val selectedIndex: Int? = null,
    /** Every wrong entry ever placed, counted at the moment it's placed — never decrements; same running-total idiom as SudokuState.mistakes/NonogramState.mistakes. */
    val mistakes: Int = 0,
    val won: Boolean = false
) {
    val isOver: Boolean get() = won
}

/**
 * KenKen (aka KenDoku/Calcudoku): an N x N grid that must form a complete
 * LATIN SQUARE (digit 1..N exactly once per row AND exactly once per column
 * — no 3x3-box constraint the way Sudoku has), simultaneously partitioned
 * into irregular "cages" whose cells must combine via a stated operator to
 * reach a stated target. Select a cell, then enter a value or (in notes
 * mode) toggle a pencil mark — the exact same select-then-act interaction
 * model as [com.gamesuite.games.sudoku.SudokuGame], chosen deliberately (see
 * "INPUT MODEL / MISTAKES / STATS / DAILY SEED" below) because KenKen's
 * input needs are identical to Sudoku's, not because this file assumed it
 * without checking.
 *
 * KenKen is explicitly NOT a reskin of
 * [com.gamesuite.games.wordgames] or this app's Kakuro module, despite
 * superficially both being "numeric grid puzzles in the Sudoku family."
 * Every scoping decision below was made fresh for KenKen's own actual rules,
 * not copied wholesale from Kakuro's own design — the two puzzles are
 * mechanically different in ways that matter for a generator/solver:
 *   - Kakuro's board is IRREGULAR (black/white cells) with clues living in
 *     black cells "outside" the white runs; KenKen's board is a PLAIN N x N
 *     square with clues living INSIDE the very cells they constrain (via the
 *     top-left-most cell of each cage — see [KenKenCage]'s KDoc).
 *   - Kakuro's runs are straight, axis-aligned, row/column-length segments.
 *     KenKen's cages are free-form orthogonally-connected polyominoes (an
 *     L-shape, a zigzag, a 2x2 block — anything connected) that don't need
 *     to align with rows/columns/runs at all.
 *   - Kakuro enforces AllDifferent WITHIN each run independently, in
 *     addition to (implicitly, since a run is a maximal straight segment of
 *     one row/column) the row/column constraint. KenKen has NO independent
 *     per-cage AllDifferent rule: two cells in the same cage are only
 *     forbidden from repeating a digit if they ALSO happen to share a row or
 *     column — i.e. purely as a consequence of the Latin-square constraint,
 *     never as a separate cage-level rule. A 2-cell cage whose two cells
 *     sit in different rows AND different columns can legitimately hold the
 *     same digit twice (e.g. a target of "0" is never valid for such a cage,
 *     precisely BECAUSE this engine's own [deriveCageClue] treats equal
 *     values as the degenerate case for SUB/DIV — see "OPERATOR SELECTION"
 *     point 2 — but the underlying rule allowing the repeat in the first
 *     place is real KenKen, not a bug).
 *   - Kakuro's own design doc calls a length-1 run "degenerate, avoid it."
 *     KenKen's single-cell cage is the OPPOSITE: a normal, common, load-
 *     bearing part of the puzzle (it just reveals one cell's value
 *     outright) — see [KenKenCage]'s KDoc and "OPERATOR SELECTION" point 1.
 *     Importing Kakuro's "avoid length-1" rule here would be a real
 *     correctness mistake, not a harmless stylistic choice, so this file
 *     does not.
 *
 * SCOPING DECISION 1 — CAGE TOPOLOGY: procedurally generating valid,
 * well-formed cage PARTITIONS (every cage connected, sizes reasonable, the
 * whole grid covered with no gaps or overlaps) is itself a real
 * combinatorial generation problem, structurally the same kind of problem
 * this app's Kakuro module faces for its own run topology. Given that
 * precedent — a small set of hand-authored FIXED shape templates per
 * difficulty tier, chosen randomly at generation time, with the actual
 * digit-fill (and, here, the cage clues derived from it) generated fresh on
 * top of the fixed shape — this file makes the identical choice rather than
 * attempting a general procedural cage-partitioner: [KENKEN_CAGE_TEMPLATES]
 * holds 3 hand-authored templates per tier (each a `size*size`-length
 * `IntArray` giving a cage id per cell, row-major), independently validated
 * by [KenKenGameTest] for full coverage, per-cage connectivity, and a sane
 * max cage size (4 cells — large enough for real variety, small enough that
 * an operator/target clue stays legible and an ADD/MUL target stays a
 * reasonably small number). A general topology generator (recursive
 * polyomino tiling with backtracking on dead partitions) is a strictly
 * harder, genuinely separate problem from anything else this file needs to
 * solve, and isn't required for a correct, well-varied first version — the
 * same honest trade-off Kakuro's own doc makes for its run shapes.
 *
 * TEMPLATE SHAPE MATTERS MORE THAN CAGE COUNT (a real finding, not an
 * assumption): the first MEDIUM/HARD templates this file shipped with were
 * axis-aligned strips (all-horizontal or all-vertical dominoes/trominoes,
 * plus one template mixing in a few same-row/same-column blocks) — visually
 * reasonable, and exactly what [KenKenGameTest]'s own cage-template
 * validation (connectivity/partition/size) happily accepts. They were also
 * CATASTROPHICALLY under-constrained: an empirical sweep (hundreds of
 * derive-clues-then-solve trials per template, mirroring
 * [KenKenGameTest]'s own "measure it, don't assume it" precedent) found a
 * 0% uniqueness rate at 6x6 across every one of them — [generatePuzzle]
 * reliably burned through all [MAX_GENERATION_ATTEMPTS] and failed outright.
 * The reason: an ADD or MUL clue over an entire row/column is a
 * MATHEMATICAL NO-OP (the sum/product of *any* permutation of 1..N is the
 * same fixed constant), and even a partial strip confined to one row/column
 * turns out to leave far too much of the Latin square's enormous solution
 * space (812 million grids at 6x6; ~5.5x10^27 at 9x9 — nothing like
 * Sudoku's boxed-down 9x9 space) simultaneously satisfiable. The fix,
 * confirmed by that same empirical sweep before being locked in as the
 * actual shipped templates: cages that are BENT/branching (not confined to
 * one row or column) genuinely interlock the row and column constraints
 * together, and a meaningfully higher proportion of single-cell cages
 * (roughly a quarter of the board's cells, versus barely any in the first
 * attempt) provides enough direct pins for the rest to resolve uniquely.
 * Every template actually in [KENKEN_CAGE_TEMPLATES] for MEDIUM/HARD follows
 * this discovered rule; EASY's 4x4 templates didn't need it (4x4's tiny
 * 576-grid solution space tolerates even a fully-regular partition — see
 * [KenKenGameTest]'s own measured attempt counts).
 *
 * SCOPING DECISION 2 — BOARD SIZES: EASY 4x4, MEDIUM 6x6, HARD 9x9 (see
 * [boardSizePerTier]) — real KenKen commonly spans exactly this range (4x4
 * through 9x9), and a bigger grid is both a strictly larger Latin-square
 * deduction problem AND allows longer/more varied cages, a genuine
 * technique-level difficulty jump per tier, not just "more of the same
 * size." 9x9 was picked over a smaller "hard" ceiling because it's the
 * genre-standard upper end AND because [countKenKenSolutions]'s own MRV +
 * call-budget technique is already proven at exactly this size by
 * SudokuGame's 9x9 solver — no new performance risk being taken on here (see
 * [KenKenGameTest]'s own timing sweep for the actual measured numbers, not
 * just this assumption).
 *
 * SCOPING DECISION 3 — OPERATOR SELECTION (see [deriveCageClue]): given a
 * cage's cells and their already-decided solution values,
 *   1. A 1-CELL cage carries no operator at all ([KenKenCage.operator] =
 *      null) — the clue is just that cell's value, revealed outright. This
 *      is normal, common KenKen, not a corner case (see the class KDoc's
 *      contrast with Kakuro above).
 *   2. A 2-CELL cage can honestly support any of the four operators. Let a
 *      >= b be the pair's two values. SUB (target = a - b) and DIV (target =
 *      a / b) are the "interesting," more genre-authentic clues, since they
 *      constrain the *difference/ratio* of an unordered pair rather than a
 *      commutative combination — but both are DEGENERATE when a == b (SUB
 *      would show "0−", DIV would show "1÷"; a same-value pair is legal here
 *      exactly because KenKen has no per-cage AllDifferent rule, per the
 *      class KDoc above). This file therefore only ever offers SUB/DIV when
 *      they're non-degenerate (a != b for SUB; a != b AND a % b == 0 for
 *      DIV, which together also guarantee DIV's target is >= 2, never a
 *      trivial "1÷"), picks UNIFORMLY AT RANDOM among whichever of the two
 *      qualify (using the puzzle's own seeded [Random], so this stays
 *      reproducible under a daily seed) for genre-authentic variety, and
 *      only falls back to a random pick between ADD/MUL (both always valid,
 *      for any pair including a == b) when neither SUB nor DIV qualifies.
 *   3. A 3+-CELL cage is restricted to ADD or MUL only (chosen uniformly at
 *      random between the two, both always valid for any list of
 *      1..[size]-range positive integers) — this is a REAL, well-established
 *      KenKen convention this file is documenting, not a simplification
 *      invented for this codebase: SUB and DIV are only well-defined
 *      operations on an ORDERED PAIR of two values (which of several values
 *      is "the" larger one, once there are 3+, stops being a natural
 *      question the way it is for exactly two) — no standard KenKen variant
 *      extends them to 3+ cells.
 *
 * SCOPING DECISION 4 — GENERATION / UNIQUENESS DISCIPLINE (non-negotiable,
 * same discipline every generator in this batch follows): a full solved grid
 * is filled via randomized backtracking ([generateLatinSquare] — the exact
 * same AllDifferent-per-row/AllDifferent-per-column technique
 * `SudokuGame.generateSolvedGrid` uses, just without that method's box
 * constraint), a cage template is picked, cage clues are DERIVED from that
 * one solution (never the other way around), and then — critically, since
 * nothing about "derive clues from a solution" guarantees those clues can't
 * ALSO be satisfied by some other grid, the same open question Nonogram's
 * own generator (see that class's KDoc) faces and Sudoku's carve-based
 * generator structurally doesn't — [countKenKenSolutions] actually
 * RE-SOLVES the candidate puzzle from scratch (Latin-square masks plus
 * per-cage arithmetic pruning, checked the instant a cage's last cell is
 * filled) under a hard, UNCONDITIONAL call-budget ceiling, exactly mirroring
 * `SudokuGame.countSolutions`'s own shape: a result of exactly 1 accepts the
 * puzzle, anything else (2+, or [INCONCLUSIVE] because the budget ran out
 * before a verdict) discards it and [generatePuzzle] tries again — a fresh
 * template pick, a fresh grid fill, and fresh operator choices, up to
 * [MAX_GENERATION_ATTEMPTS] — never guessing past an exhausted budget the
 * way `SudokuGame.countSolutions`'s KDoc warns against. If every attempt
 * fails, generation fails LOUDLY via `error(...)` rather than ever silently
 * shipping an unverified or non-unique board.
 *
 * SCOPING DECISION 5 — INPUT MODEL / MISTAKES / STATS / DAILY SEED /
 * SESSION SHAPE: all mirror [com.gamesuite.games.sudoku.SudokuGame]'s own
 * choices exactly, because nothing about KenKen's actual mechanics demands
 * anything different here — select-then-act digit entry plus notes (same
 * explicit-mode-toggle idiom), mistakes counted the Sudoku way (a wrong
 * digit is ACCEPTED, not blocked, compared against the known [solution],
 * and tallied as a running non-decrementing count — simpler and always
 * correct for a puzzle already generated with a verified-unique solution,
 * the same reasoning SudokuState's own KDoc gives for not independently
 * re-deriving row/column/cage conflicts), time-only stats (see
 * [KenKenStatsStore] — "fewest moves" isn't a meaningful KenKen metric any
 * more than it is for Sudoku), `startMatch(dailySeed)` daily-seed support,
 * and the standard solo-puzzle [GameModule] shape (`matchOver` reset in
 * both [init] AND [startMatch]; every mutating method checks `matchOver`
 * FIRST, not just the per-board `isOver`; pause/resume anchor a wall-clock
 * offset the same idempotent way).
 *
 * NO GIVEN CELLS: unlike Sudoku, KenKen's board starts COMPLETELY BLANK —
 * there is no `isGiven` flag on [KenKenCell] at all. A single-cell cage
 * reveals its answer as a CLUE (the label shown on the board), but the
 * player still has to actually write that digit into the cell themselves;
 * real KenKen never pre-fills a cell the way Sudoku pre-fills its givens.
 * Getting this wrong (accidentally auto-filling 1-cell cages, or treating
 * them as uneditable) would be exactly the kind of "Kakuro with weird cages"
 * mistake this file's own KDoc opens by warning against — it's called out
 * explicitly here so a future reader can see it was checked, not assumed.
 *
 * DELIBERATE SCOPE CUTS (honest MVP, same spirit as every other game's own
 * documented cuts):
 *   - No real-time row/column/cage conflict highlighting (a wrong entry is
 *     detected purely by comparing against the known [KenKenState.solution]
 *     — see SCOPING DECISION 5 above). Resume, Undo and Hint were added
 *     later: see the "Resume", "Undo" and "Hint" sections of the class body.
 *   - [countKenKenSolutions] only checks a cage's arithmetic once ALL of
 *     that cage's cells are filled, not via incremental partial-sum/product
 *     bound pruning (e.g. rejecting an ADD cage the instant its running sum
 *     already exceeds the target with cells still empty). Real potential
 *     optimization for pathological seeds, left out because Latin-square
 *     masking plus MRV ordering plus "prune the moment a cage completes"
 *     already keeps generation comfortably fast at every tier this game
 *     actually ships (9x9 and below) — see [KenKenGameTest]'s own measured
 *     timing sweep, not just this assumption.
 */
class KenKenGame(private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() }) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "kenken"
    override val displayName = "KenKen"
    override val category = GameCategory.PUZZLE
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<KenKenState?>(null)
    val puzzlesSolved = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via "Back to Menu"), not per-board. */
    val matchOver = mutableStateOf(false)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** Same "stopwatch starts on the first real move" idiom as every other solo puzzle's own field. */
    val timerStartElapsedRealtime = mutableStateOf<Long?>(null)

    /** Total time for the current board, frozen the instant [setValue] detects a win; null until then. */
    val finishedElapsedMillis = mutableStateOf<Long?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** [pause]'s own nowMillis() reading, or null while not currently paused — see [pause]/[resume]'s KDoc. */
    private var pausedAtElapsedRealtime: Long? = null

    /** Total time spent paused during the CURRENT board, subtracted out in [freezeTimer] — see [pause]/[resume]'s KDoc. */
    private var totalPausedMillis: Long = 0L

    /** Board size (N of NxN) per tier — see this class's KDoc, SCOPING DECISION 2. */
    private val boardSizePerTier: Map<CpuDifficulty, Int> = mapOf(
        CpuDifficulty.EASY to 4,
        CpuDifficulty.MEDIUM to 6,
        CpuDifficulty.HARD to 9
    )

    /** Hints taken on the CURRENT puzzle (never decreases within it, reset by a new puzzle). Any hint makes the solve "assisted": it still counts as solved but sets no best-time record. */
    val hintsUsed = mutableStateOf(0)

    /** The cell the last [hint] filled, shown marked until the next entry, undo, new puzzle or restore. */
    val hintCell = mutableStateOf<Int?>(null)

    /** The seed of the daily puzzle on the board, or null for a random one. Lets the screen re-derive its "Daily puzzle" tag after a resume. */
    var dailySeedInUse: Long? = null
        private set

    /** True once this puzzle has been solved (or is being solved) with help; such a solve must not set a record. */
    val isAssisted: Boolean get() = hintsUsed.value > 0

    /** Snapshots taken just BEFORE each human entry/note/hint, newest last; popping one is Undo. Per puzzle: a new puzzle starts empty. */
    private val history = SnapshotHistory()

    override fun init(context: GameContext) {
        this.context = context
        puzzlesSolved.value = 0
        matchOver.value = false
        hintsUsed.value = 0
        hintCell.value = null
        dailySeedInUse = null
        history.clear()
    }

    // --- Resume -----------------------------------------------------------------------------

    private fun captureSnapshot(s: KenKenState): String {
        val roster = context.players.map { KenKenSnapshotPlayer(it.playerId, it.isBot) }
        return kenKenSnapshotJson.encodeToString(
            KenKenSnapshot.serializer(),
            KenKenSnapshot(
                size = s.size,
                cellValues = s.cells.map { it.value },
                cellNotes = s.cells.map { it.notes.sorted() },
                cellCageIds = s.cells.map { it.cageId },
                solution = s.solution,
                cages = s.cages.map { KenKenSnapshotCage(it.id, it.cellIndices, it.operator?.name, it.target) },
                selectedIndex = s.selectedIndex,
                mistakes = s.mistakes,
                won = s.won,
                hintsUsed = hintsUsed.value,
                puzzlesSolved = puzzlesSolved.value,
                difficulty = difficulty.name,
                dailySeed = dailySeedInUse,
                activeElapsedMillis = activeElapsedMillis(),
                finishedElapsedMillis = finishedElapsedMillis.value,
                players = roster
            )
        )
    }

    /** Decodes and sanity-checks a snapshot against THIS game's tiers, roster and puzzle rules; null if it is unusable. Never throws. */
    private fun decodeSnapshot(text: String): KenKenSnapshot? {
        try {
            if (!this::context.isInitialized) return null
            val snap = kenKenSnapshotJson.decodeFromString(KenKenSnapshot.serializer(), text)
            val n = snap.size
            val cellCount = n * n
            val roster = context.players
            val tier = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: return null
            if (boardSizePerTier[tier] != n) return null
            if (snap.cellValues.size != cellCount || snap.cellNotes.size != cellCount ||
                snap.cellCageIds.size != cellCount || snap.solution.size != cellCount
            ) return null
            if (snap.players.size != roster.size) return null
            if (!snap.players.indices.all { snap.players[it].playerId == roster[it].playerId && snap.players[it].isBot == roster[it].isBot }) return null
            if (snap.solution.any { it !in 1..n }) return null
            // The solution must be a Latin square: each digit once per row and once per column.
            for (i in 0 until n) {
                if ((0 until n).map { snap.solution[i * n + it] }.toSet().size != n) return null
                if ((0 until n).map { snap.solution[it * n + i] }.toSet().size != n) return null
            }
            if (snap.cellValues.any { it != null && it !in 1..n }) return null
            if (snap.cellNotes.any { notes -> notes.any { it !in 1..n } }) return null
            if (snap.selectedIndex != null && snap.selectedIndex !in 0 until cellCount) return null
            if (snap.mistakes < 0 || snap.hintsUsed < 0 || snap.puzzlesSolved < 0) return null
            if ((snap.activeElapsedMillis ?: 0L) < 0L || (snap.finishedElapsedMillis ?: 0L) < 0L) return null
            // Cages: ids unique, every cell in exactly one cage, each cell's cageId agrees, clue matches the solution.
            if (snap.cages.map { it.id }.toSet().size != snap.cages.size) return null
            val seen = BooleanArray(cellCount)
            for (cage in snap.cages) {
                if (cage.cellIndices.isEmpty()) return null
                val op: KenKenOperator? = if (cage.operator == null) {
                    null
                } else {
                    KenKenOperator.values().firstOrNull { it.name == cage.operator } ?: return null
                }
                for (idx in cage.cellIndices) {
                    if (idx !in 0 until cellCount || seen[idx]) return null
                    seen[idx] = true
                    if (snap.cellCageIds[idx] != cage.id) return null
                }
                if (!cageIsSatisfied(op, cage.target, cage.cellIndices.map { snap.solution[it] })) return null
            }
            if (seen.any { !it }) return null
            val allCorrect = (0 until cellCount).all { snap.cellValues[it] == snap.solution[it] }
            if (snap.won != allCorrect) return null
            return snap
        } catch (_: Exception) {
            return null
        }
    }

    /** The board a (valid) snapshot describes. */
    private fun stateFromSnapshot(snap: KenKenSnapshot): KenKenState {
        val n = snap.size
        return KenKenState(
            size = n,
            cells = List(n * n) { i ->
                KenKenCell(value = snap.cellValues[i], notes = snap.cellNotes[i].toSet(), cageId = snap.cellCageIds[i])
            },
            solution = snap.solution,
            cages = snap.cages.map { c ->
                KenKenCage(
                    id = c.id,
                    cellIndices = c.cellIndices.sorted(),
                    operator = c.operator?.let { name -> KenKenOperator.values().first { it.name == name } },
                    target = c.target
                )
            },
            selectedIndex = snap.selectedIndex,
            mistakes = snap.mistakes,
            won = snap.won
        )
    }

    /** Null when there is nothing to save: no puzzle yet, the session already ended, or a mode whose state lives with other players. */
    override fun snapshot(): String? {
        try {
            if (!this::context.isInitialized) return null
            val s = state.value ?: return null
            if (matchOver.value) return null
            if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
            return captureSnapshot(s)
        } catch (_: Exception) {
            return null
        }
    }

    override fun restore(snapshot: String): Boolean {
        val snap = decodeSnapshot(snapshot) ?: return false
        return try {
            // Everything is computed before anything is assigned (all-or-nothing).
            val newState = stateFromSnapshot(snap)
            val tier = CpuDifficulty.values().first { it.name == snap.difficulty }
            val now = nowMillis()
            val finished = if (snap.won) (snap.finishedElapsedMillis ?: snap.activeElapsedMillis ?: 0L) else null
            val timerStart: Long? = when {
                finished != null -> now - finished
                snap.activeElapsedMillis != null -> now - snap.activeElapsedMillis
                else -> null
            }
            matchOver.value = false
            difficulty = tier
            dailySeedInUse = snap.dailySeed
            puzzlesSolved.value = snap.puzzlesSolved
            hintsUsed.value = snap.hintsUsed
            hintCell.value = null
            pausedAtElapsedRealtime = null
            totalPausedMillis = 0L
            timerStartElapsedRealtime.value = timerStart
            finishedElapsedMillis.value = finished
            history.clear()
            state.value = newState
            true
        } catch (_: Exception) {
            false
        }
    }

    // --- Undo -------------------------------------------------------------------------------

    override val canUndo: Boolean get() = history.depth > 0 && !matchOver.value && state.value?.isOver != true

    /**
     * Takes back the last entry, note or hint (one step each). The clock is never rewound, and
     * mistakes and hints used never go down, so undo cannot be used to launder either. Does nothing
     * once the puzzle is solved (a recorded solve is not reopened) or the session has ended.
     */
    override fun undo() {
        val current = state.value ?: return
        if (matchOver.value || current.isOver) return
        val previous = history.pop() ?: return
        val snap = decodeSnapshot(previous) ?: return
        val restored = stateFromSnapshot(snap)
        state.value = restored.copy(mistakes = maxOf(current.mistakes, snap.mistakes))
        hintsUsed.value = maxOf(hintsUsed.value, snap.hintsUsed)
        hintCell.value = null
    }

    // --- Hint -------------------------------------------------------------------------------

    /**
     * Fills one cell with its correct value and returns its index; null when there is nothing to
     * fill (board solved or session over). Which cell: the selected cell if it is empty; otherwise
     * an empty cell of the most constrained cage (the one with the fewest empty cells, then the
     * smallest cage); if no cell is empty (a full board that still has wrong numbers), the first
     * wrong cell. Counts in [hintsUsed], which makes the solve assisted (see [isAssisted]), and is
     * itself one undo step.
     */
    fun hint(): Int? {
        val s = state.value ?: return null
        if (matchOver.value || s.isOver) return null
        val index = chooseHintCell(s) ?: return null
        history.push(captureSnapshot(s))
        hintsUsed.value += 1
        hintCell.value = index
        placeValue(s.copy(selectedIndex = index), index, s.solution[index])
        return index
    }

    private fun chooseHintCell(s: KenKenState): Int? {
        val selected = s.selectedIndex
        if (selected != null && s.cells[selected].value == null) return selected
        var bestCage: KenKenCage? = null
        var bestEmpty: List<Int> = emptyList()
        for (cage in s.cages) {
            val empty = cage.cellIndices.filter { s.cells[it].value == null }
            if (empty.isEmpty()) continue
            val better = bestCage == null ||
                empty.size < bestEmpty.size ||
                (empty.size == bestEmpty.size && cage.cellIndices.size < bestCage.cellIndices.size)
            if (better) {
                bestCage = cage
                bestEmpty = empty
            }
        }
        if (bestCage != null) return bestEmpty.first()
        return s.cells.indices.firstOrNull { s.cells[it].value != s.solution[it] }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() = startMatch(dailySeed = null)

    /** See this class's KDoc / MinesweeperGame.startMatch(dailySeed)'s KDoc for what this is for. */
    fun startMatch(dailySeed: Long?) {
        val random = dailySeed?.let { Random(it) } ?: Random.Default
        val generated = generatePuzzle(difficulty, random)

        timerStartElapsedRealtime.value = null
        finishedElapsedMillis.value = null
        pausedAtElapsedRealtime = null
        totalPausedMillis = 0L
        // A new puzzle starts a new undo history and a fresh hint count: undo never reaches back
        // into the previous puzzle.
        history.clear()
        hintsUsed.value = 0
        hintCell.value = null
        dailySeedInUse = dailySeed
        // A fresh board is always playable, regardless of whether a PRIOR
        // board's endMatch() left matchOver stuck true -- see
        // SudokuGame/NonogramGame's own KDocs on this exact fix, built in
        // from the start here rather than rediscovered later.
        matchOver.value = false

        val cellCage = IntArray(generated.size * generated.size)
        for (cage in generated.cages) {
            for (idx in cage.cellIndices) cellCage[idx] = cage.id
        }

        state.value = KenKenState(
            size = generated.size,
            cells = List(generated.size * generated.size) { i -> KenKenCell(cageId = cellCage[i]) },
            solution = generated.solution.toList(),
            cages = generated.cages
        )
    }

    /**
     * Snapshots the current time so [resume] can measure how long the app
     * was actually paused, so backgrounding mid-puzzle never inflates the
     * recorded solve time — the same fix (with the same idempotence guard)
     * every other solo puzzle in this batch needed after adversarial review
     * found it missing; built in here from the start rather than
     * rediscovered again.
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

    /** Selects [index] as the target of the next setValue/clearValue/toggleNote call. Every cell is selectable (there is no "given" cell here — see this class's KDoc). Out-of-range indices are ignored rather than stored, same guarded-no-op treatment every other invalid state gets in this batch. Also a no-op once the board is won or the whole session has ended via [leaveSession]/[endMatch], like every other input method here. */
    fun selectCell(index: Int) {
        val s = state.value ?: return
        if (matchOver.value || s.isOver) return
        if (index !in s.cells.indices) return
        state.value = s.copy(selectedIndex = index)
    }

    /**
     * Sets the selected cell's value (1..[KenKenState.size]). No-op if
     * nothing is selected, [value] is out of the current board's own digit
     * range (defensive — unlike Sudoku's fixed 1-9, KenKen's valid digit
     * range varies 1-4/1-6/1-9 by tier, so a stale caller sending a
     * digit valid for a DIFFERENT board size must never corrupt state), the
     * board is already won, or the whole session has already ended via
     * [leaveSession]/[endMatch] (see SudokuGame.setValue's KDoc for why that
     * last check matters and isn't redundant with the per-board check). A
     * wrong entry is still accepted (not blocked) and counted in
     * [KenKenState.mistakes] — see this class's KDoc, SCOPING DECISION 5.
     */
    fun setValue(value: Int) {
        val s = state.value ?: return
        val index = s.selectedIndex ?: return
        if (matchOver.value || s.isOver) return
        if (value !in 1..s.size) return
        history.push(captureSnapshot(s))
        hintCell.value = null
        placeValue(s, index, value)
    }

    /** Writes [value] into [index] of [s] (clearing it from peers' notes), counting a wrong one as a mistake and finishing the board on a win. Shared by [setValue] and [hint]. */
    private fun placeValue(s: KenKenState, index: Int, value: Int) {
        val cell = s.cells[index]

        if (timerStartElapsedRealtime.value == null) timerStartElapsedRealtime.value = nowMillis()

        val cells = s.cells.toMutableList()
        cells[index] = cell.copy(value = value, notes = emptySet())
        // Placing a value clears that same digit from every peer's pencil
        // marks (peers = same row or column here -- no box concept in
        // KenKen) -- the same auto-clean-notes convenience SudokuGame.setValue
        // documents.
        for (p in peersOf(index, s.size)) {
            val peer = cells[p]
            if (value in peer.notes) cells[p] = peer.copy(notes = peer.notes - value)
        }

        val mistakes = if (value != s.solution[index]) s.mistakes + 1 else s.mistakes
        val won = cells.indices.all { cells[it].value == s.solution[it] }
        state.value = s.copy(cells = cells, mistakes = mistakes, won = won)
        if (won) {
            puzzlesSolved.value += 1
            freezeTimer()
        }
    }

    /** Clears the selected cell's value only (notes are untouched). No-op on an already-empty cell, an already-won board, or once the whole session has already ended via [leaveSession]/[endMatch] (see [setValue]'s KDoc for why). */
    fun clearValue() {
        val s = state.value ?: return
        val index = s.selectedIndex ?: return
        if (matchOver.value || s.isOver) return
        val cell = s.cells[index]
        if (cell.value == null) return
        history.push(captureSnapshot(s))
        hintCell.value = null
        val cells = s.cells.toMutableList()
        cells[index] = cell.copy(value = null)
        state.value = s.copy(cells = cells)
    }

    /** Toggles a pencil mark (1..[KenKenState.size]) on the selected cell. No-op if [value] is out of the current board's own digit range, the cell already holds a value, the board is already won, or once the whole session has already ended via [leaveSession]/[endMatch] (see [setValue]'s KDoc for why). */
    fun toggleNote(value: Int) {
        val s = state.value ?: return
        val index = s.selectedIndex ?: return
        if (matchOver.value || s.isOver) return
        if (value !in 1..s.size) return
        val cell = s.cells[index]
        if (cell.value != null) return
        history.push(captureSnapshot(s))
        hintCell.value = null
        val cells = s.cells.toMutableList()
        cells[index] = cell.copy(notes = if (value in cell.notes) cell.notes - value else cell.notes + value)
        state.value = s.copy(cells = cells)
    }

    private fun freezeTimer() {
        val start = timerStartElapsedRealtime.value ?: nowMillis()
        finishedElapsedMillis.value = (nowMillis() - start) - totalPausedMillis
    }

    /**
     * Pause-aware live reading of the current board's stopwatch, for the on-screen clock: null
     * before the first real entry, frozen at the pause instant while [pause]d (so backgrounding
     * never makes the display jump ahead), and exactly [finishedElapsedMillis] once the board is
     * won (so the clock ends on the very number that gets recorded).
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

    /** Every OTHER index sharing [index]'s row or column — no box concept in KenKen, unlike Sudoku's peersOf. */
    private fun peersOf(index: Int, size: Int): List<Int> {
        val row = index / size
        val col = index % size
        return (0 until size * size).filter { it != index && (it / size == row || it % size == col) }
    }

    // -------------------------------------------------------------------
    // Generation -- see this class's KDoc, SCOPING DECISIONS 1/3/4, for the
    // full reasoning behind every choice made here.
    // -------------------------------------------------------------------

    private data class GeneratedPuzzle(val size: Int, val solution: IntArray, val cages: List<KenKenCage>)

    /**
     * Picks a random hand-authored cage template for [difficulty], fills a
     * fresh Latin square, derives cage clues from that fill, and verifies
     * the resulting puzzle is genuinely uniquely solvable via
     * [countKenKenSolutions] — discarding and retrying (fresh template pick,
     * fresh fill, fresh operator choices) up to [MAX_GENERATION_ATTEMPTS] on
     * any non-unique or inconclusive result. See this class's KDoc, SCOPING
     * DECISION 4, for why every one of these steps is necessary and why
     * `error(...)` below is the right behavior on total failure rather than
     * a silent fallback.
     */
    private fun generatePuzzle(difficulty: CpuDifficulty, random: Random): GeneratedPuzzle {
        val size = boardSizePerTier[difficulty] ?: boardSizePerTier.getValue(CpuDifficulty.MEDIUM)
        val templates = KENKEN_CAGE_TEMPLATES[difficulty] ?: KENKEN_CAGE_TEMPLATES.getValue(CpuDifficulty.MEDIUM)

        repeat(MAX_GENERATION_ATTEMPTS) {
            val template = templates[random.nextInt(templates.size)]
            val solution = generateLatinSquare(size, random)

            val cellsByCage = HashMap<Int, MutableList<Int>>()
            for (cellIndex in template.indices) {
                cellsByCage.getOrPut(template[cellIndex]) { mutableListOf() }.add(cellIndex)
            }
            val cages = cellsByCage.entries.sortedBy { it.key }.mapIndexed { newId, entry ->
                val cells = entry.value
                val (op, target) = deriveCageClue(cells, solution, random)
                KenKenCage(id = newId, cellIndices = cells, operator = op, target = target)
            }

            if (countKenKenSolutions(size, cages, cap = 2, callBudget = SOLVE_CALL_BUDGET) == 1) {
                return GeneratedPuzzle(size, solution, cages)
            }
        }
        error("KenKen generation failed to find a uniquely-solvable ${size}x$size puzzle for difficulty=$difficulty after $MAX_GENERATION_ATTEMPTS attempts")
    }

    /**
     * Randomized backtracking fill of a complete NxN Latin square (digit
     * 1..N exactly once per row AND per column) — the exact same technique
     * `SudokuGame.generateSolvedGrid` uses, minus that method's 3x3-box
     * constraint (KenKen has none). Always terminates with a valid grid —
     * same reasoning as Sudoku's own version of this method.
     */
    private fun generateLatinSquare(size: Int, random: Random): IntArray {
        val grid = IntArray(size * size)
        val rowMask = IntArray(size)
        val colMask = IntArray(size)

        fun fill(pos: Int): Boolean {
            if (pos == size * size) return true
            val row = pos / size
            val col = pos % size
            for (d in (1..size).shuffled(random)) {
                val bit = 1 shl d
                if (rowMask[row] and bit == 0 && colMask[col] and bit == 0) {
                    rowMask[row] = rowMask[row] or bit
                    colMask[col] = colMask[col] or bit
                    grid[pos] = d
                    if (fill(pos + 1)) return true
                    grid[pos] = 0
                    rowMask[row] = rowMask[row] and bit.inv()
                    colMask[col] = colMask[col] and bit.inv()
                }
            }
            return false
        }

        check(fill(0)) { "KenKen Latin square generation failed for size=$size -- should be unreachable" }
        return grid
    }

    /**
     * Derives one cage's (operator, target) pair from its cells' already-
     * decided [solution] values — see this class's KDoc, SCOPING DECISION 3,
     * for the full reasoning behind every branch here.
     */
    private fun deriveCageClue(cellIndices: List<Int>, solution: IntArray, random: Random): Pair<KenKenOperator?, Int> {
        val values = cellIndices.map { solution[it] }
        return when (values.size) {
            1 -> null to values[0]
            2 -> {
                val a = maxOf(values[0], values[1])
                val b = minOf(values[0], values[1])
                val interesting = mutableListOf<KenKenOperator>()
                if (a != b) interesting += KenKenOperator.SUB
                if (a != b && a % b == 0) interesting += KenKenOperator.DIV
                if (interesting.isNotEmpty()) {
                    val op = interesting[random.nextInt(interesting.size)]
                    val target = if (op == KenKenOperator.SUB) a - b else a / b
                    op to target
                } else {
                    // a == b: SUB (target 0) and DIV (target 1) are both
                    // degenerate -- fall back to ADD/MUL, always valid.
                    val op = if (random.nextBoolean()) KenKenOperator.ADD else KenKenOperator.MUL
                    val target = if (op == KenKenOperator.ADD) a + b else a * b
                    op to target
                }
            }
            else -> {
                val op = if (random.nextBoolean()) KenKenOperator.ADD else KenKenOperator.MUL
                val target = if (op == KenKenOperator.ADD) values.sum() else values.fold(1) { acc, v -> acc * v }
                op to target
            }
        }
    }

    /**
     * True iff [values] (one per cell of a single cage, in any order)
     * satisfies [operator]/[target] — the one shared source of truth used
     * both by [countKenKenSolutions]'s backtracking pruning and by
     * [KenKenGameTest]'s own independent checks (which reimplement this
     * function fresh rather than calling it, per this batch's "don't trust
     * the engine to check itself" precedent). `operator == null` is only
     * ever valid for a single-cell cage — see [KenKenCage.operator]'s KDoc.
     */
    private fun cageIsSatisfied(operator: KenKenOperator?, target: Int, values: List<Int>): Boolean = when (operator) {
        null -> values.size == 1 && values[0] == target
        KenKenOperator.ADD -> values.sum() == target
        KenKenOperator.MUL -> values.fold(1) { acc, v -> acc * v } == target
        KenKenOperator.SUB -> values.size == 2 && kotlin.math.abs(values[0] - values[1]) == target
        KenKenOperator.DIV -> {
            if (values.size != 2) {
                false
            } else {
                val a = maxOf(values[0], values[1])
                val b = minOf(values[0], values[1])
                b != 0 && a % b == 0 && a / b == target
            }
        }
    }

    /**
     * Counts how many completions of an (initially empty) `size x size`
     * grid satisfy BOTH the Latin-square constraint AND every cage in
     * [cages], stopping as soon as [cap] is reached — [generatePuzzle] only
     * ever needs to distinguish "exactly one" from "more than one."
     *
     * Uses the exact same minimum-remaining-values (MRV) backtracking shape
     * as `SudokuGame.countSolutions` (row/col bitmasks, branch on the empty
     * cell with fewest legal candidates first) for the Latin-square half of
     * the constraint, PLUS a per-cage arithmetic check applied the instant a
     * cage's last cell gets filled (via [cageIsSatisfied]) — real pruning
     * mid-search, not just a final full-grid check (see this class's KDoc,
     * "DELIBERATE SCOPE CUTS," for what this does and does not do relative
     * to a more aggressive partial-sum-bound pruner).
     *
     * [callBudget] is a hard, unconditional ceiling on total backtracking
     * steps, mirroring `SudokuGame.countSolutions`'s own fix (found
     * necessary there by adversarial review after a real ANR risk) — if
     * exhausted before a verdict is reached, this returns [INCONCLUSIVE]
     * rather than guessing, and [generatePuzzle] discards that candidate and
     * tries a fresh one rather than ever accepting a puzzle whose uniqueness
     * was never actually verified.
     */
    private fun countKenKenSolutions(size: Int, cages: List<KenKenCage>, cap: Int, callBudget: Int): Int {
        val cellCage = IntArray(size * size)
        for (cage in cages) for (idx in cage.cellIndices) cellCage[idx] = cage.id
        val cageById = cages.associateBy { it.id }

        val work = IntArray(size * size)
        val rowMask = IntArray(size)
        val colMask = IntArray(size)
        val allMask = (1..size).fold(0) { acc, d -> acc or (1 shl d) }

        var count = 0
        var calls = 0
        var budgetExceeded = false

        fun solve(): Boolean {
            calls++
            if (calls > callBudget) {
                budgetExceeded = true
                return true
            }

            var bestPos = -1
            var bestMask = 0
            var bestCandidateCount = size + 1
            for (p in 0 until size * size) {
                if (work[p] != 0) continue
                val row = p / size
                val col = p % size
                val availableMask = allMask and (rowMask[row] or colMask[col]).inv()
                val candidateCount = Integer.bitCount(availableMask)
                if (candidateCount == 0) return false
                if (candidateCount < bestCandidateCount) {
                    bestCandidateCount = candidateCount
                    bestPos = p
                    bestMask = availableMask
                    if (candidateCount == 1) break
                }
            }
            if (bestPos == -1) {
                count++
                return count >= cap
            }

            val row = bestPos / size
            val col = bestPos % size
            var remaining = bestMask
            while (remaining != 0) {
                val bit = remaining and (-remaining)
                remaining = remaining and (remaining - 1)
                val d = Integer.numberOfTrailingZeros(bit)
                work[bestPos] = d
                rowMask[row] = rowMask[row] or bit
                colMask[col] = colMask[col] or bit

                val cage = cageById.getValue(cellCage[bestPos])
                val cageValues = cage.cellIndices.map { work[it] }
                val cageComplete = cageValues.none { it == 0 }
                val cageOk = !cageComplete || cageIsSatisfied(cage.operator, cage.target, cageValues)

                if (cageOk && solve()) return true

                work[bestPos] = 0
                rowMask[row] = rowMask[row] and bit.inv()
                colMask[col] = colMask[col] and bit.inv()
            }
            return false
        }

        solve()
        return if (budgetExceeded) INCONCLUSIVE else count
    }

    private companion object {
        const val MAX_GENERATION_ATTEMPTS = 500
        const val SOLVE_CALL_BUDGET = 200_000
        const val INCONCLUSIVE = -1
    }
}

/**
 * Hand-authored cage-partition templates, 3 per difficulty tier — see
 * [KenKenGame]'s class KDoc, SCOPING DECISION 1, for why this file uses
 * fixed templates rather than a general procedural cage-topology generator.
 *
 * Each `IntArray` is `size*size` long (row-major, index = row*size + col)
 * and gives the CAGE ID for that cell — cells sharing an id belong to the
 * same cage. Every template here independently satisfies (verified by
 * [KenKenGameTest], not just asserted here): every cage is a single
 * orthogonally-connected region, every cell belongs to exactly one cage
 * (full partition, no gaps/overlaps), and no cage exceeds 4 cells. `internal`
 * (not `private`) specifically so [KenKenGameTest] can validate this data
 * directly rather than only indirectly through generated puzzles.
 */
internal val KENKEN_CAGE_TEMPLATES: Map<CpuDifficulty, List<IntArray>> = mapOf(
    CpuDifficulty.EASY to listOf(
        // Template E1 -- six cages of size 2-3 across a 4x4 board:
        //   A A B B      cage sizes: A2 B3 C3 D3 E3 F2
        //   C D D B
        //   C D E E
        //   C F F E
        intArrayOf(
            0, 0, 1, 1,
            2, 3, 3, 1,
            2, 3, 4, 4,
            2, 5, 5, 4
        ),
        // Template E2 -- six cages of size 2-3, a different shape from E1:
        //   A B B C
        //   A B D C
        //   A D D C
        //   E E F F
        intArrayOf(
            0, 1, 1, 2,
            0, 1, 3, 2,
            0, 3, 3, 2,
            4, 4, 5, 5
        ),
        // Template E3 -- includes one 4-cell cage (D) for variety, the rest smaller:
        //   A A B C
        //   D A B C
        //   D E E C
        //   D D F F
        intArrayOf(
            0, 0, 1, 2,
            3, 0, 1, 2,
            3, 4, 4, 2,
            3, 3, 5, 5
        )
    ),
    CpuDifficulty.MEDIUM to listOf(
        // Template M1 -- irregular mix: one bent 4-cell hook (id 0), a couple
        // of straight trominoes (ids 4, 13), several dominoes, and 9 single-
        // cell cages scattered through the rest. NOT axis-aligned strips
        // (see this class's KDoc, SCOPING DECISION 1, "TEMPLATE SHAPE MATTERS
        // MORE THAN CAGE COUNT" for why that distinction was the actual fix).
        //   0 1 2 3 4 5
        //   0 0 0 3 4 6
        //   7 8 9 10 4 11
        //   12 8 13 13 13 14
        //   15 16 17 18 18 14
        //   15 16 17 18 19 19
        intArrayOf(
            0, 1, 2, 3, 4, 5,
            0, 0, 0, 3, 4, 6,
            7, 8, 9, 10, 4, 11,
            12, 8, 13, 13, 13, 14,
            15, 16, 17, 18, 18, 14,
            15, 16, 17, 18, 19, 19
        ),
        // Template M2 -- a different irregular mix: one bent 4-cell hook
        // (id 0), a straight tromino (id 6), several bent trominoes (ids 5,
        // 10), dominoes, and 5 single-cell cages.
        //   0 1 1 2 2 3
        //   4 4 5 6 6 3
        //   4 7 5 8 6 9
        //   4 7 10 10 10 9
        //   11 12 12 13 14 15
        //   16 12 13 13 14 14
        intArrayOf(
            0, 1, 1, 2, 2, 3,
            4, 4, 5, 6, 6, 3,
            4, 7, 5, 8, 6, 9,
            4, 7, 10, 10, 10, 9,
            11, 12, 12, 13, 14, 15,
            16, 12, 13, 13, 14, 14
        ),
        // Template M3 -- two 2x2 blocks (ids 1, 7 -- size 4 each), a bent
        // 4-cell cage (id 9), straight and bent trominoes, dominoes, and 8
        // single-cell cages.
        //   0 1 1 2 3 4
        //   5 1 1 2 6 4
        //   7 7 8 8 6 6
        //   7 7 9 10 6 11
        //   12 12 13 14 15 15
        //   12 16 13 17 17 17
        intArrayOf(
            0, 1, 1, 2, 3, 4,
            5, 1, 1, 2, 6, 4,
            7, 7, 8, 8, 6, 6,
            7, 7, 9, 10, 6, 11,
            12, 12, 13, 14, 15, 15,
            12, 16, 13, 17, 17, 17
        )
    ),
    CpuDifficulty.HARD to listOf(
        // Template H1, H2, H3 -- irregular 9x9 mixes (roughly a quarter of
        // cells are single-cell cages, the rest dominoes/trominoes/a few
        // tetrominoes, all bent/branching rather than axis-aligned strips).
        // See this class's KDoc, SCOPING DECISION 1, for why this shape
        // family (not H1/H2's original all-tromino strips) is what actually
        // makes HARD-tier generation succeed in practice.
        intArrayOf(
            0, 1, 2, 3, 3, 4, 5, 5, 6,
            7, 7, 2, 3, 8, 4, 4, 9, 10,
            11, 12, 12, 13, 8, 14, 14, 15, 16,
            17, 18, 19, 19, 20, 21, 14, 22, 22,
            23, 24, 25, 26, 27, 28, 14, 22, 22,
            23, 24, 29, 26, 27, 27, 30, 30, 30,
            24, 24, 31, 32, 32, 33, 33, 34, 34,
            35, 35, 36, 36, 37, 37, 37, 37, 38,
            39, 35, 40, 40, 40, 41, 42, 43, 38
        ),
        intArrayOf(
            0, 1, 2, 3, 4, 5, 5, 6, 7,
            1, 1, 8, 8, 4, 9, 10, 11, 11,
            12, 1, 13, 14, 15, 16, 17, 17, 11,
            12, 12, 18, 19, 19, 16, 20, 21, 21,
            12, 22, 23, 24, 24, 25, 26, 26, 21,
            27, 23, 23, 24, 24, 25, 28, 29, 30,
            27, 31, 32, 32, 33, 34, 35, 35, 36,
            37, 37, 32, 38, 39, 40, 41, 35, 35,
            42, 37, 43, 38, 41, 41, 41, 44, 44
        ),
        intArrayOf(
            0, 1, 2, 2, 3, 4, 4, 5, 6,
            0, 7, 2, 2, 3, 8, 9, 9, 10,
            11, 11, 11, 12, 13, 14, 15, 16, 16,
            17, 11, 12, 12, 18, 19, 20, 21, 21,
            22, 23, 24, 19, 19, 19, 25, 26, 21,
            27, 28, 29, 29, 30, 30, 25, 25, 31,
            27, 32, 33, 29, 34, 35, 36, 37, 37,
            38, 38, 39, 40, 41, 42, 36, 43, 43,
            44, 45, 46, 47, 41, 41, 48, 49, 50
        )
    )
)
