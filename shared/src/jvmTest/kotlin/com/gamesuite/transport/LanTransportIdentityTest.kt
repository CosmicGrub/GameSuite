package com.gamesuite.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LanMultiplayerTransport] is the one transport where the host relays for every guest, and a
 * guest is a plain TCP client that writes its own JSON frames. These tests play a hostile guest
 * with a raw socket, which can also send what the API can't produce (a message with no
 * `fromPlayerId`, a `join` for the host's id, many joins at once), and check that `fromPlayerId`,
 * which games trust as the real sender, can't be forged or hijacked. (Forging `fromPlayerId`
 * through a real guest's own `send(...)` is covered at the game level in the TicTacToe and Chess
 * LAN tests.)
 */
class LanTransportIdentityTest {

    /** A guest that speaks the wire protocol by hand: newline-delimited JSON over a plain socket. */
    private class RawGuest(port: Int) : AutoCloseable {
        private val socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        private val out = socket.getOutputStream()

        fun send(json: String) { out.write((json + "\n").toByteArray()); out.flush() }

        /** The next frame, or null once the host has CLOSED the connection. Nothing arriving in time is a
         *  test failure, not a close: "the host closed it" must not be satisfiable by a host that did nothing. */
        fun read(): JsonObject? {
            val line = try {
                reader.readLine()
            } catch (e: java.net.SocketTimeoutException) {
                throw AssertionError("the host neither replied nor closed the connection within the timeout")
            } catch (e: java.io.IOException) {
                null // reset: the host closed it
            } ?: return null
            return Json.parseToJsonElement(line).jsonObject
        }

        /** Reads frames until one of [type] arrives (skipping roster chatter like playerJoined). */
        fun readUntil(type: String): JsonObject? {
            while (true) {
                val frame = read() ?: return null
                if (frame["type"]?.jsonPrimitive?.content == type) return frame
            }
        }

        fun join(playerId: String) = send("""{"type":"join","playerId":"$playerId","displayName":"$playerId"}""")

        override fun close() { runCatching { socket.close() } }
    }

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun startHost(hostId: String): Pair<LanMultiplayerTransport, Int> {
        val host = LanMultiplayerTransport()
        val hosted = CountDownLatch(1)
        var port = -1
        host.onHosted { port = it; hosted.countDown() }
        host.hostGame(hostId, "Host")
        assertTrue(hosted.await(5, TimeUnit.SECONDS), "host never opened its port")
        return host to port
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

    /** The host lists a guest a moment AFTER it writes that guest's `joined`, so poll rather than read once. */
    private fun awaitPlayers(host: LanMultiplayerTransport, expected: List<String>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (host.connectedPlayers.value.map { it.playerId } != expected && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(expected, host.connectedPlayers.value.map { it.playerId })
    }

    // ---- a guest can't forge who a message is from ----

    @Test
    fun aGuestCannotForgeTheSenderOfAMessageTheHostReceives() {
        val (host, port) = startHost("host-p")
        val received = CopyOnWriteArrayList<Pair<String, String>>()
        val got = CountDownLatch(1)
        host.onMessageReceived { from, payload -> received += from to String(payload); got.countDown() }
        try {
            RawGuest(port).use { guest ->
                guest.join("guest-a")
                assertEquals("joined", guest.read()?.str("type"))

                // Claims to be the host.
                guest.send("""{"type":"message","fromPlayerId":"host-p","payloadBase64":"${b64("play card 7")}"}""")

                assertTrue(got.await(5, TimeUnit.SECONDS), "host never got the message")
                assertEquals(listOf("guest-a" to "play card 7"), received.toList(), "stamped as the connection's seat, not the claim")
            }
        } finally {
            host.disconnect()
        }
    }

    @Test
    fun theHostRestampsWhatItRelaysSoOtherGuestsAreNotFooledEither() {
        val (host, port) = startHost("host-p")
        try {
            RawGuest(port).use { attacker ->
                RawGuest(port).use { victim ->
                    attacker.join("guest-a"); assertEquals("joined", attacker.read()?.str("type"))
                    victim.join("guest-b"); assertEquals("joined", victim.read()?.str("type"))

                    // Broadcast forged as the host: every other guest is told who sent it.
                    attacker.send("""{"type":"message","fromPlayerId":"host-p","payloadBase64":"${b64("StateSync")}"}""")

                    val relayed = victim.readUntil("message")
                    assertEquals("guest-a", relayed?.str("fromPlayerId"), "a forged sender must never reach the other guests")
                }
            }
        } finally {
            host.disconnect()
        }
    }

    @Test
    fun aFrameWithNoFromPlayerIdIsStillStampedByTheHost() {
        val (host, port) = startHost("host-p")
        val from = CopyOnWriteArrayList<String>()
        val got = CountDownLatch(1)
        host.onMessageReceived { f, _ -> from += f; got.countDown() }
        try {
            RawGuest(port).use { guest ->
                guest.join("guest-a"); assertEquals("joined", guest.read()?.str("type"))
                guest.send("""{"type":"message","payloadBase64":"${b64("x")}"}""")
                assertTrue(got.await(5, TimeUnit.SECONDS))
                assertEquals(listOf("guest-a"), from.toList())
            }
        } finally {
            host.disconnect()
        }
    }

    // ---- a guest can't take the host's seat, or another guest's ----

    @Test
    fun aGuestJoiningWithTheHostsOwnIdIsRefused() {
        val (host, port) = startHost("host-p")
        val joined = CopyOnWriteArrayList<String>()
        host.onPlayerJoined { joined += it }
        try {
            RawGuest(port).use { attacker ->
                attacker.join("host-p")

                assertNull(attacker.read(), "the host closes the connection instead of admitting a second 'host-p'")
            }
            assertEquals(emptyList(), joined.toList(), "the host must not report anyone joining")
            assertEquals(listOf("host-p"), host.connectedPlayers.value.map { it.playerId })
        } finally {
            host.disconnect()
        }
    }

    @Test
    fun aSecondGuestWithAnAlreadyHeldIdCannotReplaceTheRealGuestsConnection() {
        val (host, port) = startHost("host-p")
        try {
            RawGuest(port).use { real ->
                real.join("guest-a"); assertEquals("joined", real.read()?.str("type"))

                RawGuest(port).use { impostor ->
                    impostor.join("guest-a")
                    assertNull(impostor.read(), "the impostor is disconnected")
                }

                // The real guest still owns its seat: a message the host addresses to it reaches it.
                host.send("host-p", "guest-a", "for the real one".toByteArray())
                val delivered = real.readUntil("message")
                assertEquals(b64("for the real one"), delivered?.str("payloadBase64"))
                awaitPlayers(host, listOf("host-p", "guest-a"))
            }
        } finally {
            host.disconnect()
        }
    }

    @Test
    fun afterARefusedImpostorTheRealGuestKeepsSpeakingAsItself() {
        val (host, port) = startHost("host-p")
        val from = CopyOnWriteArrayList<String>()
        val got = CountDownLatch(1)
        host.onMessageReceived { f, _ -> from += f; got.countDown() }
        try {
            RawGuest(port).use { real ->
                real.join("guest-a"); assertEquals("joined", real.read()?.str("type"))
                RawGuest(port).use { impostor -> impostor.join("guest-a"); assertNull(impostor.read()) }

                real.send("""{"type":"message","payloadBase64":"${b64("still me")}"}""")

                assertTrue(got.await(5, TimeUnit.SECONDS))
                assertEquals(listOf("guest-a"), from.toList())
            }
        } finally {
            host.disconnect()
        }
    }

    @Test
    fun manyGuestsRacingForOneIdAdmitExactlyOne() {
        // The host checks "is this id taken?" and registers the connection as ONE step under a lock. Sixteen
        // sockets, all connected first and then released at once with the same id, must yield exactly one
        // admission (a check-then-put without the lock admits several), round after round.
        val (host, port) = startHost("host-p")
        try {
            repeat(15) { round ->
                val contested = "contested-$round"
                val racers = 16
                val guests = (0 until racers).map { RawGuest(port) }
                val go = CountDownLatch(1)
                val admitted = java.util.concurrent.atomic.AtomicInteger()
                val answered = java.util.concurrent.atomic.AtomicInteger()
                val threads = guests.map { g ->
                    Thread {
                        go.await()
                        g.join(contested)
                        if (g.read()?.str("type") == "joined") admitted.incrementAndGet()
                        answered.incrementAndGet()
                    }.apply { isDaemon = true; start() }
                }
                go.countDown()
                threads.forEach { it.join(20_000) }
                try {
                    assertEquals(racers, answered.get(), "round $round: every racer got an answer (a reply or a close)")
                    assertEquals(1, admitted.get(), "round $round: exactly one racer is admitted for one id")
                } finally {
                    guests.forEach { it.close() }
                }
            }
        } finally {
            host.disconnect()
        }
    }

    // ---- honest play is unaffected ----

    @Test
    fun honestGuestsStillJoinAndMessageEachOtherThroughTheHost() {
        val (host, port) = startHost("host-p")
        try {
            RawGuest(port).use { a ->
                RawGuest(port).use { b ->
                    a.join("guest-a"); assertEquals("joined", a.read()?.str("type"))
                    b.join("guest-b"); assertEquals("joined", b.read()?.str("type"))

                    a.send("""{"type":"message","toPlayerId":"guest-b","fromPlayerId":"guest-a","payloadBase64":"${b64("hi b")}"}""")

                    val relayed = b.readUntil("message")
                    assertEquals("guest-a", relayed?.str("fromPlayerId"))
                    assertEquals(b64("hi b"), relayed?.str("payloadBase64"))
                    awaitPlayers(host, listOf("host-p", "guest-a", "guest-b"))
                }
            }
        } finally {
            host.disconnect()
        }
    }
}
