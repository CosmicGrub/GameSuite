package com.gamesuite.games.tictactoe

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import com.gamesuite.transport.MultiplayerTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The leave/abort lifecycle that TicTacToeScreen's shared GameChrome drives: [abortMatch] must
 * end the match exactly once (the chrome guards its own double-tap, but the engine must hold up
 * on its own), and once the match is over every input function must be inert, including the
 * networked-guest paths that forward an intent to the host instead of applying it locally.
 * Rules and bot behaviour are covered by TicTacToeGameTest and the LAN test in the shared module.
 */
class TicTacToeGameLifecycleTest {

    /** Counts what the game sends; delivers nothing, so a guest never hears back from a host. */
    private class RecordingTransport : MultiplayerTransport {
        var sendCount = 0
        override fun connect() {}
        override fun disconnect() {}
        override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {
            sendCount++
        }
        override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) {}
        override fun onPlayerJoined(listener: (playerId: String) -> Unit) {}
        override fun onPlayerLeft(listener: (playerId: String) -> Unit) {}
    }

    private fun newLocalGame(): TicTacToeGame {
        val game = TicTacToeGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                players = listOf(
                    PlayerInfo(playerId = "p1", displayName = "Player 1"),
                    PlayerInfo(playerId = "p2", displayName = "Player 2")
                ),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        return game
    }

    @Test
    fun abortMatchEndsTheMatchOnceAsAnAbortWithNoScores() {
        val game = newLocalGame()
        val results = mutableListOf<GameResult>()
        game.setOnMatchEnd { result -> results.add(result) }

        game.abortMatch()
        game.abortMatch()

        assertTrue(game.matchOver.value)
        assertEquals("a second abort must not fire onMatchEnd again", 1, results.size)
        assertTrue(results[0].wasAborted)
        assertTrue(results[0].scores.isEmpty())
    }

    @Test
    fun abortAfterLeaveSessionDoesNotFireTheCallbackAgain() {
        val game = newLocalGame()
        var ends = 0
        game.setOnMatchEnd { ends += 1 }

        game.leaveSession()
        game.abortMatch()

        assertEquals(1, ends)
    }

    @Test
    fun leaveSessionAfterAbortDoesNotFireTheCallbackAgain() {
        val game = newLocalGame()
        var ends = 0
        game.setOnMatchEnd { ends += 1 }

        game.abortMatch()
        game.leaveSession()

        assertEquals(1, ends)
    }

    @Test
    fun inputIsIgnoredOnceTheMatchIsOver() {
        val game = newLocalGame()
        game.abortMatch()
        val boardBefore = game.board.value.copyOf()
        val roundBefore = game.roundNumber.value

        game.cellClicked(4)
        game.chooseSymbol(2)
        game.playAgain()

        assertTrue("board must not change after the match ended", boardBefore.contentEquals(game.board.value))
        assertEquals("chooseSymbol must not change the picker after the match ended", 1, game.selectedSymbol.value)
        assertEquals("playAgain must not start a new round after the match ended", roundBefore, game.roundNumber.value)
    }

    @Test
    fun networkedGuestForwardsNothingOnceTheMatchIsOver() {
        val transport = RecordingTransport()
        val guest = TicTacToeGame()
        guest.init(
            GameContext(
                activeMode = PlayMode.LOCAL_AD_HOC,
                players = listOf(
                    PlayerInfo(playerId = "host", displayName = "Host"),
                    PlayerInfo(playerId = "guest", displayName = "Guest")
                ),
                localPlayerIndex = 1,
                transport = transport
            )
        )
        val sentAfterInit = transport.sendCount // the guest's own RequestState

        // Sanity: while the match is live, a guest tap IS forwarded to the host.
        guest.cellClicked(0)
        assertEquals(sentAfterInit + 1, transport.sendCount)

        guest.abortMatch()
        guest.cellClicked(1)
        guest.playAgain()

        assertEquals(
            "a finished match must not send intents to the host",
            sentAfterInit + 1,
            transport.sendCount
        )
    }
}
