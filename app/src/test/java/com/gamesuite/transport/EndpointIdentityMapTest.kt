package com.gamesuite.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EndpointIdentityMap] is what [NearbyConnectionsTransport] stamps `fromPlayerId` from. These pin
 * down its rules: one endpoint is one seat, one seat is one endpoint, and a host-side mapping can
 * never hand a guest endpoint the host's id.
 */
class EndpointIdentityMapTest {

    @Test
    fun `an endpoint is stamped with the seat it was mapped to and unknown endpoints get no stamp`() {
        val map = EndpointIdentityMap(mapOf("ep1" to "p-a", "ep2" to "p-b"))

        assertEquals("p-a", map.playerIdFor("ep1"))
        assertEquals("p-b", map.playerIdFor("ep2"))
        assertNull("an unmapped (late or rogue) endpoint must be dropped, not guessed at", map.playerIdFor("ep-unknown"))
    }

    @Test
    fun `the reverse lookup finds the one endpoint for a seat`() {
        val map = EndpointIdentityMap(mapOf("ep1" to "p-a", "ep2" to "p-b"))

        assertEquals("ep2", map.endpointFor("p-b"))
        assertNull(map.endpointFor("host"))
        assertEquals(listOf("ep1", "ep2"), map.endpointIds)
    }

    @Test
    fun `two endpoints sharing one seat id is rejected instead of silently letting the later one win`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            EndpointIdentityMap(mapOf("ep-honest" to "p-a", "ep-attacker" to "p-a"))
        }
        assertTrue(e.message!!, e.message!!.contains("same playerId"))
    }

    @Test
    fun `a host-side mapping that stamps a guest endpoint as the host is rejected`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            EndpointIdentityMap(mapOf("ep-attacker" to NEARBY_HOST_PLAYER_ID), reservedPlayerIds = setOf(NEARBY_HOST_PLAYER_ID))
        }
        assertTrue(e.message!!, e.message!!.contains("reserved"))
    }

    @Test
    fun `the guest side legitimately maps the host endpoint to the host id`() {
        // A guest's only endpoint IS the host, and the game engine expects that stamp on StateSync.
        val map = EndpointIdentityMap(mapOf("ep-host" to NEARBY_HOST_PLAYER_ID))
        assertEquals("host", map.playerIdFor("ep-host"))
    }

    @Test
    fun `blank seat ids are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { EndpointIdentityMap(mapOf("ep1" to "")) }
        assertThrows(IllegalArgumentException::class.java) { EndpointIdentityMap(mapOf("ep1" to "  ")) }
    }

    @Test
    fun `the map is a snapshot - changing the source map afterwards changes nothing`() {
        val source = mutableMapOf("ep1" to "p-a")
        val map = EndpointIdentityMap(source)

        source["ep1"] = "host"
        source["ep2"] = "p-b"

        assertEquals("p-a", map.playerIdFor("ep1"))
        assertNull(map.playerIdFor("ep2"))
    }

    @Test
    fun `the empty map stamps nothing`() {
        assertNull(EndpointIdentityMap.EMPTY.playerIdFor("ep1"))
        assertEquals(emptyList<String>(), EndpointIdentityMap.EMPTY.endpointIds)
    }
}
