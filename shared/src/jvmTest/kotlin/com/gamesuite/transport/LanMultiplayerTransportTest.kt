package com.gamesuite.transport

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real socket tests over actual loopback TCP/UDP -- not mocks. This is genuinely concurrent
 * networking code (an accept loop, per-connection read loops, and a UDP beacon/discovery
 * loop, all real background coroutines touching shared state), and "it compiles" tells you
 * almost nothing about whether the protocol is actually correct end-to-end -- these tests
 * spin up two (or three) real [LanMultiplayerTransport] instances in the same JVM process,
 * talking over real 127.0.0.1 sockets, exactly the way two separate desktop processes (or a
 * desktop and an Android device) would.
 *
 * [joinHost] is used directly with the port [onHosted] reports, bypassing the UDP discovery
 * beacon for these tests -- deterministic and fast, and it's the actual game-relevant
 * connection path (discovery only exists to save a human from typing an IP; the protocol
 * underneath is identical either way). [discoveryFindsTheHost] is the one test that exercises
 * the real UDP broadcast path end-to-end.
 *
 * Every wait below is a bounded [CountDownLatch] await, not a fixed sleep -- these are real
 * background coroutines whose completion time isn't fully deterministic, so polling for the
 * actual expected event (with a generous timeout) is both faster and more honest than
 * guessing a sleep duration long enough to "probably" work.
 */
class LanMultiplayerTransportTest {

    private fun await(latch: CountDownLatch, message: String, seconds: Long = 5) {
        assertTrue(latch.await(seconds, TimeUnit.SECONDS), "Timed out waiting for: $message")
    }

    private fun hostAndAwaitPort(host: LanMultiplayerTransport, playerId: String, displayName: String): Int {
        val hostedLatch = CountDownLatch(1)
        var port = -1
        host.onHosted { p -> port = p; hostedLatch.countDown() }
        host.hostGame(playerId, displayName)
        await(hostedLatch, "host to finish opening its port")
        return port
    }

    /** Joins [guest] to [host] and waits until BOTH sides agree the guest is connected --
     *  the host's own [LanMultiplayerTransport.onPlayerJoined] firing (guest registered
     *  server-side) and the guest's [LanMultiplayerTransport.onJoinedLobby] firing (guest has
     *  the roster) are two independent async events; tests that send messages immediately
     *  after only one of them would be a real race. */
    private fun joinAndAwaitBothSidesReady(
        host: LanMultiplayerTransport,
        guest: LanMultiplayerTransport,
        hostPort: Int,
        guestPlayerId: String,
        guestDisplayName: String
    ): List<LanPlayerInfo> {
        val hostSeesJoinLatch = CountDownLatch(1)
        host.onPlayerJoined { hostSeesJoinLatch.countDown() }
        val guestJoinedLatch = CountDownLatch(1)
        var rosterAtJoin: List<LanPlayerInfo> = emptyList()
        guest.onJoinedLobby { players -> rosterAtJoin = players; guestJoinedLatch.countDown() }

        guest.joinHost("127.0.0.1", hostPort, guestPlayerId, guestDisplayName)

        await(guestJoinedLatch, "guest to receive the joined roster")
        await(hostSeesJoinLatch, "host to see the guest register")
        return rosterAtJoin
    }

    @Test
    fun hostAndGuestConnectAndExchangeRoster() {
        val host = LanMultiplayerTransport()
        val guest = LanMultiplayerTransport()
        try {
            val hostPort = hostAndAwaitPort(host, "host-p", "Host")
            val rosterAtJoin = joinAndAwaitBothSidesReady(host, guest, hostPort, "guest-p", "Guest")

            // The roster the guest receives on join must show the host as already present
            // (a fresh room with just its own host, before this guest arrived).
            assertEquals(1, rosterAtJoin.size)
            assertEquals("host-p", rosterAtJoin[0].playerId)
            assertEquals("Host", rosterAtJoin[0].displayName)
        } finally {
            host.disconnect()
            guest.disconnect()
        }
    }

    @Test
    fun messagesFlowInBothDirections() {
        val host = LanMultiplayerTransport()
        val guest = LanMultiplayerTransport()
        try {
            val hostPort = hostAndAwaitPort(host, "host-p", "Host")
            joinAndAwaitBothSidesReady(host, guest, hostPort, "guest-p", "Guest")

            val hostReceivedLatch = CountDownLatch(1)
            var hostReceivedFrom: String? = null
            var hostReceivedPayload: String? = null
            host.onMessageReceived { from, payload ->
                hostReceivedFrom = from
                hostReceivedPayload = payload.decodeToString()
                hostReceivedLatch.countDown()
            }
            guest.send("guest-p", null, "hello-host".encodeToByteArray())
            await(hostReceivedLatch, "host to receive the guest's message")
            assertEquals("guest-p", hostReceivedFrom)
            assertEquals("hello-host", hostReceivedPayload)

            val guestReceivedLatch = CountDownLatch(1)
            var guestReceivedFrom: String? = null
            var guestReceivedPayload: String? = null
            guest.onMessageReceived { from, payload ->
                guestReceivedFrom = from
                guestReceivedPayload = payload.decodeToString()
                guestReceivedLatch.countDown()
            }
            host.send("host-p", null, "hello-guest".encodeToByteArray())
            await(guestReceivedLatch, "guest to receive the host's message")
            assertEquals("host-p", guestReceivedFrom)
            assertEquals("hello-guest", guestReceivedPayload)
        } finally {
            host.disconnect()
            guest.disconnect()
        }
    }

    /**
     * The real design nuance this class's own KDoc calls out: the host is both a relay AND a
     * player. Three peers (host + 2 guests); guestA broadcasts. Confirms it reaches BOTH the
     * host's own local listener (the host has no other way to receive a guest's broadcast --
     * there's no separate relay server the way OnlineTransport has one) and guestB, but never
     * echoes back to guestA itself (an honest broadcast should never hand a sender its own
     * message back).
     */
    @Test
    fun broadcastReachesHostAndOtherGuestButNotTheSender() {
        val host = LanMultiplayerTransport()
        val guestA = LanMultiplayerTransport()
        val guestB = LanMultiplayerTransport()
        try {
            val hostPort = hostAndAwaitPort(host, "host-p", "Host")
            joinAndAwaitBothSidesReady(host, guestA, hostPort, "guestA-p", "GuestA")
            joinAndAwaitBothSidesReady(host, guestB, hostPort, "guestB-p", "GuestB")

            val hostReceivedLatch = CountDownLatch(1)
            var hostReceivedFrom: String? = null
            host.onMessageReceived { from, _ -> hostReceivedFrom = from; hostReceivedLatch.countDown() }

            val guestBReceivedLatch = CountDownLatch(1)
            var guestBReceivedFrom: String? = null
            guestB.onMessageReceived { from, _ -> guestBReceivedFrom = from; guestBReceivedLatch.countDown() }

            var guestAReceivedAnything = false
            guestA.onMessageReceived { _, _ -> guestAReceivedAnything = true }

            guestA.send("guestA-p", null, "broadcast-payload".encodeToByteArray())

            await(hostReceivedLatch, "host to receive guestA's broadcast")
            await(guestBReceivedLatch, "guestB to receive guestA's broadcast")
            assertEquals("guestA-p", hostReceivedFrom)
            assertEquals("guestA-p", guestBReceivedFrom)

            // Give any wrongful echo-back a real chance to arrive before asserting it didn't.
            Thread.sleep(300)
            assertFalse(guestAReceivedAnything, "a broadcast must never be echoed back to its own sender")
        } finally {
            host.disconnect()
            guestA.disconnect()
            guestB.disconnect()
        }
    }

    @Test
    fun hostAndGuestBothSeePlayerLeftWhenGuestDisconnects() {
        val host = LanMultiplayerTransport()
        val guest = LanMultiplayerTransport()
        val bystander = LanMultiplayerTransport()
        try {
            val hostPort = hostAndAwaitPort(host, "host-p", "Host")
            joinAndAwaitBothSidesReady(host, guest, hostPort, "guest-p", "Guest")
            joinAndAwaitBothSidesReady(host, bystander, hostPort, "bystander-p", "Bystander")

            val hostSeesLeftLatch = CountDownLatch(1)
            var hostSeesLeftPlayerId: String? = null
            host.onPlayerLeft { playerId -> hostSeesLeftPlayerId = playerId; hostSeesLeftLatch.countDown() }

            val bystanderSeesLeftLatch = CountDownLatch(1)
            var bystanderSeesLeftPlayerId: String? = null
            bystander.onPlayerLeft { playerId -> bystanderSeesLeftPlayerId = playerId; bystanderSeesLeftLatch.countDown() }

            guest.disconnect()

            await(hostSeesLeftLatch, "host to notice the guest disconnected")
            await(bystanderSeesLeftLatch, "the other guest to notice the disconnect too")
            assertEquals("guest-p", hostSeesLeftPlayerId)
            assertEquals("guest-p", bystanderSeesLeftPlayerId)
        } finally {
            host.disconnect()
            bystander.disconnect()
        }
    }

    /** The one test exercising the real UDP broadcast discovery path end-to-end, rather than
     *  joining directly via a known port -- see this file's own top comment for why the other
     *  tests deliberately don't rely on this (deterministic, faster, and it's the same
     *  protocol underneath either way). */
    @Test
    fun discoveryFindsTheHost() {
        val host = LanMultiplayerTransport()
        val guest = LanMultiplayerTransport()
        try {
            val hostPort = hostAndAwaitPort(host, "host-p", "DiscoverableHost")
            guest.startDiscovery()

            val deadline = System.currentTimeMillis() + 8_000
            var found: LanHostInfo? = null
            while (System.currentTimeMillis() < deadline) {
                found = guest.discoveredHosts.value.firstOrNull { it.hostId == "host-p" }
                if (found != null) break
                Thread.sleep(100)
            }

            assertTrue(found != null, "expected the guest's discovery loop to see the host's beacon within 8s")
            assertEquals(hostPort, found?.port)
            assertEquals("DiscoverableHost", found?.displayName)
        } finally {
            guest.stopDiscovery()
            host.disconnect()
            guest.disconnect()
        }
    }
}
