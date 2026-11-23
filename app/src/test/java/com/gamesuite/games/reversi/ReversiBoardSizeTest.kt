package com.gamesuite.games.reversi

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.HubOption
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Board sizes 6x6 / 8x8 / 10x10: rules, game flow, save/restore, networking, the Hub option and the bot. */
class ReversiBoardSizeTest {

    private val sizes = listOf(6, 8, 10)

    private val allDirections = listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)

    // ---- helpers ------------------------------------------------------------------------------

    private fun newGame(size: Int, vsBot: Boolean = false): ReversiGame {
        val g = ReversiGame()
        g.boardSize = size
        val roster = if (vsBot) {
            listOf(PlayerInfo("p1", "You"), PlayerInfo("bot1", "CPU", isBot = true))
        } else {
            listOf(PlayerInfo("p1", "Player 1"), PlayerInfo("p2", "Player 2"))
        }
        val mode = if (vsBot) PlayMode.SINGLE_PLAYER_VS_BOT else PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
        g.init(GameContext(mode, roster, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        return g
    }

    private fun emptyCells(n: Int): MutableList<Int?> = MutableList<Int?>(n * n) { null }

    /** A hand-built snapshot in the engine's own save format. [discs] maps cell index to owner. */
    private fun snapshotJson(
        size: Int,
        discs: Map<Int, Int>,
        current: Int,
        vsBot: Boolean = false,
        includeSize: Boolean = true
    ): String {
        val cells = (0 until size * size).joinToString(",") { discs[it]?.toString() ?: "null" }
        val secondPlayer = if (vsBot) {
            "{\"playerId\":\"bot1\",\"displayName\":\"CPU\",\"isBot\":true}"
        } else {
            "{\"playerId\":\"p2\",\"displayName\":\"Player 2\",\"isBot\":false}"
        }
        val secondId = if (vsBot) "bot1" else "p2"
        val sizePart = if (includeSize) ",\"size\":$size" else ""
        return "{\"cells\":[" + cells + "]," +
            "\"players\":[{\"playerId\":\"p1\",\"displayName\":\"Player 1\",\"isBot\":false}," + secondPlayer + "]," +
            "\"currentPlayerIndex\":" + current + ",\"lastAction\":\"test\",\"justPassed\":false,\"boardOver\":false," +
            "\"winnerPlayerId\":null,\"sessionWins\":{\"p1\":0,\"" + secondId + "\":0},\"sessionDraws\":0," +
            "\"nextStartingPlayerIndex\":0,\"difficulty\":\"MEDIUM\"" + sizePart + "}"
    }

    private fun cellOf(n: Int, row: Int, col: Int) = row * n + col

    private fun inBoard(n: Int, row: Int, col: Int) = row in 0 until n && col in 0 until n

    /** Plays the lowest-numbered legal cell until the board ends; fails if it never does. */
    private fun playToTheEnd(g: ReversiGame) {
        var guard = 0
        while (!g.state.value!!.boardOver && guard++ < 1000) {
            g.placeDisc(g.state.value!!.legalMoves.min())
        }
        assertTrue("the board must finish", g.state.value!!.boardOver)
    }

    /**
     * Player 0 plays [target]; in every direction whose second cell is on the board, an opponent
     * disc sits next to it and player 0's own disc behind that. Returns how many directions flank.
     */
    private fun checkEveryFlankingDirection(n: Int, targetRow: Int, targetCol: Int): Int {
        val engine = ReversiEngine.of(n)
        val cells = emptyCells(n)
        var flanking = 0
        for ((dr, dc) in allDirections) {
            if (!inBoard(n, targetRow + 2 * dr, targetCol + 2 * dc)) continue
            cells[cellOf(n, targetRow + dr, targetCol + dc)] = 1
            cells[cellOf(n, targetRow + 2 * dr, targetCol + 2 * dc)] = 0
            flanking++
        }
        val target = cellOf(n, targetRow, targetCol)
        assertTrue("n=$n target=($targetRow,$targetCol) must be legal", engine.isLegalMove(cells, target, 0))
        assertTrue(target in engine.legalMovesFor(cells, 0))
        assertFalse("the opponent cannot play there: no flank for them", engine.isLegalMove(cells, target, 1))

        val after = engine.applyMove(cells, target, 0)
        assertEquals("no opponent disc is left", 0, after.count { it == 1 })
        assertEquals("target + $flanking flipped + $flanking anchors", 1 + 2 * flanking, after.count { it == 0 })
        for ((dr, dc) in allDirections) {
            if (!inBoard(n, targetRow + 2 * dr, targetCol + 2 * dc)) continue
            assertEquals(0, after[cellOf(n, targetRow + dr, targetCol + dc)])
        }
        return flanking
    }

    // ---- initial position ---------------------------------------------------------------------

    @Test
    fun `the opening position is the four centre discs on every size`() {
        for (n in sizes) {
            val g = newGame(n)
            g.startMatch()
            val s = g.state.value!!
            val h = n / 2
            assertEquals(n, s.size)
            assertEquals(n * n, s.cells.size)
            // Player 1 holds the main diagonal of the centre, player 0 (who moves first) the other.
            assertEquals(1, s.cells[cellOf(n, h - 1, h - 1)])
            assertEquals(1, s.cells[cellOf(n, h, h)])
            assertEquals(0, s.cells[cellOf(n, h - 1, h)])
            assertEquals(0, s.cells[cellOf(n, h, h - 1)])
            assertEquals(4, s.cells.count { it != null })
            assertEquals(listOf(2, 2), s.scores)
            assertEquals(0, s.currentPlayerIndex)
            val expected = setOf(
                cellOf(n, h - 2, h - 1), cellOf(n, h - 1, h - 2), cellOf(n, h, h + 1), cellOf(n, h + 1, h)
            )
            assertEquals("n=$n", expected, s.legalMoves)
        }
    }

    @Test
    fun `the standard size is the default and matches the classic 8x8 indices`() {
        val g = ReversiGame()
        assertEquals(8, g.boardSize)
        val p = newGame(8)
        p.startMatch()
        val s = p.state.value!!
        assertEquals(64, s.cells.size)
        assertEquals(1, s.cells[27]); assertEquals(1, s.cells[36])
        assertEquals(0, s.cells[28]); assertEquals(0, s.cells[35])
        assertEquals(setOf(19, 26, 37, 44), s.legalMoves)
    }

    @Test
    fun `an unsupported size falls back to standard`() {
        for (bad in listOf(0, 4, 7, 9, 12, -8)) {
            val g = ReversiGame()
            g.boardSize = bad
            assertEquals(8, g.boardSize)
        }
        assertEquals(6, ReversiBoardSizes.fromOptionId("small"))
        assertEquals(8, ReversiBoardSizes.fromOptionId("standard"))
        assertEquals(10, ReversiBoardSizes.fromOptionId("large"))
        assertEquals(8, ReversiBoardSizes.fromOptionId(null))
        assertEquals(8, ReversiBoardSizes.fromOptionId("huge"))
    }

    // ---- legal moves and flipping -------------------------------------------------------------

    @Test
    fun `flanking works in all eight directions on every size`() {
        for (n in sizes) {
            val h = n / 2
            assertEquals("centre of n=$n flanks all 8 ways", 8, checkEveryFlankingDirection(n, h, h))
        }
    }

    @Test
    fun `flanking works from every corner and edge cell on every size`() {
        for (n in sizes) {
            val last = n - 1
            val h = n / 2
            for ((r, c) in listOf(0 to 0, 0 to last, last to 0, last to last)) {
                assertEquals("corner ($r,$c) of n=$n flanks 3 ways", 3, checkEveryFlankingDirection(n, r, c))
            }
            for ((r, c) in listOf(0 to h, last to h, h to 0, h to last)) {
                assertEquals("edge ($r,$c) of n=$n flanks 5 ways", 5, checkEveryFlankingDirection(n, r, c))
            }
        }
    }

    @Test
    fun `a line that runs off the board never wraps to the next row`() {
        for (n in sizes) {
            val engine = ReversiEngine.of(n)
            // Right edge: opponent at the end of row 1, player 0's disc at the START of row 2.
            val right = emptyCells(n)
            right[cellOf(n, 1, n - 1)] = 1
            right[cellOf(n, 2, 0)] = 0
            assertFalse("n=$n right wrap", engine.isLegalMove(right, cellOf(n, 1, n - 2), 0))
            // Left edge: opponent at the start of row 2, player 0's disc at the END of row 1.
            val left = emptyCells(n)
            left[cellOf(n, 2, 0)] = 1
            left[cellOf(n, 1, n - 1)] = 0
            assertFalse("n=$n left wrap", engine.isLegalMove(left, cellOf(n, 2, 1), 0))
        }
    }

    @Test
    fun `an occupied cell and a cell with no flank are not legal`() {
        for (n in sizes) {
            val engine = ReversiEngine.of(n)
            val cells = engine.openingCells(0)
            assertFalse(engine.isLegalMove(cells, cellOf(n, n / 2, n / 2), 0))
            assertFalse(engine.isLegalMove(cells, 0, 0))
            assertFalse(engine.isLegalMove(cells, -1, 0))
            assertFalse(engine.isLegalMove(cells, n * n, 0))
        }
    }

    @Test
    fun `a first move flips exactly one disc and hands over the turn on every size`() {
        for (n in sizes) {
            val g = newGame(n)
            g.startMatch()
            val move = g.state.value!!.legalMoves.min()
            g.placeDisc(move)
            val s = g.state.value!!
            assertEquals(1, s.currentPlayerIndex)
            assertEquals(listOf(4, 1), s.scores)
            assertEquals(0, s.cells[move])
            assertEquals(n, s.size)
        }
    }

    // ---- passes, endings ----------------------------------------------------------------------

    @Test
    fun `a pass hands the turn back and a wipe-out ends the board on every size`() {
        for (n in sizes) {
            val last = n - 1
            val g = newGame(n)
            val discs = mapOf(
                cellOf(n, 0, 0) to 0, cellOf(n, 0, 1) to 1,
                cellOf(n, last, 0) to 0, cellOf(n, last, 1) to 1
            )
            assertTrue(g.restore(snapshotJson(n, discs, current = 0)))

            g.placeDisc(cellOf(n, 0, 2))
            val passed = g.state.value!!
            assertTrue("n=$n seat 1 has no move", passed.justPassed)
            assertEquals(0, passed.currentPlayerIndex)
            assertFalse(passed.boardOver)
            assertTrue(cellOf(n, last, 2) in passed.legalMoves)

            g.placeDisc(cellOf(n, last, 2))
            val done = g.state.value!!
            assertTrue(done.boardOver)
            assertEquals("p1", done.winnerPlayerId)
            assertEquals(listOf(6, 0), done.scores)
            assertEquals(emptySet<Int>(), done.legalMoves)
            assertEquals(1, g.sessionWins.value["p1"])
            assertEquals(n, done.size)
        }
    }

    @Test
    fun `a full game ends with a consistent winner on every size`() {
        for (n in sizes) {
            val g = newGame(n)
            g.startMatch()
            playToTheEnd(g)
            val s = g.state.value!!
            assertEquals(n * n, s.cells.size)
            assertEquals(s.cells.count { it == 0 }, s.scores[0])
            assertEquals(s.cells.count { it == 1 }, s.scores[1])
            assertTrue(s.scores.sum() <= n * n)
            val expectedWinner = when {
                s.scores[0] > s.scores[1] -> "p1"
                s.scores[1] > s.scores[0] -> "p2"
                else -> null
            }
            assertEquals("n=$n", expectedWinner, s.winnerPlayerId)
            // Neither side can move at the end.
            val engine = ReversiEngine.of(n)
            assertTrue(engine.legalMovesFor(s.cells, 0).isEmpty())
            assertTrue(engine.legalMovesFor(s.cells, 1).isEmpty())
            // Play Again deals a fresh board of the same size.
            g.playAgain()
            assertEquals(n, g.state.value!!.size)
            assertEquals(4, g.state.value!!.cells.count { it != null })
        }
    }

    // ---- snapshot -----------------------------------------------------------------------------

    @Test
    fun `a snapshot round-trips on every size and carries the size`() {
        for (n in sizes) {
            val a = newGame(n)
            a.startMatch()
            repeat(3) { a.placeDisc(a.state.value!!.legalMoves.min()) }
            val before = a.state.value!!
            val saved = a.snapshot()
            assertNotNull(saved)
            if (n != 8) assertTrue("n=$n size is saved", saved!!.contains("\"size\":$n"))

            // The receiving game was built at the default size: the save decides.
            val b = newGame(8)
            assertTrue(b.restore(saved!!))
            val after = b.state.value!!
            assertEquals(n, b.boardSize)
            assertEquals(n, after.size)
            assertEquals(before.cells, after.cells)
            assertEquals(before.legalMoves, after.legalMoves)
            assertEquals(before.scores, after.scores)
            assertEquals(before.currentPlayerIndex, after.currentPlayerIndex)
            assertFalse(b.canUndo)

            // It stays playable and the next board keeps the size.
            b.placeDisc(after.legalMoves.min())
            assertEquals(n, b.state.value!!.size)
            playToTheEnd(b)
            b.playAgain()
            assertEquals(n * n, b.state.value!!.cells.size)
        }
    }

    @Test
    fun `undo works on a non-standard size`() {
        for (n in listOf(6, 10)) {
            val g = newGame(n)
            g.startMatch()
            val start = g.state.value!!
            g.placeDisc(start.legalMoves.min())
            assertTrue(g.canUndo)
            g.undo()
            assertEquals(start.cells, g.state.value!!.cells)
            assertEquals(n, g.state.value!!.size)
        }
    }

    @Test
    fun `an old save without a size restores as 8x8`() {
        val g = newGame(8)
        g.startMatch()
        g.placeDisc(g.state.value!!.legalMoves.min())
        val saved = g.snapshot()!!
        assertFalse("standard saves keep the old format", saved.contains("\"size\""))

        val b = newGame(6) // launched small, but the save is a standard board
        assertTrue(b.restore(saved))
        assertEquals(8, b.state.value!!.size)
        assertEquals(64, b.state.value!!.cells.size)
        assertEquals(8, b.boardSize)

        val hand = newGame(10)
        assertTrue(hand.restore(snapshotJson(8, mapOf(27 to 1, 28 to 0), current = 0, includeSize = false)))
        assertEquals(8, hand.state.value!!.size)
    }

    @Test
    fun `a restore with a bad size or a cell count that does not match is rejected`() {
        val a = newGame(6)
        a.startMatch()
        val valid = a.snapshot()!!
        assertTrue(valid.contains("\"size\":6"))

        val bad = listOf(
            valid.replace("\"size\":6", "\"size\":10"),   // 36 cells claiming 10x10
            valid.replace("\"size\":6", "\"size\":8"),    // 36 cells claiming 8x8
            valid.replace("\"size\":6", "\"size\":7"),    // unsupported size
            valid.replace("\"size\":6", "\"size\":0"),
            valid.replace("\"size\":6", "\"size\":-6"),
            valid.replace(",\"size\":6", "")              // size missing means 8x8, but 36 cells
        )
        for (text in bad) {
            assertNotEquals(valid, text)
            val g = newGame(8)
            assertFalse(g.restore(text))
            assertNull("a rejected restore leaves no board", g.state.value)
        }

        val standard = newGame(8)
        standard.startMatch()
        val eight = standard.snapshot()!!
        val claimsSix = eight.dropLast(1) + ",\"size\":6}" // 64 cells claiming 6x6
        val g = newGame(8)
        assertFalse(g.restore(claimsSix))
        assertNull(g.state.value)

        // A size the snapshot never mentions cannot sneak a short or long cell list in either.
        val ok = snapshotJson(8, mapOf(27 to 1, 28 to 0), current = 0)
        assertTrue(newGame(8).restore(ok))
        val short = ok.replaceFirst("null,", "") // the cell list now has 63 entries
        assertNotEquals(ok, short)
        assertFalse(newGame(8).restore(short))
    }

    // ---- networking ---------------------------------------------------------------------------

    @Test
    fun `a guest adopts the size the host deals and plays on it`() {
        val hub = ReversiHub()
        val host = ReversiTestKit.node(0, PlayMode.LOCAL_AD_HOC, hub.endpoint(ReversiTestKit.HOST))
        host.game.boardSize = 6
        host.game.startMatch()
        val guest = ReversiTestKit.node(1, PlayMode.LOCAL_AD_HOC, hub.endpoint(ReversiTestKit.GUEST))

        val guestView = guest.game.state.value!!
        assertEquals(6, guestView.size)
        assertEquals(36, guestView.cells.size)
        assertEquals(host.game.state.value!!.legalMoves, guestView.legalMoves)

        host.game.placeDisc(host.game.state.value!!.legalMoves.min())
        guest.game.placeDisc(guest.game.state.value!!.legalMoves.min())
        val hostView = host.game.state.value!!
        assertEquals(6, hostView.size)
        assertEquals("the guest's move was applied on the 6x6 board", 6, hostView.cells.count { it != null })
        assertEquals(hostView.cells, guest.game.state.value!!.cells)
    }

    @Test
    fun `a guest rejects a host state whose size does not match its cells`() {
        val hub = ReversiHub()
        val host = ReversiTestKit.node(0, PlayMode.LOCAL_AD_HOC, hub.endpoint(ReversiTestKit.HOST), start = true)
        val guest = ReversiTestKit.node(1, PlayMode.LOCAL_AD_HOC, hub.endpoint(ReversiTestKit.GUEST))
        val adopted = guest.game.state.value!!
        assertEquals(8, adopted.size)

        val base = ReversiTestKit.netState()
        var version = 1000
        for (forged in listOf(base.copy(size = 6), base.copy(size = 10), base.copy(size = 7), base.copy(size = 0))) {
            guest.transport.inject(ReversiTestKit.HOST, ReversiNetMessage.StateSync(version++, forged))
            assertSame("a forged size changes nothing", adopted, guest.game.state.value)
        }

        val big = ReversiEngine.of(10).openingCells(0)
        guest.transport.inject(
            ReversiTestKit.HOST,
            ReversiNetMessage.StateSync(version++, ReversiTestKit.netState(cells = big).copy(size = 10))
        )
        assertEquals(10, guest.game.state.value!!.size)
        assertEquals(100, guest.game.state.value!!.cells.size)
        assertEquals(10, guest.game.boardSize)
        assertTrue(host.game.state.value != null)
    }

    @Test
    fun `a network state without a size field means 8x8`() {
        val text = Json.encodeToString(ReversiNetState.serializer(), ReversiTestKit.netState())
        assertFalse("the default size is not written", text.contains("\"size\""))
        assertEquals(8, Json.decodeFromString(ReversiNetState.serializer(), text).size)
        val six = Json.encodeToString(ReversiNetState.serializer(), ReversiTestKit.netState().copy(size = 6))
        assertEquals(6, Json.decodeFromString(ReversiNetState.serializer(), six).size)
    }

    // ---- Hub option ---------------------------------------------------------------------------

    @Test
    fun `the Hub offers a board size choice on vs Computer and Pass and Play only`() {
        val spec = GameHubSpecs.get("reversi")
        for (modeId in listOf("cpu", "pass")) {
            val mode = spec.modes.first { it.id == modeId }
            val option = mode.options.filterIsInstance<HubOption.Choice>().firstOrNull { it.key == "board" }
            assertNotNull("mode $modeId has the board option", option)
            assertEquals("standard", option!!.default)
            assertEquals(listOf("small", "standard", "large"), option.choices.map { it.id })
        }
        for (mode in spec.modes.filter { it.id != "cpu" && it.id != "pass" }) {
            assertTrue(mode.options.none { it.key == "board" })
        }
    }

    // ---- bot ----------------------------------------------------------------------------------

    @Test
    fun `search depth scales down on the large board`() {
        for (d in CpuDifficulty.values()) {
            assertTrue(reversiSearchDepth(10, d) <= reversiSearchDepth(8, d))
            assertEquals(reversiSearchDepth(8, d), reversiSearchDepth(6, d))
        }
        assertEquals(2, reversiSearchDepth(8, CpuDifficulty.EASY))
        assertEquals(4, reversiSearchDepth(8, CpuDifficulty.MEDIUM))
        assertEquals(6, reversiSearchDepth(8, CpuDifficulty.HARD))
        assertTrue(reversiSearchDepth(10, CpuDifficulty.HARD) <= 4)
    }

    /** Bot (seat 1) can take the corner (0,0) or the X-square (1,1); both are legal, nothing else is. */
    private fun cornerOffer(n: Int): String = snapshotJson(
        n,
        mapOf(
            cellOf(n, 0, 1) to 0, cellOf(n, 0, 2) to 1,
            cellOf(n, 2, 2) to 0, cellOf(n, 3, 3) to 1
        ),
        current = 1,
        vsBot = true
    )

    @Test
    fun `the bot prefers a corner to the X-square beside it on every size`() {
        // One-ply search plus the positional table: the corner scores 140, the X-square 24
        // (hand-computed; the numbers do not depend on the board size).
        for (n in sizes) {
            val engine = ReversiEngine.of(n)
            val cells = emptyCells(n)
            cells[cellOf(n, 0, 1)] = 0
            cells[cellOf(n, 0, 2)] = 1
            cells[cellOf(n, 2, 2)] = 0
            cells[cellOf(n, 3, 3)] = 1
            val legal = engine.legalMovesFor(cells, 1)
            assertEquals(setOf(cellOf(n, 0, 0), cellOf(n, 1, 1)), legal)
            assertEquals("n=$n", cellOf(n, 0, 0), engine.chooseMove(cells, legal, bot = 1, depth = 1))
        }
    }

    @Test
    fun `the bot plays the corner when it is the move on every size and difficulty`() {
        for (n in sizes) {
            for (difficulty in CpuDifficulty.values()) {
                val g = newGame(n, vsBot = true)
                val offer = snapshotJson(n, mapOf(cellOf(n, 0, 1) to 0, cellOf(n, 0, 2) to 1), current = 1, vsBot = true)
                assertTrue(g.restore(offer))
                assertEquals(setOf(0), g.state.value!!.legalMoves)
                g.difficulty = difficulty // restore() takes the saved difficulty; pin the one under test
                g.playBotTurn()
                val s = g.state.value!!
                assertEquals("n=$n $difficulty", 1, s.cells[0])
                assertEquals(1, s.cells[1])
                assertTrue("the human has no disc left: the board is over", s.boardOver)
                assertEquals("bot1", s.winnerPlayerId)
            }
        }
    }

    @Test
    fun `the off-thread bot plays the same move as the synchronous one`() {
        for (n in sizes) {
            val sync = newGame(n, vsBot = true)
            assertTrue(sync.restore(cornerOffer(n)))
            sync.difficulty = CpuDifficulty.EASY
            sync.playBotTurn()

            val async = newGame(n, vsBot = true)
            assertTrue(async.restore(cornerOffer(n)))
            async.difficulty = CpuDifficulty.EASY
            runBlocking { async.playBotTurnAsync() }

            assertEquals("n=$n", sync.state.value!!.cells, async.state.value!!.cells)
        }
    }

    @Test
    fun `the bot never moves for a human and an async hint is legal and leaves the position alone`() {
        for (n in sizes) {
            val g = newGame(n, vsBot = true)
            g.startMatch()
            val s = g.state.value!!
            runBlocking { g.playBotTurnAsync() }
            assertSame("it is the human's turn: nothing happens", s, g.state.value)

            val hint = runBlocking { g.requestHint() }
            assertNotNull(hint)
            assertTrue(hint!! in s.legalMoves)
            assertEquals(hint, g.hintMove.value)
            assertSame(s, g.state.value)
        }
    }

    @Test
    fun `the bot finishes a whole game on every size`() {
        for (n in sizes) {
            val g = newGame(n, vsBot = true)
            g.difficulty = CpuDifficulty.EASY
            g.startMatch()
            var guard = 0
            while (!g.state.value!!.boardOver && guard++ < 1000) {
                val s = g.state.value!!
                if (s.players[s.currentPlayerIndex].isBot) g.playBotTurn() else g.placeDisc(s.legalMoves.min())
            }
            assertTrue("n=$n", g.state.value!!.boardOver)
        }
    }
}
