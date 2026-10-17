package com.gamesuite.games.hangman

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resume (snapshot/restore), Hint and the finished-word accounting for Hangman. Hangman has no
 * Undo (a guess is the whole move), so there is nothing here for it. Words are set with the
 * engine's internal test seam so every case is deterministic.
 */
class HangmanResumeHintTest {

    private val solo = listOf(PlayerInfo("you", "You"))
    private val duo = listOf(PlayerInfo("p1", "Player 1"), PlayerInfo("p2", "Player 2"))

    private fun newGame(
        players: List<PlayerInfo> = solo,
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT
    ): HangmanGame {
        val game = HangmanGame()
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        return game
    }

    private fun started(word: String, mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT): HangmanGame =
        newGame(mode = mode).also { it.startMatchWithWord(word) }

    private fun HangmanGame.guess(letters: String) = letters.forEach { guessLetter(it) }

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `there is nothing to snapshot before the first word, after the session ends, or in online play`() {
        val game = newGame()
        assertNull("no word yet", game.snapshot())

        game.startMatchWithWord("APPLE")
        assertNotNull(game.snapshot())

        game.leaveSession()
        assertNull("a finished session has nothing to resume", game.snapshot())

        assertNull("online state lives with the other players", started("APPLE", PlayMode.ONLINE).snapshot())
        assertNull("nearby state lives with the other players", started("APPLE", PlayMode.LOCAL_AD_HOC).snapshot())
    }

    @Test
    fun `a restored game keeps the same hidden word and plays on identically`() {
        val a = started("APPLE")
        a.guess("AX")
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))

        assertEquals("APPLE", b.state.value!!.word)
        assertEquals(a.state.value, b.state.value)

        a.guessLetter('P')
        b.guessLetter('P')
        assertEquals("the same guess must lead to the same position", a.state.value, b.state.value)
    }

    @Test
    fun `the tally, hints and difficulty survive a resume`() {
        val a = started("TIGER")
        a.difficulty = CpuDifficulty.HARD
        a.guess("TIGER")
        assertEquals(1, a.wins.value)
        a.startMatchWithWord("APPLE")
        a.guess("Q") // one wrong guess
        a.hint()
        assertEquals(1, a.state.value!!.hintsUsed)
        val snapshot = a.snapshot()!!

        val b = newGame()
        b.difficulty = CpuDifficulty.EASY
        assertTrue(b.restore(snapshot))
        assertEquals(1, b.wins.value)
        assertEquals(0, b.losses.value)
        assertEquals(1, b.state.value!!.hintsUsed)
        assertEquals(1, b.state.value!!.wrongGuesses)
        assertEquals(CpuDifficulty.HARD, b.difficulty)
        assertEquals(1, b.finishedWords)
    }

    @Test
    fun `a finished word comes back finished so its result panel can show`() {
        val a = started("CAT")
        a.guess("QWERYU") // six wrong guesses
        assertTrue(a.state.value!!.matchOver)
        assertEquals(1, a.losses.value)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertTrue(b.state.value!!.matchOver)
        assertFalse(b.state.value!!.won)
        assertEquals(1, b.losses.value)
        assertEquals("a resumed lost word must not be lost twice", 1, b.finishedWords)
    }

    @Test
    fun `restore rejects garbage and leaves the game untouched`() {
        val fresh = newGame()
        assertFalse(fresh.restore("not json at all"))
        assertFalse(fresh.restore("{}"))
        assertFalse(fresh.restore(""))
        assertNull("a rejected restore must not deal a word", fresh.state.value)

        val playing = started("APPLE")
        playing.guess("A")
        val before = playing.state.value
        assertFalse(playing.restore("garbage"))
        assertEquals(before, playing.state.value)
        assertEquals(0, playing.wins.value)
    }

    @Test
    fun `restore rejects a snapshot from a different roster`() {
        val snapshot = started("APPLE").snapshot()!!

        val twoPlayers = newGame(players = duo, mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
        assertFalse("a one-player save must not load into a two-player game", twoPlayers.restore(snapshot))
        assertNull(twoPlayers.state.value)

        val renamed = newGame(players = listOf(PlayerInfo("someone-else", "Other")))
        assertFalse(renamed.restore(snapshot))
        assertNull(renamed.state.value)

        val botFlagged = newGame(players = listOf(PlayerInfo("you", "You", isBot = true)))
        assertFalse(botFlagged.restore(snapshot))
    }

    @Test
    fun `restore rejects a snapshot that contradicts itself`() {
        val a = started("APPLE")
        a.guess("Z") // one wrong guess
        val good = a.snapshot()!!
        assertTrue(newGame().restore(good))

        val wrongCount = good.replace("\"wrongGuesses\":1", "\"wrongGuesses\":3")
        assertNotEquals("test setup: the edit must change the snapshot", good, wrongCount)
        val g1 = newGame()
        assertFalse("wrong-guess count must match the guessed letters", g1.restore(wrongCount))
        assertNull(g1.state.value)

        val wrongMax = good.replace("\"maxWrongGuesses\":6", "\"maxWrongGuesses\":9")
        assertNotEquals(good, wrongMax)
        assertFalse(newGame().restore(wrongMax))

        val claimsOver = good.replace("\"wordOver\":false", "\"wordOver\":true")
        assertNotEquals(good, claimsOver)
        assertFalse("a word in progress cannot be marked over", newGame().restore(claimsOver))

        val badLetter = good.replace("\"guessed\":\"Z\"", "\"guessed\":\"z\"")
        assertNotEquals(good, badLetter)
        assertFalse(newGame().restore(badLetter))
    }

    @Test
    fun `restore after the session ended re-opens the game`() {
        val a = started("APPLE")
        val snapshot = a.snapshot()!!
        val b = started("PEAR")
        b.leaveSession()
        assertTrue(b.matchOver.value)
        assertTrue(b.restore(snapshot))
        assertFalse(b.matchOver.value)
        assertEquals("APPLE", b.state.value!!.word)
    }

    // --- Finished words / abort policy ------------------------------------------------------

    @Test
    fun `finished words count solved and lost words but not the one in progress`() {
        val game = newGame()
        game.startMatchWithWord("CAT")
        assertEquals(0, game.finishedWords)

        game.guess("CA")
        assertEquals("a word in progress is not finished", 0, game.finishedWords)
        game.guessLetter('T')
        assertEquals(1, game.wins.value)
        assertEquals(1, game.finishedWords)

        game.startMatchWithWord("CAT")
        game.guess("QWERYU")
        assertEquals(1, game.losses.value)
        assertEquals("solved + lost", 2, game.finishedWords)

        game.startMatchWithWord("DOG")
        game.guess("D")
        assertEquals("the unfinished third word does not count", 2, game.finishedWords)
    }

    @Test
    fun `input after the session ends is ignored`() {
        val game = started("CAT")
        game.leaveSession()
        game.guessLetter('C')
        assertTrue(game.state.value!!.guessedLetters.isEmpty())
        assertNull(game.hint())
        assertFalse(game.canHint)
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `hint reveals one correct letter without costing a guess`() {
        val game = started("APPLE")
        val letter = game.hint()
        assertEquals('A', letter)
        val s = game.state.value!!
        assertTrue('A' in s.guessedLetters)
        assertEquals(0, s.wrongGuesses)
        assertEquals(1, s.hintsUsed)
        assertFalse(s.matchOver)

        assertEquals("the next hint reveals the next hidden letter", 'P', game.hint())
        assertEquals(2, game.state.value!!.hintsUsed)
        assertEquals(0, game.state.value!!.wrongGuesses)
    }

    @Test
    fun `hint skips letters the player already guessed`() {
        val game = started("APPLE")
        game.guessLetter('A')
        assertEquals('P', game.hint())
    }

    @Test
    fun `a hint can finish the word and it counts as an assisted win`() {
        val game = started("CAT")
        game.guess("CA")
        assertEquals('T', game.hint())
        val s = game.state.value!!
        assertTrue(s.matchOver)
        assertTrue(s.won)
        assertEquals(1, s.hintsUsed)
        assertEquals("an assisted word still counts as solved", 1, game.wins.value)
        assertEquals(1, game.finishedWords)
    }

    @Test
    fun `hint is null before a word, when the word is over and when the session is over`() {
        val fresh = newGame()
        assertNull(fresh.hint())
        assertFalse(fresh.canHint)

        val won = started("CAT")
        won.guess("CAT")
        assertNull("no hint on a finished word", won.hint())
        assertFalse(won.canHint)

        val ended = started("CAT")
        assertTrue(ended.canHint)
        ended.leaveSession()
        assertNull(ended.hint())
    }

    @Test
    fun `hints are counted per word and reset for the next one`() {
        val game = started("CAT")
        game.hint()
        game.guess("AT")
        assertEquals(1, game.state.value!!.hintsUsed)
        game.playAgain()
        assertEquals("a new word starts with no hints", 0, game.state.value!!.hintsUsed)
    }
}
