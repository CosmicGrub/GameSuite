package com.gamesuite.games.hex

import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.gomoku.FakeTransport
import com.gamesuite.games.gomoku.TestSeats
import com.gamesuite.settings.CpuDifficulty
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Flow, pie rule, results, Resume, Undo, Hint and fuzzing of [HexGame] (local modes). */
class HexGameTest {
    private val n = 9
    private fun at(r: Int, c: Int) = r * n + c

    private fun game(
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        players: List<PlayerInfo> = TestSeats.humans,
        size: Int = n,
        pie: Boolean = false,
        start: Boolean = true,
        opener: Int = 0
    ) = HexGame().also {
        it.boardSize = size
        it.pieRule = pie
        it.firstOpenerIndex = opener
        it.init(TestSeats.context(mode, players))
        if (start) it.startMatch()
    }

    private fun HexGame.s() = state.value!!

    /** Seat 0 fills column 3 top to bottom; seat 1 plays harmless stones down column 7. */
    private fun playColumnWin(g: HexGame) {
        for (r in 0 until n) {
            assertEquals(HexPlaceResult.PLACED, g.placeStone(at(r, 3)))
            if (r < n - 1) assertEquals(HexPlaceResult.PLACED, g.placeStone(at(r, 7)))
        }
    }

    @Test
    fun `a new game is an empty board of the chosen size and unsupported sizes fall back to 11`() {
        for (size in HexBoardSizes.ALL) {
            val g = game(size = size)
            assertEquals(size * size, g.s().cells.size)
            assertEquals(size, g.s().size)
            assertEquals(0, g.s().currentPlayerIndex)
        }
        val odd = HexGame().also { it.boardSize = 10; it.init(TestSeats.context(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)); it.startMatch() }
        assertEquals(11, odd.s().size)
        assertTrue(PlayMode.ONLINE in odd.supportedModes && PlayMode.LOCAL_AD_HOC in odd.supportedModes)
    }

    @Test
    fun `connecting your two sides wins and records the chain`() {
        val g = game()
        playColumnWin(g)
        val s = g.s()
        assertTrue(s.boardOver)
        assertEquals("h", s.winnerPlayerId)
        assertEquals(n, s.winPath.size)
        assertEquals(1, g.sessionWins.value["h"])
        assertEquals(0, g.sessionDraws.value)
        assertEquals(HexPlaceResult.BOARD_OVER, g.placeStone(at(0, 0)))
    }

    @Test
    fun `seat 1 wins by joining left to right`() {
        val g = game(opener = 1)
        for (c in 0 until n) {
            assertEquals(HexPlaceResult.PLACED, g.placeStone(at(4, c)))
            if (c < n - 1) assertEquals(HexPlaceResult.PLACED, g.placeStone(at(0, c)))
        }
        assertEquals("g", g.s().winnerPlayerId)
    }

    @Test
    fun `rejections say why`() {
        val g = game()
        g.placeStone(at(4, 4))
        assertEquals(HexPlaceResult.OCCUPIED, g.placeStone(at(4, 4)))
        assertEquals(HexPlaceResult.OUT_OF_RANGE, g.placeStone(-1))
        assertEquals(HexPlaceResult.OUT_OF_RANGE, g.placeStone(81))
        val vsCpu = game(PlayMode.SINGLE_PLAYER_VS_BOT, TestSeats.humanAndCpu)
        vsCpu.placeStone(at(4, 4))
        assertEquals(HexPlaceResult.NOT_YOUR_TURN, vsCpu.placeStone(at(5, 5)))
        assertEquals(HexPlaceResult.NO_BOARD, game(start = false).placeStone(0))
    }

    @Test
    fun `play again deals a fresh board, alternates the opener and keeps the tally`() {
        val g = game()
        playColumnWin(g)
        g.playAgain()
        assertFalse(g.s().boardOver)
        assertTrue(g.s().cells.all { it == null })
        assertEquals(1, g.s().currentPlayerIndex)
        assertEquals(1, g.sessionWins.value["h"])
    }

    @Test
    fun `leaving reports only a strict leader as the winner`() {
        val g = game()
        playColumnWin(g)
        val results = mutableListOf<GameResult>()
        g.setOnMatchEnd { results += it }
        g.leaveSession()
        assertEquals(listOf(true, false), results.single().scores.map { it.isWinner })
        assertTrue(g.matchOver.value)
    }

    // ---- pie rule ----

    @Test
    fun `without the pie rule there is never a swap`() {
        val g = game(pie = false)
        g.placeStone(at(2, 5))
        assertFalse(g.s().pieOpen)
        assertFalse(g.canSwap)
        assertFalse(g.swap())
    }

    @Test
    fun `with the pie rule the second player may swap once and the opener moves next`() {
        val g = game(pie = true)
        assertFalse(g.canSwap)
        g.placeStone(at(2, 5))
        assertTrue(g.s().pieOpen)
        assertTrue(g.canSwap)
        assertTrue(g.swap())
        val s = g.s()
        assertFalse(s.pieOpen)
        assertTrue(s.swapped)
        assertNull(s.cells[at(2, 5)])
        assertEquals(1, s.cells[at(5, 2)]) // transposed and re-owned by the swapper (seat 1)
        assertEquals(at(5, 2), s.lastMove)
        assertEquals(1, s.stones)
        assertEquals(0, s.currentPlayerIndex)
        assertFalse(g.canSwap)
        assertFalse(g.swap())
        g.placeStone(at(0, 0))
        assertFalse(g.s().pieOpen)
    }

    @Test
    fun `playing a stone instead of swapping closes the pie offer`() {
        val g = game(pie = true)
        g.placeStone(at(4, 4))
        assertTrue(g.canSwap)
        g.placeStone(at(0, 0))
        assertFalse(g.s().pieOpen)
        assertFalse(g.canSwap)
        assertFalse(g.swap())
    }

    @Test
    fun `a swap on the long diagonal keeps the cell and changes the owner`() {
        val g = game(pie = true)
        g.placeStone(at(4, 4))
        g.swap()
        assertEquals(1, g.s().cells[at(4, 4)])
    }

    @Test
    fun `undo takes a swap back to the offer`() {
        val g = game(pie = true)
        g.placeStone(at(2, 5))
        val offered = g.s()
        g.swap()
        assertTrue(g.canUndo)
        g.undo()
        assertEquals(offered, g.s())
        assertTrue(g.canSwap)
    }

    @Test
    fun `a cpu second player swaps a strong opening and keeps a weak one`() {
        val strong = game(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(TestSeats.humans[0], PlayerInfo("c", "CPU", isBot = true)), pie = true)
        strong.difficulty = CpuDifficulty.HARD
        strong.placeStone(at(4, 4))
        strong.playBotTurn()
        assertTrue(strong.s().swapped)
        assertEquals(0, strong.s().currentPlayerIndex)
        val weak = game(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(TestSeats.humans[0], PlayerInfo("c", "CPU", isBot = true)), pie = true)
        weak.difficulty = CpuDifficulty.HARD
        weak.placeStone(at(0, 1))
        weak.playBotTurn()
        assertFalse(weak.s().swapped)
        assertEquals(2, weak.s().stones)
    }

    @Test
    fun `a cpu opener with the pie rule picks a modest opening the player may swap`() {
        val g = HexGame().also {
            it.pieRule = true
            it.boardSize = 11
            it.firstOpenerIndex = 1
            it.random = Random(4)
            it.init(TestSeats.context(PlayMode.SINGLE_PLAYER_VS_BOT, TestSeats.humanAndCpu))
            it.startMatch()
        }
        g.playBotTurn()
        assertEquals(1, g.s().stones)
        assertTrue(g.s().pieOpen)
        assertTrue(g.canSwap)
    }

    // ---- Resume ----

    @Test
    fun `snapshot and restore round-trip a game in progress including the pie offer`() {
        val g = game(PlayMode.SINGLE_PLAYER_VS_BOT, TestSeats.humanAndCpu, pie = true)
        g.difficulty = CpuDifficulty.HARD
        g.placeStone(at(0, 1))
        g.playBotTurn()
        val snap = g.snapshot()!!
        val g2 = game(PlayMode.SINGLE_PLAYER_VS_BOT, TestSeats.humanAndCpu, start = false)
        assertTrue(g2.restore(snap))
        assertEquals(g.s(), g2.s())
        assertEquals(CpuDifficulty.HARD, g2.difficulty)
        assertEquals(snap, g2.snapshot())

        val offer = game(pie = true)
        offer.placeStone(at(2, 5))
        val twin = game(start = false)
        assertTrue(twin.restore(offer.snapshot()!!))
        assertTrue(twin.s().pieOpen)
        assertTrue(twin.canSwap)
    }

    @Test
    fun `a finished board round-trips with its winning chain and a finished board snapshot restores the size`() {
        val g = game()
        playColumnWin(g)
        val g2 = game(size = 13, start = false)
        assertTrue(g2.restore(g.snapshot()!!))
        assertEquals(g.s(), g2.s())
        assertEquals(9, g2.s().size)
        assertEquals(n, g2.s().winPath.size)
    }

    @Test
    fun `bad snapshots are rejected and leave the game untouched`() {
        val g = game(pie = true)
        g.placeStone(at(2, 5))
        val before = g.s()
        val good = g.snapshot()!!
        val cases = listOf(
            "", "not json", "{}",
            good.replace("\"size\":9", "\"size\":10"),
            good.replace("\"currentPlayerIndex\":1", "\"currentPlayerIndex\":5"),
            good.replace("\"h\"", "\"zzz\""),
            good.replace("\"isBot\":false", "\"isBot\":true"),
            good.replace("\"cells\":[", "\"cells\":[7,"),
            good.replace("\"boardOver\":false", "\"boardOver\":true"),
            good.replace("\"pieOpen\":true", "\"pieOpen\":false,\"swapped\":true,\"pieRule\":false"),
            good.replace("\"currentPlayerIndex\":1", "\"currentPlayerIndex\":0"), // the offer belongs to the other seat
            good.replace("\"sessionDraws\":0", "\"sessionDraws\":-1")
        )
        for (c in cases) assertFalse("accepted: ${c.take(90)}", g.restore(c))
        assertEquals(before, g.s())
    }

    @Test
    fun `a forged win or a win that is not on the board is rejected`() {
        val g = game()
        playColumnWin(g)
        val good = g.snapshot()!!
        assertFalse(game(start = false).restore(good.replace("\"winnerPlayerId\":\"h\"", "\"winnerPlayerId\":\"g\"")))
        assertFalse(game(start = false).restore(good.replace("\"winnerPlayerId\":\"h\"", "\"winnerPlayerId\":null")))
        val mid = game()
        mid.placeStone(at(0, 0))
        val s = mid.snapshot()!!
        assertFalse(game(start = false).restore(s.replace("\"boardOver\":false", "\"boardOver\":true").replace("\"winnerPlayerId\":null", "\"winnerPlayerId\":\"h\"")))
    }

    @Test
    fun `snapshot is null before a board, after the session ends and in networked modes`() {
        assertNull(game(start = false).snapshot())
        val g = game()
        g.placeStone(at(1, 1))
        assertNotNull(g.snapshot())
        g.leaveSession()
        assertNull(g.snapshot())
        val net = HexGame().also { it.init(TestSeats.context(PlayMode.LOCAL_AD_HOC, transport = FakeTransport())); it.startMatch() }
        assertNull(net.snapshot())
    }

    // ---- Undo ----

    @Test
    fun `undo restores the exact position and the cpu reply goes with the human move`() {
        val g = game()
        assertFalse(g.canUndo)
        val start = g.s()
        g.placeStone(at(1, 1))
        val one = g.s()
        g.placeStone(at(2, 2))
        g.undo()
        assertEquals(one, g.s())
        g.undo()
        assertEquals(start, g.s())
        g.undo()
        assertEquals(start, g.s())

        val vsCpu = game(PlayMode.SINGLE_PLAYER_VS_BOT, TestSeats.humanAndCpu)
        vsCpu.placeStone(at(4, 4))
        vsCpu.playBotTurn()
        vsCpu.placeStone(at(1, 1))
        vsCpu.playBotTurn()
        vsCpu.undo()
        assertEquals(2, vsCpu.s().stones)
        vsCpu.undo()
        assertEquals(0, vsCpu.s().stones)
    }

    @Test
    fun `undoing the winning move re-opens the board and takes the win off the tally`() {
        val g = game()
        playColumnWin(g)
        g.undo()
        assertFalse(g.s().boardOver)
        assertNull(g.s().winnerPlayerId)
        assertTrue(g.s().winPath.isEmpty())
        assertEquals(0, g.sessionWins.value["h"])
    }

    // ---- Hint ----

    @Test
    fun `hint suggests an empty cell, finds a winning move and clears on the next move`() {
        val g = game()
        g.placeStone(at(4, 4))
        val h = g.hint()!!
        assertNull(g.s().cells[h])
        g.placeStone(h)
        assertNull(g.hintMove.value)

        val w = game()
        for (r in 0 until n - 1) {
            w.placeStone(at(r, 3))
            w.placeStone(at(r, 7))
        }
        // Two cells below (7,3) finish the chain: straight below and down-left.
        val finishing = setOf(at(n - 1, 3), at(n - 1, 2))
        assertTrue(w.hint() in finishing)
        assertTrue(runBlocking { w.requestHint() } in finishing)
    }

    @Test
    fun `hint is unavailable on the cpu turn, after the end and when networked`() {
        val vsCpu = game(PlayMode.SINGLE_PLAYER_VS_BOT, TestSeats.humanAndCpu)
        vsCpu.placeStone(at(4, 4))
        assertNull(vsCpu.hint())
        val done = game()
        playColumnWin(done)
        assertNull(done.hint())
        val net = HexGame().also { it.init(TestSeats.context(PlayMode.ONLINE, transport = FakeTransport())); it.startMatch() }
        assertNull(net.hint())
    }

    // ---- CPU ----

    @Test
    fun `async cpu turn plays a legal move and is a no-op on the human turn`() {
        val g = game(PlayMode.SINGLE_PLAYER_VS_BOT, TestSeats.humanAndCpu)
        g.placeStone(at(4, 4))
        runBlocking { g.playBotTurnAsync() }
        assertEquals(2, g.s().stones)
        runBlocking { g.playBotTurnAsync() }
        assertEquals(2, g.s().stones)
    }

    // ---- fuzz ----

    @Test
    fun `random legal play never throws and keeps every invariant, with swap, resume and undo checks`() {
        repeat(90) { seed ->
            val rnd = Random(seed)
            val size = HexBoardSizes.ALL[seed % 3]
            val pie = seed % 2 == 0
            val engine = HexEngine.of(size)
            val g = game(size = size, pie = pie)
            var steps = 0
            while (!g.s().boardOver) {
                steps++
                assertTrue(steps < size * size + 5)
                val before = g.s()
                if (g.canSwap && rnd.nextInt(3) == 0) {
                    assertTrue(g.swap())
                    assertEquals(1, g.s().stones)
                    assertEquals(1 - before.currentPlayerIndex, g.s().currentPlayerIndex)
                    assertFalse(g.s().pieOpen)
                    continue
                }
                val empties = before.cells.indices.filter { before.cells[it] == null }
                val pick = empties[rnd.nextInt(empties.size)]
                assertEquals(HexPlaceResult.PLACED, g.placeStone(pick))
                val s = g.s()
                assertEquals(before.stones + 1, s.stones)
                val board = engine.toBoard(s.cells)
                val c0 = engine.connected(board, 0)
                val c1 = engine.connected(board, 1)
                assertEquals(s.boardOver, c0 || c1)
                assertFalse(c0 && c1)
                if (s.boardOver) assertEquals(if (c0) s.players[0].playerId else s.players[1].playerId, s.winnerPlayerId)
                if (rnd.nextInt(7) == 0) {
                    val twin = game(size = 9, start = false)
                    assertTrue("seed $seed step $steps", twin.restore(g.snapshot()!!))
                    assertEquals(s, twin.s())
                }
                if (rnd.nextInt(9) == 0 && g.canUndo) {
                    g.undo()
                    assertEquals(before, g.s())
                    g.placeStone(pick)
                    assertEquals(s, g.s())
                }
            }
            assertEquals(1, g.sessionWins.value.values.sum())
            assertNotNull(g.s().winnerPlayerId)
        }
    }
}
