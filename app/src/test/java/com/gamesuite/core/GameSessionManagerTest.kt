package com.gamesuite.core

import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the "one MatchOutcome per match" fix. Before it,
 * `lastMatchOutcome` sat in the StateFlow forever once set, which caused two
 * real bugs that this file reproduces directly rather than through the UI:
 *
 * 1. DOUBLE-COUNT: MainActivity's root `LaunchedEffect(lastMatchOutcome)` only
 *    replays when the *key* changes across recompositions WITHIN one
 *    composition. An Activity recreation (dark mode at sunset, a font-size
 *    change, ...) starts a brand-new composition with no memory of the
 *    LaunchedEffect that already ran in the destroyed one, so it fires again
 *    against the same still-non-null value and records the same match twice.
 * 2. UNDER-COUNT: MutableStateFlow only emits to collectors on a value
 *    CHANGE. Two matches in a row whose MatchOutcome happens to compare equal
 *    (data class equality — same game, same scores) collapse into a single
 *    emission, so the second match's stats are silently never recorded.
 *
 * [GameSessionManager.acknowledgeMatchOutcome] fixes both by clearing the
 * value back to null the moment the stats layer has consumed it: a
 * recreation then replays against null (a no-op), and the next real match
 * always transitions null -> outcome, which is always a change even when two
 * outcomes in a row are otherwise identical.
 */
class GameSessionManagerTest {

    private fun context(localPlayerIndex: Int = 0, playerId: String = "p1") = GameContext(
        activeMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        players = listOf(PlayerInfo(playerId = playerId, displayName = "Player")),
        localPlayerIndex = localPlayerIndex,
        transport = LocalPassAndPlayTransport()
    )

    private fun win(playerId: String = "p1") = GameResult(
        scores = listOf(PlayerScore(playerId = playerId, score = 1, isWinner = true))
    )

    private class FakeModule(override val gameId: String = "fake-game", override val displayName: String = "Fake Game") : GameModule {
        override val category = GameCategory.OTHER
        override val minPlayers = 1
        override val maxPlayers = 1
        override val supportedModes = listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
        override fun init(context: GameContext) {}
        override fun startMatch() {}
        override fun pause() {}
        override fun resume() {}
        override fun endMatch(result: GameResult) {}
    }

    /** Wires up a manager with an active context + module, exactly the state a real game
     *  screen leaves it in right before calling endActiveGame(). */
    private fun managerReadyToEnd(): GameSessionManager {
        val manager = GameSessionManager()
        manager.setActiveModule(FakeModule())
        manager.launchGame(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, context().players, 0)
        return manager
    }

    @Test
    fun `endActiveGame publishes a MatchOutcome when a module is active`() {
        val manager = managerReadyToEnd()
        manager.endActiveGame(win())
        assertEquals("fake-game", manager.lastMatchOutcome.value?.gameId)
        assertEquals(LocalOutcome.WIN, manager.lastMatchOutcome.value?.localOutcome)
    }

    @Test
    fun `acknowledgeMatchOutcome clears it back to null`() {
        val manager = managerReadyToEnd()
        manager.endActiveGame(win())
        assertEquals(LocalOutcome.WIN, manager.lastMatchOutcome.value?.localOutcome)

        manager.acknowledgeMatchOutcome()

        assertNull(manager.lastMatchOutcome.value)
    }

    @Test
    fun `a recreation that replays against an already-consumed outcome sees null, not a stale value`() {
        // Simulates MainActivity's LaunchedEffect: record once, then immediately acknowledge —
        // exactly the sequence the fixed LaunchedEffect(lastMatchOutcome) runs. A fresh
        // composition (an Activity recreation with no new match) reads lastMatchOutcome.value
        // again from scratch; it must see null, not the match that already ended.
        val manager = managerReadyToEnd()
        manager.endActiveGame(win())
        manager.acknowledgeMatchOutcome() // the consuming side's job, done once per real record

        assertNull(
            "a value still sitting here after being consumed would double-record on the next recreation",
            manager.lastMatchOutcome.value
        )
    }

    @Test
    fun `two consecutive matches with an equal outcome are both observable, not collapsed into one emission`() {
        // Without acknowledgeMatchOutcome, setting the SAME (data-class-equal) MatchOutcome
        // value twice in a row is a StateFlow no-op — the second match's collectors never see a
        // change and its stats are silently dropped. Clearing to null between matches makes
        // every real match end a null -> outcome transition, which is always an emission.
        val manager = managerReadyToEnd()

        manager.endActiveGame(win())
        val first = manager.lastMatchOutcome.value
        assertEquals(LocalOutcome.WIN, first?.localOutcome)
        manager.acknowledgeMatchOutcome()
        assertNull(manager.lastMatchOutcome.value)

        // A second, identical match (same game, same winner, same score) right after the first.
        manager.launchGame(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, context().players, 0)
        manager.setActiveModule(FakeModule())
        manager.endActiveGame(win())

        val second = manager.lastMatchOutcome.value
        assertEquals("the second, equal-shaped match must still be observable", LocalOutcome.WIN, second?.localOutcome)
        assertEquals("sanity check: the two outcomes really were data-class-equal", first, second)
    }

    @Test
    fun `an aborted result never publishes a MatchOutcome`() {
        val manager = managerReadyToEnd()
        manager.endActiveGame(GameResult(wasAborted = true, scores = listOf(PlayerScore("p1", 0, false))))
        assertNull(manager.lastMatchOutcome.value)
    }

    @Test
    fun `endActiveGame without an active module never publishes a MatchOutcome`() {
        // The bug TicTacToeScreen used to hit: a screen that never calls setActiveModule (via
        // rememberActiveModule) still calls endActiveGame directly, but with activeModule ==
        // null nothing is ever published — see MainActivity's tic-tac-toe routes for the fix
        // (registering TicTacToeGame through rememberActiveModule like every other game).
        val manager = GameSessionManager()
        manager.launchGame(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, context().players, 0)
        manager.endActiveGame(win())
        assertNull(manager.lastMatchOutcome.value)
    }
}
