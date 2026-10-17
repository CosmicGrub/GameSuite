package com.gamesuite.games.wordgames.tiles

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Game Hub options for [TileGame]: who moves first, and the two-human pass-and-play flow. Dictionary-free. */
class TileGameHubOptionsTest {

    private fun vsBot(): TileGame = TileGame().also {
        it.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(
                    PlayerInfo(playerId = "p1", displayName = "You"),
                    PlayerInfo(playerId = "bot1", displayName = "CPU", isBot = true)
                ),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
    }

    private fun passAndPlay(): TileGame = TileGame().also {
        it.init(
            GameContext(
                activeMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                players = listOf(
                    PlayerInfo(playerId = "p1", displayName = "Ann"),
                    PlayerInfo(playerId = "p2", displayName = "Ben")
                ),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
    }

    @Test
    fun `the default first mover is seat 0`() {
        val game = vsBot()
        game.startMatch()
        assertEquals(0, game.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `a named first mover takes the first turn without reordering the roster`() {
        val game = vsBot()
        game.firstMoverIndex = 1
        game.startMatch()
        val s = game.state.value!!
        assertEquals(1, s.currentPlayerIndex)
        assertTrue(s.players[1].isBot)
        assertEquals("p1", s.players[0].playerId)
        assertEquals("bot1", s.players[1].playerId)
    }

    @Test
    fun `an out of range first mover is clamped`() {
        val high = vsBot()
        high.firstMoverIndex = 9
        high.startMatch()
        assertEquals(1, high.state.value!!.currentPlayerIndex)

        val low = vsBot()
        low.firstMoverIndex = -3
        low.startMatch()
        assertEquals(0, low.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `the first mover is consumed by startMatch`() {
        val game = vsBot()
        game.firstMoverIndex = 1
        game.startMatch()
        assertEquals(0, game.firstMoverIndex)
        game.startMatch()
        assertEquals(0, game.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `a restored game ignores the first mover`() {
        val a = vsBot()
        a.startMatch()
        a.pass()
        val snap = a.snapshot()!!
        assertEquals(1, a.state.value!!.currentPlayerIndex)

        val b = vsBot()
        b.firstMoverIndex = 0
        assertTrue(b.restore(snap))
        assertEquals(1, b.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `pass and play alternates two humans with their own racks and names`() {
        val game = passAndPlay()
        game.firstMoverIndex = 1
        game.startMatch()
        var s = game.state.value!!
        assertEquals(1, s.currentPlayerIndex)
        assertFalse(s.players.any { it.isBot })
        assertEquals(listOf("Ann", "Ben"), s.players.map { it.displayName })
        assertEquals(7, s.players[0].rack.size)
        assertEquals(7, s.players[1].rack.size)

        game.pass()
        s = game.state.value!!
        assertEquals(0, s.currentPlayerIndex)
        assertEquals("Ben passed", s.lastAction)
        game.pass()
        assertEquals("Ann passed", game.state.value!!.lastAction)
    }

    @Test
    fun `pass and play swap names the swapper and hands the turn over`() {
        val game = passAndPlay()
        game.startMatch()
        val before = game.state.value!!.players[0].rack
        game.swapTiles(setOf(before.first().instanceId))
        val s = game.state.value!!
        assertEquals(1, s.currentPlayerIndex)
        assertEquals("Ann swapped 1 tile(s)", s.lastAction)
        assertEquals(7, s.players[0].rack.size)
    }

    @Test
    fun `two passes each end the match with scores per player id`() {
        val game = passAndPlay()
        val results = mutableListOf<GameResult>()
        game.setOnMatchEnd { results.add(it) }
        game.startMatch()
        repeat(4) { game.pass() }
        val s = game.state.value!!
        assertTrue(s.matchOver)
        assertEquals(1, results.size)
        assertEquals(listOf("p1", "p2"), results[0].scores.map { it.playerId })
        // Nobody scored: a tie, and the engine reports both as leaders.
        assertEquals(2, s.leaders().size)
    }

    @Test
    fun `a sole leader is named by display name`() {
        val game = passAndPlay()
        game.startMatch()
        val s = game.state.value!!
        val ahead = s.copy(players = s.players.mapIndexed { i, p -> if (i == 1) p.copy(score = 12) else p })
        assertEquals(listOf("Ben"), ahead.leaders().map { it.displayName })
    }
}
