package com.gamesuite.games.mastermind

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

/** Resume (snapshot/restore) and Hint for Mastermind, written against the Connect Four reference tests. */
class MastermindResumeHintTest {

    private val solo = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1"))

    private fun newGame(
        clock: () -> Long = { 0L },
        players: List<PlayerInfo> = solo,
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT,
        difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    ): MastermindGame {
        val game = MastermindGame(nowMillis = clock)
        game.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
        game.difficulty = difficulty
        return game
    }

    /** Independent peg scoring (multiset overlap minus blacks), same idea as MastermindGameTest. */
    private fun score(guess: List<Int>, secret: List<Int>): Pair<Int, Int> {
        var black = 0
        for (i in guess.indices) if (guess[i] == secret[i]) black++
        val g = guess.groupingBy { it }.eachCount()
        val s = secret.groupingBy { it }.eachCount()
        val overlap = (g.keys + s.keys).sumOf { minOf(g[it] ?: 0, s[it] ?: 0) }
        return black to (overlap - black)
    }

    /** A guess that is guaranteed not to be the secret. */
    private fun wrongGuess(game: MastermindGame): List<Int> {
        val s = game.state.value!!
        val first = MutableList(s.positions) { 0 }
        return if (first != s.secret) first else MutableList(s.positions) { 1 }
    }

    // --- Resume -----------------------------------------------------------------------------

    @Test
    fun `there is nothing to snapshot before the first code, after the session ends, or online`() {
        val game = newGame()
        assertNull("no code dealt yet", game.snapshot())

        game.startMatch()
        assertNotNull(game.snapshot())

        game.leaveSession()
        assertNull("a finished session has nothing to resume", game.snapshot())

        val online = newGame(mode = PlayMode.ONLINE).also { it.startMatch() }
        assertNull("online state lives with the other players", online.snapshot())
    }

    @Test
    fun `a restored game is identical and plays on identically`() {
        val a = newGame()
        a.startMatch()
        a.submitGuess(wrongGuess(a))
        a.submitGuess(List(4) { 2 })
        val snapshot = a.snapshot()!!

        val b = newGame()
        assertTrue(b.restore(snapshot))

        assertEquals(a.state.value, b.state.value)
        assertEquals("the same hidden secret", a.state.value!!.secret, b.state.value!!.secret)
        assertEquals(a.difficulty, b.difficulty)
        assertFalse(b.matchOver.value)

        a.submitGuess(listOf(3, 1, 4, 0))
        b.submitGuess(listOf(3, 1, 4, 0))
        assertEquals("the same guess must give the same feedback", a.state.value, b.state.value)
    }

    @Test
    fun `tally, tier, daily seed and hint count survive a resume`() {
        val a = newGame(difficulty = CpuDifficulty.HARD)
        a.startMatch(dailySeed = 42L)
        a.submitGuess(a.state.value!!.secret) // solve the daily code
        a.playAgain()
        assertNull("a fresh random code is no longer the daily one", a.activeDailySeed.value)
        a.submitGuess(wrongGuess(a))
        a.hint()
        assertEquals(1, a.puzzlesSolved.value)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(1, b.puzzlesSolved.value)
        assertEquals(0, b.puzzlesFailed.value)
        assertEquals(CpuDifficulty.HARD, b.difficulty)
        assertEquals(5, b.state.value!!.positions)
        assertEquals(1, b.hintsUsed.value)
        assertNull(b.activeDailySeed.value)
        assertNull("a hint highlight is transient", b.hintCode.value)
    }

    @Test
    fun `an unfinished daily code stays the daily code`() {
        val a = newGame()
        a.startMatch(dailySeed = 20_000L)
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertEquals(20_000L, b.activeDailySeed.value)
        assertEquals(a.state.value!!.secret, b.state.value!!.secret)
    }

    @Test
    fun `the round number stays monotonic across a restore`() {
        val a = newGame()
        a.startMatch()
        a.playAgain()
        a.playAgain()
        val before = a.roundNumber.value
        val snapshot = a.snapshot()!!

        val fresh = newGame()
        assertTrue(fresh.restore(snapshot))
        assertEquals(before, fresh.roundNumber.value)

        // Restoring into an engine that has already used that number must not hand it out again.
        assertTrue(a.restore(snapshot))
        assertTrue(a.roundNumber.value > before)
    }

    @Test
    fun `the clock continues from the active elapsed time and ignores the time the app was gone`() {
        var clock = 1_000L
        val a = newGame(clock = { clock })
        a.startMatch()
        assertNull("unstarted stopwatch", a.activeElapsedMillis())
        a.submitGuess(wrongGuess(a)) // stopwatch starts at 1000
        clock = 5_000L
        assertEquals(4_000L, a.activeElapsedMillis())
        val snapshot = a.snapshot()!!

        var clockB = 900_000L // a different boot, much later
        val b = newGame(clock = { clockB })
        assertTrue(b.restore(snapshot))
        assertEquals(4_000L, b.activeElapsedMillis())
        clockB = 901_000L
        assertEquals("the clock continues, it does not restart", 5_000L, b.activeElapsedMillis())
    }

    @Test
    fun `a paused clock is saved as its frozen active time`() {
        var clock = 0L
        val a = newGame(clock = { clock })
        a.startMatch()
        clock = 100L
        a.submitGuess(wrongGuess(a))
        clock = 2_100L
        a.pause()
        clock = 50_000L
        val snapshot = a.snapshot()!!

        var clockB = 10L
        val b = newGame(clock = { clockB })
        assertTrue(b.restore(snapshot))
        clockB = 10L + 7L
        assertEquals(2_000L + 7L, b.activeElapsedMillis())
    }

    @Test
    fun `an unstarted timer stays unstarted`() {
        val a = newGame()
        a.startMatch()
        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertNull(b.timerStartElapsedRealtime.value)
        assertNull(b.activeElapsedMillis())
    }

    @Test
    fun `a finished code resumes showing its result and is not recorded twice`() {
        var clock = 0L
        val a = newGame(clock = { clock })
        a.startMatch()
        clock = 10L
        a.submitGuess(a.state.value!!.secret)
        clock = 90L
        a.markResultHandled()
        val finished = a.finishedElapsedMillis.value
        assertNotNull(finished)

        val b = newGame()
        assertTrue(b.restore(a.snapshot()!!))
        assertTrue(b.state.value!!.solved)
        assertEquals(1, b.puzzlesSolved.value)
        assertEquals(finished, b.finishedElapsedMillis.value)
        assertTrue("the screen must not record the same solve again", b.resultHandled.value)
    }

    @Test
    fun `restore rejects garbage, a wrong roster and a wrong-sized board and leaves the engine untouched`() {
        val a = newGame()
        a.startMatch()
        a.submitGuess(wrongGuess(a))
        val good = a.snapshot()!!

        val b = newGame()
        b.startMatch()
        b.submitGuess(wrongGuess(b))
        val before = b.state.value
        val round = b.roundNumber.value

        assertFalse(b.restore("not json at all"))
        assertFalse(b.restore("{}"))
        assertFalse(b.restore(good.replace("\"maxGuesses\":10", "\"maxGuesses\":9")))
        assertFalse(b.restore(good.replace("\"positions\":4", "\"positions\":5")))
        assertFalse(
            "feedback that does not match the secret",
            b.restore(Regex("\"blackPegs\":\\d+").replace(good, "\"blackPegs\":9"))
        )

        val otherRoster = newGame(players = listOf(PlayerInfo(playerId = "someone-else", displayName = "Other")))
        assertFalse(otherRoster.restore(good))
        assertNull("untouched: still no code", otherRoster.state.value)

        assertEquals(before, b.state.value)
        assertEquals(round, b.roundNumber.value)
        assertEquals(0, b.puzzlesSolved.value)
    }

    @Test
    fun `restore before init does not throw`() {
        val game = MastermindGame(nowMillis = { 0L })
        assertNull(game.snapshot())
        assertFalse(game.restore("{}"))
    }

    // --- Hint -------------------------------------------------------------------------------

    @Test
    fun `the first hint is a legal code and is not submitted`() {
        val game = newGame()
        game.startMatch()
        val s = game.state.value!!
        val code = game.hint()!!

        assertEquals(s.positions, code.size)
        assertTrue(code.all { it in 0 until s.colorCount })
        assertTrue("a hint is a suggestion, not a guess", game.state.value!!.guesses.isEmpty())
        assertEquals(code, game.hintCode.value)
        assertEquals(1, game.hintsUsed.value)
        assertTrue(game.isAssisted)
    }

    @Test
    fun `a hint is consistent with all the feedback so far`() {
        val game = newGame(difficulty = CpuDifficulty.HARD)
        game.startMatch(dailySeed = 99L)
        val secret = game.state.value!!.secret
        // Skip any probe that happens to equal the secret, so the code stays undecided and a hint exists.
        for (probe in listOf(listOf(0, 1, 2, 3, 4), listOf(5, 5, 6, 6, 7), listOf(1, 0, 3, 2, 4))) {
            if (probe != secret) game.submitGuess(probe)
        }
        assertTrue(game.state.value!!.guesses.size >= 2)

        val hint = game.hint()!!
        for (g in game.state.value!!.guesses) {
            assertEquals(
                "the hint must have produced the same feedback as the secret on every guess",
                score(g.colors, secret),
                score(g.colors, hint)
            )
        }
    }

    @Test
    fun `asking again for the same position is free, a new position costs another hint`() {
        val game = newGame()
        game.startMatch()
        val first = game.hint()
        assertEquals(first, game.hint())
        assertEquals(1, game.hintsUsed.value)

        game.submitGuess(wrongGuess(game))
        assertNull("the next guess clears the highlight", game.hintCode.value)
        game.hint()
        assertEquals(2, game.hintsUsed.value)
    }

    @Test
    fun `hint is null once the code is decided or the session ended`() {
        val solved = newGame()
        solved.startMatch()
        solved.submitGuess(solved.state.value!!.secret)
        assertNull(solved.hint())
        assertEquals(0, solved.hintsUsed.value)

        val lost = newGame()
        lost.startMatch()
        repeat(MastermindGame.MAX_GUESSES) { lost.submitGuess(wrongGuess(lost)) }
        assertTrue(lost.state.value!!.outOfGuesses)
        assertNull(lost.hint())

        val left = newGame()
        left.startMatch()
        left.leaveSession()
        assertNull(left.hint())

        assertNull("no code dealt yet", newGame().hint())
    }

    @Test
    fun `a new code and a restore clear the hint state`() {
        val game = newGame()
        game.startMatch()
        game.hint()
        assertEquals(1, game.hintsUsed.value)

        game.playAgain()
        assertEquals(0, game.hintsUsed.value)
        assertNull(game.hintCode.value)
        assertFalse(game.isAssisted)

        game.hint()
        val snapshot = game.snapshot()!!
        assertTrue(game.restore(snapshot))
        assertNull(game.hintCode.value)
        assertEquals("the assisted mark survives a resume", 1, game.hintsUsed.value)
    }

    @Test
    fun `an assisted solve still counts as solved but is marked assisted`() {
        val game = newGame()
        game.startMatch()
        game.hint()
        game.submitGuess(game.state.value!!.secret)

        assertTrue(game.state.value!!.solved)
        assertEquals(1, game.puzzlesSolved.value)
        // The screen reads this to skip the best-time / best-guesses store call.
        assertTrue(game.isAssisted)
        assertEquals(1, game.hintsUsed.value)
        assertNotEquals(0, game.hintsUsed.value)
    }
}
