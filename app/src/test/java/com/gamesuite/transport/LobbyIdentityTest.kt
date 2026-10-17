package com.gamesuite.transport

import com.gamesuite.core.PlayerInfo
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guest-side lobby checks: which sender a GameStart is believed from, and which seat (if any)
 * a roster gives the guest. A roster that says "you are seat 0" would make a guest run the game
 * engine as the host, so it must never be believed.
 */
class LobbyIdentityTest {

    private fun roster(vararg ids: String) = ids.map { PlayerInfo(playerId = it, displayName = it) }

    // ---- guestSeatIndex ----

    @Test
    fun `a guest finds its own seat in the roster`() {
        assertEquals(2, guestSeatIndex(roster("host", "p-a", "p-b"), "p-b"))
    }

    @Test
    fun `a guest missing from the roster is not seated`() {
        assertEquals(-1, guestSeatIndex(roster("host", "p-a"), "p-me"))
    }

    @Test
    fun `a roster that puts the guest at seat 0 is refused`() {
        // localPlayerIndex == 0 is what makes a device run the engine as the host.
        assertEquals(-1, guestSeatIndex(roster("p-me", "p-a"), "p-me"))
    }

    @Test
    fun `a roster repeating an id is refused`() {
        assertEquals(-1, guestSeatIndex(roster("host", "p-a", "p-me", "p-a"), "p-me"))
        assertEquals(-1, guestSeatIndex(roster("host", "p-me", "p-me"), "p-me"))
    }

    @Test
    fun `an empty roster seats nobody`() {
        assertEquals(-1, guestSeatIndex(emptyList(), "p-me"))
    }

    // ---- Nearby GameStart: who may start a guest ----

    private fun nearbyStart(vararg ids: String, yours: String? = null) =
        NearbyLobbyMessage.GameStart("uno", roster(*ids), yourPlayerId = yours)

    @Test
    fun `a Nearby GameStart is accepted only from the endpoint the guest connected to`() {
        val start = nearbyStart("host", "p-me")

        assertTrue(start.isAcceptedFrom("ep-host", hostEndpointId = "ep-host"))
        assertFalse("some other connected endpoint", start.isAcceptedFrom("ep-rogue", hostEndpointId = "ep-host"))
        assertFalse("not connected to anyone yet", start.isAcceptedFrom("ep-host", hostEndpointId = null))
    }

    @Test
    fun `a Nearby GameStart with an empty roster is never accepted`() {
        assertFalse(NearbyLobbyMessage.GameStart("uno", emptyList()).isAcceptedFrom("ep-host", "ep-host"))
    }

    // ---- Online GameStart: who may start a guest ----

    private fun onlineStart(vararg ids: String) = OnlineLobbyMessage.GameStart("uno", roster(*ids))

    @Test
    fun `an Online GameStart is accepted only from the room's host, and only if the roster agrees`() {
        val fromHost = onlineStart("p-host", "p-me")

        assertTrue(fromHost.isFromHost(fromPlayerId = "p-host", hostPlayerId = "p-host"))
        assertFalse("a guest or spectator relaying a GameStart", fromHost.isFromHost("p-other", "p-host"))
        assertFalse("host not known yet", fromHost.isFromHost("p-host", null))
    }

    @Test
    fun `an Online GameStart whose roster names someone else as seat 0 is refused even from the host`() {
        // A forged roster that makes an attacker "the host" of the guest's match.
        val forged = onlineStart("p-mallory", "p-me")
        assertFalse(forged.isFromHost(fromPlayerId = "p-host", hostPlayerId = "p-host"))
        assertFalse(onlineStart().isFromHost("p-host", "p-host"))
    }

    @Test
    fun `a forged Online GameStart from a guest is refused end to end with the roster helper`() {
        val forged = onlineStart("p-mallory", "p-me")
        // Mallory is a room member, so the relay stamps it truthfully as p-mallory...
        assertFalse(forged.isFromHost(fromPlayerId = "p-mallory", hostPlayerId = "p-host"))
        // ...and even if the guest didn't know the host yet, nothing is accepted.
        assertFalse(forged.isFromHost(fromPlayerId = "p-mallory", hostPlayerId = null))
    }

    // ---- wire compatibility ----

    private val json = Json

    @Test
    fun `Nearby GameStart round-trips yourPlayerId`() {
        val sent: NearbyLobbyMessage = NearbyLobbyMessage.GameStart("uno", roster("host", "p-a"), yourPlayerId = "p-a")
        val received = json.decodeFromString<NearbyLobbyMessage>(json.encodeToString(sent))
        assertEquals(sent, received)
    }

    @Test
    fun `a GameStart from an older host without yourPlayerId still decodes, and the guest falls back to its own id`() {
        val old: NearbyLobbyMessage = NearbyLobbyMessage.GameStart("uno", roster("host", "p-a"))
        val text = json.encodeToString(old)
        assertFalse("default null is not encoded, so this is the old wire shape", text.contains("yourPlayerId"))

        val decoded = json.decodeFromString<NearbyLobbyMessage>(text) as NearbyLobbyMessage.GameStart
        assertNull(decoded.yourPlayerId)
        assertEquals(1, guestSeatIndex(decoded.players, decoded.yourPlayerId ?: "p-a"))
    }

    @Test
    fun `the JoinRequest wire shape is unchanged`() {
        val text = json.encodeToString<NearbyLobbyMessage>(NearbyLobbyMessage.JoinRequest("p-a", "Guest (Pixel)"))
        assertTrue(text, text.contains("\"clientPlayerId\":\"p-a\"") && text.contains("\"displayName\":\"Guest (Pixel)\""))
    }

    // ---- blank ids never reach the transport's validation (the guest-crash finding) ----

    @Test
    fun `a roster with a blank id is refused, so a host cannot crash a guest through the mapping validation`() {
        assertEquals(-1, guestSeatIndex(roster("", "p-me"), "p-me"))
        assertEquals(-1, guestSeatIndex(roster("host", "  ", "p-me"), "p-me"))
        val start = NearbyLobbyMessage.GameStart("uno", roster("", "p-me"))
        assertNull("nothing is resolved, and nothing throws", start.resolveForGuest("ep-host", "ep-host", "p-me"))
    }

    // ---- resolveForGuest: every guest-side rule in one place ----

    @Test
    fun `a guest resolves its seat and maps the host endpoint to the roster's seat 0`() {
        val start = NearbyLobbyMessage.GameStart("uno", roster("host", "p-a", "p-me"))

        val resolved = start.resolveForGuest("ep-host", "ep-host", "p-me")!!

        assertEquals(2, resolved.localPlayerIndex)
        assertEquals(mapOf("ep-host" to "host"), resolved.hostEndpointToPlayerId)
    }

    @Test
    fun `the host endpoint is mapped to whatever the roster says seat 0 is, not to a constant`() {
        // The engine compares a sender against context.players[0].playerId, so the guest's stamp for the host
        // must be exactly that, even for a host whose id isn't the usual "host".
        val start = NearbyLobbyMessage.GameStart("uno", roster("h-42", "p-me"))
        assertEquals(mapOf("ep-host" to "h-42"), start.resolveForGuest("ep-host", "ep-host", "p-me")!!.hostEndpointToPlayerId)
    }

    @Test
    fun `a GameStart from any endpoint but the host's resolves to nothing`() {
        val start = NearbyLobbyMessage.GameStart("uno", roster("host", "p-me"))
        assertNull(start.resolveForGuest("ep-rogue", "ep-host", "p-me"))
        assertNull(start.resolveForGuest("ep-host", null, "p-me"))
    }

    @Test
    fun `a guest is found by yourPlayerId when the host replaced its id, else by the id it proposed`() {
        val replaced = NearbyLobbyMessage.GameStart("uno", roster("host", "p-a", "minted-1"), yourPlayerId = "minted-1")
        assertEquals(2, replaced.resolveForGuest("h", "h", "host")!!.localPlayerIndex)

        val unchanged = NearbyLobbyMessage.GameStart("uno", roster("host", "p-a", "p-me"))
        assertEquals(2, unchanged.resolveForGuest("h", "h", "p-me")!!.localPlayerIndex)
        assertNull("a guest whose id is not in the roster is not seated", unchanged.resolveForGuest("h", "h", "p-stranger"))
    }

    @Test
    fun `a roster that seats the guest as the host is never believed`() {
        val forged = NearbyLobbyMessage.GameStart("uno", roster("p-me", "p-a"))
        assertNull(forged.resolveForGuest("h", "h", "p-me"))
    }

    // ---- Online: where a verified GameStart seats the guest ----

    @Test
    fun `an Online player is seated at its index, a spectator watches, an unseated player is ignored`() {
        val start = OnlineLobbyMessage.GameStart("uno", roster("p-host", "p-a", "p-me"))

        assertEquals(2, start.localSeatIndex("p-me", asSpectator = false))
        assertEquals(-1, start.localSeatIndex("p-watcher", asSpectator = true))
        assertNull(start.localSeatIndex("p-stranger", asSpectator = false))
    }

    @Test
    fun `an Online spectator is never given a seat, even one the roster claims to give it`() {
        val start = OnlineLobbyMessage.GameStart("uno", roster("p-host", "p-me"))
        assertEquals(-1, start.localSeatIndex("p-me", asSpectator = true))
    }

    @Test
    fun `an Online roster that seats the player as the host or repeats an id is ignored`() {
        assertNull(OnlineLobbyMessage.GameStart("uno", roster("p-me", "p-a")).localSeatIndex("p-me", asSpectator = false))
        assertNull(OnlineLobbyMessage.GameStart("uno", roster("p-host", "p-me", "p-me")).localSeatIndex("p-me", asSpectator = false))
    }
}
