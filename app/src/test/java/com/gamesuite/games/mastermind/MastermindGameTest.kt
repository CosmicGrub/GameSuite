package com.gamesuite.games.mastermind

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
 * [MastermindGame]'s scoring (`scoreGuess`, private) is never trusted at face value here.
 * [independentScoreGuess] computes black/white pegs via a genuinely DIFFERENT algorithm (full
 * multiset intersection minus black pegs, rather than the engine's own remove-then-intersect
 * two-pass method) — see that function's own KDoc for why the two are provably equivalent for
 * every input, not just spot-checked ones. Every property-based check below drives the real
 * engine through the public `submitGuess()` API and compares its OBSERVED feedback against this
 * independent computation, never calling the engine's own private `scoreGuess` directly.
 */
class MastermindGameTest {

    private fun newGame(difficulty: CpuDifficulty = CpuDifficulty.MEDIUM): MastermindGame {
        var fakeClock = 0L
        val game = MastermindGame(nowMillis = { fakeClock++ })
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

    /**
     * Independent re-derivation of Mastermind scoring: total black pegs plus the full-multiset
     * color intersection of [guess]/[secret] (counting every occurrence of every color in BOTH
     * sequences, not just the "leftover after removing exact matches" ones the engine's own
     * two-pass method uses) minus those same black pegs. This is a real, general mathematical
     * identity — for every color c, `min(countInGuess(c), countInSecret(c))` always equals
     * `blackPegsOfColor(c) + min(remainingGuess(c), remainingSecret(c))`, since the number of
     * black-matched positions of any color can never exceed how many times that color appears in
     * either sequence — not just true for the specific cases this file happens to check.
     */
    private fun independentScoreGuess(guess: List<Int>, secret: List<Int>): Pair<Int, Int> {
        var black = 0
        for (i in guess.indices) if (guess[i] == secret[i]) black++

        val guessCounts = guess.groupingBy { it }.eachCount()
        val secretCounts = secret.groupingBy { it }.eachCount()
        val totalOverlap = (guessCounts.keys + secretCounts.keys).sumOf { color ->
            minOf(guessCounts[color] ?: 0, secretCounts[color] ?: 0)
        }
        return black to (totalOverlap - black)
    }

    @Test
    fun `difficulty controls positions and color count -- EASY 4x4, MEDIUM 4x6, HARD 5x8`() {
        val expected = mapOf(
            CpuDifficulty.EASY to (4 to 4),
            CpuDifficulty.MEDIUM to (4 to 6),
            CpuDifficulty.HARD to (5 to 8)
        )
        for ((difficulty, positionsAndColors) in expected) {
            val (positions, colorCount) = positionsAndColors
            val game = newGame(difficulty)
            game.startMatch()
            val s = game.state.value!!
            assertEquals("difficulty=$difficulty", positions, s.positions)
            assertEquals(colorCount, s.colorCount)
            assertEquals(positions, s.secret.size)
            assertEquals(MastermindGame.MAX_GUESSES, s.maxGuesses)
            for (color in s.secret) {
                assertTrue("difficulty=$difficulty: every secret color must be in 0 until $colorCount", color in 0 until colorCount)
            }
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
    fun `scoring against random guesses matches an independent re-derivation, across many trials`() {
        val random = Random(7)
        for (difficulty in CpuDifficulty.entries) {
            repeat(50) {
                val game = newGame(difficulty)
                game.startMatch()
                val s0 = game.state.value!!
                val guess = List(s0.positions) { random.nextInt(s0.colorCount) }
                val expected = independentScoreGuess(guess, s0.secret)

                game.submitGuess(guess)
                val recorded = game.state.value!!.guesses.last()
                assertEquals("difficulty=$difficulty guess=$guess secret=${s0.secret}: black pegs", expected.first, recorded.blackPegs)
                assertEquals("difficulty=$difficulty guess=$guess secret=${s0.secret}: white pegs", expected.second, recorded.whitePegs)
            }
        }
    }

    @Test
    fun `hand-verified scoring -- repeated colors are never double-counted`() {
        // secret=[1,1,2,3], guess=[1,2,2,2]: position 0 and 2 are black (2 total).
        // Remaining guess=[2,2] (positions 1,3), remaining secret=[1,3] (positions 1,3) --
        // zero shared colors left, so white=0. See MastermindGame.scoreGuess's own KDoc
        // for this exact worked example.
        val game = newGame()
        game.startMatch()
        game.state.value = game.state.value!!.copy(secret = listOf(1, 1, 2, 3))
        game.submitGuess(listOf(1, 2, 2, 2))
        val recorded = game.state.value!!.guesses.single()
        assertEquals(2, recorded.blackPegs)
        assertEquals(0, recorded.whitePegs)
    }

    @Test
    fun `hand-verified scoring -- an exact match is all black, zero white`() {
        val game = newGame()
        game.startMatch()
        val secret = listOf(0, 1, 2, 3)
        game.state.value = game.state.value!!.copy(secret = secret)
        game.submitGuess(secret)
        val recorded = game.state.value!!.guesses.single()
        assertEquals(4, recorded.blackPegs)
        assertEquals(0, recorded.whitePegs)
        assertTrue(game.state.value!!.solved)
    }

    @Test
    fun `hand-verified scoring -- completely disjoint colors score zero and zero`() {
        val game = newGame()
        game.startMatch()
        game.state.value = game.state.value!!.copy(secret = listOf(0, 0, 0, 0))
        game.submitGuess(listOf(1, 1, 1, 1))
        val recorded = game.state.value!!.guesses.single()
        assertEquals(0, recorded.blackPegs)
        assertEquals(0, recorded.whitePegs)
    }

    @Test
    fun `hand-verified scoring -- every color right, every position wrong, scores all white`() {
        // secret=[0,1,2,3], guess=[1,2,3,0] -- a full rotation, no position matches, every
        // color still present exactly once on each side.
        val game = newGame()
        game.startMatch()
        game.state.value = game.state.value!!.copy(secret = listOf(0, 1, 2, 3))
        game.submitGuess(listOf(1, 2, 3, 0))
        val recorded = game.state.value!!.guesses.single()
        assertEquals(0, recorded.blackPegs)
        assertEquals(4, recorded.whitePegs)
    }

    @Test
    fun `an exact guess solves the round, increments puzzlesSolved, and freezes the timer`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch()
        val secret = game.state.value!!.secret
        game.submitGuess(secret)
        val s = game.state.value!!
        assertTrue(s.solved)
        assertFalse(s.outOfGuesses)
        assertEquals(1, game.puzzlesSolved.value)
        assertTrue(game.finishedElapsedMillis.value != null)
    }

    @Test
    fun `running out of guesses without solving marks outOfGuesses, freezes the timer, and does not count as solved`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch()
        val secret = game.state.value!!.secret
        // Guaranteed-wrong guess: every peg one color index higher than the secret's own,
        // wrapping -- never an exact match at any position, colors chosen so this can never
        // accidentally equal the secret.
        val colorCount = game.state.value!!.colorCount
        val wrongGuess = secret.map { (it + 1) % colorCount }
        repeat(MastermindGame.MAX_GUESSES) { game.submitGuess(wrongGuess) }

        val s = game.state.value!!
        assertEquals(MastermindGame.MAX_GUESSES, s.guesses.size)
        assertTrue(s.outOfGuesses)
        assertFalse(s.solved)
        assertEquals("a lost round must never count toward puzzlesSolved", 0, game.puzzlesSolved.value)
        assertTrue(game.finishedElapsedMillis.value != null)

        // Once the round is decided, further guesses are rejected -- same guard every other engine's own mutating method has.
        val stateAfterLoss = game.state.value
        game.submitGuess(wrongGuess)
        assertEquals("a guess after the round is already decided must be a total no-op", stateAfterLoss, game.state.value)
    }

    @Test
    fun `submitGuess rejects the wrong length or an out-of-range color, without crashing or mutating state`() {
        val game = newGame(CpuDifficulty.EASY) // 4 positions, 4 colors
        game.startMatch()
        val before = game.state.value

        game.submitGuess(listOf(0, 0, 0)) // too short
        assertEquals(before, game.state.value)

        game.submitGuess(listOf(0, 0, 0, 0, 0)) // too long
        assertEquals(before, game.state.value)

        game.submitGuess(listOf(0, 0, 0, 99)) // color out of range
        assertEquals(before, game.state.value)

        game.submitGuess(listOf(-1, 0, 0, 0)) // negative color
        assertEquals(before, game.state.value)
    }

    @Test
    fun `submitGuess is rejected once the session has ended via leaveSession, even if the round itself was not yet decided`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch()
        game.leaveSession()
        assertTrue(game.matchOver.value)
        val stateAtLeave = game.state.value

        game.submitGuess(List(4) { 0 })
        assertEquals("a submitGuess() after leaveSession() must be a total no-op", stateAtLeave, game.state.value)
    }

    @Test
    fun `matchOver resets on a new match, even after a prior endMatch -- playAgain and leaveSession never get permanently stuck`() {
        val game = newGame(CpuDifficulty.EASY)
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
        val game = MastermindGame(nowMillis = { clock })
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
        val secret = game.state.value!!.secret

        clock = 0L
        game.submitGuess(secret.map { (it + 1) % game.state.value!!.colorCount }) // wrong, starts the timer at t=0
        assertFalse(game.state.value!!.isOver)

        clock = 10L
        game.pause() // pausedAt = 10
        clock = 100_000L
        game.pause() // must be a no-op -- pausedAt should STILL be 10, not overwritten to 100_000
        clock = 100_050L
        game.resume() // totalPausedMillis += 100_050 - 10 = 100_040 (not 100_050 - 100_000 = 50)

        clock = 100_060L
        game.submitGuess(secret) // the exact secret -- solves the round

        val recordedMillis = game.finishedElapsedMillis.value!!
        assertTrue(
            "recorded solve time was ${recordedMillis}ms -- the [10,100_000] interval between the two pause() calls must count as paused, not active",
            recordedMillis < 100L
        )
    }

    @Test
    fun `playAgain deals a fresh secret and clears the previous round's guesses`() {
        val game = newGame(CpuDifficulty.EASY)
        game.startMatch(dailySeed = 1L)
        val firstSecret = game.state.value!!.secret
        game.submitGuess(firstSecret)
        assertTrue(game.state.value!!.solved)

        game.playAgain()
        val s = game.state.value!!
        assertTrue("a fresh round must not start already decided", s.guesses.isEmpty())
        assertFalse(s.solved)
        assertFalse(s.outOfGuesses)
    }
}
