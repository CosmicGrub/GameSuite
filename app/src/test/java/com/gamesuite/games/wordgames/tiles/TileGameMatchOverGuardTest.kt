package com.gamesuite.games.wordgames.tiles

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.wordgames.WordDictionary
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The corner menu can end a Word Tiles game (abort) while a tap or the bot's delayed turn is still
 * in flight, and a finished game stays on screen for its result panel. These pin the engine side:
 * once the match is over every input is a no-op, the end is reported exactly once, a tie is a tie,
 * and a word-play event says whether the human or a bot made it (so only the human's plays get the
 * success feedback).
 *
 * Move validation and scoring need the word list, which only loads from an Android asset, so they
 * are not covered here; only the one test that needs a dictionary installs a tiny fake one.
 */
class TileGameMatchOverGuardTest {

    private fun newGame(secondIsBot: Boolean = false): TileGame {
        val game = TileGame()
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(
                    PlayerInfo(playerId = "you", displayName = "You"),
                    PlayerInfo(playerId = "cpu", displayName = "CPU", isBot = secondIsBot)
                ),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        return game
    }

    /** Two players passing twice each in a row: the engine's "everyone passed" end (players * 2 passes). */
    private fun TileGame.passUntilOver() {
        repeat(4) { pass() }
    }

    @Test
    fun `staging and passing work while the match is live (control for the guard test below)`() {
        val game = newGame()
        game.startMatch()
        val tile = game.state.value!!.players[0].rack.first()

        game.stageTile(7, 7, tile, tile.letter)
        assertEquals(1, game.state.value!!.pending.size)

        game.pass()
        assertEquals(1, game.state.value!!.currentPlayerIndex)
        assertTrue(game.state.value!!.pending.isEmpty())
    }

    @Test
    fun `every player passing twice in a row ends the match as a tie`() {
        val game = newGame()
        val results = mutableListOf<GameResult>()
        game.setOnMatchEnd { results += it }
        game.startMatch()

        repeat(3) { game.pass() }
        assertFalse("three passes are not yet everyone twice", game.state.value!!.matchOver)
        assertTrue(results.isEmpty())

        game.pass()
        assertTrue(game.state.value!!.matchOver)
        val result = results.single()
        assertFalse(result.wasAborted)
        assertEquals(2, result.scores.size)
        assertTrue("equal scores are a tie, so both are flagged winner", result.scores.all { it.isWinner })
        assertEquals(2, game.state.value!!.leaders().size)
    }

    @Test
    fun `input is ignored once the match is over`() {
        val game = newGame()
        game.startMatch()
        game.passUntilOver()
        val over = game.state.value!!
        assertTrue(over.matchOver)
        val tile = over.players[0].rack.first()

        game.stageTile(7, 7, tile, 'A')
        game.unstageTile(7, 7)
        game.clearStaged()
        game.pass()
        game.swapTiles(setOf(tile.instanceId))
        assertEquals("The game is over", game.submitMove())
        game.playBotTurn()

        assertSame("nothing may touch a finished game", over, game.state.value)
    }

    @Test
    fun `abortMatch reports one aborted score-less result and ends the match`() {
        val game = newGame()
        val results = mutableListOf<GameResult>()
        game.setOnMatchEnd { results += it }
        game.startMatch()

        game.abortMatch()
        game.abortMatch()

        assertEquals(1, results.size)
        assertTrue(results.single().wasAborted)
        assertTrue(results.single().scores.isEmpty())
        assertTrue(game.state.value!!.matchOver)
    }

    @Test
    fun `an abort after the game already finished adds no second result`() {
        val game = newGame()
        val results = mutableListOf<GameResult>()
        game.setOnMatchEnd { results += it }
        game.startMatch()
        game.passUntilOver()

        game.abortMatch()

        assertEquals(1, results.size)
        assertFalse(results.single().wasAborted)
    }

    @Test
    fun `startMatch after a finished match accepts input and reports its own end again`() {
        val game = newGame()
        val results = mutableListOf<GameResult>()
        game.setOnMatchEnd { results += it }
        game.startMatch()
        game.passUntilOver()

        game.startMatch()
        val fresh = game.state.value!!
        assertFalse(fresh.matchOver)
        game.pass()
        assertNotSame(fresh, game.state.value)
        assertEquals(1, game.state.value!!.currentPlayerIndex)

        repeat(3) { game.pass() }
        assertEquals(2, results.size)
    }

    @Test
    fun `leaders names a sole winner, every tied player, and nobody for an empty roster`() {
        fun player(id: String, score: Int) =
            TilePlayerState(playerId = id, displayName = id, isBot = false, rack = emptyList(), score = score)

        fun stateOf(vararg players: TilePlayerState) = TileGameState(
            board = emptyList(),
            players = players.toList(),
            currentPlayerIndex = 0,
            bagCount = 0
        )

        assertEquals(listOf("b"), stateOf(player("a", 10), player("b", 12)).leaders().map { it.playerId })
        assertEquals(
            listOf("a", "b"),
            stateOf(player("a", 12), player("b", 12), player("c", 3)).leaders().map { it.playerId }
        )
        assertTrue(stateOf().leaders().isEmpty())
    }

    @Test
    fun `a word play result says who played and whether it was a bot`() {
        // WordDictionary only loads from an Android asset, which a plain JVM test cannot reach, so
        // install a one-word fake through its private field and always put the original set back.
        // The field is a private static one (a Kotlin `object`'s property), so the target argument of
        // Field.get/set is ignored; the object itself is passed anyway so this also works unchanged
        // if the field ever becomes an instance field.
        val field = WordDictionary::class.java.getDeclaredField("allWords").apply { isAccessible = true }
        val original = field.get(WordDictionary)
        try {
            val game = newGame(secondIsBot = true)
            val plays = mutableListOf<WordPlayResult>()
            game.setOnWordPlayed { plays += it }
            game.startMatch()

            // Seat 0 (the human) opens with a two-letter word across the centre star.
            val first = game.state.value!!.players[0].rack.filter { !it.isBlank }.take(2)
            val opening = first.joinToString("") { it.letter.toString() }
            field.set(WordDictionary, setOf(opening.lowercase()))
            game.stageTile(7, 7, first[0], first[0].letter)
            game.stageTile(7, 8, first[1], first[1].letter)
            assertNull(game.submitMove())
            assertEquals(1, plays.size)
            assertEquals(0, plays[0].playerIndex)
            assertFalse(plays[0].byBot)

            // Seat 1 is flagged as a bot (submitMove does not care who drives it) and extends the word.
            val extra = game.state.value!!.players[1].rack.first { !it.isBlank }
            field.set(WordDictionary, setOf((opening + extra.letter).lowercase()))
            game.stageTile(7, 9, extra, extra.letter)
            assertNull(game.submitMove())
            assertEquals(2, plays.size)
            assertEquals(1, plays[1].playerIndex)
            assertTrue(plays[1].byBot)
        } finally {
            field.set(WordDictionary, original)
        }
    }
}
