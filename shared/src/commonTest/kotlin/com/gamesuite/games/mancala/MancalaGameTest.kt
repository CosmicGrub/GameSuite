package com.gamesuite.games.mancala

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Migrated from app/src/test/java/com/gamesuite/games/mancala/MancalaGameTest.kt as part of
 * the ongoing per-game KMP port sweep (docs/ENGINE_DECISION.md) -- run against both the
 * android and desktop targets from this one shared source file, same as the three earlier
 * pilots. MancalaGame.kt itself ported with genuinely zero code changes (same import profile
 * as TicTacToeGame.kt: androidx.compose.runtime.mutableStateOf, com.gamesuite.core.*,
 * CpuDifficulty -- no Math.random-style JVM-only calls to translate this time).
 *
 * The only change from the original: org.junit.Test/Assert.assertTrue (JVM-only) became
 * kotlin.test.Test/assertTrue (a real Kotlin Multiplatform artifact) -- and kotlin.test's
 * assertTrue takes its message LAST (actual, message), the reverse of JUnit's
 * (message, actual), the same parameter-order swap already called out migrating the three
 * earlier pilots' tests.
 *
 * [MancalaGame.playBotTurn]'s HARD tier picks a move via a private
 * depth-limited minimax (`minimaxBestMove`) over `legalMoves`, which is
 * itself supposed to only ever offer non-empty pits — this file is a
 * regression guard on that invariant holding end-to-end through the real
 * public entry point, across several hardcoded board shapes (empty-heavy,
 * near-endgame, single-legal-move, and bot-seated-as-either-player), rather
 * than only trusting `legalMoves`'s own filter never regresses.
 *
 * `minimaxBestMove` is private, so the chosen pit isn't read directly;
 * instead each fixture seeds `state` (public `mutableStateOf<MancalaState?>`)
 * with a hardcoded board, calls the public `playBotTurn()`, and parses the
 * pit number back out of the resulting `lastAction` message — the same
 * "sowed from pit N" text `sow()` always produces on a successful move
 * (MancalaGame.kt's `sow()`) — then checks that pit held stones in the
 * board as it stood *before* the move.
 */
class MancalaGameTest {

    private fun newGame(botIndex: Int): MancalaGame {
        val game = MancalaGame()
        val players = listOf(
            PlayerInfo(playerId = "p0", displayName = "P0", isBot = botIndex == 0),
            PlayerInfo(playerId = "p1", displayName = "P1", isBot = botIndex == 1)
        )
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
                players = players,
                localPlayerIndex = if (botIndex == 0) 1 else 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = CpuDifficulty.HARD
        return game
    }

    /**
     * Seeds [pits] with [botIndex] on the move, fires `playBotTurn()`, and
     * asserts whichever pit the resulting `lastAction` reports was actually
     * non-empty in [pits] beforehand — i.e. the minimax selector never
     * pointed at an empty pit.
     */
    private fun assertBotSowsFromNonEmptyPit(pits: List<Int>, botIndex: Int) {
        val game = newGame(botIndex)
        game.state.value = MancalaState(pits = pits, currentPlayerIndex = botIndex, lastAction = "Fixture seeded")

        game.playBotTurn()

        val lastAction = game.state.value!!.lastAction
        val match = Regex("sowed from pit (\\d+)").find(lastAction)
            ?: throw AssertionError("Expected the bot to sow from some pit, but lastAction was: \"$lastAction\"")
        val chosenPitIndex = match.groupValues[1].toInt() - 1

        assertTrue(
            pits[chosenPitIndex] > 0,
            "HARD bot chose pit $chosenPitIndex which was empty before the move (pits=$pits)"
        )
    }

    @Test
    fun `HARD bot never sows from an empty pit - asymmetric board, player 0 to move`() {
        // player0 pits (0-5): [4,0,3,0,7,2] — several legal candidates plus empties interleaved.
        val pits = listOf(4, 0, 3, 0, 7, 2, 0, 1, 0, 4, 0, 0, 6, 0)
        assertBotSowsFromNonEmptyPit(pits, botIndex = 0)
    }

    @Test
    fun `HARD bot never sows from an empty pit - same board, player 1 to move`() {
        // Same board as above but player1 (pits 7-12: [1,0,4,0,0,6]) is on the move this time —
        // the invariant must hold for either seat, not just index 0.
        val pits = listOf(4, 0, 3, 0, 7, 2, 0, 1, 0, 4, 0, 0, 6, 0)
        assertBotSowsFromNonEmptyPit(pits, botIndex = 1)
    }

    @Test
    fun `HARD bot never sows from an empty pit - near-endgame, few stones left`() {
        // Most pits already emptied out; only two legal candidates remain for player 0.
        val pits = listOf(0, 0, 1, 0, 0, 2, 20, 0, 3, 0, 0, 0, 0, 22)
        assertBotSowsFromNonEmptyPit(pits, botIndex = 0)
    }

    @Test
    fun `HARD bot never sows from an empty pit - only one legal move available`() {
        // Forced move: pit 5 is the only non-empty pit player 0 owns.
        val pits = listOf(0, 0, 0, 0, 0, 5, 10, 4, 4, 4, 4, 4, 4, 0)
        assertBotSowsFromNonEmptyPit(pits, botIndex = 0)
    }

    @Test
    fun `HARD bot never sows from an empty pit - player 1 with a sparse board`() {
        // player1 pits (7-12): [0,0,2,0,4,0] — legal candidates at 9 and 11 only.
        val pits = listOf(3, 0, 0, 0, 0, 0, 5, 0, 0, 2, 0, 4, 0, 7)
        assertBotSowsFromNonEmptyPit(pits, botIndex = 1)
    }
}
