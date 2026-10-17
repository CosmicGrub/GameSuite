package com.gamesuite.core

import com.gamesuite.transport.LobbyJson
import com.gamesuite.transport.NearbyLobbyMessage
import com.gamesuite.transport.OnlineLobbyMessage
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regression tests for the review findings on the Game Hub PR (legacy saves, wire tolerance, keys, caps). */
class ReviewFixesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ---- saves written before the Hub still resume ----------------------------------------

    @Test
    fun aLegacyVsCpuKeyRebuildsItsLaunch() {
        val l = legacyLaunchFromKey("chess@SINGLE_PLAYER_VS_BOT@p1,bot1!", "chess")!!
        assertEquals("SINGLE_PLAYER_VS_BOT", l.mode)
        assertEquals(listOf("p1", "bot1"), l.seats.map { it.id })
        assertEquals(listOf("You", "CPU"), l.seats.map { it.name })
        assertEquals(listOf(false, true), l.seats.map { it.isBot })
        assertEquals(0, l.localPlayerIndex)
    }

    @Test
    fun aLegacyPassAndPlayKeyGetsNumberedPlayers() {
        val l = legacyLaunchFromKey("uno@SINGLE_DEVICE_PASS_AND_PLAY@p1,p2,p3,p4", "uno")!!
        assertEquals(listOf("Player 1", "Player 2", "Player 3", "Player 4"), l.seats.map { it.name })
        assertTrue(l.seats.all { it.teamId == -1 })
    }

    @Test
    fun theOldTeamsRouteGetsAlternatingTeams() {
        val l = legacyLaunchFromKey("uno-teams@SINGLE_DEVICE_PASS_AND_PLAY@p1,p2,p3,p4", "uno-teams")!!
        assertEquals(listOf(0, 1, 0, 1), l.seats.map { it.teamId })
    }

    @Test
    fun manyCpusAreNumberedAndNewKeysWithTeamSuffixesParse() {
        val l = legacyLaunchFromKey("uno@SINGLE_PLAYER_VS_BOT@p1,bot1!,bot2!,bot3!", "uno")!!
        assertEquals(listOf("You", "CPU 1", "CPU 2", "CPU 3"), l.seats.map { it.name })
        val t = legacyLaunchFromKey("uno@SINGLE_DEVICE_PASS_AND_PLAY@p1#0,p2#1", "uno")!!
        assertEquals(listOf("p1", "p2"), t.seats.map { it.id })
    }

    @Test
    fun anUnparseableKeyYieldsNull() {
        assertNull(legacyLaunchFromKey("chess", "chess"))
        assertNull(legacyLaunchFromKey("chess@@p1", "chess"))
        assertNull(legacyLaunchFromKey("chess@SINGLE_PLAYER_VS_BOT@", "chess"))
    }

    private class Resumable : GameModule, ResumableGame {
        override val gameId = "chess"
        override val displayName = "Chess"
        override val category = GameCategory.BOARD
        override val minPlayers = 1
        override val maxPlayers = 2
        override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)
        var restored: String? = null
        override fun init(context: GameContext) {}
        override fun startMatch() {}
        override fun pause() {}
        override fun resume() {}
        override fun endMatch(result: GameResult) {}
        override fun snapshot(): String? = "snap"
        override fun restore(snapshot: String): Boolean { restored = snapshot; return true }
    }

    @Test
    fun aPreHubSaveResumesInsteadOfBeingDeleted() {
        val store = SavedGameStore(File(tmp.root, "saves"))
        val legacyKey = "chess@SINGLE_PLAYER_VS_BOT@p1,bot1!"
        store.save(
            SavedGame(
                route = legacyKey, label = "Chess", savedAtMillis = System.currentTimeMillis(),
                savedAtEpochDay = java.time.LocalDate.now().toEpochDay(), snapshot = "legacy-state",
                navRoute = "chess", launch = null
            )
        )
        val m = GameSessionManager().also { it.savedGames = store }
        val saved = store.load(legacyKey)!!
        assertTrue("a legacy save must resume", m.resumeSavedGame(saved))
        assertNotNull("the file stays until the restore succeeds", store.load(legacyKey))
        val ctx = m.activeContext.value!!
        assertEquals(PlayMode.SINGLE_PLAYER_VS_BOT, ctx.activeMode)
        assertEquals(listOf("p1", "bot1"), ctx.players.map { it.playerId })
        // the screen then restores the snapshot through tryResume
        val module = Resumable()
        m.setActiveModule(module, "chess")
        assertTrue(m.tryResume(module))
        assertEquals("legacy-state", module.restored)
    }

    @Test
    fun aSaveWhoseKeyChangedKeepsItsOldFileUntilTheRestoreSucceedsThenDropsIt() {
        val store = SavedGameStore(File(tmp.root, "saves3"))
        val oldKey = "uno-teams@SINGLE_DEVICE_PASS_AND_PLAY@p1,p2,p3,p4"   // no team suffixes: pre-change key
        val save = SavedGame(
            route = oldKey, label = "UNO", savedAtMillis = System.currentTimeMillis(),
            savedAtEpochDay = java.time.LocalDate.now().toEpochDay(), snapshot = "teams-state",
            navRoute = "uno-teams", launch = null
        )
        store.save(save)
        val m = GameSessionManager().also { it.savedGames = store }
        assertTrue(m.resumeSavedGame(save))
        assertNotNull("kept while the restore is pending", store.load(oldKey))
        val module = Resumable()
        m.setActiveModule(module, "uno-teams")
        assertTrue(m.tryResume(module))
        assertNull("dropped once restored, because the key changed", store.load(oldKey))
    }

    @Test
    fun aRejectedRestoreStillDeletesTheSaveAndNeverTheNewKeyFile() {
        val store = SavedGameStore(File(tmp.root, "saves4"))
        val key = "chess@SINGLE_PLAYER_VS_BOT@p1,bot1!"
        val save = SavedGame(key, "Chess", System.currentTimeMillis(), java.time.LocalDate.now().toEpochDay(), "x", navRoute = "chess", launch = null)
        store.save(save)
        val m = GameSessionManager().also { it.savedGames = store }
        assertTrue(m.resumeSavedGame(save))
        val rejecting = object : GameModule by Resumable(), ResumableGame {
            override fun snapshot(): String? = null
            override fun restore(snapshot: String) = false
        }
        m.setActiveModule(rejecting, "chess")
        assertFalse(m.tryResume(rejecting))
        assertNull(store.load(key))
    }

    @Test
    fun theUnoSpecKeepsItsLegacyRoutesSoOldSavesGetAResumeCard() {
        val routes = GameHubSpecs.get("uno").routes()
        assertTrue(routes.containsAll(listOf("uno", "uno-teams", "uno-custom")))
    }

    @Test
    fun aSaveWithNoUsableKeyStillCannotResume() {
        val m = GameSessionManager().also { it.savedGames = SavedGameStore(File(tmp.root, "s2")) }
        val bad = SavedGame("nokey", "X", 0L, 0L, "s", navRoute = "x", launch = null)
        assertFalse(m.resumeSavedGame(bad))
    }

    // ---- lobby messages tolerate fields they do not know ------------------------------------

    @Test
    fun aLobbyGameStartWithAnUnknownFieldStillDecodes() {
        val nearby: NearbyLobbyMessage = NearbyLobbyMessage.GameStart("chess", emptyList(), null, "Chess")
        val nearbyJson = LobbyJson.encodeToString(nearby)
        val nearbyExtra = nearbyJson.dropLast(1) + ",\"someFutureField\":42}"
        assertEquals(nearby, LobbyJson.decodeFromString<NearbyLobbyMessage>(nearbyExtra))
        assertTrue(
            "the strict decoder (what older builds use) rejects the extra field, which is why the new one must not",
            runCatching { Json.decodeFromString<NearbyLobbyMessage>(nearbyExtra) }.isFailure
        )

        val online: OnlineLobbyMessage = OnlineLobbyMessage.GameStart("chess", emptyList(), "Chess")
        val onlineExtra = LobbyJson.encodeToString(online).dropLast(1) + ",\"someFutureField\":\"x\"}"
        assertEquals(online, LobbyJson.decodeFromString<OnlineLobbyMessage>(onlineExtra))
    }

    // ---- save keys, spectators and the Online cap ---------------------------------------------

    private fun ctx(vararg players: PlayerInfo) = GameContext(
        activeMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        players = players.toList(), localPlayerIndex = 0,
        transport = com.gamesuite.transport.LocalPassAndPlayTransport()
    )

    @Test
    fun teamsAndPassAndPlayAreDifferentSaveKeys() {
        val m = GameSessionManager()
        val plain = ctx(PlayerInfo("p1", "A"), PlayerInfo("p2", "B"), PlayerInfo("p3", "C"), PlayerInfo("p4", "D"))
        val teams = ctx(PlayerInfo("p1", "A", teamId = 0), PlayerInfo("p2", "B", teamId = 1),
            PlayerInfo("p3", "C", teamId = 0), PlayerInfo("p4", "D", teamId = 1))
        assertNotEquals(m.saveKey("uno", plain), m.saveKey("uno", teams))
        assertEquals("uno@SINGLE_DEVICE_PASS_AND_PLAY@p1,p2,p3,p4", m.saveKey("uno", plain))
    }

    @Test
    fun onlyUnoCanBeWatchedAndOnlineTablesNeverExceedTheRelayLimit() {
        assertTrue("no engine delivers state to spectators yet, so Watch stays hidden", GameHubSpecs.spectatorGames.isEmpty())
        val net = GameHubSpecs.buildAll(GameHubSpecs.networkedGames)
        val uno = net.first { it.gameId == "uno" }
        assertEquals(10, uno.mode("nearby")!!.maxSeats)
        assertEquals(GameHubSpecs.ONLINE_MAX_SEATS, uno.mode("online")!!.maxSeats)
        assertTrue(net.filter { it.isNetworked }.all { it.mode("online")!!.maxSeats <= GameHubSpecs.ONLINE_MAX_SEATS })
        assertEquals(2, net.first { it.gameId == "chess" }.mode("online")!!.maxSeats)
    }
}
