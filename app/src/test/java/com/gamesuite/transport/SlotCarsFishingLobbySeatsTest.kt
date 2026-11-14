package com.gamesuite.transport

import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.GameModule
import com.gamesuite.core.HubModeKind
import com.gamesuite.games.fishing.FishingGame
import com.gamesuite.games.slotcars.SlotCarsGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A Nearby or Online lobby for Slot Cars or Fishing can never start alone or overfill the engine's seats.
 * Skipped until the integrator has added both ids to GameHubSpecs (they are not there yet when this file lands).
 */
class SlotCarsFishingLobbySeatsTest {
    private val games: Map<String, GameModule> = mapOf("slot-cars" to SlotCarsGame(), "fishing" to FishingGame())

    private fun integrated() = assumeTrue("add slot-cars and fishing to GameHubSpecs first", games.keys.all { GameHubSpecs.byId(it) != null })

    @Test fun bothGamesAreNetworkedWithBothLobbies() {
        integrated()
        for (id in games.keys) {
            assertTrue("$id networked", id in GameHubSpecs.networkedGames)
            val kinds = GameHubSpecs.get(id).modes.map { it.kind }
            assertTrue(HubModeKind.NEARBY in kinds && HubModeKind.ONLINE in kinds)
        }
    }

    @Test fun aLobbyNeverStartsWithFewerThanTwoSeatsEvenForFishingsSoloMode() {
        integrated()
        for ((id, game) in games) for (kind in listOf(HubModeKind.NEARBY, HubModeKind.ONLINE)) {
            val limits = LobbySeatLimits.forGame(id, kind)
            assertTrue("$id $kind min ${limits.minPlayers}", limits.minPlayers >= 2)
            assertFalse("$id $kind starts with no guest", limits.canStart(0))
            assertTrue("$id $kind starts with one guest", limits.canStart(1))
            assertEquals("Waiting for 1 more player", limits.waitingMessage(0))
            assertTrue("$id $kind max ${limits.maxPlayers} vs engine ${game.maxPlayers}", limits.maxPlayers <= game.maxPlayers)
            assertTrue(limits.maxPlayers == 4 && limits.minPlayers >= game.minPlayers)
        }
    }
}
