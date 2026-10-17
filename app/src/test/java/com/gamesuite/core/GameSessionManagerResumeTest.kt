package com.gamesuite.core

import com.gamesuite.transport.LocalPassAndPlayTransport
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The shell side of Resume: when a resumable game is written down, when the save is discarded,
 * and how a launched screen picks it back up. The engines' own snapshot/restore are tested with
 * each engine; here a fake module stands in for them.
 */
class GameSessionManagerResumeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private open class FakeModule(
        override val gameId: String = "fake",
        override val displayName: String = "Fake Game"
    ) : GameModule {
        override val category = GameCategory.PUZZLE
        override val minPlayers = 1
        override val maxPlayers = 1
        override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)
        override fun init(context: GameContext) {}
        override fun startMatch() {}
        override fun pause() {}
        override fun resume() {}
        override fun endMatch(result: GameResult) {}
    }

    private class FakeResumable : FakeModule(), ResumableGame {
        var snapshotValue: String? = "state-1"
        var restoreResult = true
        var restoreThrows = false
        var restoredWith: String? = null
        var restoreCalls = 0

        override fun snapshot(): String? = snapshotValue
        override fun restore(snapshot: String): Boolean {
            restoreCalls++
            restoredWith = snapshot
            if (restoreThrows) throw IllegalStateException("corrupt")
            return restoreResult
        }
    }

    private fun store() = SavedGameStore(File(tmp.root, "saves"))

    private fun manager(module: GameModule, route: String? = "sudoku", withStore: Boolean = true): GameSessionManager {
        val m = GameSessionManager()
        if (withStore) m.savedGames = store()
        m.setActiveModule(module, route)
        return m
    }

    private fun saved(route: String = "sudoku", snapshot: String = "saved-state") = SavedGame(
        route = route,
        label = "Fake Game",
        savedAtMillis = System.currentTimeMillis(),
        savedAtEpochDay = java.time.LocalDate.now().toEpochDay(),
        snapshot = snapshot
    )

    @Test
    fun `pausing the active game saves its snapshot under its route`() {
        val module = FakeResumable().apply { snapshotValue = "mid-game" }
        val m = manager(module, route = "sudoku-daily")

        m.pauseActiveGame()

        val saved = store().load("sudoku-daily")
        assertNotNull(saved)
        assertEquals("mid-game", saved!!.snapshot)
        assertEquals("Fake Game", saved.label)
    }

    @Test
    fun `a null snapshot saves nothing and leaves an existing save alone`() {
        val module = FakeResumable().apply { snapshotValue = null }
        val m = manager(module)
        store().save(saved(snapshot = "older"))

        assertFalse(m.saveActiveProgress())

        assertEquals("a pause before the screen resumed must not wipe the save", "older", store().load("sudoku")?.snapshot)
    }

    @Test
    fun `a module that is not resumable is never saved`() {
        val m = manager(FakeModule())
        assertFalse(m.canSaveActiveProgress())
        assertFalse(m.saveActiveProgress())
        m.pauseActiveGame()
        assertNull(store().load("sudoku"))
    }

    @Test
    fun `saving needs both a route and a store`() {
        assertFalse(manager(FakeResumable(), route = null).canSaveActiveProgress())
        assertFalse(manager(FakeResumable(), withStore = false).canSaveActiveProgress())
        assertTrue(manager(FakeResumable()).canSaveActiveProgress())
    }

    @Test
    fun `a snapshot that throws is treated as nothing to save`() {
        val module = object : FakeModule(), ResumableGame {
            override fun snapshot(): String? = throw IllegalStateException("boom")
            override fun restore(snapshot: String) = false
        }
        val m = manager(module)
        assertFalse(m.saveActiveProgress())
    }

    @Test
    fun `finishing a match deletes the save but aborting keeps it`() {
        val module = FakeResumable()
        val m = manager(module)
        m.launchGame(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(PlayerInfo("p1", "Player")), 0)
        m.saveActiveProgress()
        assertNotNull(store().load("sudoku"))

        m.endActiveGame(GameResult(wasAborted = true))
        assertNotNull("leave-and-resume: an abort must keep the save", store().load("sudoku"))

        m.launchGame(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(PlayerInfo("p1", "Player")), 0)
        m.endActiveGame(GameResult(scores = listOf(PlayerScore("p1", 1, true))))
        assertNull("a finished match has nothing left to resume", store().load("sudoku"))
    }

    @Test
    fun `tryResume with nothing pending does nothing`() {
        val module = FakeResumable()
        val m = manager(module)
        assertFalse(m.tryResume(module))
        assertEquals(0, module.restoreCalls)
    }

    @Test
    fun `tryResume restores once and consumes the pending save`() {
        val module = FakeResumable()
        val m = manager(module)
        m.pendingResume = saved(snapshot = "pick-me-up")

        assertTrue(m.tryResume(module))
        assertEquals("pick-me-up", module.restoredWith)
        assertNull(m.pendingResume)

        assertFalse("a second call must not restore again", m.tryResume(module))
        assertEquals(1, module.restoreCalls)
    }

    @Test
    fun `a rejected restore deletes the stale save and falls back to a fresh match`() {
        val module = FakeResumable().apply { restoreResult = false }
        val m = manager(module)
        store().save(saved(snapshot = "stale"))
        m.pendingResume = saved(snapshot = "stale")

        assertFalse(m.tryResume(module))

        assertNull("the unusable save must not be offered again", store().load("sudoku"))
    }

    @Test
    fun `a restore that throws is a rejected restore`() {
        val module = FakeResumable().apply { restoreThrows = true }
        val m = manager(module)
        store().save(saved())
        m.pendingResume = saved()

        assertFalse(m.tryResume(module))
        assertNull(store().load("sudoku"))
    }

    @Test
    fun `tryResume on a module that is not resumable consumes the pending save and reports false`() {
        val module = FakeModule()
        val m = manager(module)
        m.pendingResume = saved()

        assertFalse(m.tryResume(module))
        assertNull(m.pendingResume)
    }

    @Test
    fun `leaving the screen forgets the route so a later pause cannot save under it`() {
        val module = FakeResumable()
        val m = manager(module)
        m.setActiveModule(null)
        m.pauseActiveGame()
        assertNull(store().load("sudoku"))
    }
}
