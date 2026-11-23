package com.gamesuite.games.connectfour

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Hub "size" variants: classic 7x6 connect 4, wide 8x7 connect 4, big 9x7 connect 5. */
class ConnectFourBoardVariantsTest {

    private val specs = ConnectFourBoardSpec.ALL

    private val twoHumans = listOf(PlayerInfo("p1", "Ann"), PlayerInfo("p2", "Bo"))
    private val humanAndCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))

    private fun newGame(
        spec: ConnectFourBoardSpec,
        players: List<PlayerInfo> = twoHumans,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
    ): ConnectFourGame {
        val g = ConnectFourGame()
        g.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        g.boardSpec = spec
        g.startMatch()
        return g
    }

    private fun idx(spec: ConnectFourBoardSpec, row: Int, col: Int) = row * spec.columns + col

    /** Puts [owned] (index to owner) on the board directly and gives the move to [mover]. */
    private fun setBoard(g: ConnectFourGame, cells: Map<Int, Int>, mover: Int) {
        val s = g.state.value!!
        val list = MutableList<Int?>(s.cells.size) { null }
        cells.forEach { (i, owner) -> list[i] = owner }
        g.state.value = s.copy(cells = list, currentPlayerIndex = mover)
    }

    // --- shapes ---------------------------------------------------------------------------------

    @Test
    fun `each size deals a board of its own dimensions and win length`() {
        val expected = listOf(Triple(7, 6, 4), Triple(8, 7, 4), Triple(9, 7, 5))
        specs.zip(expected).forEach { (spec, dims) ->
            val s = newGame(spec).state.value!!
            assertEquals(dims.first, s.cols)
            assertEquals(dims.second, s.rows)
            assertEquals(dims.third, s.winLength)
            assertEquals(dims.first * dims.second, s.cells.size)
            assertEquals(spec, s.spec)
        }
    }

    @Test
    fun `size ids map to specs and unknown ids fall back to classic`() {
        assertEquals(ConnectFourBoardSpec.CLASSIC, ConnectFourBoardSpec.fromId("classic"))
        assertEquals(ConnectFourBoardSpec.WIDE, ConnectFourBoardSpec.fromId("wide"))
        assertEquals(ConnectFourBoardSpec.BIG, ConnectFourBoardSpec.fromId("big"))
        assertEquals(ConnectFourBoardSpec.CLASSIC, ConnectFourBoardSpec.fromId(null))
        assertEquals(ConnectFourBoardSpec.CLASSIC, ConnectFourBoardSpec.fromId("huge"))
        specs.forEach { assertEquals(it, ConnectFourBoardSpec.fromId(it.id)) }
        assertNull(ConnectFourBoardSpec.forDims(7, 7, 4))
    }

    // --- win detection --------------------------------------------------------------------------

    @Test
    fun `a horizontal line wins on every size and only at the full length`() {
        for (spec in specs) {
            val g = newGame(spec)
            val len = spec.winLength
            // Ann builds the bottom row; Bo stacks on top of each of her discs.
            for (c in 0 until len - 1) {
                g.dropDisc(c)
                g.dropDisc(c)
            }
            assertFalse("${spec.id}: one short is not a win", g.state.value!!.boardOver)
            g.dropDisc(len - 1)
            val s = g.state.value!!
            assertTrue("${spec.id}: horizontal win", s.boardOver)
            assertEquals("p1", s.winnerPlayerId)
            assertEquals((0 until len).map { idx(spec, spec.rows - 1, it) }.toSet(), s.winningLine!!.toSet())
        }
    }

    @Test
    fun `a horizontal line against the right edge wins`() {
        for (spec in specs) {
            val g = newGame(spec)
            val len = spec.winLength
            val first = spec.columns - len
            for (c in first until spec.columns - 1) {
                g.dropDisc(c)
                g.dropDisc(c)
            }
            g.dropDisc(spec.columns - 1)
            val s = g.state.value!!
            assertTrue("${spec.id}: edge win", s.boardOver)
            assertEquals((first until spec.columns).map { idx(spec, spec.rows - 1, it) }.toSet(), s.winningLine!!.toSet())
        }
    }

    @Test
    fun `a vertical line wins on every size`() {
        for (spec in specs) {
            val g = newGame(spec)
            val len = spec.winLength
            repeat(len - 1) {
                g.dropDisc(0)
                g.dropDisc(spec.columns - 1)
            }
            assertFalse(g.state.value!!.boardOver)
            g.dropDisc(0)
            val s = g.state.value!!
            assertTrue("${spec.id}: vertical win", s.boardOver)
            assertEquals("p1", s.winnerPlayerId)
            assertEquals((0 until len).map { idx(spec, spec.rows - 1 - it, 0) }.toSet(), s.winningLine!!.toSet())
        }
    }

    /** Owner-1 discs holding up every cell under [line] (as many as each column needs); nothing there makes a line. */
    private fun supports(spec: ConnectFourBoardSpec, line: List<Int>): MutableMap<Int, Int> {
        val cells = mutableMapOf<Int, Int>()
        for (i in line) {
            val col = i % spec.columns
            for (r in i / spec.columns + 1 until spec.rows) cells[idx(spec, r, col)] = 1
        }
        return cells
    }

    @Test
    fun `a rising diagonal wins on every size, at both edges`() {
        for (spec in specs) {
            for (c0 in listOf(0, spec.columns - spec.winLength)) {
                val len = spec.winLength
                val line = (0 until len).map { idx(spec, spec.rows - 1 - it, c0 + it) }
                val g = newGame(spec)
                val cells = supports(spec, line)
                line.dropLast(1).forEach { cells[it] = 0 }
                setBoard(g, cells, mover = 0)
                g.dropDisc(c0 + len - 1)
                val s = g.state.value!!
                assertTrue("${spec.id} c0=$c0: rising diagonal", s.boardOver)
                assertEquals("p1", s.winnerPlayerId)
                assertEquals(line.toSet(), s.winningLine!!.toSet())
            }
        }
    }

    @Test
    fun `a falling diagonal wins on every size, at both edges`() {
        for (spec in specs) {
            for (c0 in listOf(0, spec.columns - spec.winLength)) {
                val len = spec.winLength
                val line = (0 until len).map { idx(spec, spec.rows - len + it, c0 + it) }
                val g = newGame(spec)
                val cells = supports(spec, line)
                line.dropLast(1).forEach { cells[it] = 0 }
                setBoard(g, cells, mover = 0)
                g.dropDisc(c0 + len - 1)
                val s = g.state.value!!
                assertTrue("${spec.id} c0=$c0: falling diagonal", s.boardOver)
                assertEquals("p1", s.winnerPlayerId)
                assertEquals(line.toSet(), s.winningLine!!.toSet())
            }
        }
    }

    @Test
    fun `four in a row does not win the big connect 5 board but five does`() {
        val spec = ConnectFourBoardSpec.BIG
        val g = newGame(spec)
        for (c in 0..3) {
            g.dropDisc(c)
            g.dropDisc(c)
        }
        assertFalse("four across is not a win on connect 5", g.state.value!!.boardOver)
        // Ann has 0..3 on the bottom row (her first four), Bo 0..3 above; Ann is to move again.
        g.dropDisc(4)
        assertTrue(g.state.value!!.boardOver)
        assertEquals(5, g.state.value!!.winningLine!!.size)
        assertTrue(g.state.value!!.lastAction.contains("five"))
    }

    @Test
    fun `the win text names the line length`() {
        val classic = newGame(ConnectFourBoardSpec.CLASSIC)
        listOf(0, 1, 0, 1, 0, 1, 0).forEach { classic.dropDisc(it) }
        assertTrue(classic.state.value!!.lastAction.contains("connects four and wins"))
    }

    // --- full board, full column ----------------------------------------------------------------

    /** A pattern with no run longer than 2 in any direction: it can fill a board without a winner. */
    private fun drawOwner(row: Int, col: Int) = (row + col / 2) % 2

    @Test
    fun `filling the last slot with no line is a draw on every size`() {
        for (spec in specs) {
            val g = newGame(spec)
            val cells = mutableMapOf<Int, Int>()
            for (r in 0 until spec.rows) for (c in 0 until spec.columns) {
                if (r == 0 && c == 0) continue // the top of column 0 is the last empty slot
                cells[idx(spec, r, c)] = drawOwner(r, c)
            }
            setBoard(g, cells, mover = drawOwner(0, 0))
            g.dropDisc(0)
            val s = g.state.value!!
            assertTrue("${spec.id}: board over", s.boardOver)
            assertNull("${spec.id}: no winner", s.winnerPlayerId)
            assertNull(s.winningLine)
            assertTrue(s.cells.none { it == null })
            assertEquals(1, g.sessionDraws.value)
        }
    }

    @Test
    fun `dropping into a full column changes nothing on every size`() {
        for (spec in specs) {
            val g = newGame(spec)
            repeat(spec.rows) { g.dropDisc(0) } // alternating owners: no vertical line
            assertFalse(g.state.value!!.boardOver)
            val before = g.state.value!!
            g.dropDisc(0)
            assertEquals("${spec.id}: full column ignored", before, g.state.value)
            // Out-of-range columns are ignored too.
            g.dropDisc(spec.columns)
            g.dropDisc(-1)
            assertEquals(before, g.state.value)
        }
    }

    // --- resume ---------------------------------------------------------------------------------

    @Test
    fun `a snapshot round trips on every size and keeps the size for Play Again`() {
        for (spec in specs) {
            val a = newGame(spec)
            listOf(3, 4, 3, 2).forEach { a.dropDisc(it) }
            val snap = a.snapshot()!!

            val b = ConnectFourGame()
            b.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, twoHumans, 0, LocalPassAndPlayTransport()))
            assertTrue("${spec.id}: restore", b.restore(snap))
            assertEquals(a.state.value, b.state.value)
            assertEquals(spec, b.boardSpec)

            b.playAgain() // deals a fresh board of the restored size
            val fresh = b.state.value!!
            assertEquals(spec, fresh.spec)
            assertEquals(spec.cellCount, fresh.cells.size)
        }
    }

    @Test
    fun `a snapshot with bad dimensions or a mismatched cell count is rejected`() {
        val wide = newGame(ConnectFourBoardSpec.WIDE).also { it.dropDisc(1) }.snapshot()!!
        val big = newGame(ConnectFourBoardSpec.BIG).also { it.dropDisc(1) }.snapshot()!!
        val target = ConnectFourGame()
        target.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, twoHumans, 0, LocalPassAndPlayTransport()))

        assertTrue(wide.contains("\"rows\":7"))
        assertFalse("unknown height", target.restore(wide.replace("\"rows\":7", "\"rows\":6")))
        assertTrue(big.contains("\"winLength\":5"))
        assertFalse("unknown win length", target.restore(big.replace("\"winLength\":5", "\"winLength\":4")))
        assertTrue(big.contains("\"rows\":7,\"cols\":9,\"winLength\":5"))
        assertFalse(
            "classic dimensions over a big board's 63 cells",
            target.restore(big.replace("\"rows\":7,\"cols\":9,\"winLength\":5", "\"rows\":6,\"cols\":7,\"winLength\":4"))
        )
        assertNull("nothing was loaded", target.state.value)
        assertTrue("an untouched snapshot still loads", target.restore(wide))
    }

    @Test
    fun `a classic snapshot carries no dimensions yet restores as classic, as an old save does`() {
        val a = newGame(ConnectFourBoardSpec.CLASSIC).also { it.dropDisc(3) }
        val snap = a.snapshot()!!
        val b = ConnectFourGame()
        b.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, twoHumans, 0, LocalPassAndPlayTransport()))
        assertTrue(b.restore(snap))
        assertEquals(ConnectFourBoardSpec.CLASSIC, b.state.value!!.spec)
        assertEquals(42, b.state.value!!.cells.size)
    }

    // --- network --------------------------------------------------------------------------------

    @Test
    fun `an old peer's state without dimensions is the classic board, and a classic state adds nothing to the wire`() {
        val json = Json.encodeToString(ConnectFourNetState.serializer(), C4TestKit.net())
        assertFalse(json.contains("columns"))
        val decoded = Json.decodeFromString(ConnectFourNetState.serializer(), json)
        assertEquals(7, decoded.columns)
        assertEquals(6, decoded.rows)
        assertEquals(4, decoded.winLength)
    }

    @Test
    fun `a guest accepts a known size and rejects a state whose cell count does not match`() {
        val guest = C4TestKit.node(1)
        val wins = mapOf("p0" to 0, "p1" to 0)
        val bad = ConnectFourNetState(List(42) { null }, 1, "bad", false, null, null, wins, 0, columns = 9, rows = 7, winLength = 5)
        guest.transport.inject("p0", ConnectFourNetMessage.StateSync(1, bad))
        assertNull("42 cells do not fill 9 x 7", guest.game.state.value)
        val unknown = ConnectFourNetState(List(49) { null }, 1, "unknown", false, null, null, wins, 0, columns = 7, rows = 7, winLength = 4)
        guest.transport.inject("p0", ConnectFourNetMessage.StateSync(2, unknown))
        assertNull(guest.game.state.value)

        val good = ConnectFourNetState(List(63) { null }, 1, "big", false, null, null, wins, 0, columns = 9, rows = 7, winLength = 5)
        guest.transport.inject("p0", ConnectFourNetMessage.StateSync(3, good))
        val s = guest.game.state.value!!
        assertEquals(ConnectFourBoardSpec.BIG, s.spec)
        assertEquals("big", s.lastAction)
    }

    @Test
    fun `a networked host always deals the classic board and syncs its dimensions`() {
        val host = C4TestKit.node(0)
        host.game.boardSpec = ConnectFourBoardSpec.BIG
        host.game.startMatch()
        assertEquals(ConnectFourBoardSpec.CLASSIC, host.game.state.value!!.spec)
        val sync = host.transport.sent.last { it.message is ConnectFourNetMessage.StateSync }.stateSync
        assertEquals(42, sync.state.cells.size)
        assertEquals(7, sync.state.columns)
    }

    // --- bot ------------------------------------------------------------------------------------

    @Test
    fun `the bot takes an immediate win on every size`() {
        for (spec in specs) {
            val g = newGame(spec, humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
            g.difficulty = CpuDifficulty.EASY
            val cells = (0 until spec.winLength - 1).associate { idx(spec, spec.rows - 1, it) to 1 }
            setBoard(g, cells, mover = 1)
            g.playBotTurn()
            val s = g.state.value!!
            assertTrue("${spec.id}: the bot must win at once", s.boardOver)
            assertEquals("cpu", s.winnerPlayerId)
        }
    }

    @Test
    fun `the bot blocks an immediate loss on every size`() {
        for (spec in specs) {
            val g = newGame(spec, humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
            g.difficulty = CpuDifficulty.EASY
            val cells = (0 until spec.winLength - 1).associate { idx(spec, spec.rows - 1, it) to 0 }
            setBoard(g, cells, mover = 1)
            g.playBotTurn()
            val s = g.state.value!!
            assertEquals("${spec.id}: the bot must block", 1, s.cells[idx(spec, spec.rows - 1, spec.winLength - 1)])
            assertFalse(s.boardOver)
        }
    }

    @Test
    fun `the off-thread bot and hint play the same legal moves`() = runBlocking {
        for (spec in specs) {
            val g = newGame(spec, humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
            g.difficulty = CpuDifficulty.EASY

            val hint = g.hintAsync()
            assertNotNull(hint)
            assertEquals(hint, g.hintColumn.value)

            g.dropDisc(spec.columns / 2) // the human moves, the bot is now on turn
            g.playBotTurnAsync()
            val s = g.state.value!!
            assertEquals("${spec.id}: the bot replied", 2, s.cells.count { it != null })
            assertEquals(0, s.currentPlayerIndex)
        }
    }

    @Test
    fun `the hardest bot stays within a second or so even on the biggest board`() {
        for (spec in listOf(ConnectFourBoardSpec.WIDE, ConnectFourBoardSpec.BIG)) {
            val g = newGame(spec, humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
            g.difficulty = CpuDifficulty.HARD
            g.state.value = g.state.value!!.copy(currentPlayerIndex = 1)
            val start = System.nanoTime()
            g.playBotTurn()
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue("${spec.id}: HARD took ${ms}ms", ms < 3000)
            assertEquals(1, g.state.value!!.cells.count { it != null })
        }
    }

    @Test
    fun `a stale background answer is dropped when the position changed`() = runBlocking {
        val g = newGame(ConnectFourBoardSpec.WIDE, humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
        g.difficulty = CpuDifficulty.EASY
        g.dropDisc(3)               // the bot is on turn
        val moved = g.state.value!!
        g.undo()                    // takes the human drop back before the bot ever answers
        g.playBotTurnAsync()        // not the bot's turn any more: a no-op
        assertEquals(0, g.state.value!!.cells.count { it != null })
        assertTrue(moved.cells.count { it != null } == 1)
    }
}
