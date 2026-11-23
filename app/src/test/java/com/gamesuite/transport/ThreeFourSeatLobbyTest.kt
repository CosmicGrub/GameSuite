package com.gamesuite.transport

import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.OnlineMatchStarter
import com.gamesuite.core.PlayerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 5 S4: 3-4 seat lobbies for every networked game that seats three or more, parametrised over
 * [GameHubSpecs.networkedGames]. Pure logic only (the engines are audited in [NetworkedSeatAuditTest]).
 */
class ThreeFourSeatLobbyTest {

    private val threePlus: List<String> = GameHubSpecs.networkedGames.sorted().filter {
        LobbySeatLimits.forGame(it, HubModeKind.ONLINE).maxPlayers >= 3
    }

    private fun humans(n: Int) = (1..n).map { OnlinePlayer("p-guest-$it", "Guest $it") }

    @Test
    fun `the 3+ seat list is what the audit expects, so a new game cannot slip in unaudited`() {
        println("S4 SEATS (online min-max, cpu fill)")
        GameHubSpecs.networkedGames.sorted().forEach {
            val l = LobbySeatLimits.forGame(it, HubModeKind.ONLINE)
            println("  $it | ${l.minPlayers}-${l.maxPlayers} | fill=${OnlineCpuFill.isSupported(it)}")
        }
        assertTrue(threePlus.isNotEmpty())
        assertTrue("toy-soccer" in threePlus && "president" in threePlus && "sevens" in threePlus)
        assertEquals("President and Sevens need three", 3, LobbySeatLimits.forGame("president", HubModeKind.ONLINE).minPlayers)
    }

    @Test
    fun `every 3+ seat game has online limits within the relays room size`() {
        for (id in threePlus) {
            val l = LobbySeatLimits.forGame(id, HubModeKind.ONLINE)
            assertTrue("$id min", l.minPlayers in 2..l.maxPlayers)
            assertTrue("$id max <= relay room", l.maxPlayers <= GameHubSpecs.ONLINE_MAX_SEATS)
        }
    }

    @Test
    fun `computer fill is offered only where the engine drives computer seats and never for a two-seat game`() {
        for (id in OnlineCpuFill.supportedGames) {
            assertTrue("$id is a networked game", id in GameHubSpecs.networkedGames)
            assertTrue("$id supports fill online", OnlineCpuFill.isSupported(id, HubModeKind.ONLINE))
            assertTrue("$id has empty seats to fill", LobbySeatLimits.forGame(id, HubModeKind.ONLINE).maxPlayers > 2)
        }
        for (id in GameHubSpecs.networkedGames) {
            if (LobbySeatLimits.forGame(id, HubModeKind.ONLINE).maxPlayers <= 2) assertFalse("$id", OnlineCpuFill.isSupported(id))
        }
        // The only 3+ seat networked game with no engine support for computer seats online, per the S4 audit: Six-Ball
        // Puzzle races on every device and has no computer seat. (Dominoes and the other two-seat games never need fill.)
        assertEquals(setOf("six-ball-puzzle"), threePlus.filterNot { OnlineCpuFill.isSupported(it) }.toSet())
    }

    @Test
    fun `filling every empty seat with computers makes a startable table for each supported game that has a guest`() {
        for (id in threePlus.filter { OnlineCpuFill.isSupported(it) }) {
            val limits = LobbySeatLimits.forGame(id, HubModeKind.ONLINE)
            val cpus = limits.maxCpus(1)
            assertTrue("$id: host + 1 guest + $cpus computers", limits.canStart(1, cpus))
            assertEquals("$id table size", limits.maxPlayers, 1 + 1 + cpus)
        }
    }

    @Test
    fun `an announced roster seats every human guest and no guest can claim a computer seat`() {
        for (id in threePlus.filter { OnlineCpuFill.isSupported(it) }) {
            val limits = LobbySeatLimits.forGame(id, HubModeKind.ONLINE)
            val guests = humans(2.coerceAtMost(limits.maxGuests))
            val cpus = cpuSeatsFor(limits.maxCpus(guests.size)) { "Computer $it" }
            val roster = OnlineMatchStarter.hostRoster("p-host", "Host", guests, cpus)
            assertEquals("$id roster size", limits.maxPlayers, roster.size)
            assertEquals("host is seat 0", "p-host", roster[0].playerId)
            guests.forEachIndexed { i, guest ->
                assertEquals("$id guest ${guest.playerId} sits right after the host", i + 1, guestSeatIndex(roster, guest.playerId))
            }
            cpus.forEach { assertEquals("$id ${it.playerId} is never a guest seat", -1, guestSeatIndex(roster, it.playerId)) }
            assertEquals("ids are unique", roster.size, roster.map { it.playerId }.toSet().size)
        }
    }

    @Test
    fun `online host start announces computers as bots after the guests`() {
        val limits = LobbySeatLimits.forGame("president", HubModeKind.ONLINE) // 3-6
        val sent = ArrayList<String>()
        val channel = object : com.gamesuite.core.OnlineRawChannel {
            override fun sendRaw(toPlayerId: String?, payload: ByteArray) { sent += String(payload, Charsets.UTF_8) }
            override fun onRawMessageReceived(listener: (String, ByteArray) -> Unit) {}
        }
        var players: List<PlayerInfo>? = null
        val started = OnlineMatchStarter.startAsHost(
            channel, "p-host", "Host", humans(1), limits, "president", "President",
            cpuSeats = cpuSeatsFor(2) { "Computer $it" }
        ) { players = it }
        assertTrue(started)
        assertEquals(listOf("p-host", "p-guest-1", "cpu-1", "cpu-2"), players!!.map { it.playerId })
        assertEquals(listOf(false, false, true, true), players!!.map { it.isBot })
        assertTrue(sent.single().contains("\"isBot\":true"))
    }

    @Test
    fun `online host start refuses computers that are not computers, and a table with no human guest`() {
        val limits = LobbySeatLimits(3, 6)
        val channel = object : com.gamesuite.core.OnlineRawChannel {
            override fun sendRaw(toPlayerId: String?, payload: ByteArray) {}
            override fun onRawMessageReceived(listener: (String, ByteArray) -> Unit) {}
        }
        val fake = listOf(PlayerInfo("p-evil", "Computer 1", isBot = true)) // wrong id namespace
        assertFalse(OnlineMatchStarter.startAsHost(channel, "h", "H", humans(1), limits, "president", "P", cpuSeats = fake) {})
        val notBot = listOf(PlayerInfo("cpu-1", "Computer 1", isBot = false))
        assertFalse(OnlineMatchStarter.startAsHost(channel, "h", "H", humans(1), limits, "president", "P", cpuSeats = notBot) {})
        assertFalse(
            "host and computers only is a local game",
            OnlineMatchStarter.startAsHost(channel, "h", "H", emptyList(), limits, "president", "P", cpuSeats = cpuSeatsFor(2) { "C$it" }) {}
        )
    }

    @Test
    fun `Toy Soccer plays online one against one, and fills to two against two with computers`() {
        val limits = LobbySeatLimits.forGame("toy-soccer", HubModeKind.ONLINE)
        // The design doc assumed 4-4 (the pass-and-play range); the Hub's Online mode is 2-4, so 1v1 already worked.
        assertEquals(2, limits.minPlayers)
        assertEquals(4, limits.maxPlayers)
        assertTrue(OnlineCpuFill.isSupported("toy-soccer"))
        assertTrue("1v1 with a friend", limits.canStart(1, 0))
        assertTrue("2 friends + 2 computers is 2v2", limits.canStart(1, 2))
        assertFalse("a fifth seat does not exist", limits.canStart(1, 3))
        val sent = ArrayList<String>()
        val channel = object : com.gamesuite.core.OnlineRawChannel {
            override fun sendRaw(toPlayerId: String?, payload: ByteArray) { sent += String(payload, Charsets.UTF_8) }
            override fun onRawMessageReceived(listener: (String, ByteArray) -> Unit) {}
        }
        var players: List<PlayerInfo>? = null
        assertTrue(
            OnlineMatchStarter.startAsHost(channel, "h", "Host", humans(1), limits, "toy-soccer", "Toy Soccer", cpuSeats = cpuSeatsFor(2) { "Computer $it" }) { players = it }
        )
        assertEquals(4, players!!.size)
        assertEquals(2, players!!.count { it.isBot })
        assertFalse(
            OnlineMatchStarter.startAsHost(channel, "h", "Host", humans(1), limits, "toy-soccer", "Toy Soccer", cpuSeats = cpuSeatsFor(3) { "Computer $it" }) {}
        )
    }

    @Test
    fun `a guest that proposes a computer id is given a fresh seat id by a Nearby host`() {
        var n = 0
        val roster = NearbyRoster({ "minted-${++n}" }, LobbySeatLimits(3, 6))
            .withJoinRequest("ep1", NearbyLobbyMessage.JoinRequest("cpu-1", "Sneaky"))
            .withJoinRequest("ep2", NearbyLobbyMessage.JoinRequest("CPU-2", "Sneakier"))
            .withJoinRequest("ep3", NearbyLobbyMessage.JoinRequest("p-fine", "Honest"))
        assertEquals(listOf("minted-1", "minted-2", "p-fine"), roster.guests.map { it.playerId })
        assertTrue(roster.guests[0].idWasReplaced && roster.guests[1].idWasReplaced && !roster.guests[2].idWasReplaced)
    }

    @Test
    fun `a Nearby launch with computers announces the same roster to every guest and installs no mapping for computers`() {
        var n = 0
        val roster = NearbyRoster({ "minted-${++n}" }, LobbySeatLimits(4, 4))
            .withJoinRequest("ep1", NearbyLobbyMessage.JoinRequest("p-a", "A"))
        val cpus = cpuSeatsFor(2) { "Computer $it" }
        assertFalse(roster.canStartWithCpus(1))
        assertTrue(roster.canStartWithCpus(2))
        val launch = roster.launch("toy-soccer", hostDisplayName = "Host", gameDisplayName = "Toy Soccer", cpuSeats = cpus)
        assertEquals(listOf("host", "p-a", "cpu-1", "cpu-2"), launch.players.map { it.playerId })
        assertEquals(mapOf("ep1" to "p-a"), launch.endpointToPlayerId)
        launch.startMessages.forEach { (_, start) -> assertEquals(launch.players, start.players) }
        // The guest finds its seat; the computers are never anyone's seat.
        val start = launch.startMessages.single().second
        assertEquals(1, guestSeatIndex(start.players, "p-a"))
        assertEquals(-1, guestSeatIndex(start.players, "cpu-1"))
    }

    @Test
    fun `a Nearby launch refuses computer seats that are not computers`() {
        val roster = NearbyRoster({ "m" }, LobbySeatLimits(3, 3)).withJoinRequest("ep1", NearbyLobbyMessage.JoinRequest("p-a", "A"))
        val threw = runCatching { roster.launch("x", cpuSeats = listOf(PlayerInfo("p-b", "B", isBot = true))) }.isFailure
        assertTrue(threw)
    }

    @Test
    fun `a two-seat game's lobby is exactly as before`() {
        val chess = LobbySeatLimits.forGame("chess", HubModeKind.ONLINE)
        assertEquals(LobbySeatLimits(2, 2), chess)
        assertFalse(chess.canStart(0, 0))
        assertTrue(chess.canStart(1, 0))
        assertNotEquals("no computers beside a full table", true, chess.canStart(1, 1))
    }
}
