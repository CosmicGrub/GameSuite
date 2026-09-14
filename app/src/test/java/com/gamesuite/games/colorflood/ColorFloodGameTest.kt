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
}
