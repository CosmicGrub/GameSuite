package com.gamesuite.transport

import com.gamesuite.core.HubModeKind
import com.gamesuite.core.PlayerInfo
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seat limits for the Nearby / Online lobbies of ANY game: a two-seat game is one host plus exactly one guest,
 * UNO is 2..10. Pins the roster capacity rules, the Start gating, the seat/index/name contract of a launch on
 * both sides, and the "wrong or unplayable game" guest check. All pure logic (the Compose screens only apply it).
 */
class LobbyCapacityTest {

    private val twoSeat = LobbySeatLimits(2, 2)

    private fun join(id: String, name: String = "Guest") = NearbyLobbyMessage.JoinRequest(id, name)

    private fun counter(): () -> String {
        var n = 0
        return { "minted-${++n}" }
    }

    private fun roster(limits: LobbySeatLimits) = NearbyRoster(counter(), limits)

    // ---- LobbySeatLimits ----

    @Test
    fun `a two-seat game starts with exactly one guest`() {
        assertFalse(twoSeat.canStart(0))
        assertTrue(twoSeat.canStart(1))
        assertFalse(twoSeat.canStart(2))
        assertTrue(twoSeat.isFull(1))
        assertFalse(twoSeat.isFull(0))
    }

    @Test
    fun `waiting message counts the players still missing and disappears once Start is allowed`() {
        assertEquals("Waiting for 1 more player", twoSeat.waitingMessage(0))
        assertNull(twoSeat.waitingMessage(1))
        val four = LobbySeatLimits(4, 6)
        assertEquals("Waiting for 3 more players", four.waitingMessage(0))
        assertEquals("Waiting for 1 more player", four.waitingMessage(2))
        assertNull(four.waitingMessage(3))
        assertTrue(four.canStart(5))
        assertFalse(four.canStart(6))
    }

    @Test
    fun `limits reject nonsense and of() clamps it`() {
        assertThrows(IllegalArgumentException::class.java) { LobbySeatLimits(1, 2) }
        assertThrows(IllegalArgumentException::class.java) { LobbySeatLimits(4, 3) }
        assertEquals(LobbySeatLimits(2, 2), LobbySeatLimits.of(0, 0))
        assertEquals(LobbySeatLimits(3, 3), LobbySeatLimits.of(3, 1))
    }

    @Test
    fun `UNO keeps 2 to 10 seats and a game with no network mode is treated as two-seat`() {
        assertEquals(LobbySeatLimits(2, 10), LobbySeatLimits.forGame("uno", HubModeKind.NEARBY))
        assertEquals(LobbySeatLimits(2, 10), LobbySeatLimits.forGame("uno", HubModeKind.ONLINE))
        assertEquals(LobbySeatLimits(2, 2), LobbySeatLimits.forGame("no-such-game", HubModeKind.NEARBY))
    }

    // ---- NearbyRoster capacity ----

    @Test
    fun `a two-seat roster seats one guest and refuses the second with a room-is-full message`() {
        val r = roster(twoSeat).withJoinRequest("ep1", join("p-a", "A"))
        assertNull(r.rejectionFor("ep1")) // already seated: never refused
        assertEquals(NearbyLobbyMessage.Rejected(LOBBY_FULL_MESSAGE), r.rejectionFor("ep2"))

        val after = r.withJoinRequest("ep2", join("p-b", "B"))
        assertEquals(listOf("ep1"), after.guests.map { it.endpointId })
        assertEquals(listOf("host", "p-a"), after.players.map { it.playerId })
    }

    @Test
    fun `a seated guest re-sending its JoinRequest still works on a full roster`() {
        val r = roster(twoSeat).withJoinRequest("ep1", join("p-a", "A"))
        val again = r.withJoinRequest("ep1", join("p-a", "Renamed"))
        assertEquals(1, again.guests.size)
        assertEquals("Renamed", again.guests.single().displayName)
    }

    @Test
    fun `a seat frees up when a guest disconnects`() {
        val full = roster(twoSeat).withJoinRequest("ep1", join("p-a"))
        assertTrue(full.isFull)
        val freed = full.retainingEndpoints(emptyList())
        assertFalse(freed.isFull)
        assertNull(freed.rejectionFor("ep2"))
        assertEquals(1, freed.withJoinRequest("ep2", join("p-b")).guests.size)
    }

    @Test
    fun `UNO sized roster seats nine guests and refuses the tenth`() {
        var r = roster(LobbySeatLimits(2, 10))
        for (i in 1..9) r = r.withJoinRequest("ep$i", join("p-$i"))
        assertEquals(9, r.guests.size)
        assertEquals(NearbyLobbyMessage.Rejected(LOBBY_FULL_MESSAGE), r.rejectionFor("ep10"))
        assertEquals(9, r.withJoinRequest("ep10", join("p-10")).guests.size)
    }

    @Test
    fun `a late joiner after Start is told the game already started`() {
        val started = roster(LobbySeatLimits(2, 4)).withJoinRequest("ep1", join("p-a")).close()
        assertEquals(NearbyLobbyMessage.Rejected(LOBBY_STARTED_MESSAGE), started.rejectionFor("ep-late"))
        assertNull(started.rejectionFor("ep1"))
    }

    // ---- Start gating ----

    @Test
    fun `Start is enabled only once the minimum number of guests joined`() {
        val none = roster(twoSeat)
        assertFalse(none.canStart)
        val one = none.withJoinRequest("ep1", join("p-a"))
        assertTrue(one.canStart)
        assertFalse(one.close().canStart)

        val fourToSix = roster(LobbySeatLimits(4, 6))
        val two = fourToSix.withJoinRequest("e1", join("p-1")).withJoinRequest("e2", join("p-2"))
        assertFalse(two.canStart)
        assertTrue(two.withJoinRequest("e3", join("p-3")).canStart)
    }

    // ---- seat order, local index and announced roster (host and guest) ----

    @Test
    fun `two-seat launch seats the host at 0 and the guest at 1 and both sides see the same roster and names`() {
        val r = roster(twoSeat).withJoinRequest("ep-guest", join("p-guest", "Gwen"))
        val plan = r.launch("chess", hostDisplayName = "Hal", gameDisplayName = "Chess")

        // Host side: seat 0, id "host", its own name; the guest follows.
        assertEquals(listOf("host", "p-guest"), plan.players.map { it.playerId })
        assertEquals(listOf("Hal", "Gwen"), plan.players.map { it.displayName })
        assertEquals(0, plan.players.indexOfFirst { it.playerId == NEARBY_HOST_PLAYER_ID })
        assertEquals(mapOf("ep-guest" to "p-guest"), plan.endpointToPlayerId)

        // Guest side: the GameStart the host sent that endpoint.
        val (endpoint, start) = plan.startMessages.single()
        assertEquals("ep-guest", endpoint)
        assertEquals(plan.players, start.players) // the announced roster IS the host's launch roster
        assertEquals("chess", start.gameRoute)
        assertEquals("Chess", start.gameDisplayName)

        val guest = start.resolveForGuest(fromEndpointId = "ep-to-host", hostEndpointId = "ep-to-host", clientPlayerId = "p-guest")
        assertNotNull(guest)
        assertEquals(1, guest!!.localPlayerIndex)
        assertEquals(mapOf("ep-to-host" to "host"), guest.hostEndpointToPlayerId)
    }

    @Test
    fun `a guest whose id was replaced still finds its own seat`() {
        val r = roster(twoSeat).withJoinRequest("ep-guest", join("host", "Mallory")) // proposes the reserved id
        val plan = r.launch("chess")
        val start = plan.startMessages.single().second
        assertEquals("minted-1", start.yourPlayerId)
        val guest = start.resolveForGuest("e", "e", clientPlayerId = "host")
        assertEquals(1, guest!!.localPlayerIndex)
    }

    @Test
    fun `host name in the announced roster is cleaned and falls back to Host`() {
        val r = roster(twoSeat).withJoinRequest("ep1", join("p-a"))
        assertEquals("Host", r.launch("chess", hostDisplayName = "   ").players[0].displayName)
        assertEquals("Host", r.launch("chess").players[0].displayName)
    }

    @Test
    fun `an unnamed game keeps the GameStart wire format older guests read`() {
        val r = roster(twoSeat).withJoinRequest("ep1", join("p-a"))
        val sent: NearbyLobbyMessage = r.launch("uno").startMessages.single().second
        val json = Json.encodeToString(sent)
        assertFalse(json, json.contains("gameDisplayName"))
        assertFalse(json, json.contains("yourPlayerId"))
    }

    // ---- wire format ----

    @Test
    fun `Rejected and a named GameStart survive a JSON round trip`() {
        val rejected: NearbyLobbyMessage = NearbyLobbyMessage.Rejected(LOBBY_FULL_MESSAGE)
        assertEquals(rejected, Json.decodeFromString<NearbyLobbyMessage>(Json.encodeToString(rejected)))

        val start: NearbyLobbyMessage = NearbyLobbyMessage.GameStart(
            "chess", listOf(PlayerInfo("host", "Hal"), PlayerInfo("p-a", "Gwen")), gameDisplayName = "Chess"
        )
        assertEquals(start, Json.decodeFromString<NearbyLobbyMessage>(Json.encodeToString(start)))

        val onlineRejected: OnlineLobbyMessage = OnlineLobbyMessage.Rejected(LOBBY_FULL_MESSAGE)
        assertEquals(onlineRejected, Json.decodeFromString<OnlineLobbyMessage>(Json.encodeToString(onlineRejected)))
        val onlineStart: OnlineLobbyMessage = OnlineLobbyMessage.GameStart("chess", listOf(PlayerInfo("h", "Hal")), "Chess")
        assertEquals(onlineStart, Json.decodeFromString<OnlineLobbyMessage>(Json.encodeToString(onlineStart)))
    }

    @Test
    fun `a rejection reason is made safe to show`() {
        assertEquals("ab", lobbyRejectionText("a\u0000b\n"))
        assertEquals(120, lobbyRejectionText("x".repeat(500)).length)
        assertEquals("The host did not accept you.", lobbyRejectionText("   "))
    }

    // ---- Online seating ----

    private fun players(vararg ids: String) = ids.map { OnlinePlayer(it, "n-$it") }

    @Test
    fun `online a two-seat room seats the first guest and turns the rest away`() {
        val seating = seatOnlineGuests(players("a", "b", "c"), twoSeat)
        assertEquals(listOf("a"), seating.seated.map { it.playerId })
        assertEquals(listOf("b", "c"), seating.overflow.map { it.playerId })
        assertTrue(twoSeat.canStart(seating.seated.size))
    }

    @Test
    fun `online a UNO room seats everyone up to its cap and Start waits for the minimum`() {
        val uno = LobbySeatLimits(2, 10)
        assertEquals(0, seatOnlineGuests(emptyList(), uno).seated.size)
        assertFalse(uno.canStart(0))
        val nine = seatOnlineGuests(players(*Array(12) { "p$it" }), uno)
        assertEquals(9, nine.seated.size)
        assertEquals(3, nine.overflow.size)
    }

    @Test
    fun `online a Rejected only counts when the host sent it`() {
        val rejected = OnlineLobbyMessage.Rejected(LOBBY_FULL_MESSAGE)
        assertTrue(rejected.isFromHost("p-host", "p-host"))
        assertFalse(rejected.isFromHost("p-other", "p-host"))
        assertFalse(rejected.isFromHost("p-host", null))
    }

    // ---- guest: wrong or unplayable game ----

    @Test
    fun `a guest is told when the room plays a game this build cannot play over a network`() {
        val problem = lobbyGameProblem("zzz-unknown-route", "Zzz\u0000 Game", expectedGameId = "uno")
        assertNotNull(problem)
        assertTrue(problem!!, problem.contains("Zzz Game") && problem.contains("can't play"))
        assertNotNull(lobbyGameProblem("zzz-unknown-route", null, expectedGameId = null))
    }

    @Test
    fun `a guest is told when the room plays a different game than the one it opened`() {
        // "uno" is a networked game; the guest opened "no-such-game"'s lobby.
        val problem = lobbyGameProblem("uno", "UNO", expectedGameId = "no-such-game")
        assertNotNull(problem)
        assertTrue(problem!!, problem.contains("not"))
    }

    @Test
    fun `a guest in the right lobby for a playable game is not blocked`() {
        assertNull(lobbyGameProblem("uno", "UNO", expectedGameId = "uno"))
        assertNull(lobbyGameProblem(lobbyRouteFor("uno"), null, expectedGameId = null))
    }
}
