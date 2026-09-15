package com.gamesuite.games.wordguess

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WordGuessGame] never touches the real Android-asset-backed `WordGuessAnswerList`/
 * `WordDictionary` here — both need a real [android.content.Context] to read an assets file,
 * which throws under plain JUnit (no Robolectric in this project). Every test instead injects a
 * small fixed [TEST_WORDS] pool via the constructor's own [WordGuessGame]'s `pickSecret`/
 * `isValidGuess` seams — see that class's own KDoc for why those seams exist at all.
 *
 * [WordGuessGame]'s scoring (`scoreGuess`, private) is never trusted at face value either.
 * [independentTotalMatches] computes the TOTAL correct+present count via a genuinely different
 * algorithm (a full letter-multiset intersection, never touching position order at all) as one
 * cross-check; several hand-worked FULL feedback lists (not just aggregate counts) catch the
 * class of bug an aggregate-only check could miss — see [scoring with a doubled guess letter and
 * only one real occurrence assigns PRESENT to the correct position, not just the correct count]'s
 * own KDoc for a worked example of exactly that risk.
 */
class WordGuessGameTest {

    private val TEST_WORDS = listOf("apple", "melts", "lemon", "tiger", "zebra", "mango", "crisp", "sound")

    /**
     * [acceptAnyGuess] bypasses the TEST_WORDS-restricted `isValidGuess` check entirely (always
     * true) — for hand-verified scoring tests that submit an arbitrary guess string to exercise
     * `scoreGuess`'s own algorithm and don't care whether that exact string happens to be in the
     * small fixed test vocabulary. Tests that specifically exercise VALIDATION (rejecting an
     * out-of-vocabulary guess) leave this false, the default, real restriction.
     */
    private fun newGame(difficulty: CpuDifficulty = CpuDifficulty.MEDIUM, words: List<String> = TEST_WORDS, acceptAnyGuess: Boolean = false): WordGuessGame {
        var fakeClock = 0L
        val game = WordGuessGame(
            nowMillis = { fakeClock++ },
            pickSecret = { random -> words.random(random) },
            isValidGuess = { if (acceptAnyGuess) true else it in words }
        )
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = difficulty
        return game
    }

    /** Sets the CURRENT round's secret directly (after a real startMatch() has already established init()/context), for hand-verified scoring tests that need an EXACT secret rather than whatever the injected pool happened to roll. */
    private fun withSecret(game: WordGuessGame, secret: String): WordGuessGame {
        game.state.value = game.state.value!!.copy(secret = secret)
        return game
    }

    /** Independent re-derivation of the TOTAL correct+present count: a full letter-multiset intersection between [guess] and [secret], never touching position order — genuinely different from the engine's own position-first two-pass algorithm. */
    private fun independentTotalMatches(guess: String, secret: String): Int {
        val guessCounts = guess.groupingBy { it }.eachCount()
        val secretCounts = secret.groupingBy { it }.eachCount()
        return (guessCounts.keys + secretCounts.keys).sumOf { letter ->
            minOf(guessCounts[letter] ?: 0, secretCounts[letter] ?: 0)
        }
    }

    @Test
    fun `difficulty controls the guess allowance, word length stays fixed at 5 for every tier`() {
        val expected = mapOf(CpuDifficulty.EASY to 8, CpuDifficulty.MEDIUM to 6, CpuDifficulty.HARD to 5)
        for ((difficulty, maxGuesses) in expected) {
            val game = newGame(difficulty)
            game.startMatch()
            val s = game.state.value!!
            assertEquals("difficulty=$difficulty", maxGuesses, s.maxGuesses)
            assertEquals(WordGuessGame.WORD_LENGTH, s.secret.length)
        }
    }

    @Test
    fun `a daily seed makes the secret reproducible`() {
        val gameA = newGame(CpuDifficulty.MEDIUM)
        gameA.startMatch(dailySeed = 42L)
        val gameB = newGame(CpuDifficulty.MEDIUM)
        gameB.startMatch(dailySeed = 42L)
        assertEquals(
            "the same daily seed should produce an identical secret",
            gameA.state.value!!.secret,
            gameB.state.value!!.secret
        )
    }

    @Test
    fun `scoring against random guesses -- every exact-position match is CORRECT, and the total matches an independent re-derivation`() {
        val random = Random(11)
        repeat(80) {
            val game = newGame()
            game.startMatch()
            val secret = game.state.value!!.secret
            val guess = TEST_WORDS.random(random)
            val expectedTotal = independentTotalMatches(guess, secret)

            game.submitGuess(guess)
            val recorded = game.state.value!!.guesses.last()
            for (i in guess.indices) {
                if (guess[i] == secret[i]) {
                    assertEquals("guess=$guess secret=$secret position=$i must be CORRECT", LetterFeedback.CORRECT, recorded.feedback[i])
                }
            }
            val actualTotal = recorded.feedback.count { it == LetterFeedback.CORRECT || it == LetterFeedback.PRESENT }
            assertEquals("guess=$guess secret=$secret: total correct+present", expectedTotal, actualTotal)
        }
    }

    @Test
    fun `hand-verified scoring -- a repeated guess letter with only one real secret occurrence marks just one position PRESENT, not both`() {
        // secret="apple" (a,p,p,l,e), guess="lolly" (l,o,l,l,y): position 3's 'l' matches
        // exactly (CORRECT). The other two 'l's in the guess must NOT also score PRESENT --
        // "apple" only has one 'l' total, already consumed by the exact match.
        val game = newGame(acceptAnyGuess = true)
        game.startMatch()
        withSecret(game, "apple")
        game.submitGuess("lolly")
        val feedback = game.state.value!!.guesses.single().feedback
        assertEquals(listOf(LetterFeedback.ABSENT, LetterFeedback.ABSENT, LetterFeedback.ABSENT, LetterFeedback.CORRECT, LetterFeedback.ABSENT), feedback)
    }

    @Test
    fun `hand-verified scoring -- letters present but in the wrong spot score PRESENT, not CORRECT`() {
        // secret="apple", guess="melts": 'e' and 'l' both really appear in "apple" (at
        // different positions than in the guess), 'm'/'t'/'s' do not appear at all.
        val game = newGame()
        game.startMatch()
        withSecret(game, "apple")
        game.submitGuess("melts")
        val feedback = game.state.value!!.guesses.single().feedback
        assertEquals(listOf(LetterFeedback.ABSENT, LetterFeedback.PRESENT, LetterFeedback.PRESENT, LetterFeedback.ABSENT, LetterFeedback.ABSENT), feedback)
    }

    /**
     * A repeated guess letter with only one real secret occurrence assigns PRESENT to the
     * correct SPECIFIC position (the canonical real-Wordle leftmost-priority behavior), not
     * just the right TOTAL count. A buggy implementation could accidentally assign PRESENT to
     * the WRONG one of two candidate positions while still getting the aggregate count right --
     * exactly the class of bug [independentTotalMatches]'s own aggregate cross-check alone could
     * never catch, which is why this exact full-list equality check exists as its own test.
     * secret="lemon" (l,e,m,o,n), guess="lolly" (l,o,l,l,y): position 0's 'l' is CORRECT,
     * consuming the secret's only 'l'. Of the guess's remaining three letters (o, l, l), only
     * 'o' (at guess position 1) is still really present in "lemon" -- it must score PRESENT,
     * while the guess's OTHER two 'l's (positions 2 and 3) must both score ABSENT.
     */
    @Test
    fun `hand-verified scoring -- PRESENT lands on the correct specific position, not just the right total count`() {
        val game = newGame(acceptAnyGuess = true)
        game.startMatch()
        withSecret(game, "lemon")
        game.submitGuess("lolly")
        val feedback = game.state.value!!.guesses.single().feedback
        assertEquals(
            listOf(LetterFeedback.CORRECT, LetterFeedback.PRESENT, LetterFeedback.ABSENT, LetterFeedback.ABSENT, LetterFeedback.ABSENT),
            feedback
        )
    }

    @Test
    fun `hand-verified scoring -- an exact match is all CORRECT and solves the round`() {
        val game = newGame()
        game.startMatch()
        withSecret(game, "apple")
        game.submitGuess("apple")
        val recorded = game.state.value!!.guesses.single()
        assertEquals(List(5) { LetterFeedback.CORRECT }, recorded.feedback)
        assertTrue(game.state.value!!.solved)
        assertEquals(1, game.puzzlesSolved.value)
    }

    @Test
    fun `hand-verified scoring -- completely disjoint letters score all ABSENT`() {
        val game = newGame(acceptAnyGuess = true)
        game.startMatch()
        withSecret(game, "apple")
        game.submitGuess("brown") // no letter in "brown" appears in "apple"
        val feedback = game.state.value!!.guesses.single().feedback
        assertEquals(List(5) { LetterFeedback.ABSENT }, feedback)
    }

    @Test
    fun `running out of guesses without solving marks outOfGuesses, freezes the timer, and does not count as solved`() {
        val game = newGame(CpuDifficulty.HARD, acceptAnyGuess = true) // 5 guesses
        game.startMatch()
        withSecret(game, "apple")
        repeat(5) { game.submitGuess("brown") } // guaranteed-wrong, never touches a letter in "apple"

        val s = game.state.value!!
        assertEquals(5, s.guesses.size)
        assertTrue(s.outOfGuesses)
        assertFalse(s.solved)
        assertEquals("a lost round must never count toward puzzlesSolved", 0, game.puzzlesSolved.value)
        assertTrue(game.finishedElapsedMillis.value != null)

        // Once the round is decided, further guesses are rejected -- same guard every other engine's own mutating method has.
        val stateAfterLoss = game.state.value
        game.submitGuess("brown")
        assertEquals("a guess after the round is already decided must be a total no-op", stateAfterLoss, game.state.value)
    }

    @Test
    fun `submitGuess rejects the wrong length or a word outside the valid-guess set, without crashing or mutating state`() {
        val game = newGame()
        game.startMatch()
        val before = game.state.value

        game.submitGuess("cat") // too short
        assertEquals(before, game.state.value)

        game.submitGuess("crisps") // too long
        assertEquals(before, game.state.value)

        game.submitGuess("zzzzz") // right length, not a valid word per the injected set
        assertEquals(before, game.state.value)
    }

    @Test
    fun `submitGuess normalizes case before validating and scoring`() {
        val game = newGame()
        game.startMatch()
        withSecret(game, "apple")
        game.submitGuess("APPLE")
        assertTrue("an uppercase exact match must still solve the round", game.state.value!!.solved)
        assertEquals("apple", game.state.value!!.guesses.single().word)
    }

    @Test
    fun `submitGuess is rejected once the session has ended via leaveSession, even if the round itself was not yet decided`() {
        val game = newGame()
        game.startMatch()
        game.leaveSession()
        assertTrue(game.matchOver.value)
        val stateAtLeave = game.state.value

        game.submitGuess("apple")
        assertEquals("a submitGuess() after leaveSession() must be a total no-op", stateAtLeave, game.state.value)
    }

    @Test
    fun `matchOver resets on a new match, even after a prior endMatch -- playAgain and leaveSession never get permanently stuck`() {
        val game = newGame()
        game.startMatch()
        game.endMatch(GameResult(scores = emptyList()))
        assertTrue(game.matchOver.value)

        game.startMatch()
        assertFalse("starting a new match must clear a stale matchOver flag", game.matchOver.value)

        game.leaveSession()
        assertTrue("leaveSession() after a fresh startMatch() must actually end the match", game.matchOver.value)
    }

    @Test
    fun `pausing twice without an intervening resume does not lose the interval between the two pauses`() {
        var clock = 0L
        val game = WordGuessGame(
            nowMillis = { clock },
            pickSecret = { "apple" },
            isValidGuess = { true }
        )
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        clock = 0L
        game.startMatch()

        clock = 0L
        game.submitGuess("brown") // wrong, starts the timer at t=0
        assertFalse(game.state.value!!.isOver)

        clock = 10L
        game.pause() // pausedAt = 10
        clock = 100_000L
        game.pause() // must be a no-op -- pausedAt should STILL be 10, not overwritten to 100_000
        clock = 100_050L
        game.resume() // totalPausedMillis += 100_050 - 10 = 100_040 (not 100_050 - 100_000 = 50)

        clock = 100_060L
        game.submitGuess("apple") // solves the round

        val recordedMillis = game.finishedElapsedMillis.value!!
        assertTrue(
            "recorded solve time was ${recordedMillis}ms -- the [10,100_000] interval between the two pause() calls must count as paused, not active",
            recordedMillis < 100L
        )
    }

    @Test
    fun `playAgain deals a fresh secret and clears the previous round's guesses`() {
        val game = newGame()
        game.startMatch(dailySeed = 1L)
        withSecret(game, "apple")
        game.submitGuess("apple")
        assertTrue(game.state.value!!.solved)

        game.playAgain()
        val s = game.state.value!!
        assertTrue("a fresh round must not start already decided", s.guesses.isEmpty())
        assertFalse(s.solved)
        assertFalse(s.outOfGuesses)
    }
}
