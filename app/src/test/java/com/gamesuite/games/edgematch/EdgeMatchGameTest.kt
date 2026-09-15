package com.gamesuite.games.edgematch

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EdgeMatchGame]'s generation (`generateSolvedGrid`, private) is never trusted at
 * face value here. Since a tile's POSITION never changes for the life of a puzzle
 * (see the class KDoc's CORE MECHANIC section), the real correctness question is
 * simpler than a general placement puzzle: does resetting every tile's own
 * `rotation` back to 0 — recovering the exact solved arrangement the generator
 * built — actually satisfy every adjacency? [allTilesAtRotationZeroAreSolved]
 * checks this independently, and [rotateEveryTileBackToItsOwnZero] additionally
 * proves it end-to-end through the real public `tapTile()` API on real generated
 * puzzles, rather than only checking the data.
 */
class EdgeMatchGameTest {

    private fun newGame(difficulty: CpuDifficulty = CpuDifficulty.MEDIUM): EdgeMatchGame {
        var fakeClock = 0L
        val game = EdgeMatchGame(nowMillis = { fakeClock++ })
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

    /** Independent re-derivation of the win check, never calling EdgeMatchGame's own (private) isBoardSolved. */
    private fun isSolvedIndependently(tiles: List<EdgeMatchTile>, size: Int): Boolean {
        for (r in 0 until size) {
            for (c in 0 until size) {
                val i = r * size + c
                if (c < size - 1 && tiles[i].currentEdge(EdgeMatchGame.RIGHT) != tiles[i + 1].currentEdge(EdgeMatchGame.LEFT)) return false
                if (r < size - 1 && tiles[i].currentEdge(EdgeMatchGame.BOTTOM) != tiles[i + size].currentEdge(EdgeMatchGame.TOP)) return false
            }
        }
        return true
    }

    private fun allTilesAtRotationZeroAreSolved(tiles: List<EdgeMatchTile>, size: Int): Boolean =
        isSolvedIndependently(tiles.map { it.copy(rotation = 0) }, size)

    /** Drives a REAL generated puzzle to solved through the real public `tapTile()` API: for each tile, taps it exactly enough times to bring its rotation back to 0 (`(sides - rotation) % sides`, `sides` being that tile's OWN edge count -- 4 for square, 6 for hex), relying on nothing but the generator's own "rotation 0 is always a solution" guarantee. */
    private fun rotateEveryTileBackToItsOwnZero(game: EdgeMatchGame) {
        val s0 = game.state.value!!
        for (i in s0.tiles.indices) {
            val sides = s0.tiles[i].canonicalEdges.size
            val neededTaps = (sides - s0.tiles[i].rotation) % sides
            repeat(neededTaps) { game.tapTile(i) }
        }
    }

    /** Independent re-derivation of the HEX win check (own axial deltas, own loop -- never calling EdgeMatchGame's own private isBoardSolved/neighborIndex), only checking each interior seam once (E/NE/NW, one per opposite pair) same as the engine's own [forwardDirectionsFor]-equivalent choice. */
    private fun isHexSolvedIndependently(tiles: List<EdgeMatchTile>, size: Int): Boolean {
        val deltaQ = intArrayOf(1, 1, 0) // E, NE, NW
        val deltaR = intArrayOf(0, -1, -1)
        for (r in 0 until size) {
            for (q in 0 until size) {
                val i = r * size + q
                for (dir in 0..2) {
                    val nq = q + deltaQ[dir]
                    val nr = r + deltaR[dir]
                    if (nq in 0 until size && nr in 0 until size) {
                        val ni = nr * size + nq
                        val opposite = (dir + 3) % 6
                        if (tiles[i].currentEdge(dir) != tiles[ni].currentEdge(opposite)) return false
                    }
                }
            }
        }
        return true
    }

    private fun allHexTilesAtRotationZeroAreSolved(tiles: List<EdgeMatchTile>, size: Int): Boolean =
        isHexSolvedIndependently(tiles.map { it.copy(rotation = 0) }, size)

    @Test
    fun `difficulty controls grid size and color count -- EASY 4x4x4, MEDIUM 6x6x5, HARD 8x8x6`() {
        val expected = mapOf(
            CpuDifficulty.EASY to (4 to 4),
            CpuDifficulty.MEDIUM to (6 to 5),
            CpuDifficulty.HARD to (8 to 6)
        )
        for ((difficulty, sizeAndColors) in expected) {
            val (size, colorCount) = sizeAndColors
            val game = newGame(difficulty)
            game.startMatch()
            val s = game.state.value!!
            assertEquals("difficulty=$difficulty", size, s.size)
            assertEquals(colorCount, s.colorCount)
            assertEquals(size * size, s.tiles.size)
            for (tile in s.tiles) for (edge in tile.canonicalEdges) {
                assertTrue("difficulty=$difficulty: every edge color must be in 0 until $colorCount", edge in 0 until colorCount)
            }
        }
    }

    /**
     * NOTE: this deliberately does NOT check `state.value!!.solved` -- that field defaults to
     * `false` in [EdgeMatchState] and is only ever computed by `tapTile()`, so asserting it right
     * after generation would be trivially true regardless of what the generator actually
     * produced (a real, independently-caught test-coverage gap: it would pass even against a
     * broken generator that handed back an already-matched board). The genuine check is running
     * [isSolvedIndependently] directly against the freshly generated tiles.
     */
    @Test
    fun `a freshly generated puzzle is never already solved`() {
        for (difficulty in CpuDifficulty.entries) {
            repeat(8) {
                val game = newGame(difficulty)
                game.startMatch()
                val s = game.state.value!!
                assertFalse("difficulty=$difficulty", isSolvedIndependently(s.tiles, s.size))
            }
        }
    }

    @Test
    fun `a daily seed makes the puzzle reproducible`() {
        val gameA = newGame(CpuDifficulty.MEDIUM)
        gameA.startMatch(dailySeed = 42L)
        val gameB = newGame(CpuDifficulty.MEDIUM)
        gameB.startMatch(dailySeed = 42L)
        assertEquals(
            "the same daily seed should produce identical tiles",
            gameA.state.value!!.tiles,
            gameB.state.value!!.tiles
        )
    }

    @Test
    fun `resetting every tile's own rotation to 0 always solves a freshly generated puzzle`() {
        for (difficulty in CpuDifficulty.entries) {
            repeat(8) { trial ->
                val game = newGame(difficulty)
                game.startMatch()
                val s = game.state.value!!
                assertTrue(
                    "difficulty=$difficulty trial=$trial: the generator's own solved arrangement (every tile at rotation 0) does not actually satisfy every adjacency",
                    allTilesAtRotationZeroAreSolved(s.tiles, s.size)
                )
            }
        }
    }

    @Test
    fun `rotating every scrambled tile back to its own original orientation solves a real generated puzzle through the public API`() {
        for (difficulty in CpuDifficulty.entries) {
            for (seed in 1L..5L) {
                val game = newGame(difficulty)
                game.startMatch(dailySeed = seed)
                rotateEveryTileBackToItsOwnZero(game)
                assertTrue("difficulty=$difficulty seed=$seed", game.state.value!!.solved)
                assertEquals(1, game.puzzlesSolved.value)
            }
        }
    }

    /**
     * A hand-built, hand-verified 2x2 puzzle where [tile00]/[tile01]/[tile10]/[tile11],
     * ALL at rotation 0 in that row-major order, form a genuinely solved arrangement:
     * (0,0).right==(0,1).left, (0,0).bottom==(1,0).top, (0,1).bottom==(1,1).top,
     * (1,0).right==(1,1).left. Every test below starts [tile00] at some OTHER rotation
     * (breaking the board) and drives it back through the real public API.
     */
    private val tile00 = EdgeMatchTile(canonicalEdges = listOf(0, 1, 2, 0)) // top=0(border) right=1 bottom=2 left=0(border)
    private val tile01 = EdgeMatchTile(canonicalEdges = listOf(0, 0, 3, 1)) // top=0(border) right=0(border) bottom=3 left=1
    private val tile10 = EdgeMatchTile(canonicalEdges = listOf(2, 4, 0, 0)) // top=2 right=4 bottom=0(border) left=0(border)
    private val tile11 = EdgeMatchTile(canonicalEdges = listOf(3, 0, 0, 4)) // top=3 right=0(border) bottom=0(border) left=4

    private fun newHandBuiltGame(tile00Rotation: Int): EdgeMatchGame {
        var fakeClock = 0L
        val game = EdgeMatchGame(nowMillis = { fakeClock++ })
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.startMatch() // establishes context/matchOver=false; state overwritten below
        game.state.value = EdgeMatchState(
            size = 2,
            colorCount = 5,
            tiles = listOf(tile00.copy(rotation = tile00Rotation), tile01, tile10, tile11)
        )
        return game
    }

    @Test
    fun `tapping a tile rotates it 90 degrees clockwise and always counts as a move, even before it solves anything`() {
        val game = newHandBuiltGame(tile00Rotation = 1) // 3 taps away from solved
        assertFalse(game.state.value!!.solved)

        game.tapTile(0)
        val s = game.state.value!!
        assertEquals(2, s.tiles[0].rotation)
        assertEquals(1, s.moves)
        assertFalse("one tap into a 3-tap fix should not solve the board yet", s.solved)
        assertTrue(game.timerStartElapsedRealtime.value != null)
    }

    @Test
    fun `rotating a mis-oriented tile through every step eventually solves the puzzle`() {
        val game = newHandBuiltGame(tile00Rotation = 1) // rotation sequence: 1 -> 2 -> 3 -> 0 (solved)
        game.tapTile(0)
        assertEquals(2, game.state.value!!.tiles[0].rotation)
        assertFalse(game.state.value!!.solved)

        game.tapTile(0)
        assertEquals(3, game.state.value!!.tiles[0].rotation)
        assertFalse(game.state.value!!.solved)

        game.tapTile(0) // back to rotation 0 -- the known-good orientation
        val s = game.state.value!!
        assertEquals(0, s.tiles[0].rotation)
        assertTrue("rotating tile00 back to its correct orientation should solve the board", s.solved)
        assertEquals(3, s.moves)
        assertEquals(1, game.puzzlesSolved.value)
        assertTrue(game.solvedElapsedMillis.value != null)

        // Once solved, further taps are rejected -- same guard every other engine's tapTile()/press() has.
        val stateAfterSolve = game.state.value
        game.tapTile(0)
        assertEquals("a tap after the board is already solved must be a total no-op", stateAfterSolve, game.state.value)
    }

    @Test
    fun `matchingDirections reports only sides that currently match a neighbor, and updates live`() {
        val game = newHandBuiltGame(tile00Rotation = 3) // mismatched on both its interior sides (right, bottom)
        assertEquals(
            "tile00 is a corner (no top/left neighbor); mismatched right+bottom means neither should report as matching",
            emptySet<Int>(),
            game.matchingDirections(0)
        )

        game.tapTile(0) // rotation 3 -> 0, solves the board (a single tap suffices from rotation 3)
        assertTrue(game.state.value!!.solved)
        assertEquals(
            "once solved, both of tile00's interior sides (right, bottom) should report matching",
            setOf(EdgeMatchGame.RIGHT, EdgeMatchGame.BOTTOM),
            game.matchingDirections(0)
        )
    }

    @Test
    fun `tapping an out-of-range index is ignored, not a crash`() {
        val game = newHandBuiltGame(tile00Rotation = 1)
        val before = game.state.value
        game.tapTile(-1)
        game.tapTile(999)
        assertEquals(before, game.state.value)
    }

    @Test
    fun `resetToInitial restores the original scramble and clears moves and the stopwatch`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 7L)
        val initial = game.state.value!!.tiles
        game.tapTile(0) // a real rotation -- moves the board away from `initial`
        assertTrue(game.state.value!!.moves > 0)

        game.resetToInitial()
        val s = game.state.value!!
        assertEquals(initial, s.tiles)
        assertEquals(0, s.moves)
        assertFalse(s.solved)
        assertNull(game.timerStartElapsedRealtime.value)
        assertNull(game.solvedElapsedMillis.value)
    }

    @Test
    fun `pausing twice without an intervening resume does not lose the interval between the two pauses`() {
        var clock = 0L
        val game = EdgeMatchGame(nowMillis = { clock })
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        clock = 0L
        game.startMatch()
        game.state.value = EdgeMatchState(size = 2, colorCount = 5, tiles = listOf(tile00.copy(rotation = 2), tile01, tile10, tile11))

        clock = 0L
        game.tapTile(0) // rotation 2 -> 3 -- starts the timer at t=0, does not yet solve
        assertFalse(game.state.value!!.solved)

        clock = 10L
        game.pause() // pausedAt = 10
        clock = 100_000L
        game.pause() // must be a no-op -- pausedAt should STILL be 10, not overwritten to 100_000
        clock = 100_050L
        game.resume() // totalPausedMillis += 100_050 - 10 = 100_040 (not 100_050 - 100_000 = 50)

        clock = 100_060L
        game.tapTile(0) // rotation 3 -> 0 -- solves the board

        val recordedMillis = game.solvedElapsedMillis.value!!
        assertTrue(
            "recorded solve time was ${recordedMillis}ms -- the [10,100_000] interval between the two pause() calls must count as paused, not active",
            recordedMillis < 100L
        )
    }

    @Test
    fun `tapTile is rejected once the session has ended via leaveSession, even if the puzzle itself was not yet solved`() {
        val game = newHandBuiltGame(tile00Rotation = 1)
        game.leaveSession()
        assertTrue(game.matchOver.value)
        val stateAtLeave = game.state.value

        game.tapTile(0)
        assertEquals("a tapTile() after leaveSession() must be a total no-op", stateAtLeave, game.state.value)
    }

    @Test
    fun `matchOver resets on a new match, even after a prior endMatch -- playAgain and leaveSession never get permanently stuck`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch()
        game.endMatch(GameResult(scores = emptyList()))
        assertTrue(game.matchOver.value)

        game.startMatch()
        assertFalse("starting a new match must clear a stale matchOver flag", game.matchOver.value)

        game.leaveSession()
        assertTrue("leaveSession() after a fresh startMatch() must actually end the match", game.matchOver.value)
    }

    // -------------------------------------------------------------------
    // Custom Game Builder (docs/EDGE_MATCH_CUSTOM_BUILDER_DESIGN.md) --
    // deliberately reuses every generation-correctness check above (a fresh
    // puzzle is never already-solved, rotation-0 is always a valid solution)
    // against custom sizes/color counts too, since the whole point of this
    // phase is that it's the SAME generator, just parameterized directly.
    // -------------------------------------------------------------------

    @Test
    fun `startCustomMatch uses the exact size and colorCount requested, within bounds`() {
        for ((size, colorCount) in listOf(3 to 3, 5 to 7, EdgeMatchGame.MIN_SIZE to EdgeMatchGame.MIN_COLORS, EdgeMatchGame.MAX_SIZE to EdgeMatchGame.MAX_COLORS)) {
            val game = newGame()
            game.startCustomMatch(size, colorCount)
            val s = game.state.value!!
            assertEquals("size=$size colors=$colorCount", size, s.size)
            assertEquals("size=$size colors=$colorCount", colorCount, s.colorCount)
            assertEquals(size * size, s.tiles.size)
            for (tile in s.tiles) for (edge in tile.canonicalEdges) {
                assertTrue("size=$size colors=$colorCount: every edge color must be in 0 until $colorCount", edge in 0 until colorCount)
            }
        }
    }

    @Test
    fun `startCustomMatch clamps out-of-range size and colorCount instead of crashing`() {
        val tooSmall = newGame()
        tooSmall.startCustomMatch(size = 0, colorCount = 0)
        assertEquals(EdgeMatchGame.MIN_SIZE, tooSmall.state.value!!.size)
        assertEquals(EdgeMatchGame.MIN_COLORS, tooSmall.state.value!!.colorCount)

        val tooBig = newGame()
        tooBig.startCustomMatch(size = 999, colorCount = 999)
        assertEquals(EdgeMatchGame.MAX_SIZE, tooBig.state.value!!.size)
        assertEquals(EdgeMatchGame.MAX_COLORS, tooBig.state.value!!.colorCount)
    }

    /** See the square-tier test's own KDoc above for why this checks [isSolvedIndependently] against the real tiles rather than `state.value!!.solved` (which is always `false` right after generation regardless of generator correctness). */
    @Test
    fun `a freshly generated custom puzzle is never already solved, at the size bounds`() {
        for (size in listOf(EdgeMatchGame.MIN_SIZE, EdgeMatchGame.MAX_SIZE)) {
            repeat(8) {
                val game = newGame()
                game.startCustomMatch(size, EdgeMatchGame.MIN_COLORS)
                val s = game.state.value!!
                assertFalse("size=$size", isSolvedIndependently(s.tiles, s.size))
            }
        }
    }

    /**
     * Regression test for a real, confirmed bug: at size=[EdgeMatchGame.MIN_SIZE]/
     * colorCount=[EdgeMatchGame.MIN_COLORS] -- only reachable via the Custom Builder,
     * below the old fixed EASY tier's own 4x4/4-color floor -- there is a real (~1/2048)
     * chance every one of the generator's random seam/border color draws happens to land
     * on the SAME single color. When that happens every tile ends up individually
     * monochrome, so EVERY possible rotation vector already satisfies the win check --
     * an unbounded "re-roll rotations against the same solved grid until it's not
     * already solved" loop can never find one and hangs forever (an ANR, since the real
     * call site runs on the UI thread). 5,000 trials gives strong (~91%) odds of
     * actually rolling that exact degenerate seam-color combination at least once during
     * this test; `@Test(timeout)` is what actually proves the fix holds -- if the
     * generator's fallback to regenerate genuinely fresh seam colors (not just fresh
     * rotations) were ever removed, this test would hang past its timeout and fail, not
     * just run slow.
     */
    @Test(timeout = 15_000)
    fun `generating many puzzles at the smallest possible custom bounds never hangs, even when seam colors could otherwise roll all-identical`() {
        repeat(5_000) { trial ->
            val game = newGame()
            game.startCustomMatch(EdgeMatchGame.MIN_SIZE, EdgeMatchGame.MIN_COLORS)
            val s = game.state.value!!
            assertEquals("trial=$trial", EdgeMatchGame.MIN_SIZE * EdgeMatchGame.MIN_SIZE, s.tiles.size)
            assertFalse("trial=$trial: a freshly generated puzzle must never already be solved", s.solved)
        }
    }

    @Test
    fun `resetting every tile's own rotation to 0 always solves a freshly generated custom puzzle`() {
        for (size in listOf(EdgeMatchGame.MIN_SIZE, 5, EdgeMatchGame.MAX_SIZE)) {
            repeat(5) { trial ->
                val game = newGame()
                game.startCustomMatch(size, colorCount = 6)
                val s = game.state.value!!
                assertTrue(
                    "size=$size trial=$trial: the generator's own solved arrangement does not actually satisfy every adjacency",
                    allTilesAtRotationZeroAreSolved(s.tiles, s.size)
                )
            }
        }
    }

    @Test
    fun `a daily seed makes a custom puzzle reproducible too`() {
        val gameA = newGame()
        gameA.startCustomMatch(size = 7, colorCount = 5, dailySeed = 99L)
        val gameB = newGame()
        gameB.startCustomMatch(size = 7, colorCount = 5, dailySeed = 99L)
        assertEquals(gameA.state.value!!.tiles, gameB.state.value!!.tiles)
    }

    @Test
    fun `statsKey is the tier name for a normal game and CUSTOM_SizexColors for a custom one`() {
        val tiered = newGame(CpuDifficulty.HARD)
        tiered.startMatch()
        assertEquals("HARD", tiered.statsKey())

        val custom = newGame()
        custom.startCustomMatch(size = 6, colorCount = 4)
        assertEquals("CUSTOM_6x4", custom.statsKey())
    }

    @Test
    fun `selectDifficultyTier clears customConfig so startMatch reverts to the fixed tier`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startCustomMatch(size = 9, colorCount = 8)
        assertEquals(9, game.state.value!!.size)
        assertTrue(game.customConfig != null)

        game.selectDifficultyTier(CpuDifficulty.HARD)
        assertNull("selectDifficultyTier must clear customConfig", game.customConfig)
        game.startMatch()
        assertEquals("startMatch after selectDifficultyTier must use HARD's own size, not the stale custom one", 8, game.state.value!!.size)
        assertEquals("HARD", game.statsKey())
    }

    // -------------------------------------------------------------------
    // Custom Game Builder Phase 2: HEX geometry
    // (docs/EDGE_MATCH_CUSTOM_BUILDER_DESIGN.md) -- same generation-
    // correctness checks as square above, plus a hand-built board proving
    // the axial neighbor TOPOLOGY itself is right (not just that whatever
    // pairs the engine happens to check are internally consistent).
    // -------------------------------------------------------------------

    @Test
    fun `startCustomMatch with HEX geometry uses the exact size and colorCount requested, with 6-edge tiles`() {
        for ((size, colorCount) in listOf(EdgeMatchGame.MIN_SIZE to EdgeMatchGame.MIN_COLORS, 5 to 6, EdgeMatchGame.MAX_SIZE to EdgeMatchGame.MAX_COLORS)) {
            val game = newGame()
            game.startCustomMatch(size, colorCount, geometry = EdgeMatchGeometry.HEX)
            val s = game.state.value!!
            assertEquals("size=$size colors=$colorCount", EdgeMatchGeometry.HEX, s.geometry)
            assertEquals(size, s.size)
            assertEquals(colorCount, s.colorCount)
            assertEquals(size * size, s.tiles.size)
            for (tile in s.tiles) {
                assertEquals("every hex tile must have exactly 6 edges", 6, tile.canonicalEdges.size)
                for (edge in tile.canonicalEdges) {
                    assertTrue("size=$size colors=$colorCount: every edge color must be in 0 until $colorCount", edge in 0 until colorCount)
                }
            }
        }
    }

    /** See the square-tier "never already solved" test's own KDoc for why this checks [isHexSolvedIndependently] against the real tiles rather than `state.value!!.solved`. */
    @Test
    fun `a freshly generated hex puzzle is never already solved, at the size bounds`() {
        for (size in listOf(EdgeMatchGame.MIN_SIZE, 5, EdgeMatchGame.MAX_SIZE)) {
            repeat(8) {
                val game = newGame()
                game.startCustomMatch(size, EdgeMatchGame.MIN_COLORS, geometry = EdgeMatchGeometry.HEX)
                val s = game.state.value!!
                assertFalse("size=$size", isHexSolvedIndependently(s.tiles, s.size))
            }
        }
    }

    /**
     * Hex's own equivalent of the square-board generation-hang regression test above --
     * `generatePuzzle` is geometry-generic (the SAME two-level reject-and-reroll loop, the SAME
     * [MAX_ROTATION_REROLLS]/[MAX_GENERATION_ATTEMPTS] bounds, dispatching to
     * `generateSolvedHexGrid` instead of `generateSolvedGrid` only for the initial seam
     * assignment), so the identical degenerate-all-monochrome risk at the smallest bounds applies
     * here too -- but hex has its own genuinely different seam-assignment algorithm, so this is
     * a real, separate regression test, not redundant with the square one. Same reasoning as that
     * test's own KDoc: 5,000 trials for strong odds of actually rolling the degenerate case, with
     * `@Test(timeout)` as what actually proves the fallback still works.
     */
    @Test(timeout = 15_000)
    fun `generating many hex puzzles at the smallest possible custom bounds never hangs, even when seam colors could otherwise roll all-identical`() {
        repeat(5_000) { trial ->
            val game = newGame()
            game.startCustomMatch(EdgeMatchGame.MIN_SIZE, EdgeMatchGame.MIN_COLORS, geometry = EdgeMatchGeometry.HEX)
            val s = game.state.value!!
            assertEquals("trial=$trial", EdgeMatchGame.MIN_SIZE * EdgeMatchGame.MIN_SIZE, s.tiles.size)
            assertFalse("trial=$trial: a freshly generated hex puzzle must never already be solved", isHexSolvedIndependently(s.tiles, s.size))
        }
    }

    @Test
    fun `resetting every hex tile's own rotation to 0 always solves a freshly generated hex puzzle`() {
        for (size in listOf(EdgeMatchGame.MIN_SIZE, 5, EdgeMatchGame.MAX_SIZE)) {
            repeat(5) { trial ->
                val game = newGame()
                game.startCustomMatch(size, colorCount = 6, geometry = EdgeMatchGeometry.HEX)
                val s = game.state.value!!
                assertTrue(
                    "size=$size trial=$trial: the hex generator's own solved arrangement does not actually satisfy every adjacency",
                    allHexTilesAtRotationZeroAreSolved(s.tiles, s.size)
                )
            }
        }
    }

    @Test
    fun `rotating every scrambled hex tile back to its own original orientation solves a real generated puzzle through the public API`() {
        for (size in listOf(EdgeMatchGame.MIN_SIZE, 4, 7, EdgeMatchGame.MAX_SIZE)) {
            for (seed in 1L..5L) {
                val game = newGame()
                game.startCustomMatch(size, colorCount = 6, geometry = EdgeMatchGeometry.HEX, dailySeed = seed)
                rotateEveryTileBackToItsOwnZero(game)
                assertTrue("size=$size seed=$seed", game.state.value!!.solved)
                assertEquals("size=$size seed=$seed", 1, game.puzzlesSolved.value)
            }
        }
    }

    /** The test above pins `colorCount = 6` throughout; this drives the same real
     *  public-API solve at the color-count BOUNDS instead, so MIN_COLORS/MAX_COLORS aren't only
     *  ever checked by the shallow "edge value is in range" structural test. */
    @Test
    fun `rotating every scrambled hex tile back to its own orientation solves a real generated puzzle at the color-count bounds too`() {
        for (colorCount in listOf(EdgeMatchGame.MIN_COLORS, EdgeMatchGame.MAX_COLORS)) {
            for (seed in 1L..5L) {
                val game = newGame()
                game.startCustomMatch(size = 5, colorCount = colorCount, geometry = EdgeMatchGeometry.HEX, dailySeed = seed)
                rotateEveryTileBackToItsOwnZero(game)
                assertTrue("colorCount=$colorCount seed=$seed", game.state.value!!.solved)
                assertEquals("colorCount=$colorCount seed=$seed", 1, game.puzzlesSolved.value)
            }
        }
    }

    @Test
    fun `a daily seed makes a hex custom puzzle reproducible too`() {
        val gameA = newGame()
        gameA.startCustomMatch(size = 6, colorCount = 5, geometry = EdgeMatchGeometry.HEX, dailySeed = 77L)
        val gameB = newGame()
        gameB.startCustomMatch(size = 6, colorCount = 5, geometry = EdgeMatchGeometry.HEX, dailySeed = 77L)
        assertEquals(gameA.state.value!!.tiles, gameB.state.value!!.tiles)
    }

    @Test
    fun `statsKey uses the CUSTOM_HEX prefix for a hex custom game, distinct from square's CUSTOM prefix`() {
        val hex = newGame()
        hex.startCustomMatch(size = 6, colorCount = 4, geometry = EdgeMatchGeometry.HEX)
        assertEquals("CUSTOM_HEX_6x4", hex.statsKey())

        val square = newGame()
        square.startCustomMatch(size = 6, colorCount = 4)
        assertEquals("a square and a hex custom game at the same size/colors must NOT share a stats key", "CUSTOM_6x4", square.statsKey())
    }

    /**
     * A hand-built 2x2 (q,r in 0..1) hex board, fully solved by construction, with every
     * shared value chosen BY HAND from independently-worked-out geometry (see the
     * comment above each tile) -- not by reusing the engine's own delta-array algorithm,
     * so this actually exercises whether the real axial neighbor TOPOLOGY is right, not
     * just whether the engine's own comparisons are internally consistent with each other.
     * Real neighbor pairs (worked out by hand): (0,1) via E/W, (0,2) via SE/NW,
     * (1,2) via SW/NE, (1,3) via SE/NW, (2,3) via E/W -- 5 edges total, matching the
     * combinatorial count for a 2x2 rhombus (2 E-links + 1 NE-link + 2 NW-links).
     */
    private val hexTile0 = EdgeMatchTile(canonicalEdges = listOf(10, 90, 91, 92, 93, 11)) // E=10 NE=90(border) NW=91(border) W=92(border) SW=93(border) SE=11
    private val hexTile1 = EdgeMatchTile(canonicalEdges = listOf(94, 95, 96, 10, 12, 13)) // E=94(border) NE=95(border) NW=96(border) W=10 SW=12 SE=13
    private val hexTile2 = EdgeMatchTile(canonicalEdges = listOf(14, 12, 11, 97, 98, 99)) // E=14 NE=12 NW=11 W=97(border) SW=98(border) SE=99(border)
    private val hexTile3 = EdgeMatchTile(canonicalEdges = listOf(100, 101, 13, 14, 102, 103)) // E=100(border) NE=101(border) NW=13 W=14 SW=102(border) SE=103(border)

    /**
     * Switching geometry on the SAME game instance (hex -> square -> hex again) must never leave
     * stale per-tile state from the previous geometry -- e.g. a leftover 6-edge tile or a rotation
     * value in 4..5 (valid for hex, invalid for a 4-edge square tile) surviving into a square
     * board. `startCustomMatch`/`startMatch` always rebuild `tiles` entirely from scratch via
     * `generatePuzzle`, so this is expected to be safe by construction -- but nothing in the
     * suite previously exercised the actual switch to prove it, only single-geometry sessions.
     */
    @Test
    fun `switching geometry on the same game instance never leaves stale tiles from the previous geometry`() {
        val game = newGame()

        game.startCustomMatch(size = 5, colorCount = 6, geometry = EdgeMatchGeometry.HEX)
        val hexState = game.state.value!!
        assertEquals(EdgeMatchGeometry.HEX, hexState.geometry)
        for (tile in hexState.tiles) {
            assertEquals("a hex tile must have exactly 6 edges", 6, tile.canonicalEdges.size)
            assertTrue("a hex tile's rotation must be in 0..5", tile.rotation in 0..5)
        }

        game.startCustomMatch(size = 5, colorCount = 6, geometry = EdgeMatchGeometry.SQUARE)
        val squareState = game.state.value!!
        assertEquals(EdgeMatchGeometry.SQUARE, squareState.geometry)
        for (tile in squareState.tiles) {
            assertEquals("a square tile must have exactly 4 edges, not a leftover hex one", 4, tile.canonicalEdges.size)
            assertTrue("a square tile's rotation must be in 0..3, not a leftover hex value (4 or 5)", tile.rotation in 0..3)
        }

        // And back to hex again, to prove this isn't just "the first switch happens to be clean".
        game.startCustomMatch(size = 5, colorCount = 6, geometry = EdgeMatchGeometry.HEX)
        val hexAgainState = game.state.value!!
        assertEquals(EdgeMatchGeometry.HEX, hexAgainState.geometry)
        for (tile in hexAgainState.tiles) {
            assertEquals(6, tile.canonicalEdges.size)
            assertTrue(tile.rotation in 0..5)
        }
    }

    @Test
    fun `matchingDirections on a hand-built hex board reports exactly the geometrically-real neighbor directions`() {
        val game = newGame()
        game.startCustomMatch(size = 2, colorCount = 20, geometry = EdgeMatchGeometry.HEX)
        val s = game.state.value!!
        game.state.value = s.copy(tiles = listOf(hexTile0, hexTile1, hexTile2, hexTile3))

        assertEquals("tile0 (corner): only E and SE are real neighbors", setOf(EdgeMatchGame.E, EdgeMatchGame.SE), game.matchingDirections(0))
        assertEquals("tile1: W, SW, and SE are real neighbors", setOf(EdgeMatchGame.W, EdgeMatchGame.SW, EdgeMatchGame.SE), game.matchingDirections(1))
        assertEquals("tile2: E, NE, and NW are real neighbors", setOf(EdgeMatchGame.E, EdgeMatchGame.NE, EdgeMatchGame.NW), game.matchingDirections(2))
        assertEquals("tile3 (corner): only NW and W are real neighbors", setOf(EdgeMatchGame.NW, EdgeMatchGame.W), game.matchingDirections(3))
    }
}
