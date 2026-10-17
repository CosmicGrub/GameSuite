package com.gamesuite.games.edgematch

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resume (snapshot/restore), Undo and Hint for Edge Match. Plain JUnit, injected clock. */
class EdgeMatchResumeUndoHintTest {

    private class Clock { var now = 0L }

    private val solo = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1"))

    private fun newGame(
        clock: Clock = Clock(),
        players: List<PlayerInfo> = solo,
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT
    ): EdgeMatchGame {
        val game = EdgeMatchGame(nowMillis = { clock.now })
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        game.difficulty = CpuDifficulty.EASY
        return game
    }

    private fun started(clock: Clock = Clock(), seed: Long? = 4242L): EdgeMatchGame =
        newGame(clock).also { it.startMatch(seed) }

    /** Taps every tile exactly enough times to bring it back to its generated orientation (rotation 0). */
    private fun solve(game: EdgeMatchGame) {
        val tiles = game.state.value!!.tiles
        tiles.forEachIndexed { i, t ->
            val sides = t.canonicalEdges.size
            repeat((sides - t.rotation) % sides) { game.tapTile(i) }
        }
    }

    private fun tamper(snapshot: String, edit: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit): String {
        val map = Json.parseToJsonElement(snapshot).jsonObject.toMutableMap()
        edit(map)
        return JsonObject(map).toString()
    }

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `nothing to snapshot before the first board, after the session ends, or in online play`() {
        val game = newGame()
        assertNull(game.snapshot())

        game.startMatch()
        assertNotNull(game.snapshot())

        game.leaveSession()
        assertNull(game.snapshot())

        val online = newGame(mode = PlayMode.ONLINE).also { it.startMatch() }
        assertNull(online.snapshot())
    }

    @Test
    fun `a restored game is identical and plays on identically`() {
        val a = started()
        a.tapTile(0); a.tapTile(3); a.tapTile(3); a.tapTile(5)
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))
        assertEquals(a.state.value, b.state.value)
        assertEquals(a.puzzlesSolved.value, b.puzzlesSolved.value)
        assertEquals(a.statsKey(), b.statsKey())
        assertEquals(a.difficulty, b.difficulty)

        a.tapTile(7)
        b.tapTile(7)
        assertEquals(a.state.value, b.state.value)
    }

    @Test
    fun `the daily seed and the Custom builder settings survive, hex included`() {
        val daily = started(seed = 99L)
        assertEquals(99L, daily.activeDailySeed)
        val d2 = newGame()
        assertTrue(d2.restore(daily.snapshot()!!))
        assertEquals(99L, d2.activeDailySeed)
        d2.playAgain()
        assertNull("a new board is random again", d2.activeDailySeed)

        val custom = newGame()
        custom.startCustomMatch(3, 3, EdgeMatchGeometry.HEX, 11L)
        custom.tapTile(2)
        val c2 = newGame()
        assertTrue(c2.restore(custom.snapshot()!!))
        assertEquals(custom.customConfig, c2.customConfig)
        assertEquals(EdgeMatchGeometry.HEX, c2.state.value!!.geometry)
        assertEquals("CUSTOM_HEX_3x3", c2.statsKey())
        assertEquals(custom.state.value, c2.state.value)
        // Hex tiles turn in sixths, and the restored board keeps doing so.
        c2.tapTile(2)
        custom.tapTile(2)
        assertEquals(custom.state.value, c2.state.value)
    }

    @Test
    fun `the solved tally and the solve-counted flag survive, so a re-solve is not counted twice`() {
        val a = started()
        solve(a)
        assertTrue(a.state.value!!.solved)
        assertEquals(1, a.puzzlesSolved.value)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertTrue(b.state.value!!.solved)
        assertEquals(1, b.puzzlesSolved.value)
        assertEquals(a.solvedElapsedMillis.value, b.solvedElapsedMillis.value)

        b.resetToInitial()
        solve(b)
        assertEquals("same puzzle, solved again", 1, b.puzzlesSolved.value)
    }

    @Test
    fun `the restart scramble survives a resume`() {
        val a = started()
        val scramble = a.state.value!!.tiles
        a.tapTile(1); a.tapTile(4)
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        b.resetToInitial()
        assertEquals(scramble, b.state.value!!.tiles)
        assertEquals(0, b.state.value!!.moves)
    }

    @Test
    fun `the clock continues from the active elapsed time and ignores the time the app was gone`() {
        val clock = Clock()
        val a = started(clock)
        clock.now = 1_000
        a.tapTile(0)
        clock.now = 1_500
        val snapshot = a.snapshot()!!

        val laterClock = Clock().apply { now = 9_000_000 }
        val b = newGame(laterClock)
        assertTrue(b.restore(snapshot))
        assertEquals(500L, b.activeElapsedMillis())
        laterClock.now += 200
        assertEquals(700L, b.activeElapsedMillis())
    }

    @Test
    fun `time spent paused is not part of the saved clock, and an unstarted clock stays unstarted`() {
        val clock = Clock()
        val a = started(clock)
        val untouched = newGame().also { assertTrue(it.restore(a.snapshot()!!)) }
        assertNull(untouched.timerStartElapsedRealtime.value)
        assertNull(untouched.activeElapsedMillis())

        clock.now = 1_000
        a.tapTile(0)
        clock.now = 1_500
        a.pause()
        clock.now = 5_000
        val b = newGame(Clock().apply { now = 777 })
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(500L, b.activeElapsedMillis())
    }

    @Test
    fun `restore rejects garbage, a wrong roster and malformed boards, and leaves the engine untouched`() {
        val source = started()
        source.tapTile(0)
        val good = source.snapshot()!!

        val target = started(seed = 7L)
        target.tapTile(1)
        val before = target.snapshot()!!
        val stateBefore = target.state.value

        assertFalse(target.restore("not json"))
        assertFalse(target.restore("{}"))
        assertFalse(target.restore(""))

        // Wrong roster: another player id, an extra player.
        val stranger = newGame(players = listOf(PlayerInfo(playerId = "someone-else", displayName = "X"))).also { it.startMatch(1L) }
        assertFalse(target.restore(stranger.snapshot()!!))
        val twoPlayers = newGame(players = solo + PlayerInfo(playerId = "p2", displayName = "P2")).also { it.startMatch(1L) }
        assertFalse(target.restore(twoPlayers.snapshot()!!))

        // Wrong-sized board: the declared size no longer matches the tile list.
        assertFalse(target.restore(tamper(good) { it["size"] = JsonPrimitive(5) }))
        // A tile missing.
        assertFalse(target.restore(tamper(good) { m ->
            m["tiles"] = JsonArray(m.getValue("tiles").jsonArray.drop(1))
        }))
        // Colour index out of range, rotation out of range, unknown geometry, lying solved flag.
        assertFalse(target.restore(tamper(good) { m ->
            m["tiles"] = JsonArray(m.getValue("tiles").jsonArray.map { tile ->
                JsonObject(tile.jsonObject + ("edges" to JsonArray(List(4) { JsonPrimitive(9) })))
            })
        }))
        assertFalse(target.restore(tamper(good) { m ->
            m["tiles"] = JsonArray(m.getValue("tiles").jsonArray.map { tile ->
                JsonObject(tile.jsonObject + ("rotation" to JsonPrimitive(4)))
            })
        }))
        assertFalse(target.restore(tamper(good) { it["geometry"] = JsonPrimitive("TRIANGLE") }))
        assertFalse(target.restore(tamper(good) { it["solved"] = JsonPrimitive(true) }))
        assertFalse(target.restore(tamper(good) { it["moves"] = JsonPrimitive(-1) }))

        assertEquals(stateBefore, target.state.value)
        assertEquals(before, target.snapshot())
        assertTrue("a good snapshot still restores", target.restore(good))
    }

    @Test
    fun `restore clears undo history and any hint`() {
        val a = started()
        a.tapTile(0)
        a.hint()
        val snapshot = a.snapshot()!!
        val b = newGame()
        b.startMatch(1L)
        b.tapTile(0)
        assertTrue(b.canUndo)
        assertTrue(b.restore(snapshot))
        assertFalse(b.canUndo)
        assertNull(b.hintTile.value)
        assertEquals("hints used carry over", 1, b.hintsUsed.value)
    }

    // --- Undo -------------------------------------------------------------------------------

    @Test
    fun `undo takes back one tap at a time and cannot go past the start of the board`() {
        val game = started()
        assertFalse(game.canUndo)
        val start = game.state.value!!

        game.tapTile(2)
        val afterOne = game.state.value!!
        game.tapTile(5)
        game.tapTile(5)
        assertEquals(3, game.state.value!!.moves)
        assertTrue(game.canUndo)

        game.undo()
        assertEquals(2, game.state.value!!.moves)
        game.undo()
        assertEquals(afterOne, game.state.value)
        game.undo()
        assertEquals(start, game.state.value)
        assertFalse(game.canUndo)
        game.undo()
        assertEquals(start, game.state.value)
    }

    @Test
    fun `a rejected tap leaves no undo step`() {
        val game = started()
        game.tapTile(-1)
        game.tapTile(10_000)
        assertFalse(game.canUndo)
        assertEquals(0, game.state.value!!.moves)
    }

    @Test
    fun `undoing a solving tap reopens the board and the tally is not lowered`() {
        val game = started()
        solve(game)
        assertTrue(game.state.value!!.solved)
        assertEquals(1, game.puzzlesSolved.value)
        assertNotNull(game.solvedElapsedMillis.value)

        game.undo()
        assertFalse(game.state.value!!.solved)
        assertNull(game.solvedElapsedMillis.value)
        assertEquals("a counted solve stays counted", 1, game.puzzlesSolved.value)

        // Solving it again is the same puzzle: still one.
        val tiles = game.state.value!!.tiles
        tiles.forEachIndexed { i, t ->
            val sides = t.canonicalEdges.size
            repeat((sides - t.rotation) % sides) { game.tapTile(i) }
        }
        assertTrue(game.state.value!!.solved)
        assertEquals(1, game.puzzlesSolved.value)
    }

    @Test
    fun `undo cannot lower hints used`() {
        val game = started()
        game.tapTile(0)
        game.hint()
        game.tapTile(1)
        game.undo()
        game.undo()
        assertEquals(1, game.hintsUsed.value)
        assertFalse(game.countsForRecord)
    }

    @Test
    fun `undo keeps the clock running instead of rewinding it`() {
        val clock = Clock()
        val game = started(clock)
        clock.now = 100
        game.tapTile(0)
        clock.now = 600
        game.tapTile(1)
        clock.now = 900
        game.undo()
        game.undo()
        assertEquals(800L, game.activeElapsedMillis())
    }

    @Test
    fun `undo never reaches into a previous board and does nothing after the session ends`() {
        val game = started()
        game.tapTile(0)
        assertTrue(game.canUndo)
        game.playAgain()
        assertFalse(game.canUndo)

        game.tapTile(0)
        game.resetToInitial()
        assertFalse("restart drops older steps", game.canUndo)

        game.tapTile(0)
        val board = game.state.value
        game.leaveSession()
        assertFalse(game.canUndo)
        game.undo()
        assertEquals(board, game.state.value)
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `hint is null before the first board, when solved, and after the session ends`() {
        val game = newGame()
        assertNull(game.hint())
        game.startMatch(5L)
        solve(game)
        assertNull(game.hint())
        assertEquals(0, game.hintsUsed.value)

        val ended = started()
        ended.leaveSession()
        assertNull(ended.hint())
    }

    @Test
    fun `hint names a tile that is not in its solved orientation and marks the puzzle assisted`() {
        val game = started()
        val tile = game.hint()
        assertNotNull(tile)
        assertEquals(tile, game.hintTile.value)
        assertEquals(1, game.hintsUsed.value)
        assertFalse(game.countsForRecord)

        val t = game.state.value!!.tiles[tile!!]
        assertTrue(
            "the suggested tile is turned away from its solved orientation",
            (0 until t.canonicalEdges.size).any { t.currentEdge(it) != t.canonicalEdges[it] }
        )
        // A hint only suggests: nothing moved.
        assertEquals(0, game.state.value!!.moves)
        assertFalse(game.canUndo)
    }

    @Test
    fun `following the hints solves the puzzle, and the next tap or an undo clears the highlight`() {
        val game = started()
        var guard = 0
        while (!game.state.value!!.solved && guard++ < 200) {
            val index = game.hint()!!
            val tile = game.state.value!!.tiles[index]
            val sides = tile.canonicalEdges.size
            repeat((sides - tile.rotation) % sides) { game.tapTile(index) }
            assertNull("tapping spends the hint", game.hintTile.value)
        }
        assertTrue(game.state.value!!.solved)
        assertTrue(game.hintsUsed.value > 0)
        assertEquals("assisted, but still one solved puzzle", 1, game.puzzlesSolved.value)
        assertFalse(game.countsForRecord)
    }

    @Test
    fun `undo clears the highlight and the hint count resets on a new board`() {
        val game = started()
        game.tapTile(0)
        game.hint()
        assertNotNull(game.hintTile.value)
        game.undo()
        assertNull(game.hintTile.value)

        game.hint()
        game.resetToInitial()
        assertNull(game.hintTile.value)
        assertEquals("a restart does not wash a hint away", 2, game.hintsUsed.value)

        game.playAgain()
        assertEquals(0, game.hintsUsed.value)
        assertTrue(game.countsForRecord)
    }

    @Test
    fun `a hint on the suggested tile never points at a border-only or already-correct tile`() {
        // Custom 2x2 and hex boards too: whatever the geometry, the pick is out of orientation.
        for (hex in listOf(false, true)) {
            val game = newGame()
            game.startCustomMatch(3, 4, if (hex) EdgeMatchGeometry.HEX else EdgeMatchGeometry.SQUARE)
            val index = game.hint()!!
            val t = game.state.value!!.tiles[index]
            assertTrue((0 until t.canonicalEdges.size).any { t.currentEdge(it) != t.canonicalEdges[it] })
        }
    }
}
