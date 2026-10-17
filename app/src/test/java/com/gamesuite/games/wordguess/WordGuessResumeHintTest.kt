package com.gamesuite.games.wordguess

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resume (snapshot/restore) and Hint for Word Guess. No Android assets are touched: the word pool and the clock are injected. */
class WordGuessResumeHintTest {

    private val pool = listOf("apple", "melts", "lemon", "tiger", "zebra", "mango", "crisp", "sound")

    private class Rig(val game: WordGuessGame, val clock: LongArray)

    private fun rig(
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT,
        players: List<PlayerInfo> = listOf(PlayerInfo("p1", "Player 1")),
        fixedSecret: String? = null,
        startClock: Long = 0L
    ): Rig {
        val clock = longArrayOf(startClock)
        val game = WordGuessGame(
            nowMillis = { clock[0] },
            pickSecret = { random -> fixedSecret ?: pool.random(random) },
            isValidGuess = { true }
        )
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        return Rig(game, clock)
    }

    private fun started(fixedSecret: String? = "apple", startClock: Long = 0L): Rig =
        rig(fixedSecret = fixedSecret, startClock = startClock).also { it.game.startMatch() }

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `there is nothing to snapshot before the first word, after the session ends, or in online play`() {
        val r = rig(fixedSecret = "apple")
        assertNull(r.game.snapshot())

        r.game.startMatch()
        assertNotNull(r.game.snapshot())

        r.game.leaveSession()
        assertNull(r.game.snapshot())

        val online = rig(mode = PlayMode.ONLINE, fixedSecret = "apple")
        online.game.startMatch()
        assertNull(online.game.snapshot())
    }

    @Test
    fun `a restored word has the same secret, guesses and tally and plays on identically`() {
        val a = started(fixedSecret = null)
        a.game.submitGuess("tiger")
        a.game.submitGuess("zebra")
        val snapshot = a.game.snapshot()!!

        val b = rig(fixedSecret = "other")
        assertTrue(b.game.restore(snapshot))

        assertEquals(a.game.state.value, b.game.state.value)
        assertEquals(a.game.puzzlesSolved.value, b.game.puzzlesSolved.value)
        assertEquals(a.game.puzzlesFailed.value, b.game.puzzlesFailed.value)

        val secret = a.game.state.value!!.secret
        a.game.submitGuess(secret)
        b.game.submitGuess(secret)
        assertEquals(a.game.state.value, b.game.state.value)
        assertTrue(b.game.state.value!!.solved)
        assertEquals(1, b.game.puzzlesSolved.value)
    }

    @Test
    fun `the clock continues from the active time and ignores the time the app was gone`() {
        val a = started(startClock = 1000L)
        a.game.submitGuess("tiger") // stopwatch starts at 1000
        a.clock[0] = 4000L
        val snapshot = a.game.snapshot()!!

        val b = rig(fixedSecret = "apple", startClock = 90_000L)
        assertTrue(b.game.restore(snapshot))
        assertEquals(3000L, b.game.activeElapsedMillis())
        b.clock[0] = 91_000L
        assertEquals(4000L, b.game.activeElapsedMillis())
    }

    @Test
    fun `a paused clock is saved frozen at its active time`() {
        val a = started(startClock = 1000L)
        a.game.submitGuess("tiger")
        a.clock[0] = 4000L
        a.game.pause()
        a.clock[0] = 9000L
        val snapshot = a.game.snapshot()!!

        val b = rig(fixedSecret = "apple", startClock = 500L)
        assertTrue(b.game.restore(snapshot))
        assertEquals(3000L, b.game.activeElapsedMillis())
    }

    @Test
    fun `a stopwatch that had not started stays unstarted`() {
        val a = started()
        val b = rig(fixedSecret = "apple", startClock = 50L)
        assertTrue(b.game.restore(a.game.snapshot()!!))
        assertNull(b.game.timerStartElapsedRealtime.value)
        assertNull(b.game.activeElapsedMillis())
    }

    @Test
    fun `a decided word keeps its frozen time and its place in the tally`() {
        val a = started(startClock = 100L)
        a.game.submitGuess("tiger")
        a.clock[0] = 2100L
        a.game.submitGuess("apple")
        assertTrue(a.game.state.value!!.solved)
        val frozen = a.game.finishedElapsedMillis.value
        assertEquals(2000L, frozen)

        val b = rig(fixedSecret = "other", startClock = 777_000L)
        assertTrue(b.game.restore(a.game.snapshot()!!))
        assertEquals(a.game.state.value, b.game.state.value)
        assertEquals(frozen, b.game.finishedElapsedMillis.value)
        assertEquals(frozen, b.game.activeElapsedMillis())
        assertEquals(1, b.game.puzzlesSolved.value)
    }

    @Test
    fun `a lost word and the failed tally survive a round trip`() {
        val a = started()
        a.game.difficulty = CpuDifficulty.HARD
        a.game.startMatch()
        repeat(5) { a.game.submitGuess("tiger") }
        assertTrue(a.game.state.value!!.outOfGuesses)
        assertEquals(1, a.game.puzzlesFailed.value)

        val b = rig(fixedSecret = "other")
        assertTrue(b.game.restore(a.game.snapshot()!!))
        assertTrue(b.game.state.value!!.outOfGuesses)
        assertEquals(1, b.game.puzzlesFailed.value)
        assertEquals(CpuDifficulty.HARD, b.game.difficulty)
    }

    @Test
    fun `the tier and the daily seed are restored and the daily word is the same word`() {
        val a = rig()
        a.game.difficulty = CpuDifficulty.HARD
        a.game.startMatch(42L)
        a.game.submitGuess("tiger")
        assertEquals(42L, a.game.activeDailySeed)

        val b = rig()
        assertTrue(b.game.restore(a.game.snapshot()!!))
        assertEquals(42L, b.game.activeDailySeed)
        assertEquals(CpuDifficulty.HARD, b.game.difficulty)
        assertEquals(5, b.game.state.value!!.maxGuesses)
        assertEquals(pool.random(Random(42L)), b.game.state.value!!.secret)

        val free = rig(fixedSecret = "apple")
        free.game.startMatch()
        assertNull(free.game.activeDailySeed)
    }

    @Test
    fun `hints used and hinted positions survive a round trip`() {
        val a = started()
        a.game.hint()
        a.game.hint()
        val b = rig(fixedSecret = "apple")
        assertTrue(b.game.restore(a.game.snapshot()!!))
        assertEquals(2, b.game.hintsUsed.value)
        assertTrue(b.game.assisted)
        assertNull("the shown hint is transient", b.game.activeHint.value)
        // The two earlier hints were positions 0 and 1, so the next one moves on to position 2.
        assertEquals(2, b.game.hint()!!.position)
    }

    @Test
    fun `restore rejects garbage, a wrong roster and a wrong-sized allowance and leaves the engine untouched`() {
        val a = started()
        a.game.submitGuess("tiger")
        val good = a.game.snapshot()!!

        val target = started(fixedSecret = "mango")
        target.game.submitGuess("lemon")
        val before = target.game.state.value
        val solvedBefore = target.game.puzzlesSolved.value

        assertFalse(target.game.restore("not json"))
        assertFalse(target.game.restore("{}"))
        assertFalse(target.game.restore(good.replace("\"maxGuesses\":6", "\"maxGuesses\":7")))
        assertFalse(target.game.restore(good.replace("\"secret\":\"apple\"", "\"secret\":\"app\"")))
        assertFalse(target.game.restore(good.replace("\"playerId\":\"p1\"", "\"playerId\":\"zz\"")))
        assertFalse(target.game.restore(good.replace("\"difficulty\":\"MEDIUM\"", "\"difficulty\":\"NOPE\"")))

        val botRoster = rig(players = listOf(PlayerInfo("p1", "Player 1", isBot = true)), fixedSecret = "mango")
        botRoster.game.startMatch()
        val botBefore = botRoster.game.state.value
        assertFalse(botRoster.game.restore(good))
        assertEquals(botBefore, botRoster.game.state.value)

        val twoPlayers = rig(players = listOf(PlayerInfo("p1", "Player 1"), PlayerInfo("p2", "Player 2")), fixedSecret = "mango")
        twoPlayers.game.startMatch()
        assertFalse(twoPlayers.game.restore(good))

        assertEquals(before, target.game.state.value)
        assertEquals(solvedBefore, target.game.puzzlesSolved.value)
        assertEquals(1, target.game.state.value!!.guesses.size)
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `a hint reveals the first letter position that is not green, then moves on`() {
        val r = started()
        val first = r.game.hint()!!
        assertEquals(0, first.position)
        assertEquals('a', first.letter)
        assertEquals("Hint: letter 1 is A", first.message)
        assertEquals(first, r.game.activeHint.value)
        assertEquals(1, r.game.hintsUsed.value)
        assertTrue(r.game.assisted)

        val second = r.game.hint()!!
        assertEquals(1, second.position)
        assertEquals('p', second.letter)
        assertEquals(2, r.game.hintsUsed.value)
    }

    @Test
    fun `a hint skips letters that are already green`() {
        val r = started()
        r.game.submitGuess("axxxx") // position 0 is green
        assertEquals(1, r.game.hint()!!.position)

        val r2 = started()
        r2.game.submitGuess("applx") // positions 0-3 green
        val h = r2.game.hint()!!
        assertEquals(4, h.position)
        assertEquals('e', h.letter)
    }

    @Test
    fun `a hint does not start the stopwatch or type anything`() {
        val r = started()
        r.game.hint()
        assertNull(r.game.timerStartElapsedRealtime.value)
        assertTrue(r.game.state.value!!.guesses.isEmpty())
    }

    @Test
    fun `a hint is null when there is nothing to suggest`() {
        val notStarted = rig(fixedSecret = "apple")
        assertNull(notStarted.game.hint())

        val solved = started()
        solved.game.submitGuess("apple")
        assertNull(solved.game.hint())
        assertEquals(0, solved.game.hintsUsed.value)

        val ended = started()
        ended.game.leaveSession()
        assertNull(ended.game.hint())
    }

    @Test
    fun `the next guess clears the shown hint but keeps the count`() {
        val r = started()
        r.game.hint()
        assertNotNull(r.game.activeHint.value)
        r.game.submitGuess("tiger")
        assertNull(r.game.activeHint.value)
        assertEquals(1, r.game.hintsUsed.value)
    }

    @Test
    fun `a solve with a hint still counts as solved but is marked assisted`() {
        val r = started()
        r.game.hint()
        r.game.submitGuess("apple")
        assertTrue(r.game.state.value!!.solved)
        assertEquals(1, r.game.puzzlesSolved.value)
        assertTrue("the screen skips the record store when assisted", r.game.assisted)
    }

    @Test
    fun `a new word starts with no hints`() {
        val r = started()
        r.game.hint()
        r.game.startMatch()
        assertEquals(0, r.game.hintsUsed.value)
        assertFalse(r.game.assisted)
        assertNull(r.game.activeHint.value)
        r.game.hint()
        r.game.playAgain()
        assertEquals(0, r.game.hintsUsed.value)
    }
}
