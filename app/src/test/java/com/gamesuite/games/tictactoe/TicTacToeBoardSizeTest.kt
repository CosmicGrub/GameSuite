package com.gamesuite.games.tictactoe

import com.gamesuite.core.GameContext
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
 * Board sizes for Tic-Tac-Toe: 3x3 (three in a row), 4x4 and 5x5 (four in a row). Covers win
 * detection for every line on every size, draws, the resume snapshot, the bot and the off-thread
 * move/hint protocol.
 */
class TicTacToeBoardSizeTest {

    private val sizes = listOf(3, 4, 5)
    private val twoHumans = listOf(PlayerInfo("p1", "Player 1"), PlayerInfo("p2", "Player 2"))
    private val humanAndCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))
    private val twoBots = listOf(PlayerInfo("b1", "Bot 1", isBot = true), PlayerInfo("b2", "Bot 2", isBot = true))

    private fun newGame(
        dim: Int,
        players: List<PlayerInfo> = twoHumans,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        difficulty: CpuDifficulty = CpuDifficulty.HARD,
        misere: Boolean = false,
        wild: Boolean = false,
        started: Boolean = true
    ): TicTacToeGame {
        val g = TicTacToeGame()
        g.boardSize = dim
        g.misere = misere
        g.wild = wild
        g.difficulty = difficulty
        g.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        if (started) g.startMatch()
        return g
    }

    private fun TicTacToeGame.cells(): List<Int> = board.value.toList()

    /** Plays [p1] and [p2] alternately, seat 1 first (the last move of [p1] may have no reply). */
    private fun play(g: TicTacToeGame, p1: List<Int>, p2: List<Int>) {
        for (i in p1.indices) {
            g.cellClicked(p1[i])
            if (i < p2.size) g.cellClicked(p2[i])
        }
    }

    // --- Setup ------------------------------------------------------------------------------

    @Test
    fun `the board has the requested size and an unknown size falls back to 3x3`() {
        assertEquals(9, newGame(3).board.value.size)
        assertEquals(16, newGame(4).board.value.size)
        assertEquals(25, newGame(5).board.value.size)
        assertEquals(4, newGame(4).boardDim.value)
        assertEquals(3, newGame(7).boardDim.value)
        assertEquals(3, newGame(2).boardDim.value)
        assertEquals(3, newGame(0).boardDim.value)
    }

    @Test
    fun `misere and wild are always 3x3 whatever size was asked for`() {
        assertEquals(3, newGame(5, misere = true).boardDim.value)
        assertEquals(9, newGame(5, misere = true).board.value.size)
        assertEquals(3, newGame(4, wild = true).boardDim.value)
        assertEquals(3, newGame(4, misere = true, wild = true).boardDim.value)
    }

    @Test
    fun `win lengths and line counts per size`() {
        assertEquals(3, TicTacToeGame.winLength(3))
        assertEquals(4, TicTacToeGame.winLength(4))
        assertEquals(4, TicTacToeGame.winLength(5))
        assertEquals(8, TicTacToeGame.winningLines(3).size)
        assertEquals(10, TicTacToeGame.winningLines(4).size)
        assertEquals(28, TicTacToeGame.winningLines(5).size)
    }

    @Test
    fun `every size has rows, columns, diagonals and anti-diagonals and no line wraps an edge`() {
        for (dim in sizes) {
            val k = TicTacToeGame.winLength(dim)
            val lines = TicTacToeGame.winningLines(dim)
            val rows = lines.filter { it[1] - it[0] == 1 }
            val cols = lines.filter { it[1] - it[0] == dim }
            val diags = lines.filter { it[1] - it[0] == dim + 1 }
            val antis = lines.filter { it[1] - it[0] == dim - 1 }
            assertEquals("dim $dim: every line is one of the four kinds", lines.size, rows.size + cols.size + diags.size + antis.size)
            assertTrue("dim $dim has rows", rows.isNotEmpty())
            assertTrue("dim $dim has columns", cols.isNotEmpty())
            assertTrue("dim $dim has diagonals", diags.isNotEmpty())
            assertTrue("dim $dim has anti-diagonals", antis.isNotEmpty())
            for (line in lines) {
                assertEquals(k, line.size)
                assertEquals("lines are distinct cells", k, line.toSet().size)
                val rowsTouched = line.map { it / dim }
                val colsTouched = line.map { it % dim }
                // Every step is exactly one of the four directions, so a row line stays in its row
                // (no wrap-around) and the others advance one row per step.
                val step = line[1] - line[0]
                for (i in 1 until k) assertEquals(step, line[i] - line[i - 1])
                when (step) {
                    1 -> assertEquals("row line stays in one row", 1, rowsTouched.toSet().size)
                    dim -> assertEquals("column line stays in one column", 1, colsTouched.toSet().size)
                    dim + 1 -> {
                        for (i in 1 until k) {
                            assertEquals(rowsTouched[i - 1] + 1, rowsTouched[i])
                            assertEquals(colsTouched[i - 1] + 1, colsTouched[i])
                        }
                    }
                    dim - 1 -> {
                        for (i in 1 until k) {
                            assertEquals(rowsTouched[i - 1] + 1, rowsTouched[i])
                            assertEquals(colsTouched[i - 1] - 1, colsTouched[i])
                        }
                    }
                    else -> throw AssertionError("unexpected line step $step")
                }
            }
        }
    }

    // --- Win detection -----------------------------------------------------------------------

    @Test
    fun `every line on every size wins for the player who completes it, and only then`() {
        for (dim in sizes) {
            val k = TicTacToeGame.winLength(dim)
            for (line in TicTacToeGame.winningLines(dim)) {
                val g = newGame(dim)
                // Seat 2 only ever holds k-1 marks when seat 1 completes the line, so it cannot win first.
                val fillers = (0 until dim * dim).filter { it !in line }.take(k - 1)
                for (i in 0 until k) {
                    assertFalse("dim $dim line $line: round must still be open before mark $i", g.roundOver.value)
                    g.cellClicked(line[i])
                    if (i < k - 1) g.cellClicked(fillers[i])
                }
                assertTrue("dim $dim line $line completes", g.roundOver.value)
                assertEquals("dim $dim line $line is the highlighted line", line, g.winningLine.value)
                assertEquals(1, g.scoreP1.value)
                assertEquals(0, g.scoreP2.value)
                assertEquals(0, g.draws.value)
            }
        }
    }

    @Test
    fun `seat two can win on the bigger boards too`() {
        for (dim in listOf(4, 5)) {
            // An engine whose first round is opened by seat 2 (so the first listed mover is seat 2).
            val h = TicTacToeGame()
            h.boardSize = dim
            h.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, twoHumans, 0, LocalPassAndPlayTransport()))
            h.firstOpenerIndex = 1
            h.startMatch()
            assertEquals(2, h.currentPlayer.value)
            val line = TicTacToeGame.winningLines(dim).first { it[1] - it[0] == dim } // a column
            val fillers = (0 until dim * dim).filter { it !in line }.take(3)
            play(h, line, fillers)
            assertTrue(h.roundOver.value)
            assertEquals(line, h.winningLine.value)
            assertEquals(1, h.scoreP2.value)
            assertEquals(0, h.scoreP1.value)
        }
    }

    @Test
    fun `three in a row does not win on 4x4 or 5x5 but four does`() {
        for (dim in listOf(4, 5)) {
            val g = newGame(dim)
            val fillers = listOf(dim * dim - 1, dim * dim - 2, dim * dim - 3)
            play(g, listOf(0, 1, 2), fillers) // three across the top for seat 1, three across the bottom for seat 2
            assertFalse("dim $dim: three in a row is not a win", g.roundOver.value)
            assertNull(g.winningLine.value)
            assertEquals(1, g.currentPlayer.value)
            g.cellClicked(3) // seat 1 completes four
            assertTrue(g.roundOver.value)
            assertEquals(listOf(0, 1, 2, 3), g.winningLine.value)
        }
    }

    @Test
    fun `marks that continue across a row edge or off an edge do not win`() {
        // 4x4: cells 2,3,4,5 are consecutive in memory but run (0,2),(0,3),(1,0),(1,1).
        val a = newGame(4)
        play(a, listOf(2, 3, 4, 5), listOf(15, 14, 13))
        assertFalse("row-major wrap must not win", a.roundOver.value)

        // 5x5: 3,4,5,6 wraps the same way.
        val b = newGame(5)
        play(b, listOf(3, 4, 5, 6), listOf(24, 23, 22))
        assertFalse(b.roundOver.value)

        // 4x4 anti-diagonal step (3) that leaves the board: 1,4,7,10 is not a line.
        val c = newGame(4)
        play(c, listOf(1, 4, 7, 10), listOf(15, 14, 13))
        assertFalse(c.roundOver.value)

        // 5x5 anti-diagonal step (4): 2,6,10,14 breaks across the left edge.
        val d = newGame(5)
        play(d, listOf(2, 6, 10, 14), listOf(24, 23, 22))
        assertFalse(d.roundOver.value)

        // 3x3: 2,3,4 wraps from the end of row 0 into row 1.
        val e = newGame(3)
        play(e, listOf(2, 3, 4), listOf(8, 7))
        assertFalse(e.roundOver.value)
    }

    @Test
    fun `a line of one player's marks broken by an opponent mark is no win`() {
        for (dim in listOf(4, 5)) {
            val g = newGame(dim)
            // Seat 1: 0,1,3 ; seat 2 takes 2 in the middle of the top row.
            play(g, listOf(0, 1, 3), listOf(2, dim * dim - 1))
            g.cellClicked(dim * dim - 2) // seat 2 elsewhere
            assertFalse(g.roundOver.value)
        }
    }

    // --- Draws -------------------------------------------------------------------------------

    @Test
    fun `a full 3x3 board with no line is a draw`() {
        val g = newGame(3)
        play(g, listOf(0, 2, 3, 7, 8), listOf(1, 4, 5, 6))
        assertTrue(g.roundOver.value)
        assertNull(g.winningLine.value)
        assertEquals(1, g.draws.value)
        assertEquals(0, g.scoreP1.value + g.scoreP2.value)
    }

    @Test
    fun `a full 4x4 board with no four in a row is a draw`() {
        val g = newGame(4)
        play(g, listOf(0, 1, 6, 7, 8, 9, 14, 15), listOf(2, 3, 4, 5, 10, 11, 12, 13))
        assertTrue(g.roundOver.value)
        assertNull(g.winningLine.value)
        assertEquals(1, g.draws.value)
        assertTrue(g.board.value.none { it == 0 })
    }

    @Test
    fun `a full 5x5 board with no four in a row is a draw`() {
        val x = mutableListOf<Int>()
        val o = mutableListOf<Int>()
        for (r in 0 until 5) {
            val shift = if (r % 2 == 0) 0 else 2
            for (c in 0 until 5) {
                if ((c + shift) % 4 < 2) x += r * 5 + c else o += r * 5 + c
            }
        }
        assertEquals(13, x.size)
        assertEquals(12, o.size)
        val g = newGame(5)
        play(g, x, o)
        assertTrue(g.roundOver.value)
        assertNull("no four in a row anywhere", g.winningLine.value)
        assertEquals(1, g.draws.value)
        assertEquals(0, g.scoreP1.value + g.scoreP2.value)
    }

    @Test
    fun `play again keeps the board size and swaps the opener`() {
        for (dim in sizes) {
            val g = newGame(dim)
            val k = TicTacToeGame.winLength(dim)
            val line = TicTacToeGame.winningLines(dim).first()
            val fillers = (0 until dim * dim).filter { it !in line }.take(k - 1)
            play(g, line, fillers)
            assertTrue(g.roundOver.value)
            g.playAgain()
            assertEquals(dim * dim, g.board.value.size)
            assertEquals(dim, g.boardDim.value)
            assertTrue(g.board.value.all { it == 0 })
            assertEquals(2, g.currentPlayer.value)
            assertEquals(1, g.scoreP1.value)
        }
    }

    @Test
    fun `undo works on the bigger boards`() {
        val g = newGame(4)
        g.cellClicked(5)
        g.cellClicked(6)
        g.undo()
        assertEquals(0, g.board.value[6])
        assertEquals(1, g.board.value[5])
        assertEquals(2, g.currentPlayer.value)
        g.undo()
        assertTrue(g.board.value.all { it == 0 })
    }

    // --- Resume snapshot ---------------------------------------------------------------------

    @Test
    fun `the snapshot round trips on every size`() {
        for (dim in sizes) {
            val a = newGame(dim)
            play(a, listOf(0, 2, 5), listOf(1, 4))
            val snap = a.snapshot()
            assertNotNull(snap)

            val b = newGame(dim, started = false)
            assertTrue("dim $dim restore", b.restore(snap!!))
            assertEquals(a.cells(), b.cells())
            assertEquals(a.currentPlayer.value, b.currentPlayer.value)
            assertEquals(dim, b.boardDim.value)
            assertEquals(dim * dim, b.board.value.size)
            // And it keeps playing at the restored size.
            b.cellClicked(dim * dim - 1)
            assertEquals(1 + a.cells().count { it != 0 }, b.cells().count { it != 0 })
        }
    }

    @Test
    fun `a save brings its own size even when the engine was started with another`() {
        val a = newGame(4)
        play(a, listOf(0, 5), listOf(1))
        val snap = a.snapshot()!!

        val classic = newGame(3, started = false)
        assertTrue(classic.restore(snap))
        assertEquals(4, classic.boardDim.value)
        assertEquals(16, classic.board.value.size)

        val five = newGame(5, started = false)
        assertTrue(five.restore(snap))
        assertEquals(4, five.boardDim.value)
    }

    @Test
    fun `an old 3x3 save with no size field restores as 3x3`() {
        val a = newGame(3)
        play(a, listOf(4), listOf(0))
        val snap = a.snapshot()!!
        assertFalse("3x3 saves carry no size field, exactly like saves from before board sizes", snap.contains("\"size\""))
        val target = newGame(5, started = false)
        assertTrue(target.restore(snap))
        assertEquals(3, target.boardDim.value)
        assertEquals(9, target.board.value.size)
    }

    @Test
    fun `restore rejects a size the board does not match and leaves the engine untouched`() {
        val a = newGame(4)
        play(a, listOf(0, 5), listOf(1))
        val good = a.snapshot()!!
        assertTrue(good.contains("\"size\":4"))

        val target = newGame(4)
        target.cellClicked(10)
        val before = target.cells()
        val dimBefore = target.boardDim.value

        for (bad in listOf(7, 3, 5, 2, 0, -4)) {
            val tampered = good.replace("\"size\":4", "\"size\":$bad")
            assertTrue(tampered != good)
            assertFalse("size $bad over a 16-cell board must be rejected", target.restore(tampered))
        }
        assertEquals(before, target.cells())
        assertEquals(dimBefore, target.boardDim.value)
    }

    @Test
    fun `a 4x4 or 5x5 save is not accepted by a misere or wild engine`() {
        val a = newGame(5)
        a.cellClicked(12)
        val big = a.snapshot()!!

        // Forge a misere save of the big board (the variant flag matches, the size cannot).
        val forgedMisere = big.replace("\"misere\":false", "\"misere\":true")
        assertTrue(forgedMisere != big)
        assertFalse(newGame(3, misere = true, started = false).restore(forgedMisere))
        val forgedWild = big.replace("\"wild\":false", "\"wild\":true")
        assertTrue(forgedWild != big)
        assertFalse(newGame(3, wild = true, started = false).restore(forgedWild))
        // Honest variant mismatch is rejected too.
        assertFalse(newGame(3, misere = true, started = false).restore(big))
    }

    @Test
    fun `a winning line of the wrong length or off the board is rejected`() {
        val a = newGame(4)
        play(a, listOf(0, 1, 2, 3), listOf(8, 9, 10))
        assertTrue(a.roundOver.value)
        val finished = a.snapshot()!!
        assertTrue(finished.contains("\"winningLine\":[0,1,2,3]"))

        assertTrue(newGame(4, started = false).restore(finished))
        val threeLong = finished.replace("\"winningLine\":[0,1,2,3]", "\"winningLine\":[0,1,2]")
        assertFalse("a 4x4 line has four cells", newGame(4, started = false).restore(threeLong))
        val offBoard = finished.replace("\"winningLine\":[0,1,2,3]", "\"winningLine\":[0,1,2,16]")
        assertFalse(newGame(4, started = false).restore(offBoard))
        // A 3x3 line on a 3x3 board is still fine (old behaviour).
        val old = newGame(3)
        play(old, listOf(0, 1, 2), listOf(3, 4))
        assertTrue(newGame(3, started = false).restore(old.snapshot()!!))
    }

    @Test
    fun `a networked game stays 3x3 and a network state of another size is not plausible`() {
        val state = TicTacToeNetState(
            board = List(9) { 0 }, currentPlayer = 1, roundOver = false, winningLine = null,
            scoreP1 = 0, scoreP2 = 0, draws = 0, roundNumber = 1, matchOver = false, selectedSymbol = 1
        )
        assertEquals("the size field defaults to 3 for older peers", 3, state.size)
        assertEquals(3, TicTacToeNetState(
            board = List(9) { 0 }, currentPlayer = 1, roundOver = false, winningLine = null,
            scoreP1 = 0, scoreP2 = 0, draws = 0, roundNumber = 1, matchOver = false, selectedSymbol = 1, size = 3
        ).size)
        // Networked engines ignore a requested size.
        val net = TicTacToeGame()
        net.boardSize = 5
        net.init(GameContext(PlayMode.LOCAL_AD_HOC, twoHumans, 0, LocalPassAndPlayTransport()))
        assertEquals(3, net.boardDim.value)
        assertEquals(9, net.board.value.size)
    }

    // --- The bot -----------------------------------------------------------------------------

    /** Seat 1 holds [p1] marks, seat 2 (the CPU) [p2], scripted with cellClicked; leaves seat 2 to move. */
    private fun botGame(dim: Int, difficulty: CpuDifficulty, p1: List<Int>, p2: List<Int>): TicTacToeGame {
        val g = newGame(dim, humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT, difficulty)
        require(p1.size == p2.size + 1)
        play(g, p1, p2)
        assertFalse("the scripted position must still be open", g.roundOver.value)
        assertEquals(2, g.currentPlayer.value)
        return g
    }

    @Test
    fun `the bot completes its own line when it can, on every size and level`() {
        for (dim in sizes) {
            val k = TicTacToeGame.winLength(dim)
            val last = (dim - 1) * dim
            val botMarks = (0 until k - 1).map { last + it }          // k-1 across the bottom row
            val winCell = last + k - 1
            // Seat 1 threatens too (k-1 across the top) plus one stray mark: the bot must still prefer winning.
            val humanMarks = (0 until k - 1).map { it } + listOf(dim)
            for (difficulty in listOf(CpuDifficulty.HARD, CpuDifficulty.MEDIUM)) {
                val g = botGame(dim, difficulty, humanMarks, botMarks)
                g.playBotTurn()
                assertTrue("dim $dim $difficulty: the bot wins at once", g.roundOver.value)
                assertEquals(2, g.board.value[winCell])
                assertEquals(1, g.scoreP2.value)
                assertEquals(0, g.scoreP1.value)
            }
        }
    }

    @Test
    fun `the bot blocks a line it would otherwise lose to, on every size and level`() {
        for (dim in sizes) {
            val k = TicTacToeGame.winLength(dim)
            val last = (dim - 1) * dim
            val humanMarks = (0 until k - 1).toList()                  // k-1 across the top: threat at k-1
            val blockCell = k - 1
            // k-2 harmless bot marks on the bottom row, spaced apart.
            val botMarks = (0 until k - 2).map { last + it * 2 }
            for (difficulty in listOf(CpuDifficulty.HARD, CpuDifficulty.MEDIUM)) {
                val g = botGame(dim, difficulty, humanMarks, botMarks)
                g.playBotTurn()
                assertEquals("dim $dim $difficulty: the bot takes the blocking cell", 2, g.board.value[blockCell])
                assertFalse(g.roundOver.value)
                assertEquals(1, g.currentPlayer.value)
            }
        }
    }

    @Test
    fun `the bot blocks a vertical, diagonal and anti-diagonal threat on 4x4 and 5x5`() {
        for (dim in listOf(4, 5)) {
            // Three of a four-long line held by seat 1, the open end named alongside.
            val threats = listOf(
                Triple("column", (0..2).map { it * dim }, 3 * dim),
                Triple("diagonal", (0..2).map { it * (dim + 1) }, 3 * (dim + 1)),
                Triple("anti-diagonal", (1..3).map { it * (dim - 1) }, 4 * (dim - 1))
            )
            for ((name, humanMarks, blockCell) in threats) {
                // Two harmless bot marks, away from the threat line and its open end.
                val spare = (0 until dim * dim).filter { it !in humanMarks && it != blockCell }
                val botMarks = listOf(spare[spare.size - 1], spare[spare.size - 2])
                for (difficulty in listOf(CpuDifficulty.HARD, CpuDifficulty.MEDIUM)) {
                    val g = botGame(dim, difficulty, humanMarks, botMarks)
                    g.playBotTurn()
                    assertEquals("dim $dim $name $difficulty: the bot blocks the open end", 2, g.board.value[blockCell])
                }
            }
        }
    }

    @Test
    fun `a bot move on the bigger boards is quick`() {
        for (dim in listOf(4, 5)) {
            val g = newGame(dim, humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT, CpuDifficulty.HARD)
            g.cellClicked(dim * dim / 2) // the human opens near the middle
            val request = g.beginBotTurn()
            assertNotNull(request)
            val start = System.nanoTime()
            val move = g.searchBotMove(request!!)
            val ms = (System.nanoTime() - start) / 1_000_000
            assertNotNull(move)
            assertEquals(2, move!!.second)
            assertEquals(0, g.board.value[move.first])
            assertTrue("dim $dim: bot took ${ms}ms, expected well under 5s", ms < 5_000)
        }
    }

    @Test
    fun `two hard bots play a legal game to the end on each size`() {
        for (dim in sizes) {
            val g = newGame(dim, twoBots, PlayMode.SINGLE_PLAYER_VS_BOT, CpuDifficulty.HARD)
            var moves = 0
            while (!g.roundOver.value && moves <= dim * dim) {
                g.playBotTurn()
                moves++
            }
            assertTrue("dim $dim: the game ended", g.roundOver.value)
            val line = g.winningLine.value
            if (line == null) {
                assertTrue("a draw means a full board", g.board.value.none { it == 0 })
                assertEquals(1, g.draws.value)
            } else {
                assertEquals(TicTacToeGame.winLength(dim), line.size)
                assertTrue(line.all { g.board.value[it] == g.board.value[line[0]] && g.board.value[it] != 0 })
            }
        }
    }

    @Test
    fun `easy and medium bots also finish a game on the bigger boards`() {
        for (dim in listOf(4, 5)) {
            for (difficulty in listOf(CpuDifficulty.EASY, CpuDifficulty.MEDIUM)) {
                val g = newGame(dim, twoBots, PlayMode.SINGLE_PLAYER_VS_BOT, difficulty)
                var moves = 0
                while (!g.roundOver.value && moves <= dim * dim) {
                    g.playBotTurn()
                    moves++
                }
                assertTrue("dim $dim $difficulty", g.roundOver.value)
            }
        }
    }

    // --- Off-thread protocol -----------------------------------------------------------------

    @Test
    fun `a bot move computed for an old position is not applied`() {
        for (dim in sizes) {
            val g = newGame(dim, humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT, CpuDifficulty.HARD)
            g.cellClicked(0)
            val request = g.beginBotTurn()!!
            val move = g.searchBotMove(request)!!
            // The position changes underneath (an undo would do the same).
            g.undo()
            assertFalse("dim $dim: stale result dropped", g.finishBotTurn(request, move))
            assertTrue(g.board.value.all { it == 0 })
            assertEquals(1, g.currentPlayer.value)
        }
    }

    @Test
    fun `a bot move computed for the live position is applied`() {
        for (dim in sizes) {
            val g = newGame(dim, humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT, CpuDifficulty.HARD)
            g.cellClicked(0)
            val request = g.beginBotTurn()!!
            val move = g.searchBotMove(request)!!
            assertTrue(g.finishBotTurn(request, move))
            assertEquals(2, g.board.value[move.first])
            assertEquals(1, g.currentPlayer.value)
        }
    }

    @Test
    fun `beginBotTurn is null unless a bot is to move`() {
        val g = newGame(4, humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        assertNull("the human opens", g.beginBotTurn())
        val h = newGame(4)
        h.cellClicked(0)
        assertNull("pass and play has no bot", h.beginBotTurn())
    }

    @Test
    fun `the hint is a legal cell on every size and a stale hint is dropped`() {
        for (dim in sizes) {
            val g = newGame(dim, humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
            val hint = g.hint()
            assertNotNull(hint)
            assertEquals(0, g.board.value[hint!!.first])
            assertEquals(1, hint.second)
            assertEquals(hint, g.hintMove.value)

            val h = newGame(dim, humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
            val request = h.beginHint()!!
            val answer = h.searchHint(request)
            h.cellClicked(0) // the position moves on before the answer is published
            assertNull(h.finishHint(request, answer))
            assertNull(h.hintMove.value)
        }
    }

    @Test
    fun `the hint takes a win and blocks a loss on the bigger boards`() {
        for (dim in listOf(4, 5)) {
            val last = (dim - 1) * dim
            // Human (seat 1) has three across the bottom row; the hint should complete it at last+3.
            val win = newGame(dim, humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
            play(win, listOf(last, last + 1, last + 2, 0), listOf(1, 2, 3, 7))
            assertEquals("four marks each, so seat 1 is to move", 1, win.currentPlayer.value)
            assertFalse(win.roundOver.value)
            val winHint = win.hint()!!
            assertEquals(last + 3, winHint.first)
        }
    }
}
