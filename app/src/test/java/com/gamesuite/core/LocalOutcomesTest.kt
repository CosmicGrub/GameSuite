package com.gamesuite.core

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Win / loss / draw derivation for every play mode. The regression: a session with ONLY losses in a
 * one-human game (a puzzle that solved nothing, Air Hockey vs the bot reporting just the human row)
 * carried no `isWinner` flag at all and was recorded as a DRAW.
 */
class LocalOutcomesTest {

    private val human = PlayerInfo("p1", "You")
    private val bot = PlayerInfo("bot1", "CPU", isBot = true)
    private val friend = PlayerInfo("p2", "Friend")

    private fun result(vararg scores: PlayerScore) = GameResult(scores = scores.toList())
    private fun s(id: String, score: Int, winner: Boolean) = PlayerScore(id, score, winner)

    // --- the bug: single-human sessions of losses ---

    @Test
    fun `puzzle session with nothing solved is a loss not a draw`() {
        val r = result(s("p1", 0, false))
        assertEquals(LocalOutcome.LOSS, deriveLocalOutcome(listOf(human), 0, r, GameCategory.PUZZLE))
    }

    @Test
    fun `puzzle session with a solve is a win`() {
        val r = result(s("p1", 3, true))
        assertEquals(LocalOutcome.WIN, deriveLocalOutcome(listOf(human), 0, r, GameCategory.PUZZLE))
    }

    @Test
    fun `bot-only-loss session reporting only the human row is a loss`() {
        // Air Hockey vs the CPU reports just the human's row, isWinner = false after a defeat.
        val r = result(s("p1", 3, false))
        assertEquals(LocalOutcome.LOSS, deriveLocalOutcome(listOf(human, bot), 0, r, GameCategory.ARCADE))
        // The same shape in a board game is also a loss: only one player was scored.
        assertEquals(LocalOutcome.LOSS, deriveLocalOutcome(listOf(human, bot), 0, r, GameCategory.BOARD))
    }

    @Test
    fun `a score-less arcade run is a loss when nothing was beaten`() {
        // Breakout / Tower Defence: isWinner false, solo.
        val r = result(s("p1", 0, false))
        assertEquals(LocalOutcome.LOSS, deriveLocalOutcome(listOf(human), 0, r, GameCategory.ARCADE))
    }

    // --- vs CPU with both rows ---

    @Test
    fun `vs cpu win loss and genuine draw`() {
        val players = listOf(human, bot)
        assertEquals(LocalOutcome.WIN, deriveLocalOutcome(players, 0, result(s("p1", 2, true), s("bot1", 1, false)), GameCategory.BOARD))
        assertEquals(LocalOutcome.LOSS, deriveLocalOutcome(players, 0, result(s("p1", 1, false), s("bot1", 2, true)), GameCategory.BOARD))
        assertEquals(LocalOutcome.DRAW, deriveLocalOutcome(players, 0, result(s("p1", 1, false), s("bot1", 1, false)), GameCategory.BOARD))
    }

    @Test
    fun `a tie in a card or other game that can tie is a draw`() {
        val players = listOf(human, bot)
        val tie = result(s("p1", 0, false), s("bot1", 0, false))
        assertEquals(LocalOutcome.DRAW, deriveLocalOutcome(players, 0, tie, GameCategory.CARD))
        assertEquals(LocalOutcome.DRAW, deriveLocalOutcome(players, 0, tie, GameCategory.OTHER))
    }

    // --- pass and play ---

    @Test
    fun `pass and play outcomes are resolved for seat zero`() {
        val players = listOf(human, friend)
        assertEquals(LocalOutcome.WIN, deriveLocalOutcome(players, 0, result(s("p1", 5, true), s("p2", 2, false)), GameCategory.BOARD))
        assertEquals(LocalOutcome.LOSS, deriveLocalOutcome(players, 0, result(s("p1", 2, false), s("p2", 5, true)), GameCategory.BOARD))
        assertEquals(LocalOutcome.DRAW, deriveLocalOutcome(players, 0, result(s("p1", 3, false), s("p2", 3, false)), GameCategory.BOARD))
    }

    @Test
    fun `a shared-board puzzle in pass and play that nobody solved is a loss`() {
        // Word Search / Crossword give every seat the same tally and no winner flag when unsolved.
        val players = listOf(human, friend)
        val unsolved = result(s("p1", 0, false), s("p2", 0, false))
        assertEquals(LocalOutcome.LOSS, deriveLocalOutcome(players, 0, unsolved, GameCategory.WORD))
        assertEquals(LocalOutcome.LOSS, deriveLocalOutcome(players, 0, unsolved, GameCategory.PUZZLE))
    }

    @Test
    fun `puzzles with solved and failed counts`() {
        // puzzlesSolved > 0 flags the win; a session of only failures (solved = 0) is a loss.
        assertEquals(LocalOutcome.WIN, deriveLocalOutcome(listOf(human), 0, result(s("p1", 2, true)), GameCategory.PUZZLE))
        assertEquals(LocalOutcome.LOSS, deriveLocalOutcome(listOf(human), 0, result(s("p1", 0, false)), GameCategory.PUZZLE))
        // Hangman style: more losses than wins -> not a winner -> loss.
        assertEquals(LocalOutcome.LOSS, deriveLocalOutcome(listOf(human), 0, result(s("p1", 1, false)), GameCategory.WORD))
    }

    // --- teams and networked ---

    @Test
    fun `a winning teammate makes the local player a winner`() {
        val a = PlayerInfo("p1", "A", teamId = 0)
        val b = PlayerInfo("bot1", "B", isBot = true, teamId = 0)
        val c = PlayerInfo("bot2", "C", isBot = true, teamId = 1)
        val r = result(s("p1", 0, false), s("bot1", 0, true), s("bot2", 0, false))
        assertEquals(LocalOutcome.WIN, deriveLocalOutcome(listOf(a, b, c), 0, r, GameCategory.CARD))
    }

    @Test
    fun `a networked guest is resolved by player id not seat order`() {
        val players = listOf(PlayerInfo("host", "Host"), PlayerInfo("guest", "Guest"))
        val r = result(s("host", 3, true), s("guest", 1, false))
        assertEquals(LocalOutcome.LOSS, deriveLocalOutcome(players, 1, r, GameCategory.CARD))
        assertEquals(LocalOutcome.WIN, deriveLocalOutcome(players, 0, r, GameCategory.CARD))
    }

    // --- nothing to attribute ---

    @Test
    fun `aborted spectator unscored and unknown player give null`() {
        val players = listOf(human, bot)
        assertNull(deriveLocalOutcome(players, 0, GameResult(wasAborted = true, scores = listOf(s("p1", 1, true)))))
        assertNull(deriveLocalOutcome(players, -1, result(s("p1", 1, true))))
        assertNull(deriveLocalOutcome(players, 0, GameResult()))
        assertNull(deriveLocalOutcome(players, 0, result(s("someone-else", 1, true))))
    }

    // --- through GameSessionManager, the real publishing path ---

    private class Fake(
        override val gameId: String,
        override val category: GameCategory
    ) : GameModule {
        override val displayName = "Fake"
        override val minPlayers = 1
        override val maxPlayers = 2
        override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)
        override fun init(context: GameContext) {}
        override fun startMatch() {}
        override fun pause() {}
        override fun resume() {}
        override fun endMatch(result: GameResult) {}
    }

    private fun end(
        category: GameCategory,
        mode: PlayMode,
        players: List<PlayerInfo>,
        result: GameResult,
        route: String = "fake",
        options: LaunchOptions = LaunchOptions()
    ): GameSessionManager {
        val manager = GameSessionManager()
        manager.setActiveModule(Fake("fake", category), route)
        manager.launchGame(mode, players, 0, options)
        manager.endActiveGame(result)
        return manager
    }

    @Test
    fun `manager publishes LOSS for a solo puzzle session with no solves`() {
        val m = end(GameCategory.PUZZLE, PlayMode.SINGLE_PLAYER_VS_BOT, listOf(human), result(s("p1", 0, false)))
        assertEquals(LocalOutcome.LOSS, m.lastMatchOutcome.value?.localOutcome)
    }

    @Test
    fun `manager publishes LOSS for a bot session reporting only the human`() {
        val m = end(GameCategory.ARCADE, PlayMode.SINGLE_PLAYER_VS_BOT, listOf(human, bot), result(s("p1", 2, false)))
        assertEquals(LocalOutcome.LOSS, m.lastMatchOutcome.value?.localOutcome)
    }

    @Test
    fun `manager publishes DRAW for a tied pass and play board game`() {
        val m = end(GameCategory.BOARD, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, listOf(human, friend), result(s("p1", 1, false), s("p2", 1, false)))
        assertEquals(LocalOutcome.DRAW, m.lastMatchOutcome.value?.localOutcome)
    }

    @Test
    fun `manager publishes match details and clears them on acknowledge`() {
        val m = end(
            GameCategory.BOARD, PlayMode.SINGLE_PLAYER_VS_BOT, listOf(human, bot),
            result(s("p1", 1, true), s("bot1", 0, false)),
            route = "sudoku-daily",
            options = LaunchOptions(cpuDifficulty = CpuDifficulty.HARD)
        )
        val d = m.lastMatchDetails
        assertTrue(d != null && d.daily && d.vsCpu && d.hasBots)
        assertEquals(CpuDifficulty.HARD, d?.cpuDifficulty)
        assertEquals(1, d?.humanCount)
        m.acknowledgeMatchOutcome()
        assertNull(m.lastMatchDetails)
        assertNull(m.lastMatchOutcome.value)
    }

    @Test
    fun `an aborted match publishes neither outcome nor details`() {
        val m = end(GameCategory.BOARD, PlayMode.SINGLE_PLAYER_VS_BOT, listOf(human, bot), GameResult(wasAborted = true))
        assertNull(m.lastMatchOutcome.value)
        assertNull(m.lastMatchDetails)
    }

    @Test
    fun `details fall back to the app default difficulty only for cpu games`() {
        val vsCpu = MatchDetails(PlayMode.SINGLE_PLAYER_VS_BOT, hasBots = true, cpuDifficulty = null, daily = false, humanCount = 1)
        assertEquals(CpuDifficulty.HARD, vsCpu.withDefaultDifficulty(CpuDifficulty.HARD).cpuDifficulty)
        val explicit = vsCpu.copy(cpuDifficulty = CpuDifficulty.EASY)
        assertEquals(CpuDifficulty.EASY, explicit.withDefaultDifficulty(CpuDifficulty.HARD).cpuDifficulty)
        val passAndPlay = vsCpu.copy(mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, hasBots = false)
        assertFalse(passAndPlay.vsCpu)
        assertNull(passAndPlay.withDefaultDifficulty(CpuDifficulty.HARD).cpuDifficulty)
    }
}
