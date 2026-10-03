package com.gamesuite.games.colorflood

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ColorFloodGame]'s flood-fill is private, exactly like MinesweeperGame's
 * own flood-reveal — every board comes through the real public entry point
 * (`difficulty` + `startMatch(dailySeed)` + `pick(colorIndex)`). Territory
 * connectivity is re-checked independently in this file (see
 * [territoryIsAConnectedSameColorRegion]) rather than trusting the engine's
 * own bookkeeping.
 */
class ColorFloodGameTest {

    private fun newGame(difficulty: CpuDifficulty = CpuDifficulty.MEDIUM): ColorFloodGame {
        var fakeClock = 0L
        val game = ColorFloodGame(nowMillis = { fakeClock++ })
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

    /** Independent re-derivation of orthogonal neighbors -- deliberately not calling anything in ColorFloodGame. */
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

    /** True iff [territory] is exactly the connected component of same-colored cells reachable from index 0 -- computed fresh via an independent BFS, not trusting the engine's own. */
    private fun territoryIsAConnectedSameColorRegion(cellColors: List<Int>, territory: Set<Int>, size: Int): Boolean {
        val targetColor = cellColors[0]
        val expected = mutableSetOf<Int>()
        val queue = ArrayDeque<Int>()
        queue.add(0)
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            if (i in expected) continue
            if (cellColors[i] != targetColor) continue
            expected.add(i)
            for (n in neighborsOf(i, size)) if (n !in expected) queue.add(n)
        }
        return expected == territory
    }

    @Test
    fun `difficulty controls board size and color count -- EASY 9x9x4, MEDIUM 12x12x5, HARD 16x16x6`() {
        val expected = mapOf(
            CpuDifficulty.EASY to (9 to 4),
            CpuDifficulty.MEDIUM to (12 to 5),
            CpuDifficulty.HARD to (16 to 6)
        )
        for ((difficulty, sizeAndColors) in expected) {
            val (size, colors) = sizeAndColors
            val game = newGame(difficulty)
            game.startMatch(dailySeed = 1L)
            val s = game.state.value!!
            assertEquals("difficulty=$difficulty", size, s.size)
            assertEquals(size * size, s.cellColors.size)
            assertEquals(colors, s.colorCount)
        }
    }

    @Test
    fun `every cell color is within range, across every difficulty and many seeds`() {
        for (difficulty in CpuDifficulty.entries) {
            for (seed in 1L..20L) {
                val game = newGame(difficulty)
                game.startMatch(dailySeed = seed)
                val s = game.state.value!!
                assertTrue(
                    "difficulty=$difficulty seed=$seed: every cell color must be in 0 until colorCount",
                    s.cellColors.all { it in 0 until s.colorCount }
                )
            }
        }
    }

    @Test
    fun `the initial territory is exactly the connected same-color region from the origin`() {
        for (difficulty in CpuDifficulty.entries) {
            for (seed in 1L..10L) {
                val game = newGame(difficulty)
                game.startMatch(dailySeed = seed)
                val s = game.state.value!!
                assertTrue(
                    "difficulty=$difficulty seed=$seed",
                    territoryIsAConnectedSameColorRegion(s.cellColors, s.territory, s.size)
                )
            }
        }
    }

    @Test
    fun `picking a color repaints the territory, re-floods correctly, and increments moves`() {
        for (seed in 1L..15L) {
            val game = newGame(CpuDifficulty.EASY)
            game.startMatch(dailySeed = seed)
            val before = game.state.value!!
            val pickColor = (0 until before.colorCount).first { it != before.currentColor }

            game.pick(pickColor)
            val after = game.state.value!!

            assertEquals(before.moves + 1, after.moves)
            assertEquals(pickColor, after.currentColor)
            assertTrue(
                "seed=$seed: the new territory must be exactly the connected same-color region from the origin",
                territoryIsAConnectedSameColorRegion(after.cellColors, after.territory, after.size)
            )
            assertTrue("the territory can never shrink from a pick", after.territory.size >= before.territory.size)
            assertTrue("every cell outside the OLD territory must keep its original color", before.cellColors.indices.all { i -> i in before.territory || before.cellColors[i] == after.cellColors[i] })
        }
    }

    @Test
    fun `picking the current color is a no-op`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        val before = game.state.value!!
        game.pick(before.currentColor)
        assertEquals(before, game.state.value)
    }

    @Test
    fun `picking an out-of-range color is ignored, not a crash`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        val before = game.state.value!!
        game.pick(-1)
        assertEquals(before, game.state.value)
        game.pick(before.colorCount)
        assertEquals(before, game.state.value)
    }

    /**
     * A color guaranteed to strictly grow [s]'s territory: any cell just
     * outside the territory's own color. While the territory isn't the
     * whole (connected) board, some territory cell must have a neighbor
     * outside it -- picking that neighbor's color absorbs it (at minimum)
     * on the very next [ColorFloodGame.pick] call. Used to deterministically
     * drive a real board to completion in tests, instead of a strategy that
     * could stall by repeatedly picking colors that touch nothing new.
     */
    private fun colorThatGrowsTerritory(s: ColorFloodState): Int {
        for (i in s.territory) {
            for (n in neighborsOf(i, s.size)) {
                if (n !in s.territory) return s.cellColors[n]
            }
        }
        error("territory isn't the whole board but has no boundary neighbor -- shouldn't happen on a connected grid")
    }

    private fun solveByAlwaysGrowingTerritory(game: ColorFloodGame, guardLimit: Int = 2000) {
        var guard = 0
        while (game.state.value?.won != true) {
            val s = game.state.value!!
            val before = s.territory.size
            game.pick(colorThatGrowsTerritory(s))
            assertTrue("territory must strictly grow on every picked move here", game.state.value!!.territory.size > before)
            guard++
            assertTrue("board did not flood within a sane number of moves", guard < guardLimit)
        }
    }

    @Test
    fun `repeatedly picking a color that grows the territory eventually wins the board`() {
        for (difficulty in CpuDifficulty.entries) {
            val game = newGame(difficulty)
            game.startMatch(dailySeed = 3L)
            solveByAlwaysGrowingTerritory(game)
            assertTrue(game.state.value!!.won)
            assertEquals(1, game.puzzlesSolved.value)
        }
    }

    @Test
    fun `winning freezes the timer`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        solveByAlwaysGrowingTerritory(game)
        assertNotNull(game.finishedElapsedMillis.value)
    }

    @Test
    fun `pausing mid-puzzle does not inflate the recorded solve time`() {
        var clock = 0L
        val game = ColorFloodGame(nowMillis = { clock })
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
        game.pick(colorThatGrowsTerritory(s0)) // starts the timer

        clock = 10L
        game.pause()
        clock = 600_010L // ~10 real minutes pass while backgrounded
        game.resume()
        clock = 600_020L

        solveByAlwaysGrowingTerritory(game)

        val recordedMillis = game.finishedElapsedMillis.value!!
        assertTrue(
            "recorded solve time was ${recordedMillis}ms -- should reflect only real active play, not the ~10 minutes spent paused",
            recordedMillis < 1000L
        )
    }

    @Test
    fun `pick is rejected once the session has ended via leaveSession, even if the board itself was not yet won`() {
        // Found by adversarial review: pick()'s only liveness check was
        // `s.isOver` (per-board), never `matchOver` (session-level), so a
        // pick() after leaveSession() had already delivered the final
        // GameResult could still mutate state -- including a phantom win
        // with no second result ever reported.
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        val s0 = game.state.value!!
        game.pick((0 until s0.colorCount).first { it != s0.currentColor })
        assertFalse(game.state.value!!.won)

        game.leaveSession()
        assertTrue(game.matchOver.value)
        val stateAtLeave = game.state.value

        val s1 = game.state.value!!
        game.pick((0 until s1.colorCount).first { it != s1.currentColor })
        assertEquals("a pick() after leaveSession() must be a total no-op", stateAtLeave, game.state.value)
    }

    @Test
    fun `pausing twice without an intervening resume does not lose the interval between the two pauses`() {
        // Found by adversarial review: pause() overwrote its anchor on a
        // second call with no resume() in between (unlike resume(), which
        // was already correctly idempotent), silently dropping the
        // interval between the two pause() calls from totalPausedMillis
        // and counting it as active play instead.
        var clock = 0L
        val game = ColorFloodGame(nowMillis = { clock })
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
        clock = 100L
        game.pick(colorThatGrowsTerritory(s0)) // starts the timer at t=100

        clock = 110L
        game.pause() // pausedAt = 110

        clock = 200L
        game.pause() // must be a no-op -- pausedAt should STILL be 110, not overwritten to 200

        clock = 250L
        game.resume() // totalPausedMillis += 250 - 110 = 140 (not 250 - 200 = 50)

        clock = 300L
        solveByAlwaysGrowingTerritory(game)

        val recordedMillis = game.finishedElapsedMillis.value!!
        assertTrue(
            "recorded solve time was ${recordedMillis}ms -- the [110,200] interval between the two pause() calls must count as paused, not active",
            recordedMillis < 100L
        )
    }

    @Test
    fun `matchOver resets on a new match, even after a prior endMatch -- playAgain and leaveSession never get permanently stuck`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        game.endMatch(GameResult(scores = emptyList()))
        assertTrue(game.matchOver.value)

        game.startMatch(dailySeed = 2L)
        assertFalse("starting a new match must clear a stale matchOver flag", game.matchOver.value)

        game.leaveSession()
        assertTrue("leaveSession() after a fresh startMatch() must actually end the match", game.matchOver.value)
    }

    @Test
    fun `a daily seed makes the board reproducible`() {
        val gameA = newGame(CpuDifficulty.MEDIUM)
        gameA.startMatch(dailySeed = 42L)
        val gameB = newGame(CpuDifficulty.MEDIUM)
        gameB.startMatch(dailySeed = 42L)
        assertEquals(gameA.state.value!!.cellColors, gameB.state.value!!.cellColors)
    }

    @Test
    fun `absorbCount predicts exactly how much a pick grows the territory, and never mutates state`() {
        for (difficulty in CpuDifficulty.entries) {
            for (seed in 1L..6L) {
                val probe = newGame(difficulty)
                probe.startMatch(dailySeed = seed)
                val before = probe.state.value!!
                val predicted = (0 until before.colorCount).map { probe.absorbCount(it) }
                assertEquals("absorbCount must not change the board", before, probe.state.value)

                for (color in 0 until before.colorCount) {
                    if (color == before.currentColor) {
                        assertEquals("difficulty=$difficulty seed=$seed: the current color adds nothing", 0, predicted[color])
                        continue
                    }
                    // A daily seed reproduces the board, so a second game is the same board to pick on.
                    val actual = newGame(difficulty)
                    actual.startMatch(dailySeed = seed)
                    actual.pick(color)
                    val grew = actual.state.value!!.territory.size - before.territory.size
                    assertEquals("difficulty=$difficulty seed=$seed color=$color", grew, predicted[color])
                }
            }
        }
    }

    /**
     * A hand-built 3x3 board (the randomized test above cannot guarantee it ever meets a color that
     * touches nothing, or a chain):
     *
     *     0 1 2
     *     3 1 1
     *     2 2 3
     *
     * The territory is just the corner. Color 1 chains through (0,1) into the three 1s (+3), color 3
     * takes only the 3 below the corner (+1), color 2 touches nothing (+0 but still a move), and
     * color 0 is the current color.
     */
    @Test
    fun `absorbCount on a hand-built board covers a chain, a single neighbor, a color that touches nothing, and the current color`() {
        val colors = listOf(
            0, 1, 2,
            3, 1, 1,
            2, 2, 3
        )
        fun boardGame(): ColorFloodGame {
            val game = newGame(CpuDifficulty.EASY)
            game.startMatch(dailySeed = 1L)
            game.state.value = ColorFloodState(size = 3, colorCount = 4, cellColors = colors, territory = setOf(0))
            return game
        }

        val probe = boardGame()
        assertEquals("the current color adds nothing", 0, probe.absorbCount(0))
        assertEquals("color 1 chains through the corner's neighbor into the three 1s", 3, probe.absorbCount(1))
        assertEquals("color 2 touches nothing", 0, probe.absorbCount(2))
        assertEquals("color 3 takes only the cell below the corner", 1, probe.absorbCount(3))

        for (color in 1..3) {
            val actual = boardGame()
            actual.pick(color)
            val after = actual.state.value!!
            assertEquals("color=$color: one move, even when it absorbs nothing", 1, after.moves)
            assertEquals(
                "color=$color: pick grows the territory by exactly what absorbCount predicted",
                probe.absorbCount(color),
                after.territory.size - 1
            )
        }
    }

    @Test
    fun `absorbCount is zero for an out-of-range color, a won board, and an ended session`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        val s0 = game.state.value!!
        assertEquals(0, game.absorbCount(-1))
        assertEquals(0, game.absorbCount(s0.colorCount))

        solveByAlwaysGrowingTerritory(game)
        assertTrue(game.state.value!!.won)
        for (color in 0 until s0.colorCount) {
            assertEquals("a won board has nothing left to absorb", 0, game.absorbCount(color))
        }

        game.startMatch(dailySeed = 2L)
        val s1 = game.state.value!!
        game.leaveSession()
        for (color in 0 until s1.colorCount) {
            assertEquals("an ended session absorbs nothing", 0, game.absorbCount(color))
        }
    }

    @Test
    fun `activeElapsedMillis is null before the first pick, freezes while paused, and ends equal to the finished time`() {
        var clock = 0L
        val game = ColorFloodGame(nowMillis = { clock })
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
        assertNull("no stopwatch before the first pick", game.activeElapsedMillis())

        clock = 1_000L
        game.pick(colorThatGrowsTerritory(game.state.value!!)) // starts the timer at t=1000
        assertFalse(game.state.value!!.won)
        assertEquals(0L, game.activeElapsedMillis())

        clock = 3_000L
        assertEquals(2_000L, game.activeElapsedMillis())

        game.pause() // pausedAt = 3000
        clock = 10_000L
        assertEquals("the live clock must not tick while paused", 2_000L, game.activeElapsedMillis())

        game.resume() // 7000ms spent paused
        clock = 11_000L
        assertEquals(3_000L, game.activeElapsedMillis()) // 11000 - 1000 - 7000

        solveByAlwaysGrowingTerritory(game)
        assertEquals(3_000L, game.finishedElapsedMillis.value)
        assertEquals(
            "once won, the live clock reads exactly the recorded solve time",
            game.finishedElapsedMillis.value,
            game.activeElapsedMillis()
        )
    }
}
