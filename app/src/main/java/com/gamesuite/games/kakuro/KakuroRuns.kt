package com.gamesuite.games.kakuro

/**
 * One maximal horizontal or vertical run of white cells (length >= 2) and the clue it must sum to.
 * [clueCellIndex] is the BLACK cell immediately before the run's first cell (to its left for an
 * across run, above it for a down run), whose [KakuroCell.acrossClue] / [KakuroCell.downClue] is
 * [target]. [cells] are row-major board indices in reading order.
 */
data class KakuroRunInfo(
    val cells: List<Int>,
    val clueCellIndex: Int,
    val horizontal: Boolean,
    val target: Int
)

/**
 * What the player has typed into one run so far. [entered] is the plain sum of the digits placed,
 * [filled] how many of the run's [size] cells hold one, and [complete] is true only for a run that
 * is full, sums to its target and repeats no digit.
 */
data class KakuroRunProgress(
    val entered: Int,
    val filled: Int,
    val size: Int,
    val hasRepeat: Boolean,
    val complete: Boolean
)

/**
 * Read-only run queries over a [KakuroState], for a screen that wants to show a selected cell's
 * across and down runs, their clue cells, how close each sum is and which digits are already
 * used. Pure Kotlin (no Android or Compose), so it is covered by plain JVM tests. The engine's own
 * generator keeps its private run bookkeeping; this is derived from the visible cells and clues
 * alone, so it cannot disagree with what the board shows.
 */
object KakuroRuns {

    /** A stable id for "the across (or down) clue held by [clueCellIndex]", for sets of clue halves. */
    fun clueKey(clueCellIndex: Int, horizontal: Boolean): Int = clueCellIndex * 2 + if (horizontal) 0 else 1

    /**
     * The run through white cell [index] in the given direction, or null when [index] is not a
     * white cell, the "run" has length 1 (no clue is ever generated for one), or its clue cell
     * cannot be found (never true for this app's templates, whose row 0 and column 0 are black).
     */
    fun runThrough(state: KakuroState, index: Int, horizontal: Boolean): KakuroRunInfo? {
        if (index !in state.cells.indices) return null
        if (state.cells[index].type != KakuroCellType.WHITE) return null
        val step = if (horizontal) 1 else state.cols

        var start = index
        while (canStepBack(state, start, horizontal) && state.cells[start - step].type == KakuroCellType.WHITE) {
            start -= step
        }
        val cells = ArrayList<Int>()
        var i = start
        while (true) {
            cells.add(i)
            if (canStepForward(state, i, horizontal) && state.cells[i + step].type == KakuroCellType.WHITE) i += step else break
        }
        if (cells.size < 2) return null
        if (!canStepBack(state, start, horizontal)) return null

        val clueCellIndex = start - step
        val clueCell = state.cells[clueCellIndex]
        val target = (if (horizontal) clueCell.acrossClue else clueCell.downClue) ?: return null
        return KakuroRunInfo(cells = cells, clueCellIndex = clueCellIndex, horizontal = horizontal, target = target)
    }

    /** Every run on the board, across runs and down runs, each exactly once. */
    fun allRuns(state: KakuroState): List<KakuroRunInfo> {
        val runs = ArrayList<KakuroRunInfo>()
        for (index in state.cells.indices) {
            if (state.cells[index].type != KakuroCellType.WHITE) continue
            val row = index / state.cols
            val col = index % state.cols
            if (col == 0 || state.cells[index - 1].type != KakuroCellType.WHITE) {
                runThrough(state, index, horizontal = true)?.let { runs.add(it) }
            }
            if (row == 0 || state.cells[index - state.cols].type != KakuroCellType.WHITE) {
                runThrough(state, index, horizontal = false)?.let { runs.add(it) }
            }
        }
        return runs
    }

    /** How far the player has got with [run]; see [KakuroRunProgress]. */
    fun progress(state: KakuroState, run: KakuroRunInfo): KakuroRunProgress {
        var sum = 0
        var filled = 0
        var seen = 0
        var duplicate = false
        for (i in run.cells) {
            val v = state.cells[i].value ?: continue
            sum += v
            filled += 1
            val bit = 1 shl v
            if (seen and bit != 0) duplicate = true
            seen = seen or bit
        }
        val complete = filled == run.cells.size && sum == run.target && !duplicate
        return KakuroRunProgress(entered = sum, filled = filled, size = run.cells.size, hasRepeat = duplicate, complete = complete)
    }

    /** The [clueKey]s of every run that is currently [KakuroRunProgress.complete]. */
    fun completedClueKeys(state: KakuroState): Set<Int> {
        val keys = HashSet<Int>()
        for (run in allRuns(state)) {
            if (progress(state, run).complete) keys.add(clueKey(run.clueCellIndex, run.horizontal))
        }
        return keys
    }

    /** Digits already placed in any of [runs], not counting the cell at [exceptIndex] itself. */
    fun usedDigits(state: KakuroState, runs: List<KakuroRunInfo>, exceptIndex: Int): Set<Int> {
        val used = HashSet<Int>()
        for (run in runs) {
            for (i in run.cells) {
                if (i == exceptIndex) continue
                state.cells[i].value?.let { used.add(it) }
            }
        }
        return used
    }

    private fun canStepBack(state: KakuroState, index: Int, horizontal: Boolean): Boolean =
        if (horizontal) index % state.cols > 0 else index >= state.cols

    private fun canStepForward(state: KakuroState, index: Int, horizontal: Boolean): Boolean =
        if (horizontal) index % state.cols < state.cols - 1 else index + state.cols < state.cells.size
}
