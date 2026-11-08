package com.gamesuite.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Wave 5 S6: a guest whose host vanishes must find out, once, for every game. The transport keeps the health state
 * (a game's own single onPlayerLeft listener is a setter the shell cannot share); [sessionNoticeFor] turns it into
 * what the chrome shows. The relay is scripted with MockWebServer, as in [OnlineTransportIdentityTest].
 */
class SessionHealthTest {

    private lateinit var server: MockWebServer
    private val transports = mutableListOf<OnlineTransport>()

    @Before
    fun setUp() { server = MockWebServer().apply { start() } }

    @After
    fun tearDown() {
        transports.forEach { runCatching { it.disconnect() } }
        runCatching { server.shutdown() }
    }

    private inner class Conn : WebSocketListener() {
        val frames = LinkedBlockingQueue<JsonObject>()
        @Volatile var socket: WebSocket? = null
        val opened = CountDownLatch(1)
        override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket; opened.countDown() }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = Json.parseToJsonElement(text).jsonObject
            frames.add(frame)
            if (frame["type"]?.jsonPrimitive?.content == "join") {
                webSocket.send(
                    """{"type":"joined","roomCode":"ABCD","hostPlayerId":"p-host","reconnectToken":"t","players":[""" +
                        """{"playerId":"p-host","displayName":"Host"},{"playerId":"p-me","displayName":"Me"},{"playerId":"p-other","displayName":"Other"}]}"""
                )
            }
        }
        fun push(json: String) = socket!!.send(json)
    }

    private fun guestInRoom(): Pair<OnlineTransport, Conn> {
        val conn = Conn()
        server.enqueue(MockResponse().withWebSocketUpgrade(conn))
        val t = OnlineTransport("ws://${server.hostName}:${server.port}/", OnlineTransport.DIRECT).also { transports += it }
        val joined = CountDownLatch(1)
        t.onJoined { _, _ -> joined.countDown() }
        t.joinRoom("ABCD", "p-me", "Me")
        assertTrue(joined.await(15, TimeUnit.SECONDS))
        return t to conn
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("timed out waiting for $what", condition())
    }

    @Test
    fun `a guest learns the host left for good, exactly through the relays playerLeft`() {
        val (t, conn) = guestInRoom()
        assertNull(t.sessionEnd.value)
        conn.push("""{"type":"playerLeft","playerId":"p-host"}""")
        awaitTrue("session end") { t.sessionEnd.value != null }
        assertEquals(SessionEnd.HOST_LEFT, t.sessionEnd.value)
    }

    @Test
    fun `another player leaving does not end the session`() {
        val (t, conn) = guestInRoom()
        conn.push("""{"type":"playerLeft","playerId":"p-other"}""")
        awaitTrue("player removed") { t.connectedPlayers.value.none { it.playerId == "p-other" } }
        assertNull(t.sessionEnd.value)
    }

    @Test
    fun `a host that drops and returns shows as away and then clears, never as left`() {
        val (t, conn) = guestInRoom()
        conn.push("""{"type":"playerDisconnected","playerId":"p-host"}""")
        awaitTrue("host away") { "p-host" in t.awayPlayers.value }
        assertEquals(
            SessionNotice.HOST_AWAY,
            sessionNoticeFor(t.sessionEnd.value, t.isReconnecting.value, t.awayPlayers.value, t.sessionHostId, "p-me")
        )
        conn.push("""{"type":"playerReconnected","playerId":"p-host"}""")
        awaitTrue("host back") { t.awayPlayers.value.isEmpty() }
        assertNull(t.sessionEnd.value)
    }

    @Test
    fun `an away host whose grace window expires becomes left and is no longer away`() {
        val (t, conn) = guestInRoom()
        conn.push("""{"type":"playerDisconnected","playerId":"p-host"}""")
        awaitTrue("host away") { "p-host" in t.awayPlayers.value }
        conn.push("""{"type":"playerLeft","playerId":"p-host"}""")
        awaitTrue("session end") { t.sessionEnd.value != null }
        assertTrue(t.awayPlayers.value.isEmpty())
        assertEquals(
            SessionNotice.HOST_LEFT,
            sessionNoticeFor(t.sessionEnd.value, false, t.awayPlayers.value, t.sessionHostId, "p-me")
        )
    }

    @Test
    fun `a host never sees itself as the host leaving`() {
        val conn = Conn()
        server.enqueue(MockResponse().withWebSocketUpgrade(conn))
        val t = OnlineTransport("ws://${server.hostName}:${server.port}/", OnlineTransport.DIRECT).also { transports += it }
        t.hostRoom("p-host", "Host")
        awaitTrue("socket open") { conn.opened.count == 0L }
        conn.push("""{"type":"hosted","roomCode":"ABCD","playerId":"p-host","reconnectToken":"t"}""")
        awaitTrue("hosted") { t.roomCode.value == "ABCD" }
        conn.push("""{"type":"playerLeft","playerId":"p-guest"}""")
        Thread.sleep(100)
        assertNull(t.sessionEnd.value)
    }

    // ---- the pure decision ----

    private fun notice(
        end: SessionEnd? = null, reconnecting: Boolean = false, away: Set<String> = emptySet(),
        host: String? = "h", local: String? = "me",
    ) = sessionNoticeFor(end, reconnecting, away, host, local)

    @Test
    fun `nothing is shown for a healthy session`() {
        assertEquals(SessionNotice.NONE, notice())
        assertFalse(notice().isFinal)
    }

    @Test
    fun `an ended session beats every transient notice and is final`() {
        assertEquals(SessionNotice.HOST_LEFT, notice(end = SessionEnd.HOST_LEFT, reconnecting = true, away = setOf("h")))
        assertEquals(SessionNotice.CONNECTION_LOST, notice(end = SessionEnd.CONNECTION_LOST, away = setOf("x")))
        assertTrue(notice(end = SessionEnd.HOST_LEFT).isFinal)
        assertTrue(notice(end = SessionEnd.CONNECTION_LOST).isFinal)
    }

    @Test
    fun `our own reconnect beats someone elses absence`() {
        assertEquals(SessionNotice.RECONNECTING, notice(reconnecting = true, away = setOf("h")))
    }

    @Test
    fun `host away beats another player away and neither is final`() {
        assertEquals(SessionNotice.HOST_AWAY, notice(away = setOf("x", "h")))
        assertEquals(SessionNotice.PLAYER_AWAY, notice(away = setOf("x")))
        assertFalse(notice(away = setOf("h")).isFinal)
    }

    @Test
    fun `we are never reported as away to ourselves and an unknown host is just a player`() {
        assertEquals(SessionNotice.NONE, notice(away = setOf("me")))
        assertEquals(SessionNotice.PLAYER_AWAY, notice(away = setOf("h"), host = null))
    }
}
