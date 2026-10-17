package com.gamesuite.games.wordgames.crossword

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything needed to put a Crossword session back exactly as it was: the full puzzle (grid with
 * its hidden answer letters, entries, clues), which entries are solved, which letters a hint gave,
 * the remaining hint budget, the session tally, the difficulty and the recent-seed history. The
 * puzzle is stored rather than regenerated because generation is random: a resumed game must
 * continue the SAME puzzle. Transient UI state (the selected cell/entry, the one-shot flash
 * signals) is deliberately not saved.
 */
@Serializable
private data class CrosswordSnapshot(
    val gridSize: Int,
    val cells: List<CrosswordSnapshotCell>,
    val entries: List<CrosswordSnapshotEntry>,
    val solvedEntryIds: List<String>,
    val hintsUsedByEntry: Map<String, Int>,
    val hintedCells: List<Int>,
    val puzzleOver: Boolean,
    val puzzlesSolved: Int,
    val hintsRemaining: Int,
    val difficulty: String,
    val recentSeedWords: List<String>,
    val players: List<CrosswordSnapshotPlayer>
)

@Serializable
private data class CrosswordSnapshotCell(val letter: String?, val number: Int?, val revealed: Boolean)

@Serializable
private data class CrosswordSnapshotEntry(
    val id: String,
    val word: String,
    val clue: String,
    val direction: String,
    val row: Int,
    val col: Int,
    val number: Int
)

@Serializable
private data class CrosswordSnapshotPlayer(val playerId: String, val isBot: Boolean)

private val crosswordSnapshotJson = Json { ignoreUnknownKeys = true }

enum class Direction { ACROSS, DOWN }

data class CrosswordPos(val row: Int, val col: Int)

data class CrosswordEntry(
    val id: String,
    val word: String,
    val clue: String,
    val direction: Direction,
    val start: CrosswordPos,
    val number: Int
) {
    val cells: List<CrosswordPos>
        get() = (0 until word.length).map { i ->
            if (direction == Direction.ACROSS) CrosswordPos(start.row, start.col + i)
            else CrosswordPos(start.row + i, start.col)
        }
}

data class CrosswordCell(
    val letter: Char?,          // correct answer letter, null = black/unused cell
    val number: Int? = null,    // clue number if this cell starts an entry
    val revealed: Boolean = false
)

data class CrosswordState(
    val grid: List<List<CrosswordCell>>,
    val entries: List<CrosswordEntry>,
    val solvedEntryIds: Set<String>,
    val selectedEntryId: String? = null,
    /** True once every entry in THIS puzzle is solved — not the whole session, see CrosswordGame.matchOver. */
    val matchOver: Boolean = false,
    /**
     * Per-entry hints revealed so far this puzzle, keyed by entry id — the
     * hint economy (see [CrosswordGame.revealNextLetter]) caps how many
     * letters of a given entry can be revealed for free, so the UI needs to
     * know how many of THIS entry's letters were hint-revealed rather than
     * guessed, independent of [solvedEntryIds].
     */
    val hintsUsedByEntry: Map<String, Int> = emptyMap()
)

/**
 * Crossword built from a small hand-authored clue bank (see CrosswordClueBank
 * — real clues can't be auto-generated from the bare dictionary). Words are
 * placed greedily: longest first at center, each subsequent word placed at
 * the first valid intersection found with an already-placed word. Input is
 * per-clue (tap a clue, type the whole answer) rather than per-cell — a
 * deliberate simplification for touch input; see games/wordgames/crossword.
 *
 * Upgrade pass (README item 9l): mirrors Hangman's session shape (see
 * HangmanGame's KDoc) since both are solo word puzzles with no opponent to
 * make smarter/dumber — [difficulty] instead picks which CrosswordClueBank
 * theme the puzzle is drawn from, and solving one puzzle no longer ends the
 * whole match: it surfaces a "New Puzzle"/"Back to Menu" choice ([playAgain]
 * / [leaveSession]) so a session can cover several puzzles, tallied in
 * [puzzlesSolved]. [generate] is a single bounded pass over a small pool
 * (<= ~30 candidates) with no retry loop, so it stays cheap enough to run
 * directly on the UI thread from startMatch(), same as before this pass.
 *
 * Variety pass: [generate] used to always seed the puzzle with the single
 * longest word in the theme's pool, so any theme with one clear longest word
 * (true of MEDIUM and HARD) produced the identical anchor word every time —
 * see [pickSeed], which now randomizes among the top few longest candidates.
 * That same seed choice also feeds [recentSeedWords], a small in-memory
 * rolling history (no persistence — it's session-only, same spirit as
 * [puzzlesSolved]) so back-to-back "New Puzzle" taps don't keep re-anchoring
 * on the same word.
 */
class CrosswordGame(private val gridSize: Int = 15) : GameModule, ResumableGame {
    override val gameId = "crossword"
    override val displayName = "Crossword"
    override val category = GameCategory.WORD
    override val minPlayers = 1
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT
    )

    val state = mutableStateOf<CrosswordState?>(null)

    /** Running tally of fully-solved puzzles across the session (survives "New Puzzle"). */
    val puzzlesSolved = mutableStateOf(0)

    /**
     * Cells revealed by the most recent correct [submitAnswer] — a one-shot signal the UI reads
     * to flash/pulse those cells instead of recoloring them instantly, then clears via
     * [clearJustSolved] once that flash animation finishes playing. Not persisted or read
     * anywhere else; a wrong guess or a hint reveal (see [revealNextLetter]) never touches this.
     */
    val justSolvedCells = mutableStateOf<Set<CrosswordPos>>(emptySet())

    /**
     * The single cell revealed by the most recent [revealNextLetter] hint — the same one-shot
     * signal shape as [justSolvedCells], but kept as its own field rather than folded into it so
     * the UI can tell "earned" (a correct submit) apart from "given" (a hint) and pulse each with
     * its own distinct color, which the game currently has no way to communicate at all. Cleared
     * via [clearJustHinted] once the UI's pulse animation finishes playing.
     */
    val justHintedCells = mutableStateOf<Set<CrosswordPos>>(emptySet())

    /**
     * Every cell a hint has revealed in the CURRENT puzzle. Unlike [justHintedCells] (a one-shot
     * flash) this persists, so the board can keep marking a given letter apart from an earned one
     * with a non-colour cue. Its size is also the puzzle's "solved with N hints" count.
     */
    val hintedCells = mutableStateOf<Set<CrosswordPos>>(emptySet())

    /**
     * The cell the player last tapped (or the first open cell of the clue they picked); always a
     * member of the selected entry. Null when nothing is selected. Transient: not saved.
     */
    val selectedCell = mutableStateOf<CrosswordPos?>(null)

    /** True only once the whole session ends (user leaves via "Back to Menu"), not per-puzzle. */
    val matchOver = mutableStateOf(false)

    /**
     * Hint uses left for the CURRENT puzzle — resets to [maxHintsForDifficulty]
     * on every [startMatch] (including "New Puzzle"). See [revealNextLetter].
     */
    val hintsRemaining = mutableStateOf(DEFAULT_MAX_HINTS)

    /** Pre-set by the UI from the player's default-difficulty setting before startMatch(). */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /**
     * Last few seed words used (most recent last), across "New Puzzle" taps
     * this session — excluded from [pickSeed]'s candidate pool so the anchor
     * word doesn't keep repeating. In-memory only, cleared on [init] like
     * [puzzlesSolved]; see [SEED_HISTORY_SIZE].
     */
    private val recentSeedWords = ArrayDeque<String>()

    private val clueBankByDifficulty: Map<CpuDifficulty, List<CrosswordClueBank.Entry>> = mapOf(
        CpuDifficulty.EASY to CrosswordClueBank.easyEverydayTheme,
        CpuDifficulty.MEDIUM to CrosswordClueBank.gamesAndTechTheme,
        CpuDifficulty.HARD to CrosswordClueBank.generalKnowledgeTheme
    )

    /**
     * Quick-win: the hint budget used to be one flat value ([DEFAULT_MAX_HINTS]) for every
     * difficulty, despite HARD's theme (see [clueBankByDifficulty]) producing longer, less
     * everyday answers than EASY's. Mirrors how [difficulty] already selects the clue bank and
     * (in WordSearchGame/HangmanGame) the puzzle-generation tier — more hints on EASY, fewer on
     * HARD, same shrinking-budget UX (CrosswordScreen's "Hint (n left)") either way.
     */
    private val maxHintsByDifficulty: Map<CpuDifficulty, Int> = mapOf(
        CpuDifficulty.EASY to 5,
        CpuDifficulty.MEDIUM to 3,
        CpuDifficulty.HARD to 2
    )

    private fun maxHintsForDifficulty(): Int = maxHintsByDifficulty[difficulty] ?: DEFAULT_MAX_HINTS

    override fun init(context: GameContext) {
        this.context = context
        puzzlesSolved.value = 0
        matchOver.value = false
        recentSeedWords.clear()
        justSolvedCells.value = emptySet()
        justHintedCells.value = emptySet()
        hintedCells.value = emptySet()
        selectedCell.value = null
    }

    // --- Resume -----------------------------------------------------------------------------

    /** Null when there is nothing to save: no puzzle yet, the session already ended, or a mode whose state lives with other players. */
    override fun snapshot(): String? {
        if (!::context.isInitialized) return null
        val s = state.value ?: return null
        if (matchOver.value) return null
        if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT &&
            context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
        ) return null
        return try {
            crosswordSnapshotJson.encodeToString(
                CrosswordSnapshot.serializer(),
                CrosswordSnapshot(
                    gridSize = gridSize,
                    cells = s.grid.flatten().map { CrosswordSnapshotCell(it.letter?.toString(), it.number, it.revealed) },
                    entries = s.entries.map {
                        CrosswordSnapshotEntry(it.id, it.word, it.clue, it.direction.name, it.start.row, it.start.col, it.number)
                    },
                    solvedEntryIds = s.solvedEntryIds.toList(),
                    hintsUsedByEntry = s.hintsUsedByEntry,
                    hintedCells = hintedCells.value.map { it.row * gridSize + it.col },
                    puzzleOver = s.matchOver,
                    puzzlesSolved = puzzlesSolved.value,
                    hintsRemaining = hintsRemaining.value,
                    difficulty = difficulty.name,
                    recentSeedWords = recentSeedWords.toList(),
                    players = context.players.map { CrosswordSnapshotPlayer(it.playerId, it.isBot) }
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Decodes and fully validates a snapshot against THIS game's grid size and roster; null if it is unusable. */
    private fun decodeSnapshot(text: String): CrosswordSnapshot? {
        if (!::context.isInitialized) return null
        val snap = try {
            crosswordSnapshotJson.decodeFromString(CrosswordSnapshot.serializer(), text)
        } catch (_: Exception) {
            return null
        }
        val roster = context.players
        if (snap.players.size != roster.size) return null
        for (i in roster.indices) {
            if (snap.players[i].playerId != roster[i].playerId || snap.players[i].isBot != roster[i].isBot) return null
        }
        if (snap.gridSize != gridSize || snap.cells.size != gridSize * gridSize) return null
        if (snap.cells.any { it.letter != null && it.letter.length != 1 }) return null

        val ids = snap.entries.map { it.id }.toSet()
        if (ids.size != snap.entries.size) return null
        for (e in snap.entries) {
            if (e.word.isEmpty()) return null
            val down = when (e.direction) {
                Direction.DOWN.name -> true
                Direction.ACROSS.name -> false
                else -> return null
            }
            val endRow = if (down) e.row + e.word.length - 1 else e.row
            val endCol = if (down) e.col else e.col + e.word.length - 1
            if (e.row !in 0 until gridSize || e.col !in 0 until gridSize) return null
            if (endRow !in 0 until gridSize || endCol !in 0 until gridSize) return null
            for (i in e.word.indices) {
                val r = if (down) e.row + i else e.row
                val c = if (down) e.col else e.col + i
                val letter = snap.cells[r * gridSize + c].letter ?: return null
                if (letter[0] != e.word[i]) return null
            }
        }
        if (!snap.solvedEntryIds.all { it in ids }) return null
        if (!snap.hintsUsedByEntry.keys.all { it in ids }) return null
        if (snap.hintedCells.any { it !in 0 until gridSize * gridSize }) return null
        if (snap.hintsRemaining !in 0..MAX_SNAPSHOT_HINTS) return null
        if (snap.puzzlesSolved < 0) return null
        return snap
    }

    override fun restore(snapshot: String): Boolean {
        val snap = decodeSnapshot(snapshot) ?: return false
        val grid = (0 until gridSize).map { r ->
            (0 until gridSize).map { c ->
                val sc = snap.cells[r * gridSize + c]
                CrosswordCell(letter = sc.letter?.firstOrNull(), number = sc.number, revealed = sc.revealed)
            }
        }
        val entries = snap.entries.map {
            CrosswordEntry(
                id = it.id,
                word = it.word,
                clue = it.clue,
                direction = if (it.direction == Direction.DOWN.name) Direction.DOWN else Direction.ACROSS,
                start = CrosswordPos(it.row, it.col),
                number = it.number
            )
        }
        matchOver.value = false
        difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        puzzlesSolved.value = snap.puzzlesSolved
        hintsRemaining.value = snap.hintsRemaining
        recentSeedWords.clear()
        recentSeedWords.addAll(snap.recentSeedWords)
        hintedCells.value = snap.hintedCells.map { CrosswordPos(it / gridSize, it % gridSize) }.toSet()
        justSolvedCells.value = emptySet()
        justHintedCells.value = emptySet()
        selectedCell.value = null
        state.value = CrosswordState(
            grid = grid,
            entries = entries,
            solvedEntryIds = snap.solvedEntryIds.toSet(),
            selectedEntryId = null,
            matchOver = snap.puzzleOver,
            hintsUsedByEntry = snap.hintsUsedByEntry
        )
        return true
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        val pool = (clueBankByDifficulty[difficulty] ?: CrosswordClueBank.gamesAndTechTheme).shuffled()
        val (grid, entries) = generate(pool)
        hintsRemaining.value = maxHintsForDifficulty()
        justSolvedCells.value = emptySet()
        justHintedCells.value = emptySet()
        hintedCells.value = emptySet()
        selectedCell.value = null
        state.value = CrosswordState(
            grid = grid,
            entries = entries,
            solvedEntryIds = emptySet()
        )
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Selects [entryId] (from the clue list) and parks the cursor on its first open cell. */
    fun selectEntry(entryId: String) {
        if (matchOver.value) return
        val s = state.value ?: return
        val entry = s.entries.firstOrNull { it.id == entryId } ?: return
        selectedCell.value = entry.cells.firstOrNull { !s.grid[it.row][it.col].revealed } ?: entry.cells.firstOrNull()
        state.value = s.copy(selectedEntryId = entryId)
    }

    /**
     * Tap on a grid cell. Picks an unsolved entry running through [pos] (across before down); tapping
     * the already-selected cell again switches to the crossing entry when there is one. A cell
     * whose entries are all solved, or one outside any entry, changes nothing.
     */
    fun selectCell(pos: CrosswordPos) {
        if (matchOver.value) return
        val s = state.value ?: return
        if (s.matchOver) return
        val candidates = s.entries
            .filter { it.id !in s.solvedEntryIds && pos in it.cells }
            .sortedBy { it.direction.ordinal }
        if (candidates.isEmpty()) return
        val current = candidates.firstOrNull { it.id == s.selectedEntryId }
        val next = when {
            current == null -> candidates.first()
            selectedCell.value == pos && candidates.size > 1 -> candidates[(candidates.indexOf(current) + 1) % candidates.size]
            else -> current
        }
        selectedCell.value = pos
        state.value = s.copy(selectedEntryId = next.id)
    }

    fun clearSelection() {
        val s = state.value ?: return
        selectedCell.value = null
        state.value = s.copy(selectedEntryId = null)
    }

    /**
     * Attempts to fill in [entryId] with [answer]. Returns true when the guess
     * matched and the entry is now revealed/solved, false when the guess was
     * wrong (state is left untouched, dialog stays open) or the entry/state
     * couldn't be found at all — callers use the return value to surface an
     * incorrect-guess indication in the UI instead of failing silently.
     */
    fun submitAnswer(entryId: String, answer: String): Boolean {
        if (matchOver.value) return false
        val s = state.value ?: return false
        val entry = s.entries.firstOrNull { it.id == entryId } ?: return false
        if (!answer.equals(entry.word, ignoreCase = true)) return false

        val newGrid = s.grid.map { it.toMutableList() }
        entry.cells.forEachIndexed { i, pos ->
            val cell = newGrid[pos.row][pos.col]
            newGrid[pos.row][pos.col] = cell.copy(revealed = true)
        }

        val solved = s.solvedEntryIds + entryId
        val allSolved = solved.size == s.entries.size

        state.value = s.copy(
            grid = newGrid,
            solvedEntryIds = solved,
            selectedEntryId = null,
            matchOver = allSolved
        )
        selectedCell.value = null

        // Signals CrosswordScreen to flash/pulse just these cells instead of an instant
        // recolor — see [justSolvedCells]'s KDoc. Cleared back to empty via [clearJustSolved]
        // once the UI's flash animation finishes.
        justSolvedCells.value = entry.cells.toSet()

        // Solving a puzzle only tallies it and surfaces the New Puzzle/Back to
        // Menu choice — it does NOT end the match. Only leaveSession() does
        // that, same split as Hangman's per-round win/loss vs. session end.
        if (allSolved) {
            puzzlesSolved.value += 1
        }

        return true
    }

    /** Called by the UI once the solved-cell flash animation (see [justSolvedCells]) finishes playing. */
    fun clearJustSolved() {
        justSolvedCells.value = emptySet()
    }

    /**
     * Hint economy: revealing every unsolved entry's first letter for free
     * made the hint trivially spammable (one tap disclosed the whole board's
     * starting letters at no cost). Replaced with a per-entry hint that
     * reveals one more letter of the currently-*selected* entry — reusing
     * [CrosswordState.selectedEntryId], the same selection AnswerDialog is
     * built from, rather than adding new selection UI — and costs a hint use
     * against [maxHintsForDifficulty]'s per-puzzle budget, visible in the UI as a shrinking
     * "Hint (n left)" affordance. A no-op with no selected entry, an already
     * fully-revealed entry, an already-solved entry, a completed puzzle, or
     * an exhausted hint budget.
     */
    fun revealNextLetter() {
        if (matchOver.value) return
        val s = state.value ?: return
        if (s.matchOver) return
        if (hintsRemaining.value <= 0) return
        val entryId = s.selectedEntryId ?: return
        if (entryId in s.solvedEntryIds) return
        val entry = s.entries.firstOrNull { it.id == entryId } ?: return

        val nextIndex = entry.cells.indexOfFirst { pos -> !s.grid[pos.row][pos.col].revealed }
        if (nextIndex < 0) return // entry already fully revealed
        revealHintCell(s, entry, entry.cells[nextIndex])
    }

    /** Reveals [pos] as a hint: spends one hint, marks the cell as given, flashes it. */
    private fun revealHintCell(s: CrosswordState, entry: CrosswordEntry, pos: CrosswordPos) {
        val newGrid = s.grid.map { it.toMutableList() }
        newGrid[pos.row][pos.col] = newGrid[pos.row][pos.col].copy(revealed = true)
        hintsRemaining.value -= 1
        hintedCells.value = hintedCells.value + pos
        selectedCell.value = pos
        state.value = s.copy(
            grid = newGrid,
            selectedEntryId = entry.id,
            hintsUsedByEntry = s.hintsUsedByEntry + (entry.id to ((s.hintsUsedByEntry[entry.id] ?: 0) + 1))
        )
        justHintedCells.value = setOf(pos)
    }

    /**
     * True while [hint] has something to do: a hint is left in the budget, the puzzle is not over,
     * and some unsolved entry still has a hidden letter. Backed by Compose state.
     */
    val canHint: Boolean
        get() {
            val s = state.value ?: return false
            if (matchOver.value || s.matchOver || hintsRemaining.value <= 0) return false
            return s.entries.any { e -> e.id !in s.solvedEntryIds && e.cells.any { !s.grid[it.row][it.col].revealed } }
        }

    /**
     * Reveals one letter: the selected cell if it is still hidden, otherwise the first hidden cell of
     * the selected entry; with nothing selected it opens the lowest-numbered unsolved entry. Costs
     * one hint of the per-puzzle budget (the same one [revealNextLetter] spends) and marks the cell
     * in [hintedCells]. Returns the revealed cell, or null when [canHint] is false. Crossword
     * keeps no best-time or best-score records, so "assisted" only shows on the result panel.
     */
    fun hint(): CrosswordPos? {
        if (!canHint) return null
        val s = state.value ?: return null
        fun hasHidden(e: CrosswordEntry) = e.id !in s.solvedEntryIds && e.cells.any { !s.grid[it.row][it.col].revealed }
        val entry = s.entries.firstOrNull { it.id == s.selectedEntryId }?.takeIf { hasHidden(it) }
            ?: s.entries.sortedBy { it.number }.firstOrNull { hasHidden(it) }
            ?: return null
        val target = selectedCell.value?.takeIf { it in entry.cells && !s.grid[it.row][it.col].revealed }
            ?: entry.cells.firstOrNull { !s.grid[it.row][it.col].revealed }
            ?: return null
        revealHintCell(s, entry, target)
        return target
    }

    /** Called by the UI once the hint-cell pulse animation (see [justHintedCells]) finishes playing. */
    fun clearJustHinted() {
        justHintedCells.value = emptySet()
    }

    /** Called from the puzzle-complete panel's "New Puzzle" button — keeps the running tally. */
    fun playAgain() {
        if (matchOver.value) return
        startMatch()
    }

    /** Called from the puzzle-complete panel's "Back to Menu" button — ends the whole session. */
    fun leaveSession() {
        if (matchOver.value) return
        val player = context.players.getOrNull(context.localPlayerIndex)
        val result = GameResult(
            scores = listOfNotNull(player?.let {
                PlayerScore(playerId = it.playerId, score = puzzlesSolved.value, isWinner = puzzlesSolved.value > 0)
            })
        )
        endMatch(result)
    }

    // ---- Generation ----

    private fun generate(pool: List<CrosswordClueBank.Entry>): Pair<List<List<CrosswordCell>>, List<CrosswordEntry>> {
        val letterGrid = Array(gridSize) { arrayOfNulls<Char>(gridSize) }
        val placedEntries = mutableListOf<CrosswordEntry>()
        val sorted = pool.sortedByDescending { it.word.length }
        var nextId = 0

        // Seed word, centered horizontally through the middle row. See
        // pickSeed() for why this is no longer always sorted.first().
        val seed = pickSeed(sorted) ?: return emptyGrid() to emptyList()
        val seedRow = gridSize / 2
        val seedCol = (gridSize - seed.word.length) / 2
        if (seedCol < 0) return emptyGrid() to emptyList()

        placeWord(letterGrid, seed.word, seedRow, seedCol, Direction.ACROSS)
        placedEntries.add(
            CrosswordEntry("e${nextId++}", seed.word, seed.clue, Direction.ACROSS, CrosswordPos(seedRow, seedCol), 0)
        )

        for (candidate in sorted) {
            if (candidate === seed) continue
            val placement = findIntersection(letterGrid, candidate.word) ?: continue
            placeWord(letterGrid, candidate.word, placement.start.row, placement.start.col, placement.direction)
            placedEntries.add(
                CrosswordEntry("e${nextId++}", candidate.word, candidate.clue, placement.direction, placement.start, 0)
            )
        }

        val numbered = assignNumbers(placedEntries)
        val cellGrid = buildCellGrid(letterGrid, numbered)
        return cellGrid to numbered
    }

    /**
     * Picks the anchor word [generate] seeds the grid with. Always taking the
     * single longest word in the pool made the anchor identical on every
     * "New Puzzle" tap for any theme with one clear longest word (true of the
     * MEDIUM and HARD clue banks) — instead this picks randomly among the
     * longest word and any others within [SEED_LENGTH_TOLERANCE] letters of
     * it, so the puzzle still opens on a long, well-connecting word but
     * varies which one. That candidate pool is further filtered against
     * [recentSeedWords] (the last [SEED_HISTORY_SIZE] anchors used this
     * session) to avoid immediately repeating — falling back to allowing a
     * repeat only when every top candidate is already in that history (e.g.
     * a theme whose top tier is smaller than the history window).
     */
    private fun pickSeed(sorted: List<CrosswordClueBank.Entry>): CrosswordClueBank.Entry? {
        val longest = sorted.firstOrNull() ?: return null
        val topCandidates = sorted.takeWhile { longest.word.length - it.word.length <= SEED_LENGTH_TOLERANCE }
        val unseen = topCandidates.filterNot { it.word in recentSeedWords }
        val chosen = unseen.ifEmpty { topCandidates }.random()

        recentSeedWords.addLast(chosen.word)
        while (recentSeedWords.size > SEED_HISTORY_SIZE) recentSeedWords.removeFirst()
        return chosen
    }

    private data class Placement(val start: CrosswordPos, val direction: Direction)

    private fun findIntersection(grid: Array<Array<Char?>>, word: String): Placement? {
        for (row in 0 until gridSize) for (col in 0 until gridSize) {
            val existing = grid[row][col] ?: continue
            for (i in word.indices) {
                if (word[i] != existing) continue

                // Try placing DOWN through this intersection.
                val downStart = CrosswordPos(row - i, col)
                if (canPlace(grid, word, downStart, Direction.DOWN)) return Placement(downStart, Direction.DOWN)

                // Try placing ACROSS through this intersection.
                val acrossStart = CrosswordPos(row, col - i)
                if (canPlace(grid, word, acrossStart, Direction.ACROSS)) return Placement(acrossStart, Direction.ACROSS)
            }
        }
        return null
    }

    private fun canPlace(grid: Array<Array<Char?>>, word: String, start: CrosswordPos, direction: Direction): Boolean {
        var hasIntersection = false
        for (i in word.indices) {
            val r = if (direction == Direction.DOWN) start.row + i else start.row
            val c = if (direction == Direction.ACROSS) start.col + i else start.col
            if (r !in 0 until gridSize || c !in 0 until gridSize) return false

            val existing = grid[r][c]
            if (existing != null) {
                if (existing != word[i]) return false
                hasIntersection = true
            }
        }
        return hasIntersection
    }

    private fun placeWord(grid: Array<Array<Char?>>, word: String, row: Int, col: Int, direction: Direction) {
        for (i in word.indices) {
            val r = if (direction == Direction.DOWN) row + i else row
            val c = if (direction == Direction.ACROSS) col + i else col
            grid[r][c] = word[i]
        }
    }

    private fun assignNumbers(entries: List<CrosswordEntry>): List<CrosswordEntry> {
        val startPositions = entries.map { it.start }.distinct().sortedWith(compareBy({ it.row }, { it.col }))
        val numberByPos = startPositions.withIndex().associate { (idx, pos) -> pos to (idx + 1) }
        return entries.map { it.copy(number = numberByPos[it.start] ?: 0) }
    }

    private fun buildCellGrid(letterGrid: Array<Array<Char?>>, entries: List<CrosswordEntry>): List<List<CrosswordCell>> {
        val numberAt = entries.associate { it.start to it.number }
        return (0 until gridSize).map { r ->
            (0 until gridSize).map { c ->
                CrosswordCell(letter = letterGrid[r][c], number = numberAt[CrosswordPos(r, c)])
            }
        }
    }

    private fun emptyGrid(): List<List<CrosswordCell>> =
        (0 until gridSize).map { (0 until gridSize).map { CrosswordCell(letter = null) } }

    companion object {
        /** How many letters shorter than the longest word still counts as a valid seed candidate. */
        private const val SEED_LENGTH_TOLERANCE = 2

        /** How many past seed words [pickSeed] avoids repeating. */
        private const val SEED_HISTORY_SIZE = 5

        /**
         * Fallback/initial hint budget used before the first [startMatch] call (and if
         * [difficulty] somehow isn't a key in [maxHintsByDifficulty]) — see
         * [maxHintsForDifficulty] for the actual per-difficulty values used in play.
         */
        private const val DEFAULT_MAX_HINTS = 3

        /** Sanity ceiling when validating a saved hint budget (the real maximum is 5). */
        private const val MAX_SNAPSHOT_HINTS = 99
    }
}
