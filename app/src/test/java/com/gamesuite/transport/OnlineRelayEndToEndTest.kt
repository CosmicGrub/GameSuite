package com.gamesuite.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The real [OnlineTransport] against the REAL relay (`node server/index.js`), with a hostile
 * client probing a dropped seat during its reconnect grace window. It is the end-to-end proof that
 * (a) an honest client still resumes its seat, now via the secret token, and (b) a client that only
 * knows the public playerId cannot take that seat, the host's included.
 *
 * Needs Node and the relay's dependencies (`cd server && npm install`); without them every test is
 * SKIPPED (Assume), with the reason in the report, so this never breaks a machine that only builds
 * the app. CI's Android job doesn't install Node, so it skips there; the relay is exercised by
 * server/smoke-test.js + server/identity-test.js in relay-server.yml, and this client's half of the
 * contract by [OnlineTransportIdentityTest], which always runs.
 *
 * `GAMESUITE_RELAY_JS` overrides the relay script (used to confirm this test fails on the
 * pre-fix relay).
 */
class OnlineRelayEndToEndTest {

    private var relay: Process? = null
    private lateinit var url: String
    private val transports = mutableListOf<OnlineTransport>()
    private val http = OkHttpClient()
    private val rawClients = mutableListOf<Raw>()

    /** A bare WebSocket client: the attacker, or a spectator watching the room's broadcasts. */
    private inner class Raw : WebSocketListener() {
        val frames = LinkedBlockingQueue<JsonObject>()
        @Volatile var socket: WebSocket? = null
        private val open = CountDownLatch(1)
        override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket; open.countDown() }
        override fun onMessage(webSocket: WebSocket, text: String) { frames.add(Json.parseToJsonElement(text).jsonObject) }
        fun connect(): Raw {
            http.newWebSocket(Request.Builder().url(url).build(), this)
            assertTrue("raw client never connected", open.await(10, TimeUnit.SECONDS))
            rawClients += this
            return this
        }
        fun send(json: String) { socket!!.send(json) }
        /** The next frame of [type], skipping others; fails the test if none arrives in time. */
        fun expect(type: String, timeoutSeconds: Long = 10): JsonObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (true) {
                val left = deadline - System.nanoTime()
                val frame = frames.poll(left.coerceAtLeast(1), TimeUnit.NANOSECONDS) ?: error("no '$type' frame within ${timeoutSeconds}s")
                if (frame.str("type") == type) return frame
            }
        }
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

    @Before
    fun startRelay() {
        val script = System.getenv("GAMESUITE_RELAY_JS")?.let(::File) ?: findServerDir()?.resolve("index.js")
        assumeTrue("relay script not found (looked for server/index.js above ${File(".").absoluteFile})", script != null && script.isFile)
        val serverDir = script!!.parentFile
        assumeTrue("relay dependencies missing: run `cd server && npm install`", File(serverDir, "node_modules/ws").isDirectory)
        assumeTrue("`node` is not on PATH", runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false))

        val port = ServerSocket(0).use { it.localPort }
        val process = ProcessBuilder("node", script.name)
            .directory(serverDir)
            .redirectErrorStream(true)
            .apply { environment()["PORT"] = port.toString() }
            .start()
        relay = process
        val listening = CountDownLatch(1)
        Thread {
            process.inputStream.bufferedReader().forEachLine { if (it.contains("listening")) listening.countDown() }
        }.apply { isDaemon = true }.start()
        assertTrue("relay never started listening on $port", listening.await(15, TimeUnit.SECONDS))
        url = "ws://127.0.0.1:$port"
    }

    @After
    fun stopRelay() {
        transports.forEach { runCatching { it.disconnect() } }
        rawClients.forEach { runCatching { it.socket?.cancel() } }
        relay?.destroyForcibly()
        relay?.waitFor(5, TimeUnit.SECONDS)
    }

    private fun findServerDir(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "server/index.js")
            if (candidate.isFile) return candidate.parentFile
            dir = dir.parentFile
        }
        return null
    }

    private fun newTransport() = OnlineTransport(url).also { transports += it }

    /** An abrupt client-side TCP death (a network blip): the relay sees the socket vanish and holds the seat. */
    private fun dropConnection(t: OnlineTransport) {
        val field = OnlineTransport::class.java.getDeclaredField("webSocket").apply { isAccessible = true }
        (field.get(t) as WebSocket).cancel()
    }

    private fun await(what: String, latch: CountDownLatch) = assertTrue("timed out waiting for $what", latch.await(20, TimeUnit.SECONDS))

    private class Room(val host: OnlineTransport, val guest: OnlineTransport, val code: String)

    /** Host + guest in a real room, with a spectator watching so tests can SEE a seat go into its grace window. */
    private fun openRoom(): Pair<Room, Raw> {
        val host = newTransport()
        val hosted = CountDownLatch(1)
        var code = ""
        host.onHosted { code = it; hosted.countDown() }
        host.hostRoom("p-host", "Host")
        await("hosted", hosted)

        val watcher = Raw().connect()
        watcher.send("""{"type":"join","roomCode":"$code","playerId":"p-watcher","spectator":true}""")
        watcher.expect("joined")

        val guest = newTransport()
        val joined = CountDownLatch(1)
        guest.onJoined { _, _ -> joined.countDown() }
        guest.joinRoom(code, "p-guest", "Guest")
        await("guest joined", joined)
        return Room(host, guest, code) to watcher
    }

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    @Test
    fun anHonestGuestResumesItsSeatWhileAnAttackerWhoOnlyKnowsTheIdIsRefused() {
        val (room, watcher) = openRoom()
        val received = CopyOnWriteArrayList<Pair<String, String>>()
        room.host.onMessageReceived { from, payload -> received += from to String(payload) }
        val guestReconnected = CountDownLatch(1)
        room.guest.onReconnected { guestReconnected.countDown() }
        assertEquals("p-host", room.guest.hostPlayerId)

        dropConnection(room.guest)
        watcher.expect("playerDisconnected") // the seat is now in its grace window

        // The attacker knows the guest's id (it is in every roster) but not the seat's token.
        val attacker = Raw().connect()
        attacker.send("""{"type":"join","roomCode":"${room.code}","playerId":"p-guest","displayName":"Mallory"}""")
        val refusal = attacker.expect("error")
        assertTrue(refusal.str("message")!!, refusal.str("message")!!.contains("already in this room"))
        attacker.send("""{"type":"message","toPlayerId":"p-host","payloadBase64":"${b64("forged move")}"}""")
        assertTrue("the refused attacker holds no seat", attacker.expect("error").str("message")!!.contains("Not in a room"))

        // The honest guest's own backoff-and-retry then wins the seat back with its token...
        await("guest to resume its seat", guestReconnected)
        room.guest.send("p-guest", "p-host", "real move".toByteArray())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (received.isEmpty() && System.nanoTime() < deadline) Thread.sleep(20)

        // ...and the host only ever hears from the real guest.
        assertEquals(listOf("p-guest" to "real move"), received.toList())
    }

    @Test
    fun anAttackerCannotTakeTheHostsSeatWhileItsConnectionIsDownAndTheHostResumesIt() {
        val (room, watcher) = openRoom()
        val fromHost = CopyOnWriteArrayList<Pair<String, String>>()
        room.guest.onMessageReceived { from, payload -> fromHost += from to String(payload) }
        val hostReconnected = CountDownLatch(1)
        room.host.onReconnected { hostReconnected.countDown() }

        dropConnection(room.host)
        watcher.expect("playerDisconnected")

        // The host id is public: the guest, and the spectator, both learned it from the relay.
        assertEquals("p-host", room.guest.hostPlayerId)
        val attacker = Raw().connect()
        attacker.send("""{"type":"join","roomCode":"${room.code}","playerId":"p-host","displayName":"Mallory"}""")
        assertTrue(attacker.expect("error").str("message")!!.contains("already in this room"))
        // Guessing a token doesn't help either.
        attacker.send("""{"type":"join","roomCode":"${room.code}","playerId":"p-host","reconnectToken":"not-the-token"}""")
        assertTrue(attacker.expect("error").str("message")!!.contains("already in this room"))

        await("host to resume its seat", hostReconnected)
        room.host.send("p-host", "p-guest", "StateSync".toByteArray())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (fromHost.isEmpty() && System.nanoTime() < deadline) Thread.sleep(20)

        assertEquals("the guest hears the host, stamped as the host, and nobody else", listOf("p-host" to "StateSync"), fromHost.toList())
        assertNotNull(room.host.hostPlayerId)
    }

    @Test
    fun aVacatedSeatCannotBeClaimedByAnyoneWhoKnowsItsId() {
        val (room, watcher) = openRoom()
        // The guest leaves for good (an explicit leave skips the grace window entirely).
        room.guest.disconnect()
        watcher.expect("playerLeft")

        val attacker = Raw().connect()
        attacker.send("""{"type":"join","roomCode":"${room.code}","playerId":"p-guest","displayName":"Mallory"}""")
        val refusal = attacker.expect("error").str("message")!!
        assertTrue(refusal, refusal.contains("can no longer be used"))

        // ...even as a read-only spectator, which would be sent that seat's un-redacted hand.
        val spectatorAttacker = Raw().connect()
        spectatorAttacker.send("""{"type":"join","roomCode":"${room.code}","playerId":"p-guest","spectator":true}""")
        assertTrue(spectatorAttacker.expect("error").str("message")!!.contains("can no longer be used"))

        // A brand-new player can still join the room.
        val newcomer = newTransport()
        val joined = CountDownLatch(1)
        newcomer.onJoined { _, _ -> joined.countDown() }
        newcomer.joinRoom(room.code, "p-new-guest", "New")
        await("newcomer to join", joined)
    }
}
