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

    /**
     * The single most double-counting-prone shape of all: the secret has exactly ONE occurrence
     * of a letter, and the guess repeats that same letter at TWO positions, NEITHER of which is
     * the correct spot -- so unlike every other duplicate-letter test in this file, no CORRECT
     * feedback is involved anywhere, meaning pass 1 never protects anything and pass 2's own
     * consume-one-then-ABSENT-the-rest logic is exercised completely on its own. A naive
     * "does this letter appear anywhere in the secret" check (with no consumption) would mark
     * BOTH guessed occurrences PRESENT here, which is wrong -- "apple" only has one real 'e'.
     */
    @Test
    fun `hand-verified scoring -- a repeated guess letter with only ONE real occurrence and NO exact match marks just the leftmost one PRESENT`() {
        val game = newGame(acceptAnyGuess = true)
        game.startMatch()
        withSecret(game, "apple") // one 'e', at index 4
        game.submitGuess("eebbb") // 'e' at index 0 and 1, both wrong positions; 'b' not in secret at all
        val feedback = game.state.value!!.guesses.single().feedback
        assertEquals(
            listOf(LetterFeedback.PRESENT, LetterFeedback.ABSENT, LetterFeedback.ABSENT, LetterFeedback.ABSENT, LetterFeedback.ABSENT),
            feedback
        )
    }

    /** The mirror of the case above: the secret has TWO occurrences of a letter, the guess has only ONE (in the wrong spot) -- the lower-risk direction (nothing to over-count against), but still worth a dedicated exact-value check rather than leaving it to chance inside the random property test. */
    @Test
    fun `hand-verified scoring -- a guess letter with only one occurrence still scores PRESENT even when the secret has two of that letter`() {
        val game = newGame(acceptAnyGuess = true)
        game.startMatch()
        withSecret(game, "sweet") // two 'e's, at index 2 and 3
        game.submitGuess("xexxx") // one 'e', at index 1 -- wrong position for either secret 'e'
        val feedback = game.state.value!!.guesses.single().feedback
        assertEquals(
            listOf(LetterFeedback.ABSENT, LetterFeedback.PRESENT, LetterFeedback.ABSENT, LetterFeedback.ABSENT, LetterFeedback.ABSENT),
            feedback
        )
    }

    /**
     * CORRECT and PRESENT coexisting for the SAME letter within one guess: the secret has two
     * occurrences of a letter, one guess position exactly matches one of them (CORRECT, consumed
     * in pass 1) and a DIFFERENT guess position of that same letter is genuinely present-but-
     * misplaced against the secret's OTHER occurrence (PRESENT in pass 2). Proves pass 1's
     * consumption correctly protects the exact match from also being available to pass 2, without
     * incorrectly blocking the secret's remaining, still-unmatched occurrence either.
     */
    @Test
    fun `hand-verified scoring -- an exact match and a present-but-misplaced match of the SAME letter coexist correctly in one guess`() {
        val game = newGame(acceptAnyGuess = true)
        game.startMatch()
        withSecret(game, "sweet") // two 'e's, at index 2 and 3
        game.submitGuess("exexx") // index 2's 'e' is an exact match; index 0's 'e' is present-but-misplaced against the secret's OTHER 'e'
        val feedback = game.state.value!!.guesses.single().feedback
        assertEquals(
            listOf(LetterFeedback.PRESENT, LetterFeedback.ABSENT, LetterFeedback.CORRECT, LetterFeedback.ABSENT, LetterFeedback.ABSENT),
            feedback
        )
    }

    @Test
    fun `matchOver guard blocks playAgain, the same as submitGuess`() {
        val game = newGame()
        game.startMatch()
        game.leaveSession()
        assertTrue(game.matchOver.value)

        val frozen = game.state.value
        game.playAgain()
        assertEquals("playAgain() after leaveSession() must be a total no-op", frozen, game.state.value)
    }

    @Test
    fun `leaveSession is idempotent -- a second call does not re-invoke onMatchEnd or mutate state further`() {
        val game = newGame()
        game.startMatch()
        var invocations = 0
        game.setOnMatchEnd { invocations++ }

        game.leaveSession()
        assertEquals(1, invocations)
        val stateAfterFirstLeave = game.state.value

        game.leaveSession()
        assertEquals("a second leaveSession() must not re-invoke the listener", 1, invocations)
        assertEquals("a second leaveSession() must not mutate state further", stateAfterFirstLeave, game.state.value)
    }

    /**
     * Proves the two-tier word source is genuinely two separate pools, not the same restricted
     * list backing both purposes (which every other test in this file's default [newGame] helper
     * can't distinguish, since it backs pickSecret and isValidGuess with the identical [TEST_WORDS]
     * list by default) -- a guess drawn only from the broader validation pool, never from the
     * small secret pool, must still be accepted.
     */
    @Test
    fun `guess validation genuinely accepts a word outside the small secret pool`() {
        val secretPool = listOf("apple")
        val broaderValidWords = setOf("apple", "zebra", "sound")
        var fakeClock = 0L
        val game = WordGuessGame(
            nowMillis = { fakeClock++ },
            pickSecret = { random -> secretPool.random(random) },
            isValidGuess = { it in broaderValidWords }
        )
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = listOf(PlayerInfo(playerId = "p1", displayName = "Player 1")),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.startMatch()

        game.submitGuess("zebra") // never in secretPool, only in the broader valid-guess set
        assertEquals(
            "a guess outside the secret pool but inside the broader validation pool must be accepted",
            1,
            game.state.value!!.guesses.size
        )
    }
}
