package com.gamesuite.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [NearbyGameRouter] is the identity-sensitive half of NearbyConnectionsTransport with the Android types
 * taken out: what an inbound payload is stamped as, and where a send goes. The transport delegates to it 1:1.
 */
class NearbyGameRouterTest {

    private val guests = mapOf("ep1" to "p-a", "ep2" to "p-b")

    @Test
    fun `before a mapping is installed nothing is stamped and nothing is sent`() {
        val r = NearbyGameRouter()
        assertNull(r.playerIdFor("ep1"))
        assertEquals(emptyList<String>(), r.endpointsFor(null))
        assertEquals(emptyList<String>(), r.endpointsFor("p-a"))
    }

    @Test
    fun `an inbound payload is stamped as the seat of the endpoint it arrived on`() {
        val r = NearbyGameRouter().apply { install(guests, isHost = true) }
        assertEquals("p-a", r.playerIdFor("ep1"))
        assertEquals("p-b", r.playerIdFor("ep2"))
        assertNull("a late or rogue endpoint has no seat and is dropped", r.playerIdFor("ep-rogue"))
    }

    @Test
    fun `a broadcast goes to every seated endpoint, a direct send to one, an unknown recipient to none`() {
        val r = NearbyGameRouter().apply { install(guests, isHost = true) }
        assertEquals(listOf("ep1", "ep2"), r.endpointsFor(null))
        assertEquals(listOf("ep2"), r.endpointsFor("p-b"))
        assertEquals("a stale or unknown seat is dropped, never guessed at", emptyList<String>(), r.endpointsFor("p-gone"))
        assertEquals("the host is not an endpoint on the host's own device", emptyList<String>(), r.endpointsFor("host"))
    }

    @Test
    fun `the host side refuses a mapping that stamps a guest endpoint as the host`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            NearbyGameRouter().install(mapOf("ep-attacker" to "host", "ep-honest" to "p-a"), isHost = true)
        }
        assertEquals(true, e.message!!.contains("reserved"))
    }

    @Test
    fun `the guest side maps its one endpoint, the host, to the host id`() {
        val r = NearbyGameRouter().apply { install(mapOf("ep-host" to "host"), isHost = false) }
        assertEquals("host", r.playerIdFor("ep-host"))
        assertEquals(listOf("ep-host"), r.endpointsFor("host"))
        assertEquals("a guest's broadcast goes to the host only", listOf("ep-host"), r.endpointsFor(null))
    }

    @Test
    fun `either side refuses two endpoints sharing a seat`() {
        for (isHost in listOf(true, false)) {
            assertThrows(IllegalArgumentException::class.java) {
                NearbyGameRouter().install(mapOf("ep1" to "p-a", "ep2" to "p-a"), isHost = isHost)
            }
        }
    }

    @Test
    fun `a refused install leaves the previous mapping in force`() {
        val r = NearbyGameRouter().apply { install(guests, isHost = true) }

        runCatching { r.install(mapOf("ep1" to "host"), isHost = true) }

        assertEquals("p-a", r.playerIdFor("ep1"))
        assertEquals(listOf("ep1", "ep2"), r.endpointsFor(null))
    }

    @Test
    fun `installing replaces the mapping, and reset clears it`() {
        val r = NearbyGameRouter().apply { install(guests, isHost = true) }
        r.install(mapOf("ep9" to "p-z"), isHost = true)
        assertNull(r.playerIdFor("ep1"))
        assertEquals("p-z", r.playerIdFor("ep9"))

        r.reset()

        assertNull(r.playerIdFor("ep9"))
        assertEquals(emptyList<String>(), r.endpointsFor(null))
    }
}
