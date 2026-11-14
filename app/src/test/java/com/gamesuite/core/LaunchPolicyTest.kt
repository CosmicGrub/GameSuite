package com.gamesuite.core

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LaunchPolicyTest {
    @Before fun failClosed() = LaunchPolicy.resetToFailClosed()
    @After fun restore() = LaunchPolicy.resetToFailClosed()

    @Test fun `the fail closed defaults block network play and do not block local play`() {
        LaunchPolicy.resetToFailClosed()
        assertFalse(LaunchPolicy.networkAllowed)
        assertFalse(LaunchPolicy.timeUp)
        assertFalse(LaunchPolicy.allows(PlayMode.ONLINE))
        assertFalse(LaunchPolicy.allows(PlayMode.LOCAL_AD_HOC))
        assertTrue(LaunchPolicy.allows(PlayMode.SINGLE_PLAYER_VS_BOT))
        assertTrue(LaunchPolicy.allows(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY))
        assertTrue(LaunchPolicy.allowsNewGame())
    }

    @Test fun `network play opens only when the shell explicitly allows it`() {
        LaunchPolicy.networkAllowed = true
        assertTrue(LaunchPolicy.allowsLaunch(PlayMode.ONLINE))
        assertTrue(LaunchPolicy.allowsLaunch(PlayMode.LOCAL_AD_HOC))
    }

    @Test fun `time up refuses every new launch whatever the mode`() {
        LaunchPolicy.networkAllowed = true
        LaunchPolicy.timeUp = true
        assertFalse(LaunchPolicy.allowsNewGame())
        PlayMode.values().forEach { assertFalse(it.name, LaunchPolicy.allowsLaunch(it)) }
    }

    @Test fun `a refused launch leaves no game context and a refused resume arms nothing`() {
        val m = GameSessionManager()
        LaunchPolicy.timeUp = true
        m.launchGame(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(PlayerInfo("p1", "Player")), 0)
        assertNull(m.activeContext.value)

        val saved = SavedGame(
            route = "sudoku@SINGLE_PLAYER_VS_BOT@p1", navRoute = "sudoku", label = "Sudoku",
            savedAtMillis = System.currentTimeMillis(), savedAtEpochDay = java.time.LocalDate.now().toEpochDay(),
            snapshot = "s",
            launch = SavedLaunch(mode = "SINGLE_PLAYER_VS_BOT", seats = listOf(SavedSeat("p1", "Player")))
        )
        assertFalse(m.resumeSavedGame(saved))
        assertNull("a refused resume must not leave pendingResume for the next game", m.pendingResume)

        LaunchPolicy.timeUp = false
        assertTrue(m.resumeSavedGame(saved))
    }

    @Test fun `a saved online game cannot be resumed while network play is closed`() {
        val m = GameSessionManager()
        val saved = SavedGame(
            route = "x@ONLINE@p1,p2", navRoute = "x", label = "X",
            savedAtMillis = System.currentTimeMillis(), savedAtEpochDay = java.time.LocalDate.now().toEpochDay(),
            snapshot = "s",
            launch = SavedLaunch(mode = "ONLINE", seats = listOf(SavedSeat("p1", "A"), SavedSeat("p2", "B")))
        )
        assertFalse(m.resumeSavedGame(saved))
        assertNull(m.pendingResume)
    }
}
