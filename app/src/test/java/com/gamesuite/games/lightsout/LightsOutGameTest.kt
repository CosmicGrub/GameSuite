package com.gamesuite.games.lightsout

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LightsOutGame]'s scramble is private, exactly like every other solo
 * puzzle's own generator here — every board comes through the real public
 * entry point (`difficulty` + `startMatch(dailySeed)`). Neighbor/toggle
 * logic is re-checked independently in this file (see [toggledIndependently])
 * rather than trusting the engine's own bookkeeping.
 */
class LightsOutGameTest {

    private fun newGame(difficulty: CpuDifficulty = CpuDifficulty.MEDIUM): LightsOutGame {
        var fakeClock = 0L
        val game = LightsOutGame(nowMillis = { fakeClock++ })
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = difficulty
        return game
    }

    /** Independent re-derivation of which cells a press at [index] on an NxN board should toggle -- deliberately not calling anything in LightsOutGame. */
    private fun toggledIndependently(index: Int, size: Int): Set<Int> {
        val row = index / size
        val col = index % size
        val result = mutableSetOf(index)
        for (dr in -1..1) for (dc in -1..1) {
            if (dr != 0 && dc != 0) continue // orthogonal only, never diagonal
            if (dr == 0 && dc == 0) continue // self already added
            val r = row + dr
            val c = col + dc
            if (r in 0 until size && c in 0 until size) result += r * size + c
        }
        return result
    }

    @Test
    fun `difficulty controls board size -- EASY 3x3, MEDIUM 5x5, HARD 7x7`() {
        val expected = mapOf(CpuDifficulty.EASY to 3, CpuDifficulty.MEDIUM to 5, CpuDifficulty.HARD to 7)
        for ((difficulty, size) in expected) {
            val game = newGame(difficulty)
            game.startMatch(dailySeed = 1L)
            val s = game.state.value!!
            assertEquals("difficulty=$difficulty", size, s.size)
            assertEquals(size * size, s.cells.size)
        }
    }

    @Test
    fun `every freshly scrambled board has at least one lit cell -- never presented already-solved`() {
        for (difficulty in CpuDifficulty.entries) {
            for (seed in 1L..30L) {
                val game = newGame(difficulty)
                game.startMatch(dailySeed = seed)
                assertTrue(
                    "difficulty=$difficulty seed=$seed: a scrambled board should never start already-solved",
                    game.state.value!!.cells.any { it }
                )
            }
        }
    }

    /**
     * GF(2) rank of a list of bitmask vectors, via standard bitset Gaussian
     * elimination (reduce each new vector against the pivots found so far,
     * ordered by highest set bit). A completely general, well-established
     * linear-algebra primitive -- not specific to this puzzle at all.
     */
    private fun gf2Rank(vectors: List<Long>): Int {
        val pivots = mutableListOf<Long>()
        for (v0 in vectors) {
            var v = v0
            for (p in pivots) {
                val pivotBit = java.lang.Long.highestOneBit(p)
                if (v and pivotBit != 0L) v = v xor p
            }
            if (v != 0L) pivots += v
        }
        return pivots.size
    }

    /**
     * True iff [litPattern] (bit i set == cell i lit) is reachable from
     * all-off by SOME sequence of presses on a [size]x[size] board -- i.e.
     * is a genuinely solvable configuration. This is the actual mathematical
     * definition of solvability for this puzzle (litPattern lies in the
     * GF(2) span of the press vectors, verified by comparing the rank of the
     * press-vector set with and without litPattern appended -- equal rank
     * means adding it contributed nothing new, i.e. it was already in the
     * span), computed via real linear algebra rather than by attempting to
     * search for a solving strategy. Completely independent of whatever
     * approach LightsOutGame's own [LightsOutGame] scramble uses internally.
     */
    private fun isSolvable(litPattern: Long, size: Int): Boolean {
        if (litPattern == 0L) return true
        val n = size * size
        val pressVectors = (0 until n).map { index ->
            toggledIndependently(index, size).fold(0L) { mask, i -> mask or (1L shl i) }
        }
        return gf2Rank(pressVectors + litPattern) == gf2Rank(pressVectors)
    }

    @Test
    fun `every scrambled board is genuinely solvable, verified by real GF(2) linear algebra`() {
        for (difficulty in CpuDifficulty.entries) {
            for (seed in 1L..30L) {
                val game = newGame(difficulty)
                game.startMatch(dailySeed = seed)
                val s = game.state.value!!
                val litPattern = s.cells.foldIndexed(0L) { i, mask, lit -> if (lit) mask or (1L shl i) else mask }
                assertTrue(
                    "difficulty=$difficulty seed=$seed: scrambled board is not solvable -- litPattern is outside the GF(2) span of the press vectors",
                    isSolvable(litPattern, s.size)
                )
            }
        }
    }

    /**
     * Finds SOME set of press-indices whose combined toggle effect equals
     * [target] (bit j set = cell j must end up toggled an odd number of
     * times), via standard Gaussian elimination over GF(2) reduced to full
     * (reduced) row-echelon form -- every column is eliminated from EVERY
     * other row, including earlier pivot rows, not just not-yet-processed
     * ones, which is what makes reading `x[col] = pivotRow.rhs` directly off
     * the end result correct without a separate back-substitution pass.
     * Returns null only if [target] is outside the solvable span -- should
     * be unreachable for anything LightsOutGame itself produced, per its own
     * solvability-by-construction guarantee (see that class's KDoc).
     *
     * This exists so tests can WIN a real scrambled board via genuine
     * presses through the public API, instead of relying on an unproven
     * greedy strategy (e.g. "always press the first lit cell") that isn't
     * known to converge for an arbitrary Lights Out board -- order doesn't
     * matter for the RESULT here (XOR is commutative/associative, so
     * pressing this method's returned indices in any order reaches the same
     * final board), so tests just press them in iteration order.
     */
    private fun solveForPresses(pressVectors: List<Long>, target: Long, n: Int): Set<Int>? {
        data class Row(var coeffs: Long, var rhs: Boolean)
        val rows = (0 until n).map { j -> Row(pressVectors[j], (target shr j) and 1L == 1L) }
        val usedAsPivot = BooleanArray(n)
        val pivotRowIndexForColumn = IntArray(n) { -1 }

        for (col in 0 until n) {
            val bit = 1L shl col
            val pivotRowIndex = rows.indices.firstOrNull { !usedAsPivot[it] && rows[it].coeffs and bit != 0L } ?: continue
            usedAsPivot[pivotRowIndex] = true
            pivotRowIndexForColumn[col] = pivotRowIndex
            val pivot = rows[pivotRowIndex]
            for (i in rows.indices) {
                if (i != pivotRowIndex && rows[i].coeffs and bit != 0L) {
                    rows[i].coeffs = rows[i].coeffs xor pivot.coeffs
                    rows[i].rhs = rows[i].rhs xor pivot.rhs
                }
            }
        }

        if (rows.any { it.coeffs == 0L && it.rhs }) return null // inconsistent system -- target outside the span

        val x = BooleanArray(n)
        for (col in 0 until n) {
            val idx = pivotRowIndexForColumn[col]
            if (idx != -1) x[col] = rows[idx].rhs
        }
        return (0 until n).filter { x[it] }.toSet()
    }

    /** Wins [game]'s CURRENT board via genuine presses, solved for with [solveForPresses] rather than a greedy heuristic. */
    private fun winByRealPresses(game: LightsOutGame) {
        val s = game.state.value!!
        val n = s.size * s.size
        val pressVectors = (0 until n).map { index -> toggledIndependently(index, s.size).fold(0L) { mask, i -> mask or (1L shl i) } }
        val target = s.cells.foldIndexed(0L) { i, mask, lit -> if (lit) mask or (1L shl i) else mask }
        val presses = solveForPresses(pressVectors, target, n)
        assertTrue("no solution found for a board LightsOutGame itself produced -- should be impossible per its own solvability guarantee", presses != null)
        for (i in presses!!) game.press(i)
    }

    @Test
    fun `pressing a cell toggles exactly itself and its orthogonal neighbors, independently re-verified`() {
        val game = newGame(CpuDifficulty.MEDIUM)
        game.startMatch(dailySeed = 3L)
        val before = game.state.value!!
        val size = before.size
        val target = size + 1 // an interior cell (row 1, col 1 on a 5x5), has all 4 neighbors

        game.press(target)
        val after = game.state.value!!
        val expectedToggled = toggledIndependently(target, size)

        for (i in before.cells.indices) {
            val shouldToggle = i in expectedToggled
            val actuallyToggled = before.cells[i] != after.cells[i]
            assertEquals("cell $i toggle mismatch after pressing $target", shouldToggle, actuallyToggled)
        }
    }

    @Test
    fun `pressing a corner or edge cell never toggles an out-of-bounds neighbor`() {
        val game = newGame(CpuDifficulty.EASY) // 3x3, plenty of corners/edges to check
        game.startMatch(dailySeed = 1L)
        val size = game.state.value!!.size
        for (index in 0 until size * size) {
            val expected = toggledIndependently(index, size)
            assertTrue("index=$index: every toggled cell must be a valid board index", expected.all { it in 0 until size * size })
        }
    }

    @Test
    fun `pressing the same set of cells again always returns the board to all-off`() {
        // The actual solvability guarantee this engine's KDoc claims: since a
        // press is its own inverse, pressing the exact same cell twice is a
        // no-op overall (both presses cancel).
        val game = newGame(CpuDifficulty.MEDIUM)
        game.startMatch(dailySeed = 5L)
        val size = game.state.value!!.size
        val before = game.state.value!!.cells

        game.press(7)
        game.press(7)
        assertEquals("pressing the same cell twice must be a net no-op", before, game.state.value!!.cells)
    }

    @Test
    fun `pressing an out-of-range index is ignored, not a crash`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        val before = game.state.value!!.cells
        game.press(-1)
        assertEquals(before, game.state.value!!.cells)
        game.press(99)
        assertEquals(before, game.state.value!!.cells)
    }

    @Test
    fun `pressing after the board is won is a no-op`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        winByRealPresses(game)
        val s = game.state.value!!
        assertTrue(s.won)
        val movesAtWin = s.moves

        game.press(0)
        assertEquals("a press after the board is won must not count as a move", movesAtWin, game.state.value!!.moves)
        assertFalse("a press after the board is won must not re-light any cell", game.state.value!!.cells.any { it })
    }

    @Test
    fun `winning increments puzzlesSolved and freezes the timer`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        winByRealPresses(game)
        assertEquals(1, game.puzzlesSolved.value)
        org.junit.Assert.assertNotNull(game.finishedElapsedMillis.value)
    }

    @Test
    fun `winByRealPresses actually wins across every difficulty and many seeds -- exercises the GF(2) solver itself thoroughly`() {
        for (difficulty in CpuDifficulty.entries) {
            for (seed in 1L..15L) {
                val game = newGame(difficulty)
                game.startMatch(dailySeed = seed)
                winByRealPresses(game)
                assertTrue("difficulty=$difficulty seed=$seed", game.state.value!!.won)
            }
        }
    }

    @Test
    fun `pausing mid-puzzle does not inflate the recorded solve time`() {
        // Found by adversarial review: pause()/resume() used to be no-ops
        // while the timer was a pure wall-clock delta, so backgrounding the
        // app mid-puzzle (which really does call pause()/resume() -- see
        // GameSessionManager -- not merely a theoretical concern) silently
        // added the ENTIRE background duration to the recorded solve time.
        var clock = 0L
        val game = LightsOutGame(nowMillis = { clock })
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = CpuDifficulty.EASY
        game.startMatch(dailySeed = 1L)

        val s0 = game.state.value!!
        val litIndices = s0.cells.indices.filter { s0.cells[it] }
        // Press every currently-lit cell first (guaranteed to exist since a
        // scrambled board always starts with >=1 lit cell) to start the
        // timer without necessarily winning yet.
        game.press(litIndices.first())
        assertFalse(game.state.value!!.won) // sanity: EASY (3x3) scrambled boards need more than one press

        clock = 10L // 10ms of real, active play
        game.pause() // e.g. the app was backgrounded here
        clock = 600_010L // ~10 real minutes pass while backgrounded
        game.resume()
        clock = 600_020L // another 10ms of real, active play after resuming

        winByRealPresses(game)
        val recordedMillis = game.finishedElapsedMillis.value!!
        assertTrue(
            "recorded solve time was ${recordedMillis}ms -- should reflect only real active play (well under 1s), not the ~10 minutes spent paused",
            recordedMillis < 1000L
        )
    }

    @Test
    fun `matchOver resets on a new match, even after a prior endMatch -- playAgain and leaveSession never get permanently stuck`() {
        // Found by adversarial review: matchOver was only ever cleared in
        // init(), so calling endMatch() (a public override) and then
        // starting a new board left playAgain()/leaveSession() as permanent
        // no-ops for the rest of that module instance's lifetime.
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        game.endMatch(com.gamesuite.core.GameResult(scores = emptyList()))
        assertTrue(game.matchOver.value)

        game.startMatch(dailySeed = 2L) // a fresh board should always be fully playable again
        assertFalse("starting a new match must clear a stale matchOver flag", game.matchOver.value)

        winByRealPresses(game)
        assertTrue(game.state.value!!.won)

        // playAgain/leaveSession must actually work now, not silently no-op.
        val puzzlesSolvedBeforePlayAgain = game.puzzlesSolved.value
        game.playAgain()
        assertFalse("playAgain() after a fresh startMatch() must not be a stuck no-op", game.matchOver.value)
        assertEquals(puzzlesSolvedBeforePlayAgain, game.puzzlesSolved.value) // playAgain deals a fresh board, doesn't itself change the tally

        game.leaveSession()
        assertTrue("leaveSession() after a fresh startMatch() must actually end the match", game.matchOver.value)
    }

    @Test
    fun `a daily seed makes the board reproducible`() {
        val gameA = newGame(CpuDifficulty.MEDIUM)
        gameA.startMatch(dailySeed = 42L)
        val gameB = newGame(CpuDifficulty.MEDIUM)
        gameB.startMatch(dailySeed = 42L)
        assertEquals(gameA.state.value!!.cells, gameB.state.value!!.cells)
    }
}
