package com.gamesuite.games.dotsandboxes

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.HubLaunch
import com.gamesuite.core.HubOption
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.core.buildHubLaunch
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Board sizes (3x3, 4x4, 5x5, 6x6) for Dots and Boxes: engine, bot, hint, resume and network checks. */
class DotsAndBoxesGridSizeTest {

    private val twoHumans = listOf(PlayerInfo("p1", "Player 1"), PlayerInfo("p2", "Player 2"))
    private val humanAndCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))
    private val twoBots = listOf(PlayerInfo("b1", "Bot 1", isBot = true), PlayerInfo("b2", "Bot 2", isBot = true))

    private fun newGame(
        grid: DotsAndBoxesGrid,
        players: List<PlayerInfo> = twoHumans,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
    ): DotsAndBoxesGame {
        val g = DotsAndBoxesGame()
        g.grid = grid
        g.init(GameContext(mode, players, 0, LocalPassAndPlayTransport()))
        return g
    }

    private fun started(grid: DotsAndBoxesGrid, players: List<PlayerInfo> = twoHumans, mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) =
        newGame(grid, players, mode).also { it.startMatch() }

    // ---- hand-built snapshots ------------------------------------------------------------------

    private fun rawSnapshot(
        rows: Int,
        cols: Int,
        h: List<Boolean>,
        v: List<Boolean>,
        owner: List<Int?>,
        scores: List<Int>,
        current: Int,
        bots: Boolean,
        gridId: String?,
        difficulty: String = "MEDIUM"
    ): String {
        val players = if (bots) {
            "[{\"playerId\":\"you\",\"displayName\":\"You\",\"isBot\":false},{\"playerId\":\"cpu\",\"displayName\":\"CPU\",\"isBot\":true}]"
        } else {
            "[{\"playerId\":\"p1\",\"displayName\":\"Player 1\",\"isBot\":false},{\"playerId\":\"p2\",\"displayName\":\"Player 2\",\"isBot\":false}]"
        }
        val wins = if (bots) "{\"you\":0,\"cpu\":0}" else "{\"p1\":0,\"p2\":0}"
        val gridPart = if (gridId == null) "" else ",\"grid\":\"$gridId\""
        return "{\"boxRows\":$rows,\"boxCols\":$cols" +
            ",\"horizontalEdges\":${h.joinToString(",", "[", "]")}" +
            ",\"verticalEdges\":${v.joinToString(",", "[", "]")}" +
            ",\"boxOwner\":${owner.joinToString(",", "[", "]") { it?.toString() ?: "null" }}" +
            ",\"players\":$players" +
            ",\"currentPlayerIndex\":$current" +
            ",\"scores\":${scores.joinToString(",", "[", "]")}" +
            ",\"lastAction\":\"Game started\",\"boardOver\":false,\"winnerPlayerId\":null" +
            ",\"sessionWins\":$wins,\"sessionDraws\":0,\"nextStartingPlayerIndex\":0" +
            ",\"difficulty\":\"$difficulty\"$gridPart}"
    }

    /** A position on [grid] with the given edge indices drawn and nobody owning a box. */
    private fun position(
        grid: DotsAndBoxesGrid,
        drawnH: Set<Int> = emptySet(),
        drawnV: Set<Int> = emptySet(),
        current: Int = 0,
        bots: Boolean = false,
        difficulty: String = "MEDIUM"
    ): String = rawSnapshot(
        rows = grid.boxRows, cols = grid.boxCols,
        h = List(grid.horizontalEdgeCount) { it in drawnH },
        v = List(grid.verticalEdgeCount) { it in drawnV },
        owner = List(grid.boxCount) { null },
        scores = listOf(0, 0), current = current, bots = bots, gridId = grid.id, difficulty = difficulty
    )

    /** Box (0,0) has its top, bottom and left sides drawn; V(0,1) is the one edge that completes it. */
    private fun threeSided(grid: DotsAndBoxesGrid, current: Int, bots: Boolean, difficulty: String = "MEDIUM") =
        position(grid, drawnH = setOf(0, grid.boxCols), drawnV = setOf(0), current = current, bots = bots, difficulty = difficulty)

    /** Box (0,0) has top and left drawn: a third side anywhere around it would hand a box over. */
    private fun twoSided(grid: DotsAndBoxesGrid, current: Int, bots: Boolean, difficulty: String) =
        position(grid, drawnH = setOf(0), drawnV = setOf(0), current = current, bots = bots, difficulty = difficulty)

    /**
     * Every edge drawn except H(0,0), the top of box 0, which only bordering box 0. Boxes 1..[p0Owns]
     * belong to player 0, the rest to player 1, and it is player 0's move: drawing H(0,0) finishes
     * the board and gives player 0 one more box.
     */
    private fun lastEdgeLeft(grid: DotsAndBoxesGrid, p0Owns: Int): String {
        val n = grid.boxCount
        return rawSnapshot(
            rows = grid.boxRows, cols = grid.boxCols,
            h = List(grid.horizontalEdgeCount) { it != 0 },
            v = List(grid.verticalEdgeCount) { true },
            owner = List(n) { i -> if (i == 0) null else if (i <= p0Owns) 0 else 1 },
            scores = listOf(p0Owns, n - 1 - p0Owns), current = 0, bots = false, gridId = grid.id
        )
    }

    private fun sidesDrawn(h: List<Boolean>, v: List<Boolean>, cols: Int, r: Int, c: Int): Int {
        var n = 0
        if (h[r * cols + c]) n++
        if (h[(r + 1) * cols + c]) n++
        if (v[r * (cols + 1) + c]) n++
        if (v[r * (cols + 1) + c + 1]) n++
        return n
    }

    /** The most sides any unclaimed box would have after [edge] is drawn on [s]. */
    private fun maxSidesAfter(s: DotsAndBoxesState, edge: DotsAndBoxesHintEdge): Int {
        val h = s.horizontalEdges.toMutableList()
        val v = s.verticalEdges.toMutableList()
        if (edge.horizontal) h[edge.row * s.boxCols + edge.col] = true else v[edge.row * (s.boxCols + 1) + edge.col] = true
        var best = 0
        for (r in 0 until s.boxRows) for (c in 0 until s.boxCols) {
            if (s.boxOwner[r * s.boxCols + c] != null) continue
            val n = sidesDrawn(h, v, s.boxCols, r, c)
            if (n < 4 && n > best) best = n
        }
        return best
    }

    private fun drawAll(g: DotsAndBoxesGame, grid: DotsAndBoxesGrid) {
        for (r in 0..grid.boxRows) for (c in 0 until grid.boxCols) g.drawHorizontalEdge(r, c)
        for (r in 0 until grid.boxRows) for (c in 0..grid.boxCols) g.drawVerticalEdge(r, c)
    }

    // ---- the grid table ------------------------------------------------------------------------

    @Test
    fun `the four sizes have the expected dimensions and edge counts`() {
        assertEquals(listOf(3, 4, 5, 6), DotsAndBoxesGrid.values().map { it.boxRows })
        for (grid in DotsAndBoxesGrid.values()) {
            assertEquals(grid.boxRows, grid.boxCols)
            assertEquals((grid.boxRows + 1) * grid.boxCols, grid.horizontalEdgeCount)
            assertEquals(grid.boxRows * (grid.boxCols + 1), grid.verticalEdgeCount)
        }
        assertEquals(DotsAndBoxesGrid.STANDARD, DotsAndBoxesGrid.fromId(null))
        assertEquals(DotsAndBoxesGrid.STANDARD, DotsAndBoxesGrid.fromId("nonsense"))
        assertEquals(DotsAndBoxesGrid.LARGE, DotsAndBoxesGrid.fromId("large"))
        assertNull(DotsAndBoxesGrid.byIdOrNull("nonsense"))
    }

    @Test
    fun `the default game is the standard 5x5 board`() {
        val g = DotsAndBoxesGame()
        assertEquals(DotsAndBoxesGrid.STANDARD, g.grid)
        g.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, twoHumans, 0, LocalPassAndPlayTransport()))
        g.startMatch()
        val s = g.state.value!!
        assertEquals(5, s.boxRows)
        assertEquals(5, s.boxCols)
        assertEquals(25, s.totalBoxes)
    }

    @Test
    fun `a fresh board of each size is sized correctly and empty`() {
        for (grid in DotsAndBoxesGrid.values()) {
            val s = started(grid).state.value!!
            assertEquals(grid.id, grid.boxRows, s.boxRows)
            assertEquals(grid.id, grid.boxCols, s.boxCols)
            assertEquals(grid.id, grid.horizontalEdgeCount, s.horizontalEdges.size)
            assertEquals(grid.id, grid.verticalEdgeCount, s.verticalEdges.size)
            assertEquals(grid.id, grid.boxCount, s.boxOwner.size)
            assertTrue(s.horizontalEdges.none { it } && s.verticalEdges.none { it } && s.boxOwner.all { it == null })
        }
    }

    @Test
    fun `play again keeps the chosen board size`() {
        for (grid in DotsAndBoxesGrid.values()) {
            val g = started(grid)
            drawAll(g, grid)
            assertTrue(g.state.value!!.boardOver)
            g.playAgain()
            assertEquals(grid.boxCount, g.state.value!!.totalBoxes)
            assertFalse(g.state.value!!.boardOver)
        }
    }

    // ---- box completion and go-again -----------------------------------------------------------

    @Test
    fun `completing a box claims it and grants another turn on every size`() {
        for (grid in DotsAndBoxesGrid.values()) {
            val g = started(grid)
            g.drawHorizontalEdge(0, 0) // p1
            g.drawVerticalEdge(0, 0) // p2
            g.drawHorizontalEdge(1, 0) // p1
            assertEquals(grid.id, 1, g.state.value!!.currentPlayerIndex)
            g.drawVerticalEdge(0, 1) // p2 closes box (0,0)
            val s = g.state.value!!
            assertEquals(grid.id, 1, s.boxOwner[0])
            assertEquals(grid.id, listOf(0, 1), s.scores)
            assertEquals("go again on $grid", 1, s.currentPlayerIndex)
            assertNotNull(s.lastBoxCompleted)
            assertEquals(listOf(0), s.lastBoxCompleted!!.boxIndices)
        }
    }

    @Test
    fun `an edge shared by two boxes closes both at once on every size`() {
        for (grid in DotsAndBoxesGrid.values()) {
            val cols = grid.boxCols
            // Boxes (0,0) and (0,1) share V(0,1). Draw their other six sides, then the shared edge.
            val g = started(grid)
            val others = listOf(
                true to (0 to 0), true to (1 to 0), false to (0 to 0),
                true to (0 to 1), true to (1 to 1), false to (0 to 2)
            )
            for ((horizontal, rc) in others) {
                if (horizontal) g.drawHorizontalEdge(rc.first, rc.second) else g.drawVerticalEdge(rc.first, rc.second)
            }
            val before = g.state.value!!
            assertTrue(before.boxOwner.all { it == null })
            val mover = before.currentPlayerIndex
            g.drawVerticalEdge(0, 1)
            val s = g.state.value!!
            assertEquals(grid.id, mover, s.boxOwner[0])
            assertEquals(grid.id, mover, s.boxOwner[1])
            assertEquals(grid.id, 2, s.scores[mover])
            assertEquals(grid.id, mover, s.currentPlayerIndex)
            assertEquals(listOf(0, 1), s.lastBoxCompleted!!.boxIndices.sorted())
            assertTrue(cols >= 2)
        }
    }

    // ---- win / draw counts ---------------------------------------------------------------------

    @Test
    fun `playing out any board hands out every box and records exactly one result`() {
        for (grid in DotsAndBoxesGrid.values()) {
            val g = started(grid)
            drawAll(g, grid)
            val s = g.state.value!!
            assertTrue(grid.id, s.boardOver)
            assertTrue(grid.id, s.boxOwner.all { it != null })
            assertEquals(grid.id, grid.boxCount, s.scores.sum())
            assertEquals(grid.id, 1, g.sessionWins.value.values.sum() + g.sessionDraws.value)
        }
    }

    @Test
    fun `the last box can make player 0 win on every size`() {
        for (grid in DotsAndBoxesGrid.values()) {
            val g = newGame(grid)
            assertTrue(g.restore(lastEdgeLeft(grid, p0Owns = grid.boxCount - 1)))
            g.drawHorizontalEdge(0, 0)
            val s = g.state.value!!
            assertTrue(grid.id, s.boardOver)
            assertEquals(listOf(grid.boxCount, 0), s.scores)
            assertEquals("p1", s.winnerPlayerId)
            assertEquals(1, g.sessionWins.value["p1"])
            assertEquals(0, g.sessionWins.value["p2"])
            assertEquals(0, g.sessionDraws.value)
        }
    }

    @Test
    fun `the last box can still leave player 1 the winner on every size`() {
        for (grid in DotsAndBoxesGrid.values()) {
            val g = newGame(grid)
            assertTrue(g.restore(lastEdgeLeft(grid, p0Owns = 0)))
            g.drawHorizontalEdge(0, 0)
            val s = g.state.value!!
            assertTrue(s.boardOver)
            assertEquals(listOf(1, grid.boxCount - 1), s.scores)
            assertEquals("p2", s.winnerPlayerId)
            assertEquals(1, g.sessionWins.value["p2"])
            assertEquals(0, g.sessionWins.value["p1"])
            assertEquals(0, g.sessionDraws.value)
        }
    }

    @Test
    fun `an even box count can end in a draw and an odd one cannot`() {
        for (grid in DotsAndBoxesGrid.values()) {
            val n = grid.boxCount
            if (n % 2 == 0) {
                val g = newGame(grid)
                // p0 has n/2 - 1 before the last box, p1 has n/2: the last box makes it n/2 each.
                assertTrue(g.restore(lastEdgeLeft(grid, p0Owns = n / 2 - 1)))
                g.drawHorizontalEdge(0, 0)
                val s = g.state.value!!
                assertTrue(s.boardOver)
                assertEquals(listOf(n / 2, n / 2), s.scores)
                assertNull(s.winnerPlayerId)
                assertEquals(1, g.sessionDraws.value)
                assertEquals(0, g.sessionWins.value.values.sum())
            } else {
                // Every split of an odd count has a leader.
                for (p0Owns in 0 until n) {
                    val g = newGame(grid)
                    assertTrue(g.restore(lastEdgeLeft(grid, p0Owns)))
                    g.drawHorizontalEdge(0, 0)
                    assertNotNull("${grid.id} p0Owns=$p0Owns", g.state.value!!.winnerPlayerId)
                    assertEquals(0, g.sessionDraws.value)
                }
            }
        }
    }

    // ---- bot -----------------------------------------------------------------------------------

    @Test
    fun `the bot takes a box when one is available on every size`() {
        for (grid in DotsAndBoxesGrid.values()) for (diff in listOf(CpuDifficulty.MEDIUM, CpuDifficulty.HARD)) {
            val g = newGame(grid, humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
            assertTrue(g.restore(threeSided(grid, current = 1, bots = true)))
            g.difficulty = diff
            val s = g.state.value!!
            assertEquals(DotsAndBoxesHintEdge(false, 0, 1), g.computeBotMove(s))
            g.playBotTurn()
            val after = g.state.value!!
            assertEquals("${grid.id} $diff", 1, after.boxOwner[0])
            assertEquals(1, after.scores[1])
        }
    }

    @Test
    fun `the bot avoids giving a third side when it can on every size`() {
        for (grid in DotsAndBoxesGrid.values()) for (diff in listOf(CpuDifficulty.MEDIUM, CpuDifficulty.HARD)) {
            val g = newGame(grid, humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
            assertTrue(g.restore(twoSided(grid, current = 1, bots = true, difficulty = diff.name)))
            g.difficulty = diff
            val s = g.state.value!!
            repeat(40) {
                val move = g.computeBotMove(s)!!
                assertTrue("${grid.id} $diff gave a third side with $move", maxSidesAfter(s, move) < 3)
            }
        }
    }

    @Test
    fun `computing a bot move changes nothing and applying one needs an unchanged board`() {
        for (grid in DotsAndBoxesGrid.values()) {
            val text = threeSided(grid, current = 1, bots = true)
            val g = newGame(grid, humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
            assertTrue(g.restore(text))
            g.difficulty = CpuDifficulty.HARD
            val s = g.state.value!!
            val move = g.computeBotMove(s)!!
            assertSame("computing is pure", s, g.state.value)

            // The position is replaced (an undo, restart or leave) before the move lands: dropped.
            // (Restoring an equal snapshot straight over the old one would keep the same object --
            // Compose state skips equal writes -- so go through a different position first.)
            assertTrue(g.restore(twoSided(grid, current = 1, bots = true, difficulty = "MEDIUM")))
            assertTrue(g.restore(text))
            val replaced = g.state.value!!
            assertNotSame(s, replaced)
            assertFalse(g.applyBotMove(s, move))
            assertSame(replaced, g.state.value)

            // Same position: the move is applied exactly once.
            assertTrue(g.applyBotMove(replaced, move))
            assertFalse(g.applyBotMove(replaced, move))
            assertEquals(1, g.state.value!!.boxOwner[0])
        }
    }

    @Test
    fun `no bot move is computed on a human turn, a finished board or after the session ended`() {
        for (grid in DotsAndBoxesGrid.values()) {
            val g = newGame(grid, humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
            assertTrue(g.restore(threeSided(grid, current = 0, bots = true)))
            assertNull(g.computeBotMove(g.state.value!!))
            assertTrue(g.restore(threeSided(grid, current = 1, bots = true)))
            val s = g.state.value!!
            assertNotNull(g.computeBotMove(s))
            g.leaveSession()
            assertNull(g.computeBotMove(s))
            assertFalse(g.applyBotMove(s, DotsAndBoxesHintEdge(false, 0, 1)))
        }
    }

    @Test
    fun `two bots finish a whole board on every size and difficulty without a stall`() {
        for (grid in DotsAndBoxesGrid.values()) for (diff in CpuDifficulty.values()) {
            val g = newGame(grid, twoBots, PlayMode.SINGLE_PLAYER_VS_BOT)
            g.difficulty = diff
            g.startMatch()
            var moves = 0
            val edgeTotal = grid.horizontalEdgeCount + grid.verticalEdgeCount
            while (!g.state.value!!.boardOver) {
                val s = g.state.value!!
                val move = g.computeBotMove(s)
                assertNotNull("${grid.id} $diff stalled after $moves moves", move)
                assertTrue(g.applyBotMove(s, move!!))
                moves++
                assertTrue("${grid.id} $diff exceeded the edge count", moves <= edgeTotal)
            }
            assertEquals(edgeTotal, moves)
            assertEquals(grid.boxCount, g.state.value!!.scores.sum())
        }
    }

    @Test
    fun `playBotTurn still plays a whole chain synchronously on the largest board`() {
        val grid = DotsAndBoxesGrid.LARGE
        val g = newGame(grid, twoBots, PlayMode.SINGLE_PLAYER_VS_BOT)
        g.difficulty = CpuDifficulty.HARD
        g.startMatch()
        g.playBotTurn()
        assertTrue(g.state.value!!.boardOver)
    }

    // ---- hint ----------------------------------------------------------------------------------

    @Test
    fun `the hint suggests the completing edge on every size and is only shown for an unchanged board`() {
        for (grid in DotsAndBoxesGrid.values()) {
            val text = threeSided(grid, current = 0, bots = true)
            val g = newGame(grid, humanAndCpu, PlayMode.SINGLE_PLAYER_VS_BOT)
            assertTrue(g.restore(text))
            val s = g.state.value!!

            val computed = g.computeHint(s)
            assertEquals(DotsAndBoxesHintEdge(false, 0, 1), computed)
            assertNull("computing a hint shows nothing", g.hintEdge.value)

            // The board was replaced while the hint was being worked out (via a different position:
            // Compose state skips an equal write, which would keep the very same object).
            assertTrue(g.restore(twoSided(grid, current = 0, bots = true, difficulty = "MEDIUM")))
            assertTrue(g.restore(text))
            assertNotSame(s, g.state.value)
            assertFalse(g.showHint(s, computed!!))
            assertNull(g.hintEdge.value)

            assertTrue(g.showHint(g.state.value!!, computed))
            assertEquals(computed, g.hintEdge.value)

            // The synchronous entry point agrees with the pure one.
            assertEquals(computed, g.hint())
        }
    }

    // ---- resume --------------------------------------------------------------------------------

    @Test
    fun `a snapshot round trip restores the board, its size and the turn on every size`() {
        for (grid in DotsAndBoxesGrid.values()) {
            val a = started(grid)
            a.drawHorizontalEdge(0, 0)
            a.drawVerticalEdge(0, 0)
            a.drawHorizontalEdge(1, 0)
            a.drawVerticalEdge(0, 1) // a claim, so a go-again turn is pending
            val text = a.snapshot()
            assertNotNull(text)

            val b = newGame(DotsAndBoxesGrid.STANDARD) // deliberately a different configured size
            assertTrue("${grid.id} restore", b.restore(text!!))
            assertEquals(grid, b.grid)
            assertEquals(DotsAndBoxesTestEssence.of(a.state.value), DotsAndBoxesTestEssence.of(b.state.value))
            assertEquals(a.sessionWins.value, b.sessionWins.value)

            a.drawHorizontalEdge(2, 1)
            b.drawHorizontalEdge(2, 1)
            assertEquals(DotsAndBoxesTestEssence.of(a.state.value), DotsAndBoxesTestEssence.of(b.state.value))

            // Play Again after a resume deals the restored size, not the configured one.
            drawAll(b, grid)
            b.playAgain()
            assertEquals(grid.boxCount, b.state.value!!.totalBoxes)
        }
    }

    @Test
    fun `undo works on every size`() {
        for (grid in DotsAndBoxesGrid.values()) {
            val g = started(grid)
            val before = g.state.value
            g.drawHorizontalEdge(0, 0)
            assertTrue(g.canUndo)
            g.undo()
            assertEquals(before, g.state.value)
            assertEquals(grid.boxCount, g.state.value!!.totalBoxes)
        }
    }

    @Test
    fun `a save from before board sizes existed restores as the standard board`() {
        val grid = DotsAndBoxesGrid.STANDARD
        val old = rawSnapshot(
            rows = 5, cols = 5,
            h = List(grid.horizontalEdgeCount) { false }, v = List(grid.verticalEdgeCount) { false },
            owner = List(25) { null }, scores = listOf(0, 0), current = 0, bots = false, gridId = null
        )
        val g = newGame(DotsAndBoxesGrid.SMALL)
        assertTrue(g.restore(old))
        assertEquals(DotsAndBoxesGrid.STANDARD, g.grid)
        assertEquals(25, g.state.value!!.totalBoxes)
    }

    @Test
    fun `a standard board snapshot omits nothing a pre-grid reader needs and a small one names its size`() {
        val small = started(DotsAndBoxesGrid.SMALL).snapshot()!!
        assertTrue(small, small.contains("\"grid\":\"small\""))
        val standard = started(DotsAndBoxesGrid.STANDARD).snapshot()!!
        assertTrue(standard, standard.contains("\"boxRows\":5"))
    }

    @Test
    fun `a snapshot whose size does not match its edge and box counts is rejected`() {
        val bad = mutableListOf<Pair<String, String>>()
        // Unknown size id.
        bad += "unknown id" to rawSnapshot(5, 5, List(30) { false }, List(30) { false }, List(25) { null }, listOf(0, 0), 0, false, "huge")
        // Names small but carries standard-sized lists.
        bad += "small id, 5x5 data" to rawSnapshot(5, 5, List(30) { false }, List(30) { false }, List(25) { null }, listOf(0, 0), 0, false, "small")
        // Names large but carries 3x3 data.
        bad += "large id, 3x3 data" to rawSnapshot(3, 3, List(12) { false }, List(12) { false }, List(9) { null }, listOf(0, 0), 0, false, "large")
        // Old save shape (no id = standard) but 4x4 data.
        bad += "no id, 4x4 data" to rawSnapshot(4, 4, List(20) { false }, List(20) { false }, List(16) { null }, listOf(0, 0), 0, false, null)
        // Right id, wrong declared dimensions.
        bad += "6x5 declared" to rawSnapshot(6, 5, List(35) { false }, List(36) { false }, List(30) { null }, listOf(0, 0), 0, false, "standard")
        // Right id and dimensions, but a truncated edge list.
        bad += "short vertical list" to rawSnapshot(4, 4, List(20) { false }, List(19) { false }, List(16) { null }, listOf(0, 0), 0, false, "medium")
        // Right id and dimensions, but a box list with one too many entries.
        bad += "long owner list" to rawSnapshot(3, 3, List(12) { false }, List(12) { false }, List(10) { null }, listOf(0, 0), 0, false, "small")
        // An owner outside the roster.
        bad += "owner out of roster" to rawSnapshot(3, 3, List(12) { false }, List(12) { false }, List(9) { if (it == 0) 7 else null }, listOf(0, 0), 0, false, "small")
        for ((name, text) in bad) {
            val g = newGame(DotsAndBoxesGrid.STANDARD)
            assertFalse("$name should be rejected", g.restore(text))
            assertNull("$name must leave no board behind", g.state.value)
        }
        // And the matching good shapes are accepted, so the rejections above are about size alone.
        val good = rawSnapshot(4, 4, List(20) { false }, List(20) { false }, List(16) { null }, listOf(0, 0), 0, false, "medium")
        val g = newGame(DotsAndBoxesGrid.STANDARD)
        assertTrue(g.restore(good))
        assertEquals(DotsAndBoxesGrid.MEDIUM, g.grid)
    }

    // ---- network -------------------------------------------------------------------------------

    @Test
    fun `a guest applies a host state of any offered size and rejects a size that disagrees with the snapshot`() {
        for (grid in DotsAndBoxesGrid.values()) {
            val host = DotsTestKit.node(0)
            host.game.grid = grid
            host.game.startMatch()
            val sync = host.transport.sent.first().stateSync
            assertEquals(grid.id, sync.grid)

            val guest = DotsTestKit.node(1)
            guest.transport.inject("p0", DotsAndBoxesNetMessage.StateSync(5, sync.snapshot, otherGrid(grid).id))
            assertNull("${grid.id}: a mislabelled size must be dropped", guest.game.state.value)

            guest.transport.inject("p0", DotsAndBoxesNetMessage.StateSync(5, sync.snapshot, grid.id))
            assertEquals(grid.boxCount, guest.game.state.value!!.totalBoxes)
            assertEquals(grid, guest.game.grid)
        }
    }

    private fun otherGrid(grid: DotsAndBoxesGrid): DotsAndBoxesGrid =
        DotsAndBoxesGrid.values().first { it != grid }

    @Test
    fun `a state sync without a size field is read as the standard board`() {
        val host = DotsTestKit.node(0, start = true)
        val snapshot = host.transport.sent.first().stateSync.snapshot
        // The default size is not written to the wire (encodeDefaults is off), exactly what an
        // older host sends.
        val wire = DotsTestKit.encode(DotsAndBoxesNetMessage.StateSync(1, snapshot))
        assertFalse(String(wire, Charsets.UTF_8).contains("\"grid\""))
        val guest = DotsTestKit.node(1)
        guest.transport.inject("p0", wire)
        assertEquals(25, guest.game.state.value?.totalBoxes)
    }

    @Test
    fun `a networked host and guest play a 5x5 board through unchanged`() {
        val (host, guest, _) = DotsTestKit.pair()
        DotsTestKit.playOut(host, guest)
        assertTrue(host.game.state.value!!.boardOver)
        assertEquals(DotsTestKit.essence(host.game.state.value), DotsTestKit.essence(guest.game.state.value))
        assertEquals(25, guest.game.state.value!!.totalBoxes)
    }

    // ---- Game Hub option -----------------------------------------------------------------------

    @Test
    fun `the hub offers a board size on vs computer and pass and play defaulting to standard`() {
        val spec = GameHubSpecs.get("dots-and-boxes")
        for (modeId in listOf("cpu", "pass")) {
            val mode = spec.modes.first { it.id == modeId }
            val option = mode.options.filterIsInstance<HubOption.Choice>().firstOrNull { it.key == "grid" }
            assertNotNull("$modeId has the grid option", option)
            assertEquals("standard", option!!.default)
            assertEquals(listOf("small", "medium", "standard", "large"), option.choices.map { it.id })
            assertEquals(DotsAndBoxesGrid.values().map { it.id }, option.choices.map { it.id })
        }
        // Not offered on the networked modes, which always deal the standard board.
        for (mode in spec.modes.filter { it.id == "nearby" || it.id == "online" }) {
            assertTrue(mode.options.none { it.key == "grid" })
        }
    }

    @Test
    fun `the chosen board size reaches the launch options and an untouched hub means standard`() {
        val spec = GameHubSpecs.get("dots-and-boxes")
        val cpu = spec.modes.first { it.id == "cpu" }
        val large = (buildHubLaunch(spec, cpu, cpu.defaults().with("grid", "large")) as HubLaunch.Local).launch
        assertEquals("large", large.options.extras["grid"])
        assertEquals(DotsAndBoxesGrid.LARGE, DotsAndBoxesGrid.fromId(large.options.extras["grid"]))

        val untouched = (buildHubLaunch(spec, cpu, cpu.defaults()) as HubLaunch.Local).launch
        assertEquals(DotsAndBoxesGrid.STANDARD, DotsAndBoxesGrid.fromId(untouched.options.extras["grid"]))
    }
}

/** The comparable part of a state: everything except the one-shot claim animation event. */
private object DotsAndBoxesTestEssence {
    fun of(s: DotsAndBoxesState?): DotsAndBoxesState? = s?.copy(lastBoxCompleted = null)
}
