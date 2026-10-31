package com.gamesuite.transport

import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.GameModule
import com.gamesuite.core.HubModeKind
import com.gamesuite.games.billiards.BilliardsGame
import com.gamesuite.games.bowling.BowlingGame
import com.gamesuite.games.carrom.CarromGame
import com.gamesuite.games.curling.CurlingGame
import com.gamesuite.games.darts.DartsGame
import com.gamesuite.games.minigolf.GolfGame
import com.gamesuite.games.toybaseball.ToyBaseballGame
import com.gamesuite.games.toyboxing.ToyBoxingGame
import com.gamesuite.games.toysoccer.ToySoccerGame
import com.gamesuite.games.toytennis.ToyTennisGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Nearby or Online lobby for any of the ten toy-sports games can never start alone or overfill the game's seats. */
class ToySportsLobbySeatsTest {
    private val games: Map<String, GameModule> = mapOf(
        "darts" to DartsGame(), "bowling" to BowlingGame(), "carrom" to CarromGame(), "curling" to CurlingGame(),
        "billiards" to BilliardsGame(), "mini-golf" to GolfGame(), "toy-soccer" to ToySoccerGame(),
        "toy-tennis" to ToyTennisGame(), "toy-boxing" to ToyBoxingGame(), "toy-baseball" to ToyBaseballGame()
    )

    @Test fun everyToySportsGameIsNetworkedWithBothLobbies() {
        for (id in games.keys) {
            assertTrue("$id networked", id in GameHubSpecs.networkedGames)
            val kinds = GameHubSpecs.get(id).modes.map { it.kind }
            assertTrue("$id nearby", HubModeKind.NEARBY in kinds); assertTrue("$id online", HubModeKind.ONLINE in kinds)
        }
    }

    @Test fun aLobbyNeverStartsWithFewerThanTwoSeatsEvenForGamesWithASoloMode() {
        for ((id, game) in games) for (kind in listOf(HubModeKind.NEARBY, HubModeKind.ONLINE)) {
            val limits = LobbySeatLimits.forGame(id, kind)
            assertTrue("$id $kind min ${limits.minPlayers}", limits.minPlayers >= 2)
            assertFalse("$id $kind starts with no guest", limits.canStart(0))
            assertTrue("$id $kind starts with one guest", limits.canStart(1))
            assertEquals("$id $kind", "Waiting for 1 more player", limits.waitingMessage(0))
            // Never more seats than the engine has, and never fewer than it needs.
            assertTrue("$id $kind max ${limits.maxPlayers} vs engine ${game.maxPlayers}", limits.maxPlayers <= game.maxPlayers)
            assertTrue("$id $kind min ${limits.minPlayers} vs engine ${game.minPlayers}", limits.minPlayers >= game.minPlayers)
        }
    }

    @Test fun twoSeatGamesAcceptExactlyOneGuest() {
        for (id in listOf("billiards", "toy-tennis", "toy-boxing", "toy-baseball")) for (kind in listOf(HubModeKind.NEARBY, HubModeKind.ONLINE)) {
            val limits = LobbySeatLimits.forGame(id, kind)
            assertEquals(2, limits.minPlayers); assertEquals(2, limits.maxPlayers)
            assertTrue(limits.isFull(1)); assertFalse(limits.canStart(2))
        }
    }
}
