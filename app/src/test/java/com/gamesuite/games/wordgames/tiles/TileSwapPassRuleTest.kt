package com.gamesuite.games.wordgames.tiles

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The consecutive-pass ending: a tile swap counts as a pass (so players can no longer swap forever),
 * and only a real play resets the run.
 */
class TileSwapPassRuleTest {

    private fun newGame(results: MutableList<GameResult>): TileGame {
        val game = TileGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                players = listOf(PlayerInfo("p0", "Ann"), PlayerInfo("p1", "Ben")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.setOnMatchEnd { results += it }
        game.startMatch()
        return game
    }

    private fun TileGame.swapOne() {
        val s = state.value!!
        swapTiles(setOf(s.players[s.currentPlayerIndex].rack.first().instanceId))
    }

    @Test
    fun `a swap counts toward the consecutive passes`() {
        val game = newGame(mutableListOf())
        game.swapOne()
        assertEquals(1, game.state.value!!.consecutivePasses)
        game.pass()
        assertEquals(2, game.state.value!!.consecutivePasses)
        game.swapOne()
        assertEquals(3, game.state.value!!.consecutivePasses)
    }

    @Test
    fun `four swaps in a row end the game instead of going on forever`() {
        val results = mutableListOf<GameResult>()
        val game = newGame(results)
        repeat(3) { game.swapOne() }
        assertFalse(game.state.value!!.matchOver)
        game.swapOne()
        assertTrue(game.state.value!!.matchOver)
        assertEquals(1, results.size)
        assertFalse(results.single().wasAborted)
        assertEquals(2, game.state.value!!.leaders().size)
    }

    @Test
    fun `swaps and passes mix toward the same ending`() {
        val results = mutableListOf<GameResult>()
        val game = newGame(results)
        game.pass()
        game.swapOne()
        game.pass()
        assertFalse(game.state.value!!.matchOver)
        game.swapOne()
        assertTrue(game.state.value!!.matchOver)
        assertEquals(1, results.size)
    }

    @Test
    fun `a swap hands over the turn, keeps the rack size, and leaves nothing staged`() {
        val game = newGame(mutableListOf())
        val s0 = game.state.value!!
        val tile = s0.players[0].rack.first()
        game.stageTile(7, 7, tile, tile.letter)
        game.swapTiles(setOf(tile.instanceId))
        val s = game.state.value!!
        assertEquals(1, s.currentPlayerIndex)
        assertEquals(7, s.players[0].rack.size)
        assertTrue(s.pending.isEmpty())
    }

    @Test
    fun `a swap never hands back the very tile that was given up`() {
        repeat(300) {
            val game = newGame(mutableListOf())
            val given = game.state.value!!.players[0].rack.first().instanceId
            game.swapTiles(setOf(given))
            assertTrue(game.state.value!!.players[0].rack.none { it.instanceId == given })
        }
    }

    @Test
    fun `only a real play resets the run of passes and swaps`() = TileTestKit.withWords("cat") {
        val results = mutableListOf<GameResult>()
        val game = newGame(results)
        game.pass()      // Ann
        game.swapOne()   // Ben
        game.pass()      // Ann
        assertEquals(3, game.state.value!!.consecutivePasses)

        // Ben plays CAT over the centre.
        val s = game.state.value!!
        game.state.value = s.copy(
            players = s.players.mapIndexed { i, p -> if (i == 1) p.copy(rack = TileTestKit.tiles("CATQZJX", 900)) else p }
        )
        val rack = game.state.value!!.players[1].rack
        game.stageTile(7, 7, rack[0], 'C')
        game.stageTile(7, 8, rack[1], 'A')
        game.stageTile(7, 9, rack[2], 'T')
        assertNull(game.submitMove())
        assertEquals(0, game.state.value!!.consecutivePasses)
        assertEquals(0, game.state.value!!.currentPlayerIndex)

        repeat(3) { game.pass() }
        assertFalse("three more is still not everyone twice", game.state.value!!.matchOver)
        assertTrue(results.isEmpty())
        game.pass()
        assertTrue(game.state.value!!.matchOver)
        assertEquals(1, results.size)
    }
}
