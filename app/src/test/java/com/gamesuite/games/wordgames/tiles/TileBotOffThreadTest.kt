package com.gamesuite.games.wordgames.tiles

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CPU's word search is split so the screen can run it off the main thread: prepare (main thread,
 * cheap) -> search (any thread, pure) -> apply (main thread, only if the position is unchanged).
 */
class TileBotOffThreadTest {

    private fun botGame(botFirst: Boolean = true): TileGame {
        val game = TileGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true)),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        if (botFirst) game.firstMoverIndex = 1
        game.startMatch()
        return game
    }

    @Test
    fun `there is nothing to prepare unless it is the CPU's turn in a live game`() {
        val human = botGame(botFirst = false)
        assertNull(human.prepareBotTurn())

        val bot = botGame()
        assertNotNull(bot.prepareBotTurn())
        bot.abortMatch()
        assertNull(bot.prepareBotTurn())
    }

    @Test
    fun `searching changes nothing, even from another thread`() = TileTestKit.withWords("cat") {
        val game = botGame()
        val request = game.prepareBotTurn()!!
        val before = game.state.value
        var move: List<TileBot.PendingPlacementSpec>? = emptyList()
        val worker = Thread { move = game.searchBotMove(request) }
        worker.start()
        worker.join()
        assertSame(before, game.state.value)
        // Whatever it found, applying it on the "main" thread now takes the turn.
        assertTrue(game.applyBotMove(request, move))
        assertEquals(0, game.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `a result is dropped when the game moved on while the search ran`() {
        val game = botGame()
        val request = game.prepareBotTurn()!!
        game.pass() // the position changed (the CPU's seat passed some other way)
        val after = game.state.value
        assertFalse(game.applyBotMove(request, null))
        assertSame(after, game.state.value)
    }

    @Test
    fun `a result is dropped after a restart or an abort`() {
        val restarted = botGame()
        val r1 = restarted.prepareBotTurn()!!
        restarted.startMatch()
        val fresh = restarted.state.value
        assertFalse(restarted.applyBotMove(r1, null))
        assertSame(fresh, restarted.state.value)

        val aborted = botGame()
        val r2 = aborted.prepareBotTurn()!!
        aborted.abortMatch()
        val over = aborted.state.value
        assertFalse(aborted.applyBotMove(r2, null))
        assertSame(over, aborted.state.value)
        assertTrue(over!!.matchOver)
    }

    @Test
    fun `a fresh result with no move makes the CPU pass`() {
        val game = botGame()
        val request = game.prepareBotTurn()!!
        assertTrue(game.applyBotMove(request, null))
        val s = game.state.value!!
        assertEquals(0, s.currentPlayerIndex)
        assertEquals("CPU passed", s.lastAction)
        assertEquals(1, s.consecutivePasses)
    }

    @Test
    fun `playBotTurn still works as one synchronous call`() {
        val game = botGame()
        game.playBotTurn()
        assertEquals(0, game.state.value!!.currentPlayerIndex)
        // And is a no-op on a human's turn.
        val before = game.state.value
        game.playBotTurn()
        assertSame(before, game.state.value)
    }
}
