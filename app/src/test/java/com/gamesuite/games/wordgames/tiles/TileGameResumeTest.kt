package com.gamesuite.games.wordgames.tiles

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

/** Resume (snapshot / restore) for [TileGame]. Uses only pass / swap / staging, so no dictionary is needed. */
class TileGameResumeTest {

    private fun newGame(
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT,
        ids: Pair<String, String> = "p0" to "cpu",
        bot: Boolean = mode == PlayMode.SINGLE_PLAYER_VS_BOT
    ): TileGame {
        val game = TileGame()
        game.init(
            GameContext(
                activeMode = mode,
                players = listOf(
                    PlayerInfo(playerId = ids.first, displayName = "P0"),
                    PlayerInfo(playerId = ids.second, displayName = "P1", isBot = bot)
                ),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        return game
    }

    private fun started(): TileGame = newGame().also { it.startMatch() }

    @Test
    fun `there is nothing to snapshot before the first match, after it ends, or in online play`() {
        assertNull(newGame().snapshot())

        val over = started()
        assertNotNull(over.snapshot())
        over.endMatch(GameResult(wasAborted = true))
        assertNull(over.snapshot())

        val online = newGame(mode = PlayMode.ONLINE, bot = false)
        online.startMatch()
        assertNull(online.snapshot())
    }

    @Test
    fun `a restored game is identical, keeps the bag order, and plays on identically`() {
        val a = started()
        a.difficulty = CpuDifficulty.HARD
        a.pass()
        a.pass()
        a.swapTiles(setOf(a.state.value!!.players[0].rack.first().instanceId))
        val snap = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snap))
        assertEquals(a.state.value, b.state.value)
        assertEquals(CpuDifficulty.HARD, b.difficulty)
        assertEquals(snap, b.snapshot())

        // The snapshot string carries the bag in order, so equal snapshots mean an equal bag order.
        a.pass()
        b.pass()
        assertEquals(a.state.value, b.state.value)
        assertEquals(a.snapshot(), b.snapshot())
    }

    @Test
    fun `scores and pass count survive a resume`() {
        val a = started()
        a.pass()
        val s = a.state.value!!
        a.state.value = s.copy(players = s.players.mapIndexed { i, p -> p.copy(score = 10 * (i + 1)) })
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(listOf(10, 20), b.state.value!!.players.map { it.score })
        assertEquals(1, b.state.value!!.consecutivePasses)
        assertEquals(1, b.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `tiles staged but not submitted are not saved and stay in the rack`() {
        val a = started()
        val tile = a.state.value!!.players[0].rack.first()
        a.stageTile(CENTER, CENTER, tile, 'A')
        assertEquals(1, a.state.value!!.pending.size)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertTrue(b.state.value!!.pending.isEmpty())
        assertEquals(a.state.value!!.players[0].rack, b.state.value!!.players[0].rack)
    }

    @Test
    fun `a board tile and a blank's chosen letter survive a resume`() {
        val a = started()
        val s = a.state.value!!
        val placed = RackTile('Q', false, 901)
        val blank = RackTile(' ', true, 900)
        val board = s.board.map { it.toMutableList() }
        board[CENTER][CENTER] = BoardCell(tile = placed)
        board[CENTER][CENTER + 1] = BoardCell(tile = blank, letterOverride = 'E')
        a.state.value = s.copy(board = board)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(a.state.value!!.board, b.state.value!!.board)
        assertEquals('E', b.state.value!!.board[CENTER][CENTER + 1].effectiveLetter)
    }

    @Test
    fun `restore rejects garbage and leaves the game untouched`() {
        val g = started()
        val before = g.state.value
        assertFalse(g.restore("not json"))
        assertFalse(g.restore("{}"))
        assertEquals(before, g.state.value)
    }

    @Test
    fun `restore rejects a snapshot from a different roster`() {
        val snap = started().snapshot()!!
        val other = newGame(ids = "p0" to "someone-else")
        assertFalse(other.restore(snap))
        assertNull(other.state.value)

        val noBot = newGame(mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, bot = false)
        assertFalse(noBot.restore(snap))
    }

    @Test
    fun `restore rejects a wrong-sized board`() {
        val g = started()
        val snap = g.snapshot()!!
        val bad = snap.replace("\"boardSize\":15", "\"boardSize\":13")
        assertTrue(bad != snap)
        val target = newGame()
        assertFalse(target.restore(bad))
        assertNull(target.state.value)
    }

    @Test
    fun `restore rejects a corrupted tile letter`() {
        val g = started()
        val snap = g.snapshot()!!
        val letter = g.state.value!!.players[0].rack.first { !it.isBlank }.letter
        val bad = snap.replaceFirst("\"letter\":\"$letter\"", "\"letter\":\"7\"")
        assertTrue(bad != snap)
        assertFalse(newGame().restore(bad))
    }

    @Test
    fun `a resumed game is playable and can end normally`() {
        val a = started()
        a.pass()
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertFalse(b.state.value!!.matchOver)
        b.pass()
        assertEquals(2, b.state.value!!.consecutivePasses)
        assertNotNull(b.snapshot())
    }
}
