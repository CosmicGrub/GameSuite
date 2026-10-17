package com.gamesuite.transport

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * [OnlineTransport]'s half of the relay's seat-ownership contract (server/index.js): it must keep
 * the secret `reconnectToken` the relay issues for its seat and present it when it re-takes that
 * seat after a dropped connection, learn who the room's host is, and behave sensibly against a
 * relay that does neither (older builds). The relay is scripted with MockWebServer, so the exact
 * JSON the client sends is what's asserted.
 *
 * The relay side of the same contract is server/identity-test.js.
 */
class OnlineTransportIdentityTest {

    private lateinit var server: MockWebServer
    private val transports = mutableListOf<OnlineTransport>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        transports.forEach { runCatching { it.disconnect() } }
        runCatching { server.shutdown() }
    }

    private fun newTransport(): OnlineTransport =
        OnlineTransport("ws://${server.hostName}:${server.port}/").also { transports += it }

    /** One scripted relay connection: records every client frame and lets the test answer / drop. */
    private inner class Conn(val index: Int, private val script: (Conn, JsonObject) -> Unit) : WebSocketListener() {
        val frames = LinkedBlockingQueue<JsonObject>()
        @Volatile var socket: WebSocket? = null
        override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
        /** Answers the client's close so the handshake completes; otherwise MockWebServer.shutdown() waits out its 5s timeout per test. */
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = Json.parseToJsonElement(text).jsonObject
            frames.add(frame)
            script(this, frame)
        }
        fun reply(json: String) { socket!!.send(json) }
        fun nextFrame(): JsonObject = frames.poll(10, TimeUnit.SECONDS) ?: error("connection #$index received no frame")
    }

    /**
     * An abrupt client-side TCP death, like a network blip: OkHttp reports onFailure (not a clean
     * close), which is what makes [OnlineTransport] try to win its seat back. Reached by reflection
     * because the socket is private; (cancel() on the MockWebServer side is not an option, that
     * socket has no client call to cancel).
     */
    private fun dropConnection(t: OnlineTransport) {
        val field = OnlineTransport::class.java.getDeclaredField("webSocket").apply { isAccessible = true }
        (field.get(t) as WebSocket).cancel()
    }

    private fun expectConnection(index: Int, script: (Conn, JsonObject) -> Unit): Conn {
        val conn = Conn(index, script)
        server.enqueue(MockResponse().withWebSocketUpgrade(conn))
        return conn
    }

    private fun JsonObject.str(key: String): String? = this[key]?.takeIf { it.toString() != "null" }?.jsonPrimitive?.content

    /** Polls until [condition] is false: the state flags settle a moment AFTER the listener the test waited on fires. */
    private fun awaitFalse(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse("timed out waiting for $what", condition())
    }

    private fun await(what: String, latch: CountDownLatch) =
        assertTrue("timed out waiting for $what", latch.await(15, TimeUnit.SECONDS))

    // ---- host ----

    @Test
    fun `a host keeps the reconnect token from hosted and presents it when re-taking its seat`() {
        val first = expectConnection(0) { c, f ->
            if (f.str("type") == "host") c.reply("""{"type":"hosted","roomCode":"ABCD","playerId":"p-host","reconnectToken":"tok-host"}""")
        }
        val second = expectConnection(1) { c, f ->
            if (f.str("type") == "join") c.reply("""{"type":"reconnected","roomCode":"ABCD","players":[]}""")
        }
        val t = newTransport()
        val hosted = CountDownLatch(1)
        val reconnected = CountDownLatch(1)
        t.onHosted { hosted.countDown() }
        t.onReconnected { reconnected.countDown() }

        t.hostRoom("p-host", "Host")
        val hostFrame = first.nextFrame()
        await("hosted", hosted)
        assertEquals("host", hostFrame.str("type"))
        assertNull("a fresh host frame has no token: there is none yet", hostFrame.str("reconnectToken"))
        assertEquals("the host is the room's host", "p-host", t.hostPlayerId)

        dropConnection(t)
        val rejoin = second.nextFrame()
        await("reconnected", reconnected)

        assertEquals("join", rejoin.str("type"))
        assertEquals("ABCD", rejoin.str("roomCode"))
        assertEquals("p-host", rejoin.str("playerId"))
        assertEquals("the relay's per-seat secret is what re-takes the seat", "tok-host", rejoin.str("reconnectToken"))
    }

    @Test
    fun `a send made from inside onReconnected goes out on the resumed connection`() {
        // UnoGame answers onReconnected by SENDING (a guest re-requests state, the host re-broadcasts
        // it). The resumed socket has to be the live one by then, or that resync is silently lost.
        val first = expectConnection(0) { c, f ->
            if (f.str("type") == "host") c.reply("""{"type":"hosted","roomCode":"ABCD","playerId":"p-host","reconnectToken":"tok-host"}""")
        }
        val second = expectConnection(1) { c, f ->
            if (f.str("type") == "join") c.reply("""{"type":"reconnected","roomCode":"ABCD","players":[]}""")
        }
        val t = newTransport()
        val hosted = CountDownLatch(1)
        t.onHosted { hosted.countDown() }
        t.onReconnected { t.send("p-host", "p-guest", "resync".toByteArray()) }

        t.hostRoom("p-host", "Host")
        first.nextFrame()
        await("hosted", hosted)
        dropConnection(t)

        assertEquals("join", second.nextFrame().str("type"))
        val resync = second.nextFrame()
        assertEquals("message", resync.str("type"))
        assertEquals("p-guest", resync.str("toPlayerId"))
        assertEquals(java.util.Base64.getEncoder().encodeToString("resync".toByteArray()), resync.str("payloadBase64"))
    }

    // ---- reconnect edge cases (found by adversarial review) ----

    private val hostedFrame = """{"type":"hosted","roomCode":"ABCD","playerId":"p-host","reconnectToken":"tok-host"}"""
    private val resumedFrame = """{"type":"reconnected","roomCode":"ABCD","players":[]}"""

    @Test
    fun `a fresh joined answering a real seat's resume is a foreign room - refused, nothing adopted, and the transport retries`() {
        // The relay restarted (or reaped our room) and a stranger re-hosted our code. It answers our resume with a
        // FRESH 'joined' that names the stranger as the host and hands out a token. That is not our room.
        val first = expectConnection(0) { c, f ->
            if (f.str("type") == "join") {
                c.reply("""{"type":"joined","roomCode":"ABCD","hostPlayerId":"p-host","reconnectToken":"tok-guest","players":[{"playerId":"p-host","displayName":"Host"}]}""")
            }
        }
        val second = expectConnection(1) { c, f ->
            if (f.str("type") == "join") {
                c.reply("""{"type":"joined","roomCode":"ABCD","hostPlayerId":"p-mallory","reconnectToken":"tok-mallory","players":[{"playerId":"p-mallory","displayName":"M"}]}""")
            }
        }
        val third = expectConnection(2) { c, f -> if (f.str("type") == "join") c.reply(resumedFrame) }
        val t = newTransport()
        val joined = CountDownLatch(1)
        val reconnected = CountDownLatch(1)
        t.onJoined { _, _ -> joined.countDown() }
        t.onReconnected { reconnected.countDown() }
        t.joinRoom("ABCD", "p-guest", "Guest")
        first.nextFrame()
        await("joined", joined)

        dropConnection(t)
        second.nextFrame() // the first resume attempt; answered by the stranger's room
        val retry = third.nextFrame() // ...so the transport tries again, later
        await("the real room to resume us", reconnected)

        assertEquals("the retry still presents OUR token, not the stranger's", "tok-guest", retry.str("reconnectToken"))
        assertEquals("the stranger was never adopted as the host", "p-host", t.hostPlayerId)
    }

    @Test
    fun `a game send made while a resume is still being acknowledged is not put on the new socket ahead of its join`() {
        val first = expectConnection(0) { c, f -> if (f.str("type") == "host") c.reply(hostedFrame) }
        val second = expectConnection(1) { _, _ -> } // the relay is slow: no reply until the test says so
        val t = newTransport()
        val hosted = CountDownLatch(1)
        val reconnected = CountDownLatch(1)
        t.onHosted { hosted.countDown() }
        t.onReconnected { reconnected.countDown() }
        t.hostRoom("p-host", "Host")
        first.nextFrame()
        await("hosted", hosted)

        dropConnection(t)
        assertEquals("join", second.nextFrame().str("type")) // the attempt is now in flight, unanswered
        t.send("p-host", "p-guest", "early".toByteArray())
        assertNull("a send before the relay acknowledged the resume must not reach the new socket", second.frames.poll(500, TimeUnit.MILLISECONDS))

        second.reply(resumedFrame)
        await("the resume", reconnected)
        t.send("p-host", "p-guest", "late".toByteArray())

        val delivered = second.nextFrame()
        assertEquals("message", delivered.str("type"))
        assertEquals("only the send made AFTER the acknowledgement goes out on the new socket",
            java.util.Base64.getEncoder().encodeToString("late".toByteArray()), delivered.str("payloadBase64"))
    }

    @Test
    fun `a resumed connection that drops again is noticed and resumed again with the same token`() {
        val first = expectConnection(0) { c, f -> if (f.str("type") == "host") c.reply(hostedFrame) }
        val second = expectConnection(1) { c, f -> if (f.str("type") == "join") c.reply(resumedFrame) }
        val third = expectConnection(2) { c, f -> if (f.str("type") == "join") c.reply(resumedFrame) }
        val t = newTransport()
        val hosted = CountDownLatch(1)
        val resumes = java.util.concurrent.Semaphore(0)
        t.onHosted { hosted.countDown() }
        t.onReconnected { resumes.release() }
        t.hostRoom("p-host", "Host")
        first.nextFrame()
        await("hosted", hosted)

        dropConnection(t)
        second.nextFrame()
        assertTrue("first resume", resumes.tryAcquire(15, TimeUnit.SECONDS))
        dropConnection(t) // the RESUMED connection dies too
        val again = third.nextFrame()
        assertTrue("second resume", resumes.tryAcquire(15, TimeUnit.SECONDS))

        assertEquals("join", again.str("type"))
        assertEquals("tok-host", again.str("reconnectToken"))
    }

    @Test
    fun `a refusal from an earlier resume attempt is cleared once a later attempt succeeds`() {
        val first = expectConnection(0) { c, f -> if (f.str("type") == "host") c.reply(hostedFrame) }
        val second = expectConnection(1) { c, f ->
            if (f.str("type") == "join") c.reply("""{"type":"error","message":"playerId already in this room"}""")
        }
        val third = expectConnection(2) { c, f -> if (f.str("type") == "join") c.reply(resumedFrame) }
        val t = newTransport()
        val hosted = CountDownLatch(1)
        val reconnected = CountDownLatch(1)
        t.onHosted { hosted.countDown() }
        t.onReconnected { reconnected.countDown() }
        t.hostRoom("p-host", "Host")
        first.nextFrame()
        await("hosted", hosted)

        dropConnection(t)
        second.nextFrame()
        third.nextFrame()
        await("the resume", reconnected)

        assertNull("the lobby must not keep showing a refusal that no longer applies", t.connectionError.value)
        awaitFalse("isReconnecting to clear once the resume completes") { t.isReconnecting.value }
    }

    @Test
    fun `disconnect while a resume is being acknowledged leaves nothing behind`() {
        val first = expectConnection(0) { c, f -> if (f.str("type") == "host") c.reply(hostedFrame) }
        val second = expectConnection(1) { _, _ -> } // unanswered until after disconnect()
        val t = newTransport()
        val hosted = CountDownLatch(1)
        val reconnected = CountDownLatch(1)
        t.onHosted { hosted.countDown() }
        t.onReconnected { reconnected.countDown() }
        t.hostRoom("p-host", "Host")
        first.nextFrame()
        await("hosted", hosted)

        dropConnection(t)
        second.nextFrame() // in flight
        t.disconnect()
        runCatching { second.reply(resumedFrame) } // the relay answers too late
        Thread.sleep(500)

        assertEquals("a late 'reconnected' must not fire the game's handler", 1L, reconnected.count)
        assertNull("nor bring the room back", t.roomCode.value)
        assertNull(t.hostPlayerId)
        awaitFalse("isReconnecting to stay clear after a deliberate disconnect") { t.isReconnecting.value }
    }

    // ---- guest ----

    @Test
    fun `a guest keeps the token from joined, learns the host id, and presents the token on reconnect`() {
        val first = expectConnection(0) { c, f ->
            if (f.str("type") == "join") {
                c.reply(
                    """{"type":"joined","roomCode":"ABCD","hostPlayerId":"p-host","reconnectToken":"tok-guest",""" +
                        """"players":[{"playerId":"p-host","displayName":"Host"}]}"""
                )
            }
        }
        val second = expectConnection(1) { c, f ->
            if (f.str("type") == "join") c.reply("""{"type":"reconnected","roomCode":"ABCD","players":[{"playerId":"p-host","displayName":"Host"}]}""")
        }
        val t = newTransport()
        val joined = CountDownLatch(1)
        val reconnected = CountDownLatch(1)
        t.onJoined { _, _ -> joined.countDown() }
        t.onReconnected { reconnected.countDown() }

        t.joinRoom("ABCD", "p-guest", "Guest")
        val joinFrame = first.nextFrame()
        await("joined", joined)
        assertNull("the first join has no token", joinFrame.str("reconnectToken"))
        assertEquals("p-host", t.hostPlayerId)

        dropConnection(t)
        val rejoin = second.nextFrame()
        await("reconnected", reconnected)

        assertEquals("p-guest", rejoin.str("playerId"))
        assertEquals("tok-guest", rejoin.str("reconnectToken"))
    }

    @Test
    fun `a guest that never hears a token does not invent one`() {
        // An older relay: no reconnectToken and no hostPlayerId in joined.
        val first = expectConnection(0) { c, f ->
            if (f.str("type") == "join") {
                c.reply("""{"type":"joined","roomCode":"ABCD","players":[{"playerId":"p-host","displayName":"Host"},{"playerId":"p-other","displayName":"O"}]}""")
            }
        }
        val second = expectConnection(1) { c, f ->
            if (f.str("type") == "join") c.reply("""{"type":"reconnected","roomCode":"ABCD","players":[]}""")
        }
        val t = newTransport()
        val joined = CountDownLatch(1)
        val reconnected = CountDownLatch(1)
        t.onJoined { _, _ -> joined.countDown() }
        t.onReconnected { reconnected.countDown() }

        t.joinRoom("ABCD", "p-guest", "Guest")
        first.nextFrame()
        await("joined", joined)
        assertEquals("falls back to the room's first member, which an older relay lists first", "p-host", t.hostPlayerId)

        dropConnection(t)
        val rejoin = second.nextFrame()
        await("reconnected", reconnected)

        assertNull("no token was issued, so none is sent", rejoin.str("reconnectToken"))
        assertEquals("p-guest", rejoin.str("playerId"))
    }

    // ---- spectator ----

    @Test
    fun `a spectator learns the host id, holds no token, and re-joins as a spectator`() {
        val first = expectConnection(0) { c, f ->
            if (f.str("type") == "join") {
                c.reply("""{"type":"joined","roomCode":"ABCD","hostPlayerId":"p-host","players":[{"playerId":"p-host","displayName":"Host"}]}""")
            }
        }
        val second = expectConnection(1) { c, f ->
            if (f.str("type") == "join") c.reply("""{"type":"joined","roomCode":"ABCD","hostPlayerId":"p-host","players":[]}""")
        }
        val t = newTransport()
        val joined = CountDownLatch(2) // the original join, then the spectator's fresh re-join (its "reconnect")
        t.onJoined { _, _ -> joined.countDown() }

        t.joinRoom("ABCD", "p-spec", "Spectator", spectator = true)
        val joinFrame = first.nextFrame()
        assertEquals(true, joinFrame["spectator"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("p-host", awaitHostId(t))

        dropConnection(t)
        val rejoin = second.nextFrame()
        await("spectator re-join", joined)

        assertEquals("p-spec", rejoin.str("playerId"))
        assertEquals("true", rejoin["spectator"]?.jsonPrimitive?.content)
        assertNull("a spectator has no seat to prove ownership of", rejoin.str("reconnectToken"))
    }

    private fun awaitHostId(t: OnlineTransport): String? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (t.hostPlayerId == null && System.nanoTime() < deadline) Thread.sleep(10)
        return t.hostPlayerId
    }

    // ---- lifecycle ----

    @Test
    fun `disconnect forgets the room's host`() {
        val first = expectConnection(0) { c, f ->
            if (f.str("type") == "host") c.reply("""{"type":"hosted","roomCode":"ABCD","playerId":"p-host","reconnectToken":"tok-host"}""")
        }
        val t = newTransport()
        val hosted = CountDownLatch(1)
        t.onHosted { hosted.countDown() }
        t.hostRoom("p-host", "Host")
        first.nextFrame()
        await("hosted", hosted)
        assertNotNull(t.hostPlayerId)

        t.disconnect()

        assertNull(t.hostPlayerId)
    }

    @Test
    fun `a guest joining a second room does not carry the first room's host id`() {
        val first = expectConnection(0) { c, f ->
            if (f.str("type") == "join") c.reply("""{"type":"joined","roomCode":"AAAA","hostPlayerId":"p-host-1","reconnectToken":"tok-1","players":[]}""")
        }
        val second = expectConnection(1) { _, _ -> } // the relay never answers this one
        val t = newTransport()
        val joined = CountDownLatch(1)
        t.onJoined { _, _ -> joined.countDown() }
        t.joinRoom("AAAA", "p-guest", "Guest")
        first.nextFrame()
        await("first joined", joined)
        assertEquals("p-host-1", t.hostPlayerId)

        // Joining again starts from a clean slate until the new relay reply says otherwise.
        t.joinRoom("BBBB", "p-guest", "Guest")
        assertEquals("BBBB", second.nextFrame().str("roomCode"))
        assertNull(t.hostPlayerId)
    }
}
