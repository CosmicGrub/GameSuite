package com.gamesuite.games.gomoku

import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Flow, results, Resume, Undo, Hint and fuzzing of [GomokuGame] (local modes). */
class GomokuGameTest {
    private val n = GOMOKU_SIZE
    private fun at(r: Int, c: Int) = r * n + c

    private fun game(
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        players: List<PlayerInfo> = TestSeats.humans,
        exact: Boolean = false,
        start: Boolean = true
    ) = GomokuGame().also {
        it.exactFive = exact
        it.init(TestSeats.context(mode, players))
        if (start) it.startMatch()
    }

    private fun GomokuGame.s() = state.value!!

    /** Seat 0 plays row 7 cols 7..11 while seat 1 plays harmless stones on row 0. */
    private fun playRowWin(g: GomokuGame) {
        val mine = listOf(at(7, 7), at(7, 8), at(7, 9), at(7, 10), at(7, 11))
        val theirs = listOf(at(0, 0), at(0, 2), at(0, 4), at(0, 6))
        for (i in mine.indices) {
            assertEquals(GomokuPlaceResult.PLACED, g.placeStone(mine[i]))
            if (i < theirs.size) assertEquals(GomokuPlaceResult.PLACED, g.placeStone(theirs[i]))
        }
    }

    @Test
    fun `a new game is an empty 15 by 15 board with seat 0 to move`() {
        val g = game()
        assertEquals(225, g.s().cells.size)
        assertTrue(g.s().cells.all { it == null })
        assertEquals(0, g.s().currentPlayerIndex)
        assertFalse(g.s().boardOver)
        assertTrue(PlayMode.ONLINE in g.supportedModes && PlayMode.LOCAL_AD_HOC in g.supportedModes)
    }

    @Test
    fun `turns alternate and the first mover option picks the opener`() {
        val g = GomokuGame().also {
            it.firstOpenerIndex = 1
            it.init(TestSeats.context(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY))
            it.startMatch()
        }
        assertEquals(1, g.s().currentPlayerIndex)
        g.placeStone(at(7, 7))
        assertEquals(1, g.s().cells[at(7, 7)])
        assertEquals(0, g.s().currentPlayerIndex)
        assertEquals(at(7, 7), g.s().lastMove)
    }

    @Test
    fun `five in a row ends the board, records the winner and the line, and blocks further play`() {
        val g = game()
        playRowWin(g)
        val s = g.s()
        assertTrue(s.boardOver)
        assertEquals("h", s.winnerPlayerId)
        assertEquals(setOf(at(7, 7), at(7, 8), at(7, 9), at(7, 10), at(7, 11)), s.winLine.toSet())
        assertEquals(1, g.sessionWins.value["h"])
        assertEquals(GomokuPlaceResult.BOARD_OVER, g.placeStone(at(10, 10)))
        assertNull(g.s().cells[at(10, 10)])
    }

    @Test
    fun `rejections say why`() {
        val g = game()
        g.placeStone(at(7, 7))
        assertEquals(GomokuPlaceResult.OCCUPIED, g.placeStone(at(7, 7)))
        assertEquals(GomokuPlaceResult.OUT_OF_RANGE, g.placeStone(-1))
        assertEquals(GomokuPlaceResult.OUT_OF_RANGE, g.placeStone(225))
        val vsCpu = game(PlayMode.SINGLE_PLAYER_VS_BOT, TestSeats.humanAndCpu)
        vsCpu.placeStone(at(7, 7))
        assertEquals(GomokuPlaceResult.NOT_YOUR_TURN, vsCpu.placeStone(at(8, 8)))
        assertEquals(GomokuPlaceResult.NO_BOARD, game(start = false).placeStone(0))
    }

    @Test
    fun `exact-five rules do not end the board on an overline but free-style does`() {
        fun play(exact: Boolean): GomokuGame {
            val g = game(exact = exact)
            val mine = listOf(at(7, 7), at(7, 8), at(7, 10), at(7, 11), at(7, 12), at(7, 9))
            val theirs = listOf(at(0, 0), at(0, 2), at(0, 4), at(0, 6), at(0, 8))
            for (i in mine.indices) {
                g.placeStone(mine[i])
                if (i < theirs.size) g.placeStone(theirs[i])
            }
            return g
        }
        assertTrue(play(false).s().boardOver)
        val exact = play(true)
        assertFalse(exact.s().boardOver)
        assertTrue(exact.s().exactFive)
    }

    /** A full board with no five for either side, one dark cell short. */
    private fun almostFullDrawSnapshot(): Pair<String, Int> {
        val cells = List(225) { i -> ((i / n) / 2 + i % n) % 2 }
        val engine = GomokuEngine(n, false)
        val b = engine.toBoard(cells)
        for (i in cells.indices) assertNull("pattern must contain no five (cell $i)", engine.winLine(b, i, cells[i]))
        val hole = cells.indexOfFirst { it == 0 }
        val shown = cells.mapIndexed { i, v -> if (i == hole) "null" else v.toString() }.joinToString(",")
        val json = "{\"cells\":[$shown],\"players\":[{\"playerId\":\"h\",\"displayName\":\"Hana\",\"isBot\":false}," +
            "{\"playerId\":\"g\",\"displayName\":\"Gus\",\"isBot\":false}],\"currentPlayerIndex\":0,\"boardOver\":false," +
            "\"winnerPlayerId\":null,\"winLine\":[],\"lastMove\":null,\"sessionWins\":{\"h\":0,\"g\":0},\"sessionDraws\":0," +
            "\"nextStartingPlayerIndex\":1,\"difficulty\":\"MEDIUM\"}"
        return json to hole
    }

    @Test
    fun `a full board with no five is a draw`() {
        val g = game()
        val (json, hole) = almostFullDrawSnapshot()
        assertTrue(g.restore(json))
        assertEquals(GomokuPlaceResult.PLACED, g.placeStone(hole))
        assertTrue(g.s().boardOver)
        assertNull(g.s().winnerPlayerId)
        assertEquals(1, g.sessionDraws.value)
        g.undo()
        assertFalse(g.s().boardOver)
        assertEquals(0, g.sessionDraws.value)
    }

    @Test
    fun `play again deals a new board, alternates the opener and keeps the tally`() {
        val g = game()
        playRowWin(g)
        g.playAgain()
        assertFalse(g.s().boardOver)
        assertTrue(g.s().cells.all { it == null })
        assertEquals(1, g.s().currentPlayerIndex)
        assertEquals(1, g.sessionWins.value["h"])
    }

    @Test
    fun `leaving reports only a strict leader as the winner`() {
        val g = game()
        playRowWin(g)
        val results = mutableListOf<GameResult>()
        g.setOnMatchEnd { results += it }
        g.leaveSession()
        assertTrue(g.matchOver.value)
        assertEquals(listOf(true, false), results.single().scores.map { it.isWinner })
        g.leaveSession()
        assertEquals(1, results.size)
    }

    // ---- Resume ----

    @Test
    fun `snapshot and restore round-trip a game in progress`() {
        val g = game(PlayMode.SINGLE_PLAYER_VS_BOT, TestSeats.humanAndCpu)
        g.difficulty = CpuDifficulty.HARD
        g.placeStone(at(7, 7))
        g.playBotTurn()
        g.placeStone(at(8, 8))
        val snap = g.snapshot()!!
        val g2 = game(PlayMode.SINGLE_PLAYER_VS_BOT, TestSeats.humanAndCpu, start = false)
        assertTrue(g2.restore(snap))
        assertEquals(g.s(), g2.s())
        assertEquals(CpuDifficulty.HARD, g2.difficulty)
        assertEquals(snap, g2.snapshot())
    }

    @Test
    fun `a finished board and the exact-five rule survive a round trip`() {
        val g = game(exact = true)
        playRowWin(g)
        assertTrue(g.s().boardOver)
        val g2 = game(start = false)
        assertTrue(g2.restore(g.snapshot()!!))
        assertEquals(g.s(), g2.s())
        assertTrue(g2.s().exactFive)
        assertEquals(1, g2.sessionWins.value["h"])
    }

    @Test
    fun `bad snapshots are rejected and leave the game untouched`() {
        val g = game()
        g.placeStone(at(7, 7))
        val before = g.s()
        val good = g.snapshot()!!
        val cases = listOf(
            "", "not json", "{}",
            good.replace("\"currentPlayerIndex\":1", "\"currentPlayerIndex\":5"),
            good.replace("\"h\"", "\"zzz\""),
            good.replace("\"isBot\":false", "\"isBot\":true"),
            good.replace("\"cells\":[", "\"cells\":[7,"),
            good.replace("\"boardOver\":false", "\"boardOver\":true"),
            good.replace("\"winLine\":[]", "\"winLine\":[1,2,3,4,5]"),
            good.replace("\"lastMove\":112", "\"lastMove\":3"),
            good.replace("\"sessionDraws\":0", "\"sessionDraws\":-4")
        )
        for (c in cases) assertFalse("accepted: ${c.take(80)}", g.restore(c))
        assertEquals(before, g.s())
    }

    @Test
    fun `a forged win is rejected`() {
        val g = game()
        playRowWin(g)
        val good = g.snapshot()!!
        assertFalse(game(start = false).restore(good.replace("\"winnerPlayerId\":\"h\"", "\"winnerPlayerId\":\"g\"")))
        assertFalse(game(start = false).restore(good.replace("\"winnerPlayerId\":\"h\"", "\"winnerPlayerId\":null")))
    }

    @Test
    fun `snapshot is null before a board, after the session ends and in networked modes`() {
        assertNull(game(start = false).snapshot())
        val g = game()
        g.placeStone(at(7, 7))
        assertNotNull(g.snapshot())
        g.leaveSession()
        assertNull(g.snapshot())
        val net = GomokuGame().also { it.init(TestSeats.context(PlayMode.LOCAL_AD_HOC, transport = FakeTransport())); it.startMatch() }
        assertNull(net.snapshot())
    }

    // ---- Undo ----

    @Test
    fun `undo restores the exact position in pass and play`() {
        val g = game()
        assertFalse(g.canUndo)
        val start = g.s()
        g.placeStone(at(7, 7))
        val afterOne = g.s()
        g.placeStone(at(8, 8))
        assertTrue(g.canUndo)
        g.undo()
        assertEquals(afterOne, g.s())
        g.undo()
        assertEquals(start, g.s())
        assertFalse(g.canUndo)
        g.undo()
        assertEquals(start, g.s())
    }

    @Test
    fun `against the cpu undo takes back the human move and the reply together`() {
        val g = game(PlayMode.SINGLE_PLAYER_VS_BOT, TestSeats.humanAndCpu)
        g.placeStone(at(7, 7))
        g.playBotTurn()
        assertEquals(2, g.s().stones)
        g.placeStone(at(10, 10))
        g.playBotTurn()
        assertEquals(4, g.s().stones)
        g.undo()
        assertEquals(2, g.s().stones)
        g.undo()
        assertEquals(0, g.s().stones)
        assertEquals(0, g.s().currentPlayerIndex)
    }

    @Test
    fun `undoing the winning move re-opens the board and takes the win off the tally`() {
        val g = game()
        playRowWin(g)
        assertEquals(1, g.sessionWins.value["h"])
        g.undo()
        assertFalse(g.s().boardOver)
        assertNull(g.s().winnerPlayerId)
        assertTrue(g.s().winLine.isEmpty())
        assertEquals(0, g.sessionWins.value["h"])
    }

    // ---- Hint ----

    @Test
    fun `hint suggests an empty cell and clears on the next move`() {
        val g = game()
        g.placeStone(at(7, 7))
        val h = g.hint()
        assertNotNull(h)
        assertNull(g.s().cells[h!!])
        assertEquals(h, g.hintMove.value)
        g.placeStone(h)
        assertNull(g.hintMove.value)
    }

    @Test
    fun `hint finds the winning move and the async version agrees`() {
        val g = game()
        val mine = listOf(at(7, 7), at(7, 8), at(7, 9), at(7, 10))
        val theirs = listOf(at(0, 0), at(0, 2), at(0, 4), at(0, 6))
        for (i in mine.indices) {
            g.placeStone(mine[i])
            g.placeStone(theirs[i])
        }
        val winners = setOf(at(7, 6), at(7, 11))
        assertTrue(g.hint() in winners)
        assertTrue(runBlocking { g.requestHint() } in winners)
    }

    @Test
    fun `hint is unavailable on the cpu turn, after the end and when networked`() {
        val vsCpu = game(PlayMode.SINGLE_PLAYER_VS_BOT, TestSeats.humanAndCpu)
        vsCpu.placeStone(at(7, 7))
        assertNull(vsCpu.hint())
        val done = game()
        playRowWin(done)
        assertNull(done.hint())
        val net = GomokuGame().also { it.init(TestSeats.context(PlayMode.ONLINE, transport = FakeTransport())); it.startMatch() }
        assertNull(net.hint())
    }

    // ---- CPU ----

    @Test
    fun `async cpu turn plays a legal move and is a no-op on the human turn`() {
        val g = game(PlayMode.SINGLE_PLAYER_VS_BOT, TestSeats.humanAndCpu)
        g.placeStone(at(7, 7))
        runBlocking { g.playBotTurnAsync() }
        assertEquals(2, g.s().stones)
        assertEquals(0, g.s().currentPlayerIndex)
        runBlocking { g.playBotTurnAsync() }
        assertEquals(2, g.s().stones)
    }

    @Test
    fun `the cpu opens in the centre when it goes first`() {
        val g = GomokuGame().also {
            it.firstOpenerIndex = 1
            it.init(TestSeats.context(PlayMode.SINGLE_PLAYER_VS_BOT, TestSeats.humanAndCpu))
            it.startMatch()
        }
        g.playBotTurn()
        assertEquals(at(7, 7), g.s().lastMove)
        assertEquals(0, g.s().currentPlayerIndex)
    }

    // ---- fuzz ----

    @Test
    fun `random legal play never throws and keeps every invariant, with resume and undo checks`() {
        val engine = GomokuEngine(n, false)
        repeat(60) { seed ->
            val rnd = Random(seed)
            val g = game(exact = seed % 2 == 0)
            var moves = 0
            while (!g.s().boardOver && moves < 400) {
                val empties = g.s().cells.indices.filter { g.s().cells[it] == null }
                val before = g.s()
                val pick = empties[rnd.nextInt(empties.size)]
                assertEquals(GomokuPlaceResult.PLACED, g.placeStone(pick))
                moves++
                val s = g.s()
                assertEquals(before.stones + 1, s.stones)
                assertEquals(before.currentPlayerIndex, s.cells[pick])
                if (s.boardOver && s.winnerPlayerId != null) {
                    assertTrue(s.winLine.size >= 5)
                    assertNotNull(GomokuEngine(n, s.exactFive).winLine(engine.toBoard(s.cells), pick, s.cells[pick]!!))
                } else if (!s.boardOver) {
                    assertEquals(1 - before.currentPlayerIndex, s.currentPlayerIndex)
                }
                if (rnd.nextInt(7) == 0) {
                    val twin = game(start = false)
                    assertTrue("seed $seed move $moves", twin.restore(g.snapshot()!!))
                    assertEquals(s, twin.s())
                }
                if (rnd.nextInt(9) == 0 && g.canUndo) {
                    g.undo()
                    assertEquals(before, g.s())
                    assertFalse(g.s().boardOver)
                    g.placeStone(pick)
                    assertEquals(s, g.s())
                }
            }
            assertTrue(g.s().boardOver)
        }
    }
}
