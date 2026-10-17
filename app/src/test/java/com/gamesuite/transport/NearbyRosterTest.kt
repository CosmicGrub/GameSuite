package com.gamesuite.transport

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The Nearby host lobby used to take `clientPlayerId` from a guest's JoinRequest at face value, so
 * a guest sending "host" (or another seat's id) became a duplicate/impersonating seat. These tests
 * pin down that the HOST now decides every seat id, while an honest guest still gets the id it
 * proposed.
 */
class NearbyRosterTest {

    private fun join(id: String, name: String = "Guest") = NearbyLobbyMessage.JoinRequest(id, name)

    /** A deterministic id source: hands out "minted-1", "minted-2", ... */
    private fun counter(): () -> String {
        var n = 0
        return { "minted-${++n}" }
    }

    private fun roster(vararg entries: Pair<String, NearbyLobbyMessage.JoinRequest>): NearbyRoster =
        entries.fold(NearbyRoster(counter())) { r, (endpoint, request) -> r.withJoinRequest(endpoint, request) }

    // ---- honest guests: multiplayer keeps working ----

    @Test
    fun `honest guests keep the ids they proposed and are listed after the host in join order`() {
        val r = roster("ep1" to join("p-aaa", "A"), "ep2" to join("p-bbb", "B"))

        assertEquals(listOf("host", "p-aaa", "p-bbb"), r.players.map { it.playerId })
        assertEquals(listOf("Host", "A", "B"), r.players.map { it.displayName })
        assertEquals(mapOf("ep1" to "p-aaa", "ep2" to "p-bbb"), r.endpointToPlayerId)
    }

    @Test
    fun `an id shaped like the app's own p-uuid ids is accepted as is`() {
        val id = "p-" + java.util.UUID.randomUUID()
        val r = roster("ep1" to join(id))
        assertEquals(id, r.guests.single().playerId)
    }

    @Test
    fun `the default id source mints app-shaped ids that are unique`() {
        val r = NearbyRoster()
            .withJoinRequest("ep1", join("host"))
            .withJoinRequest("ep2", join("host"))
        val ids = r.guests.map { it.playerId }
        assertEquals(2, ids.toSet().size)
        ids.forEach { assertTrue(it, it.startsWith("p-") && it != "host") }
    }

    // ---- the impersonation hole ----

    @Test
    fun `a guest proposing the reserved host id does not get it`() {
        val r = roster("ep1" to join("host", "Mallory"))

        val seat = r.guests.single()
        assertNotEquals("host", seat.playerId)
        assertEquals("minted-1", seat.playerId)
        assertEquals("host", r.players.first().playerId)
        assertEquals("only seat 0 may be the host", 1, r.players.count { it.playerId == "host" })
    }

    @Test
    fun `the reserved host id is refused in any casing`() {
        for (proposal in listOf("HOST", "Host", "hOsT")) {
            val r = roster("ep1" to join(proposal))
            assertNotEquals(proposal, r.guests.single().playerId)
            assertFalse(r.guests.single().playerId.equals("host", ignoreCase = true))
        }
    }

    @Test
    fun `a guest proposing another guest's id gets a fresh one and the victim keeps theirs`() {
        val r = roster("ep-victim" to join("p-victim", "Victim"), "ep-attacker" to join("p-victim", "Mallory"))

        assertEquals("p-victim", r.endpointToPlayerId.getValue("ep-victim"))
        assertEquals("minted-1", r.endpointToPlayerId.getValue("ep-attacker"))
        assertEquals(2, r.endpointToPlayerId.values.toSet().size)
    }

    @Test
    fun `two guests proposing the same id never share it - the earlier keeps it, the later is re-minted`() {
        // The re-minted guest is told its real id via GameStart.yourPlayerId; what matters here is
        // that no two endpoints are ever stamped with one id.
        val r = roster("ep-first" to join("p-x"), "ep-second" to join("p-x"))

        assertEquals(listOf("host", "p-x", "minted-1"), r.players.map { it.playerId })
        assertEquals(mapOf("ep-first" to "p-x", "ep-second" to "minted-1"), r.endpointToPlayerId)
    }

    @Test
    fun `re-sending a JoinRequest from the same endpoint cannot change its seat id`() {
        val first = roster("ep1" to join("p-mine", "Old name"))
        val again = first.withJoinRequest("ep1", join("host", "New name"))

        assertEquals("p-mine", again.guests.single().playerId)
        assertEquals("New name", again.guests.single().displayName)
        assertEquals(1, again.guests.size)
    }

    @Test
    fun `malformed proposals are replaced, never trusted`() {
        val bad = listOf(
            "", " ", "  host", "p x", "p-x\nhost", "p-\u0000", "p-é", "a".repeat(65), "p/../host", "{\"a\":1}", "😀",
        )
        for (proposal in bad) {
            val r = roster("ep1" to join(proposal))
            assertEquals("proposal <$proposal> must be replaced", "minted-1", r.guests.single().playerId)
        }
    }

    @Test
    fun `a proposal at the length limit is still accepted`() {
        val id = "a".repeat(64)
        assertEquals(id, roster("ep1" to join(id)).guests.single().playerId)
    }

    @Test
    fun `minting skips candidates that are reserved or already taken`() {
        val candidates = listOf("host", "p-taken", "p-taken", "fresh").iterator()
        val r = NearbyRoster { candidates.next() }
            .withJoinRequest("ep1", join("p-taken"))
            .withJoinRequest("ep2", join("host")) // proposal refused -> mint: "host"(reserved), "p-taken"(taken), "p-taken", "fresh"

        assertEquals(listOf("p-taken", "fresh"), r.guests.map { it.playerId })
    }

    @Test
    fun `a broken id source fails closed instead of seating a guest under a bad id`() {
        val r = NearbyRoster { "host" }
        assertThrows(IllegalStateException::class.java) { r.withJoinRequest("ep1", join("host")) }
    }

    // ---- telling each guest its seat ----

    @Test
    fun `a guest whose id was kept is flagged as not replaced, and one whose id was replaced is flagged`() {
        val r = roster("ep1" to join("p-a"), "ep2" to join("host"), "ep3" to join("p-a"))

        assertEquals(listOf(false, true, true), r.guests.map { it.idWasReplaced })
    }

    @Test
    fun `an honest guest gets a GameStart without yourPlayerId, exactly what older guests can decode`() {
        val r = roster("ep1" to join("p-a")).close()

        val start = r.gameStartFor(r.guests.single(), "uno")

        assertNull(start.yourPlayerId)
        val wire = Json.encodeToString<NearbyLobbyMessage>(start)
        assertFalse("older guests reject unknown JSON keys: <$wire>", wire.contains("yourPlayerId"))
    }

    @Test
    fun `a guest whose id was replaced is told its real one, and every guest is sent the same roster`() {
        val r = roster("ep-honest" to join("p-a"), "ep-mallory" to join("host")).close()

        val forHonest = r.gameStartFor(r.guests[0], "uno")
        val forMallory = r.gameStartFor(r.guests[1], "uno")

        assertEquals("minted-1", forMallory.yourPlayerId)
        assertEquals(forHonest.players, forMallory.players)
        assertEquals(listOf("host", "p-a", "minted-1"), forMallory.players.map { it.playerId })
        assertEquals("uno", forMallory.gameRoute)
    }

    @Test
    fun `a replaced guest that finds itself through yourPlayerId gets a real seat, never seat 0`() {
        val r = roster("ep-mallory" to join("host")).close()
        val start = r.gameStartFor(r.guests.single(), "uno")

        assertEquals(1, guestSeatIndex(start.players, start.yourPlayerId ?: "host"))
        assertEquals("guessing the proposed id would have meant seat 0, the host", -1, guestSeatIndex(start.players, "host"))
    }

    // ---- roster lifecycle ----

    @Test
    fun `guests that disconnect drop out of the roster`() {
        val r = roster("ep1" to join("p-a"), "ep2" to join("p-b"), "ep3" to join("p-c"))
            .retainingEndpoints(listOf("ep1", "ep3"))

        assertEquals(listOf("p-a", "p-c"), r.guests.map { it.playerId })
        assertEquals(mapOf("ep1" to "p-a", "ep3" to "p-c"), r.endpointToPlayerId)
    }

    @Test
    fun `retaining every endpoint returns the same roster`() {
        val r = roster("ep1" to join("p-a"))
        assertSame(r, r.retainingEndpoints(listOf("ep1", "ep9")))
    }

    @Test
    fun `a closed roster ignores late JoinRequests and disconnects`() {
        val started = roster("ep1" to join("p-a")).close()

        assertTrue(started.isClosed)
        val afterLateJoin = started.withJoinRequest("ep-late", join("host", "Late"))
        assertSame(started, afterLateJoin)
        assertEquals(listOf("ep1"), afterLateJoin.guests.map { it.endpointId })
        assertSame(started, started.retainingEndpoints(emptyList()))
        assertEquals("closing twice is a no-op", started, started.close())
    }

    @Test
    fun `closing keeps what was announced`() {
        val open = roster("ep1" to join("p-a"), "ep2" to join("host"))
        val closed = open.close()
        assertEquals(open.players, closed.players)
        assertEquals(open.endpointToPlayerId, closed.endpointToPlayerId)
    }

    // ---- the invariant the transport relies on, fuzzed ----

    @Test
    fun `whatever guests propose, seat ids are distinct, well-formed, never the host id, and always a valid endpoint mapping`() {
        val rng = Random(20260928)
        val hostile = listOf("host", "HOST", "p-dup", "p-dup", "", "x".repeat(200), "p-ok-1", "p-ok-2", "Host ", "p-dup ")
        repeat(300) { round ->
            var r = NearbyRoster()
            val endpoints = (0 until 1 + rng.nextInt(8)).map { "ep$it" }
            // Endpoints join (and some re-send) in a random order with hostile or random proposals.
            repeat(endpoints.size * 3) {
                val ep = endpoints[rng.nextInt(endpoints.size)]
                val proposal = if (rng.nextBoolean()) hostile[rng.nextInt(hostile.size)] else "p-" + rng.nextInt(6)
                r = r.withJoinRequest(ep, join(proposal, "n$round"))
            }
            val seatIds = r.guests.map { it.playerId }
            assertEquals("round $round: seat ids must be distinct: $seatIds", seatIds.size, seatIds.toSet().size)
            seatIds.forEach {
                assertTrue("round $round: <$it> is well-formed", Regex("[A-Za-z0-9_-]{1,64}").matches(it))
                assertFalse("round $round: <$it> must not be the host id", it.equals("host", ignoreCase = true))
            }
            assertEquals("one seat per endpoint", r.guests.map { it.endpointId }.toSet().size, r.guests.size)
            // The transport's own validation must accept exactly what the roster produces.
            EndpointIdentityMap(r.endpointToPlayerId, reservedPlayerIds = setOf(NEARBY_HOST_PLAYER_ID))
            assertEquals("host first", "host", r.players.first().playerId)
            assertEquals("no seat but 0 may be the host", 1, r.players.count { it.playerId == "host" })
        }
    }

    // ---- display names: text the guest wrote, sent to every guest ----

    @Test
    fun `a display name is capped, stripped of control characters and never blank`() {
        val r = roster(
            "ep1" to join("p-a", "x".repeat(10_000)),
            "ep2" to join("p-b", "  Al\u0000ic\ne\t  "),
            "ep3" to join("p-c", "   "),
            "ep4" to join("p-d", ""),
        )

        assertEquals("x".repeat(40), r.guests[0].displayName)
        assertEquals("Alice", r.guests[1].displayName)
        assertEquals("Guest", r.guests[2].displayName)
        assertEquals("Guest", r.guests[3].displayName)
    }

    @Test
    fun `a re-sent JoinRequest's display name is cleaned the same way`() {
        val r = roster("ep1" to join("p-a", "First")).withJoinRequest("ep1", join("p-a", "y".repeat(500)))
        assertEquals("y".repeat(40), r.guests.single().displayName)
    }

    @Test
    fun `an honest display name is untouched`() {
        assertEquals("Guest (Pixel 8)", roster("ep1" to join("p-a", "Guest (Pixel 8)")).guests.single().displayName)
    }

    // ---- launch(): what Start Game decides ----

    @Test
    fun `launch freezes the roster and derives the player list, the mapping and each guest's message together`() {
        val plan = roster("ep-a" to join("p-a", "A"), "ep-m" to join("host", "Mallory")).launch("uno")

        assertTrue(plan.roster.isClosed)
        assertEquals(listOf("host", "p-a", "minted-1"), plan.players.map { it.playerId })
        assertEquals(mapOf("ep-a" to "p-a", "ep-m" to "minted-1"), plan.endpointToPlayerId)
        assertEquals(listOf("ep-a", "ep-m"), plan.startMessages.map { it.first })
        assertTrue("every guest is sent the same roster", plan.startMessages.all { it.second.players == plan.players })
        assertTrue("and the same route", plan.startMessages.all { it.second.gameRoute == "uno" })
        assertNull("the honest guest's message is the old wire shape", plan.startMessages[0].second.yourPlayerId)
        assertEquals("the replaced guest is told its real id", "minted-1", plan.startMessages[1].second.yourPlayerId)
    }

    @Test
    fun `what launch installs is exactly what it announces, and a late joiner cannot change either`() {
        val open = roster("ep-a" to join("p-a"))
        val plan = open.launch("uno")

        val afterLateJoin = plan.roster.withJoinRequest("ep-late", join("host", "Late"))

        assertSame(plan.roster, afterLateJoin)
        assertEquals(plan.endpointToPlayerId.values.toSet(), plan.players.drop(1).map { it.playerId }.toSet())
        assertEquals("a second launch of the frozen roster is identical", plan.players, plan.roster.launch("uno").players)
    }

    @Test
    fun `launch's mapping is always accepted by the host-side router`() {
        val plan = roster("ep-a" to join("host"), "ep-b" to join("HOST"), "ep-c" to join("p-c")).launch("uno")
        NearbyGameRouter().install(plan.endpointToPlayerId, isHost = true) // must not throw
    }
}
