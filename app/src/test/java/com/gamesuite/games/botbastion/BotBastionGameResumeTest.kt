package com.gamesuite.games.botbastion

import com.gamesuite.core.GameResult
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.LaunchOptions
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.core.ResumableGame
import com.gamesuite.core.SavedGameStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Hub Resume for Bot Bastion: it is a ResumableGame, and the shell save / resume path restores it. */
class BotBastionGameResumeTest {

    @get:Rule val tmp = TemporaryFolder()

    private val testMaps = shippedMaps()
    private val extras = mapOf("map" to "twin", "tier" to "hero", "players" to "2")

    private fun game(options: Map<String, String> = extras): BotBastionGame =
        BotBastionGame().apply { configure(options, testMaps) }

    private fun played(): BotBastionGame {
        val g = game()
        g.startMatch()
        assertEquals(BbResult.OK, g.place(0, BotType.PICKET, 0))
        assertEquals(BbResult.OK, g.place(1, BotType.TENDER, 3))
        g.engine!!.callEarly(0)
        repeat(60) { g.tick(0.1f) }
        return g
    }

    @Test fun itIsAResumableGame() {
        assertTrue(BotBastionGame() is ResumableGame)
    }

    @Test fun restoringASnapshotBringsBackTheRun() {
        val a = played()
        val snap = a.snapshot()
        assertNotNull(snap)
        val b = game(emptyMap())
        assertTrue(b.restore(snap!!))
        assertEquals("twin", b.mapId)
        assertEquals(Tier.HERO, b.tier)
        assertEquals(2, b.playerCount)
        assertEquals(snap, b.snapshot())
        assertEquals(a.engine!!.bots.size, b.engine!!.bots.size)
        assertNotNull(b.hud.value)
        assertNull(b.outcome.value)
    }

    @Test fun aRestoredRunKeepsPlayingIdentically() {
        val a = played()
        val b = game(emptyMap())
        assertTrue(b.restore(a.snapshot()!!))
        repeat(100) { a.tick(0.1f); b.tick(0.1f) }
        assertEquals(a.snapshot(), b.snapshot())
    }

    @Test fun aDamagedOrForeignSnapshotIsRefusedAndLeavesNoRun() {
        val g = game(emptyMap())
        assertFalse(g.restore(""))
        assertFalse(g.restore("{"))
        assertFalse(g.restore("{\"v\":1}"))
        assertNull(g.engine)
    }

    @Test fun thereIsNothingToSaveBeforeAStartOrAfterTheEnd() {
        val g = game()
        assertNull(g.snapshot())
        g.startMatch()
        assertNotNull(g.snapshot())
        g.endMatch(GameResult(wasAborted = true))
        assertNull(g.snapshot())
    }

    @Test fun theShellSavesOnPauseAndAResumedLaunchRestoresThatRun() {
        val store = SavedGameStore(File(tmp.root, "saves"))
        val m1 = GameSessionManager().apply { savedGames = store }
        val a = played()
        m1.setActiveModule(a, "bot-bastion")
        m1.launchGame(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(PlayerInfo("p1", "Player")), 0, LaunchOptions(extras = extras))
        m1.pauseActiveGame()
        val saved = store.all().single()
        assertEquals("bot-bastion", saved.navRoute)
        assertEquals(a.snapshot(), saved.snapshot)

        // a fresh process: new manager, new game object; the screen start-up calls configure() then tryResume()
        val m2 = GameSessionManager().apply { savedGames = store }
        val b = BotBastionGame()
        m2.setActiveModule(b, "bot-bastion")
        assertTrue(m2.resumeSavedGame(saved))
        b.configure(m2.launchOptions.extras, testMaps)
        assertTrue(m2.tryResume(b))
        assertEquals(a.snapshot(), b.snapshot())
        assertEquals(2, b.engine!!.players.size)
        assertFalse("the pending save is consumed", m2.tryResume(b))
    }

    @Test fun aCorruptSaveFallsBackToAFreshMatchAndIsDeleted() {
        val store = SavedGameStore(File(tmp.root, "saves"))
        val m1 = GameSessionManager().apply { savedGames = store }
        val a = played()
        m1.setActiveModule(a, "bot-bastion")
        m1.launchGame(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(PlayerInfo("p1", "Player")), 0, LaunchOptions(extras = extras))
        m1.pauseActiveGame()
        val good = store.all().single()

        val m2 = GameSessionManager().apply { savedGames = store }
        val b = BotBastionGame()
        m2.setActiveModule(b, "bot-bastion")
        assertTrue(m2.resumeSavedGame(good.copy(snapshot = "garbage")))
        b.configure(m2.launchOptions.extras, testMaps)
        assertFalse(m2.tryResume(b))
        assertNull(b.engine)
        assertTrue("the unusable save is dropped", store.all().isEmpty())
    }

    @Test fun scrapGiftsGoThroughTheGameAndRefreshTheHud() {
        val g = game()
        g.startMatch()
        val e = g.engine!!
        val before = g.hud.value!!.scrap
        assertEquals(BbResult.OK, g.gift(0, 1, 25))
        val after = g.hud.value!!.scrap
        assertEquals(before[0] - 25, after[0])
        assertEquals(before[1] + 25, after[1])
        assertEquals(after[0], e.players[0].scrap)
        assertEquals(BbResult.BAD_ARGUMENT, g.gift(0, 0, 25))
        assertEquals(BbResult.BAD_ARGUMENT, g.gift(0, 1, 5))
        assertEquals(BbResult.NO_SCRAP, g.gift(0, 1, 10_000))
    }
}
