package com.gamesuite.games.g2048

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 2048 engine tests: the pure slide / merge rules, spawning, scoring, win and keep-going, game over,
 * every grid size, resume (snapshot round trip and garbage rejection), Undo (exact, and "assisted")
 * and Hint. No Android, no Compose runtime beyond the state holders the engine already uses.
 */
class G2048GameTest {

    /** A Random whose nextInt(bound) answers come from a script: cell picks first, then the 4-or-2 roll (0 means a 4). */
    private class ScriptedRandom(vararg picks: Int) : Random() {
        fun load(vararg more: Int) { queue.clear(); queue.addAll(more.toList()) }
        private val queue = ArrayDeque(picks.toList())
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextInt(until: Int): Int {
            val next = queue.removeFirstOrNull()
            // Out of script: the first empty cell, and a plain 2.
            return if (next == null) (if (until == 10) 1 else 0) else next % until
        }
    }

    private val one = listOf(PlayerInfo("you", "You"))

    private fun newGame(
        random: Random = Random(1),
        size: G2048Size = G2048Size.CLASSIC,
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT,
        players: List<PlayerInfo> = one
    ): G2048Game {
        val game = G2048Game(random)
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        game.gridSize = size
        return game
    }

    private fun started(random: Random = Random(1), size: G2048Size = G2048Size.CLASSIC) =
        newGame(random, size).also { it.startMatch() }

    /** A started game whose NEXT random answers are [picks] (the opening deal already used its own). */
    private fun startedScripted(vararg picks: Int): G2048Game {
        val random = ScriptedRandom()
        val game = newGame(random).also { it.startMatch() }
        random.load(*picks)
        return game
    }

    private fun G2048Game.setBoard(size: G2048Size, cells: List<Int>, score: Int = 0) {
        state.value = G2048State(size, cells, score = score)
    }

    private fun grid(size: G2048Size, vararg at: Pair<Int, Int>): List<Int> =
        MutableList(size.cellCount) { 0 }.also { l -> at.forEach { (i, v) -> l[i] = v } }

    private fun row(vararg v: Int): List<Int> = v.toList() + List(16 - v.size) { 0 }

    private fun slideRow(direction: G2048Direction, vararg v: Int): List<Int> =
        G2048Rules.move(row(*v), 4, direction).cells.take(4)

    private fun tiles(cells: List<Int>) = cells.count { it != 0 }

    // ---- slide and merge ----------------------------------------------------------------

    @Test
    fun `four equal tiles merge in pairs, once each`() {
        val r = G2048Rules.move(row(2, 2, 2, 2), 4, G2048Direction.LEFT)
        assertEquals(listOf(4, 4, 0, 0), r.cells.take(4))
        assertEquals(8, r.gained)
        assertTrue(r.moved)
    }

    @Test
    fun `two twos then a four slide to two fours`() {
        assertEquals(listOf(4, 4, 0, 0), slideRow(G2048Direction.LEFT, 2, 2, 4, 0))
    }

    @Test
    fun `a four with a gap then two twos`() {
        assertEquals(listOf(4, 4, 0, 0), slideRow(G2048Direction.LEFT, 4, 0, 2, 2))
    }

    @Test
    fun `a merged tile does not merge again in the same move`() {
        // 4 2 2: the twos make a 4 next to the existing 4, but that 4 is new this move and must stay put.
        assertEquals(listOf(4, 4, 0, 0), slideRow(G2048Direction.LEFT, 4, 2, 2, 0))
        // 2 2 4 4 -> 4 8, never 16.
        assertEquals(listOf(4, 8, 0, 0), slideRow(G2048Direction.LEFT, 2, 2, 4, 4))
        assertEquals(listOf(8, 16, 0, 0), slideRow(G2048Direction.LEFT, 4, 4, 8, 8))
    }

    @Test
    fun `three in a row merge the pair nearest the wall`() {
        assertEquals(listOf(4, 2, 0, 0), slideRow(G2048Direction.LEFT, 2, 2, 2, 0))
        assertEquals(listOf(0, 0, 2, 4), slideRow(G2048Direction.RIGHT, 2, 2, 2, 0))
    }

    @Test
    fun `tiles with a gap between them still merge`() {
        assertEquals(listOf(4, 0, 0, 0), slideRow(G2048Direction.LEFT, 2, 0, 0, 2))
    }

    @Test
    fun `an already packed row with no equal neighbours does not move`() {
        val r = G2048Rules.move(row(2, 4, 8, 16), 4, G2048Direction.LEFT)
        assertFalse(r.moved)
        assertEquals(0, r.gained)
    }

    @Test
    fun `an empty grid does not move`() {
        assertFalse(G2048Rules.move(List(16) { 0 }, 4, G2048Direction.UP).moved)
    }

    @Test
    fun `right is the mirror image of left`() {
        val r = listOf(2, 2, 4, 0, 4, 0, 2, 2, 0, 8, 8, 8, 16, 0, 0, 16)
        fun mirror(c: List<Int>) = c.chunked(4).flatMap { it.reversed() }
        assertEquals(mirror(G2048Rules.move(mirror(r), 4, G2048Direction.LEFT).cells), G2048Rules.move(r, 4, G2048Direction.RIGHT).cells)
    }

    @Test
    fun `up and down are the mirror image of each other on columns`() {
        val c = listOf(2, 0, 4, 8, 2, 4, 0, 8, 4, 4, 2, 0, 0, 2, 2, 2)
        fun flip(cells: List<Int>) = cells.chunked(4).reversed().flatten()
        assertEquals(flip(G2048Rules.move(flip(c), 4, G2048Direction.UP).cells), G2048Rules.move(c, 4, G2048Direction.DOWN).cells)
    }

    @Test
    fun `columns merge the same way rows do`() {
        // Column 0 is 2, 2, 4, 0 from the top.
        val cells = grid(G2048Size.CLASSIC, 0 to 2, 4 to 2, 8 to 4)
        val up = G2048Rules.move(cells, 4, G2048Direction.UP)
        assertEquals(listOf(4, 4, 0, 0), listOf(up.cells[0], up.cells[4], up.cells[8], up.cells[12]))
        val down = G2048Rules.move(cells, 4, G2048Direction.DOWN)
        assertEquals(listOf(0, 0, 4, 4), listOf(down.cells[0], down.cells[4], down.cells[8], down.cells[12]))
    }

    @Test
    fun `every row slides independently and the gains add up`() {
        val cells = listOf(
            2, 2, 0, 0,
            4, 4, 0, 0,
            8, 0, 8, 0,
            0, 0, 0, 0
        )
        val r = G2048Rules.move(cells, 4, G2048Direction.LEFT)
        assertEquals(4 + 8 + 16, r.gained)
        assertEquals(listOf(4, 8, 16), listOf(r.cells[0], r.cells[4], r.cells[8]))
    }

    @Test
    fun `motions name every tile and flag the ones that merged`() {
        val r = G2048Rules.move(row(2, 2, 4, 0), 4, G2048Direction.LEFT)
        assertEquals(3, r.motions.size)
        assertEquals(2, r.motions.count { it.to == 0 })
        assertEquals(1, r.motions.count { it.merged })
        assertTrue(r.motions.filter { it.to == 0 }.all { it.value == 2 })
    }

    @Test
    fun `slides work on the small and big grids`() {
        val small = G2048Rules.move(listOf(2, 2, 2, 0, 0, 0, 0, 0, 0), 3, G2048Direction.LEFT)
        assertEquals(listOf(4, 2, 0), small.cells.take(3))
        val big = G2048Rules.move(listOf(2, 2, 4, 4, 8) + List(20) { 0 }, 5, G2048Direction.LEFT)
        assertEquals(listOf(4, 8, 8, 0, 0), big.cells.take(5))
    }

    @Test
    fun `canMove sees empty cells and equal neighbours only`() {
        val checker = List(16) { if ((it / 4 + it % 4) % 2 == 0) 2 else 4 }
        assertFalse(G2048Rules.canMove(checker, 4))
        assertTrue(G2048Rules.canMove(checker.toMutableList().also { it[5] = 0 }, 4))
        // Full, but 2 2 side by side.
        assertTrue(G2048Rules.canMove(checker.toMutableList().also { it[1] = 2 }, 4))
        // Full, but two equal tiles one above the other.
        assertTrue(G2048Rules.canMove(checker.toMutableList().also { it[4] = 2 }, 4))
    }

    // ---- spawning ------------------------------------------------------------------------

    @Test
    fun `a new game deals exactly two starting tiles on every size`() {
        for (size in G2048Size.values()) {
            val g = started(size = size)
            val s = g.state.value!!
            assertEquals(size.cellCount, s.cells.size)
            assertEquals(2, tiles(s.cells))
            assertTrue(s.cells.all { it == 0 || it == 2 || it == 4 })
            assertEquals(0, s.score)
            assertEquals(0, s.moves)
            assertFalse(s.isOver)
        }
    }

    @Test
    fun `the spawn roll decides a 4 or a 2 and the pick decides the cell`() {
        val cells = grid(G2048Size.CLASSIC, 0 to 2, 5 to 2)
        // Empty cells in order: 1,2,3,4,6,... so pick 0 is cell 1 and pick 3 is cell 4.
        assertEquals(1 to 4, G2048Rules.spawn(cells, ScriptedRandom(0, 0)))
        assertEquals(4 to 2, G2048Rules.spawn(cells, ScriptedRandom(3, 7)))
    }

    @Test
    fun `a full grid has nowhere to spawn`() {
        assertNull(G2048Rules.spawn(List(16) { 2 }, Random(1)))
    }

    @Test
    fun `about one new tile in ten is a four`() {
        val random = Random(42)
        val empty = List(16) { 0 }
        val fours = (1..10_000).count { G2048Rules.spawn(empty, random)!!.second == 4 }
        assertTrue("fours: $fours", fours in 800..1200)
    }

    @Test
    fun `the same seed deals the same game`() {
        val a = started(Random(99))
        val b = started(Random(99))
        assertEquals(a.state.value!!.cells, b.state.value!!.cells)
        a.move(G2048Direction.LEFT)
        b.move(G2048Direction.LEFT)
        assertEquals(a.state.value!!.cells, b.state.value!!.cells)
    }

    @Test
    fun `an effective move spawns exactly one tile in an empty cell`() {
        val g = startedScripted(0, 0)
        g.setBoard(G2048Size.CLASSIC, grid(G2048Size.CLASSIC, 3 to 2))
        assertTrue(g.move(G2048Direction.LEFT))
        val s = g.state.value!!
        assertEquals(2, tiles(s.cells))
        assertEquals(2, s.cells[0]) // the slid tile
        assertEquals(4, s.cells[1]) // the scripted spawn: first empty cell, roll 0 = a four
        assertEquals(1, s.moves)
        assertEquals(1, s.lastSpawn)
    }

    @Test
    fun `a move that changes nothing spawns nothing and counts nothing`() {
        val g = started()
        val cells = grid(G2048Size.CLASSIC, 0 to 2, 4 to 4, 8 to 2, 12 to 4)
        g.setBoard(G2048Size.CLASSIC, cells)
        assertFalse(g.move(G2048Direction.LEFT))
        assertFalse(g.move(G2048Direction.UP))
        val s = g.state.value!!
        assertEquals(cells, s.cells)
        assertEquals(0, s.moves)
        assertFalse("no undo step for a no-op", g.canUndo)
    }

    // ---- scoring -------------------------------------------------------------------------

    @Test
    fun `the score is the sum of the tiles made by merging`() {
        val g = started(ScriptedRandom())
        g.setBoard(G2048Size.CLASSIC, listOf(2, 2, 0, 0, 4, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        assertTrue(g.move(G2048Direction.LEFT))
        assertEquals(4 + 8, g.state.value!!.score)
        assertEquals(12, g.state.value!!.lastGain)
        // A slide with no merge adds nothing.
        g.setBoard(G2048Size.CLASSIC, grid(G2048Size.CLASSIC, 3 to 2), score = 12)
        assertTrue(g.move(G2048Direction.LEFT))
        assertEquals(12, g.state.value!!.score)
        assertEquals(0, g.state.value!!.lastGain)
    }

    // ---- win and keep going ----------------------------------------------------------------

    @Test
    fun `making 2048 wins and waits for the keep going choice`() {
        val g = started(ScriptedRandom())
        g.setBoard(G2048Size.CLASSIC, grid(G2048Size.CLASSIC, 0 to 1024, 1 to 1024))
        assertTrue(g.move(G2048Direction.LEFT))
        val s = g.state.value!!
        assertTrue(s.won)
        assertTrue(s.awaitingContinue)
        assertTrue(s.isFinished)
        assertEquals(2048, s.cells[0])
        assertEquals(1, g.gamesWon)
        assertEquals(1, g.finishedGames)
        // Input is refused until the player chooses.
        assertFalse(g.move(G2048Direction.RIGHT))
        assertNull(g.hint())
        g.keepGoing()
        assertFalse(g.state.value!!.awaitingContinue)
        assertTrue(g.move(G2048Direction.RIGHT))
        assertTrue("still counted as a win", g.state.value!!.won)
    }

    @Test
    fun `keep going does nothing before a win`() {
        val g = started()
        g.keepGoing()
        assertFalse(g.state.value!!.keepGoing)
    }

    @Test
    fun `the small grid wins at 512`() {
        val g = started(ScriptedRandom(), G2048Size.SMALL)
        g.setBoard(G2048Size.SMALL, grid(G2048Size.SMALL, 0 to 256, 1 to 256))
        assertTrue(g.move(G2048Direction.LEFT))
        assertTrue(g.state.value!!.won)
        assertEquals(512, g.state.value!!.target)
        assertEquals(2048, G2048Size.CLASSIC.target)
        assertEquals(2048, G2048Size.BIG.target)
    }

    @Test
    fun `winning does not end a game that can still move, and the win survives keep going`() {
        val g = started(ScriptedRandom())
        g.setBoard(G2048Size.BIG, grid(G2048Size.BIG, 0 to 1024, 1 to 1024))
        g.move(G2048Direction.LEFT)
        g.keepGoing()
        assertFalse(g.state.value!!.isOver)
        assertEquals(1, g.gamesWon)
    }

    // ---- game over -------------------------------------------------------------------------

    @Test
    fun `a full grid with no equal neighbours is game over`() {
        val g = started()
        g.setBoard(G2048Size.CLASSIC, List(16) { if ((it / 4 + it % 4) % 2 == 0) 2 else 4 })
        val s = g.state.value!!
        assertTrue(s.isOver)
        assertTrue(s.isFinished)
        for (d in G2048Direction.values()) assertFalse(g.move(d))
        assertNull(g.hint())
        assertNull("a game that is over is not saved", g.snapshot())
    }

    @Test
    fun `the move that fills the grid with nothing left to merge ends the game`() {
        val g = startedScripted(0, 5)
        val q = listOf(2, 4, 2, 4, 2, 4, 2, 0, 4)
        g.setBoard(G2048Size.SMALL, q)
        assertTrue(g.move(G2048Direction.LEFT))
        val s = g.state.value!!
        assertEquals(listOf(2, 4, 2, 4, 2, 4, 2, 4, 2), s.cells)
        assertTrue(s.isOver)
        assertEquals(1, g.finishedGames)
        assertEquals(0, g.gamesWon)
    }

    @Test
    fun `undo can reopen a game that just ended`() {
        val g = startedScripted(0, 5)
        val q = listOf(2, 4, 2, 4, 2, 4, 2, 0, 4)
        g.setBoard(G2048Size.SMALL, q)
        g.move(G2048Direction.LEFT)
        assertTrue(g.state.value!!.isOver)
        assertTrue(g.canUndo)
        g.undo()
        assertEquals(q, g.state.value!!.cells)
        assertFalse(g.state.value!!.isOver)
        assertEquals(0, g.finishedGames)
    }

    // ---- sizes -----------------------------------------------------------------------------

    @Test
    fun `the Hub size ids map to the three sizes and unknown ids are classic`() {
        assertEquals(G2048Size.SMALL, G2048Size.fromId("small"))
        assertEquals(G2048Size.CLASSIC, G2048Size.fromId("classic"))
        assertEquals(G2048Size.BIG, G2048Size.fromId("big"))
        assertEquals(G2048Size.CLASSIC, G2048Size.fromId(null))
        assertEquals(G2048Size.CLASSIC, G2048Size.fromId("huge"))
        assertEquals(listOf(3, 4, 5), G2048Size.values().map { it.n })
    }

    @Test
    fun `a game keeps its size across new games`() {
        val g = started(size = G2048Size.BIG)
        g.newGame()
        assertEquals(G2048Size.BIG, g.state.value!!.size)
        assertEquals(25, g.state.value!!.cells.size)
    }

    // ---- resume ----------------------------------------------------------------------------

    private fun snapJson(
        side: Int = 4,
        cells: List<Int> = List(side * side) { if (it == 0) 2 else 0 },
        score: Int = 0,
        moves: Int = 0,
        won: Boolean = false,
        keepGoing: Boolean = false,
        players: String = "[\"you\"]"
    ) = """{"playerIds":$players,"side":$side,"cells":[${cells.joinToString(",")}],"score":$score,"moves":$moves,"won":$won,"keepGoing":$keepGoing}"""

    private fun playMoves(g: G2048Game, count: Int) {
        var played = 0
        while (played < count) {
            val moved = listOf(G2048Direction.LEFT, G2048Direction.UP, G2048Direction.RIGHT, G2048Direction.DOWN).any { g.move(it) }
            if (!moved) return
            played++
        }
    }

    @Test
    fun `a snapshot round trip puts the game back exactly`() {
        for (size in G2048Size.values()) {
            val a = started(Random(7), size)
            playMoves(a, 4) // 2 + 4 tiles at most: even the small grid cannot be over yet
            val text = a.snapshot()
            assertNotNull(text)
            val b = newGame()
            assertTrue(b.restore(text!!))
            val sa = a.state.value!!
            val sb = b.state.value!!
            assertEquals(sa.cells, sb.cells)
            assertEquals(sa.score, sb.score)
            assertEquals(sa.moves, sb.moves)
            assertEquals(sa.size, sb.size)
            assertEquals(sa.won, sb.won)
            assertEquals(sa.keepGoing, sb.keepGoing)
            assertEquals(a.sessionBest.value, b.sessionBest.value)
            assertEquals(size, b.gridSize)
        }
    }

    @Test
    fun `won keep going assisted and the session tally survive a resume`() {
        val a = started(ScriptedRandom())
        a.setBoard(G2048Size.CLASSIC, grid(G2048Size.CLASSIC, 0 to 1024, 1 to 1024))
        a.move(G2048Direction.LEFT)
        a.keepGoing()
        a.move(G2048Direction.RIGHT)
        a.undo()
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        val s = b.state.value!!
        assertTrue(s.won)
        assertTrue(s.keepGoing)
        assertTrue(s.assisted)
        assertEquals(a.sessionBest.value, b.sessionBest.value)
    }

    @Test
    fun `a snapshot is only written for a live solo game`() {
        val g = newGame()
        assertNull("nothing dealt yet", g.snapshot())
        g.startMatch()
        assertNotNull(g.snapshot())
        g.leaveSession()
        assertNull("a finished session has nothing to resume", g.snapshot())
        val other = newGame(mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY).also { it.startMatch() }
        assertNull(other.snapshot())
    }

    @Test
    fun `valid hand written snapshots restore`() {
        assertTrue(newGame().restore(snapJson()))
        assertTrue(newGame().restore(snapJson(side = 3, cells = listOf(2, 4, 8, 16, 32, 64, 128, 256, 512), won = true, keepGoing = true)))
        assertTrue(newGame().restore(snapJson(cells = List(16) { if (it == 3) 2048 else 0 }, won = true)))
        assertTrue(newGame().restore(snapJson(side = 5, score = 40, moves = 9)))
    }

    @Test
    fun `garbage snapshots are rejected and leave the game untouched`() {
        val tooManyCells = snapJson(side = 4, cells = List(15) { 2 })
        val garbage = listOf(
            "",
            "garbage",
            "{}",
            "[]",
            snapJson(side = 6, cells = List(36) { 2 }),
            snapJson(side = 2, cells = List(4) { 2 }),
            tooManyCells,
            snapJson(cells = List(16) { if (it == 0) 3 else 0 }),
            snapJson(cells = List(16) { if (it == 0) 1 else 0 }),
            snapJson(cells = List(16) { if (it == 0) -2 else 0 }),
            snapJson(cells = List(16) { if (it == 0) 6 else 0 }),
            snapJson(side = 3, cells = List(9) { if (it == 0) 2048 else 0 }, won = true),
            snapJson(cells = List(16) { 0 }),
            snapJson(score = -1),
            snapJson(moves = -3),
            snapJson(keepGoing = true),
            snapJson(cells = List(16) { if (it == 3) 2048 else 0 }, won = false),
            snapJson(cells = List(16) { if (it == 0) 2 else 0 }, won = true),
            snapJson(players = "[\"someone-else\"]"),
            snapJson(players = "[\"you\",\"you2\"]")
        )
        for (text in garbage) {
            val g = started(Random(5))
            val before = g.state.value
            assertFalse("should reject: $text", g.restore(text))
            assertEquals("untouched after: $text", before, g.state.value)
        }
    }

    @Test
    fun `restore before init is refused`() {
        assertFalse(G2048Game().restore(snapJson()))
    }

    // ---- undo ------------------------------------------------------------------------------

    @Test
    fun `undo puts back exactly the grid, score and move count from before`() {
        val g = started(Random(11))
        val seen = mutableListOf(g.state.value!!)
        repeat(4) {
            playMoves(g, 1)
            seen += g.state.value!!
        }
        for (i in seen.lastIndex - 1 downTo 0) {
            assertTrue(g.canUndo)
            g.undo()
            val s = g.state.value!!
            assertEquals(seen[i].cells, s.cells)
            assertEquals(seen[i].score, s.score)
            assertEquals(seen[i].moves, s.moves)
        }
        assertFalse("nothing left to undo", g.canUndo)
        g.undo() // harmless
        assertEquals(seen[0].cells, g.state.value!!.cells)
    }

    @Test
    fun `undo marks the game assisted and an assisted score cannot raise the session best`() {
        val g = started(ScriptedRandom())
        // LEFT merges the twos (+4); DOWN merges the fours (+8).
        val board = grid(G2048Size.CLASSIC, 0 to 2, 1 to 2, 8 to 4, 12 to 4)
        g.setBoard(G2048Size.CLASSIC, board)
        assertTrue(g.move(G2048Direction.LEFT))
        assertEquals(4, g.sessionBest.value)
        assertFalse(g.state.value!!.assisted)
        g.undo()
        assertTrue(g.state.value!!.assisted)
        assertTrue(g.move(G2048Direction.DOWN))
        assertEquals(8, g.state.value!!.score)
        assertEquals("an assisted score never counts", 4, g.sessionBest.value)
    }

    @Test
    fun `an unassisted game raises the session best as it goes`() {
        val g = started(ScriptedRandom())
        g.setBoard(G2048Size.CLASSIC, grid(G2048Size.CLASSIC, 0 to 2, 1 to 2, 8 to 4, 12 to 4))
        g.move(G2048Direction.DOWN)
        assertEquals(8, g.sessionBest.value)
    }

    @Test
    fun `undo does nothing without a move to take back and after the session ends`() {
        val g = started()
        val before = g.state.value
        g.undo()
        assertEquals(before, g.state.value)
        assertFalse(g.state.value!!.assisted)
        playMoves(g, 1)
        g.leaveSession()
        assertFalse(g.canUndo)
    }

    @Test
    fun `a new game starts with a clean undo history`() {
        val g = started()
        playMoves(g, 3)
        assertTrue(g.canUndo)
        g.newGame()
        assertFalse(g.canUndo)
        assertFalse(g.state.value!!.assisted)
    }

    // ---- hint ------------------------------------------------------------------------------

    @Test
    fun `a hint is always a legal move and null only when none exists`() {
        val random = Random(2024)
        val values = listOf(0, 0, 2, 4, 8, 16, 32)
        repeat(300) {
            val cells = List(16) { values[random.nextInt(values.size)] }
            val legal = G2048Rules.legalDirections(cells, 4)
            val hint = G2048Rules.bestDirection(cells, 4)
            if (legal.isEmpty()) assertNull(hint) else assertTrue("$hint not in $legal for $cells", hint in legal)
        }
    }

    @Test
    fun `a hint on a one-way board is that way`() {
        val g = started()
        // A full top row of different tiles: LEFT, RIGHT and UP change nothing, only DOWN moves.
        val cells = grid(G2048Size.CLASSIC, 0 to 2, 1 to 4, 2 to 8, 3 to 16)
        g.setBoard(G2048Size.CLASSIC, cells)
        assertEquals(listOf(G2048Direction.DOWN), G2048Rules.legalDirections(cells, 4))
        assertEquals(G2048Direction.DOWN, g.hint())
    }

    @Test
    fun `taking a hint shows it, marks the game assisted and clears on the next move`() {
        val g = started(Random(3))
        assertNull(g.hintDirection.value)
        val direction = g.hint()
        assertNotNull(direction)
        assertEquals(direction, g.hintDirection.value)
        assertTrue(g.state.value!!.assisted)
        assertTrue(g.move(direction!!))
        assertNull(g.hintDirection.value)
    }

    @Test
    fun `an assisted game from a hint never raises the session best`() {
        val g = started(ScriptedRandom())
        g.setBoard(G2048Size.CLASSIC, grid(G2048Size.CLASSIC, 0 to 2, 1 to 2, 8 to 4, 12 to 4))
        g.hint()
        g.move(G2048Direction.DOWN)
        assertEquals(0, g.sessionBest.value)
    }

    // ---- session ---------------------------------------------------------------------------

    @Test
    fun `starting a new game over one in progress counts nothing, over a finished one counts it`() {
        val g = started(ScriptedRandom())
        playMoves(g, 2)
        g.newGame()
        assertEquals(0, g.finishedGames)
        g.setBoard(G2048Size.CLASSIC, grid(G2048Size.CLASSIC, 0 to 1024, 1 to 1024))
        g.move(G2048Direction.LEFT)
        assertEquals(1, g.finishedGames)
        g.newGame()
        assertEquals(1, g.finishedGames)
        assertEquals(1, g.gamesWon)
    }

    @Test
    fun `new games bump the game number`() {
        val g = started()
        val first = g.gameNumber.value
        g.newGame()
        assertEquals(first + 1, g.gameNumber.value)
        val restored = newGame()
        val before = restored.gameNumber.value
        restored.restore(g.snapshot()!!)
        assertEquals(before + 1, restored.gameNumber.value)
    }

    @Test
    fun `leaving reports the highest score and a win only when the target was reached`() {
        var result: GameResult? = null
        var calls = 0
        val g = started(ScriptedRandom())
        g.setOnMatchEnd { result = it; calls++ }
        g.setBoard(G2048Size.CLASSIC, grid(G2048Size.CLASSIC, 0 to 1024, 1 to 1024))
        g.move(G2048Direction.LEFT)
        g.leaveSession()
        g.leaveSession() // a second call must not report twice
        assertEquals(1, calls)
        val score = result!!.scores.single()
        assertEquals("you", score.playerId)
        assertEquals(2048, score.score)
        assertTrue(score.isWinner)
        assertFalse(result!!.wasAborted)
    }

    @Test
    fun `a session with no win reports no winner`() {
        var result: GameResult? = null
        val g = startedScripted(0, 5)
        g.setOnMatchEnd { result = it }
        g.setBoard(G2048Size.SMALL, listOf(2, 4, 2, 4, 2, 4, 2, 0, 4))
        g.move(G2048Direction.LEFT)
        g.leaveSession()
        assertFalse(result!!.scores.single().isWinner)
    }

    @Test
    fun `an abort is reported as an abort`() {
        var result: GameResult? = null
        val g = started()
        g.setOnMatchEnd { result = it }
        g.abortMatch()
        assertTrue(result!!.wasAborted)
        assertTrue(result!!.scores.isEmpty())
    }

    @Test
    fun `nothing moves after the session has ended`() {
        val g = started()
        g.leaveSession()
        assertTrue(g.matchOver.value)
        for (d in G2048Direction.values()) assertFalse(g.move(d))
        assertNull(g.hint())
        g.newGame()
        assertTrue("a finished session stays finished", g.matchOver.value)
    }

    @Test
    fun `module metadata matches the registration`() {
        val g = G2048Game()
        assertEquals("game-2048", g.gameId)
        assertEquals("2048", g.displayName)
        assertEquals(1, g.minPlayers)
        assertEquals(1, g.maxPlayers)
        assertEquals(listOf(PlayMode.SINGLE_PLAYER_VS_BOT), g.supportedModes)
    }
}
