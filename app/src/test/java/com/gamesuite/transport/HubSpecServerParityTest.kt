package com.gamesuite.transport

import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.HubModeKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The client and the relay (server/) each know how many seats a room has: [GameHubSpecs.ONLINE_MAX_SEATS] must equal
 * the relay's MAX_PLAYERS_PER_ROOM, and the generated server/seat-ranges.json (which the matchmaking queue validates
 * tickets against) must list exactly the Hub's networked games with the same Online seat range. Regenerate the JSON
 * with `node server/gen-seat-ranges.js` after changing a game's seats.
 */
class HubSpecServerParityTest {

    private fun serverFile(name: String): File? =
        listOf(File("../server/$name"), File("server/$name")).firstOrNull { it.exists() }

    @Test
    fun `the online table size is the relays room size`() {
        assertEquals(8, GameHubSpecs.ONLINE_MAX_SEATS)
        val index = serverFile("index.js")
        assumeTrue("server/index.js not found from ${File(".").absolutePath}", index != null)
        val declared = Regex("""const MAX_PLAYERS_PER_ROOM\s*=\s*(\d+)""").find(index!!.readText())?.groupValues?.get(1)?.toInt()
        assertEquals("server/index.js MAX_PLAYERS_PER_ROOM", GameHubSpecs.ONLINE_MAX_SEATS, declared)
    }

    @Test
    fun `seat-ranges json matches the Hub for every networked game`() {
        val file = serverFile("seat-ranges.json")
        assumeTrue("server/seat-ranges.json not found from ${File(".").absolutePath}", file != null)
        val root = Json.parseToJsonElement(file!!.readText()).jsonObject
        assertEquals("onlineMaxSeats", GameHubSpecs.ONLINE_MAX_SEATS, root.getValue("onlineMaxSeats").jsonPrimitive.int)
        val games = root.getValue("games").jsonObject
        assertEquals("the same set of games", GameHubSpecs.networkedGames, games.keys)
        for (id in GameHubSpecs.networkedGames) {
            val limits = LobbySeatLimits.forGame(id, HubModeKind.ONLINE)
            val entry = games.getValue(id).jsonObject
            assertEquals("$id min", limits.minPlayers, entry.getValue("min").jsonPrimitive.int)
            assertEquals("$id max", limits.maxPlayers, entry.getValue("max").jsonPrimitive.int)
        }
        assertTrue(games.isNotEmpty())
    }
}
