package com.gamesuite.transport

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Review finding 1: the relay's frames arrive on OkHttp's socket thread, while every game mutates its state on the
 * main thread. [OnlineTransport] must hop ONCE, in FIFO order, before any listener runs. A single-thread executor
 * stands in for the main looper.
 */
class OnlineTransportThreadingTest {

    private lateinit var server: MockWebServer
    private val fakeMain = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-main") }
    private val transports = mutableListOf<OnlineTransport>()

    @Before fun setUp() { server = MockWebServer().apply { start() } }

    @After fun tearDown() {
        transports.forEach { runCatching { it.disconnect() } }
        fakeMain.shutdownNow()
        runCatching { server.shutdown() }
    }

    private class Conn : WebSocketListener() {
        @Volatile var socket: WebSocket? = null
        override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.contains("\"join\"")) {
                webSocket.send(
                    """{"type":"joined","roomCode":"ABCD","hostPlayerId":"p-host","reconnectToken":"t","players":[""" +
                        """{"playerId":"p-host","displayName":"Host"},{"playerId":"p-me","displayName":"Me"}]}"""
                )
            }
        }
    }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    private fun joined(deliver: (Runnable) -> Unit): Pair<OnlineTransport, Conn> {
        val conn = Conn()
        server.enqueue(MockResponse().withWebSocketUpgrade(conn))
        val t = OnlineTransport("ws://${server.hostName}:${server.port}/", deliver).also { transports += it }
        val latch = CountDownLatch(1)
        t.onJoined { _, _ -> latch.countDown() }
        t.joinRoom("ABCD", "p-me", "Me")
        assertTrue(latch.await(15, TimeUnit.SECONDS))
        return t to conn
    }

    @Test
    fun `message joined and left listeners run on the delivery thread in relay order`() {
        val (t, conn) = joined { r -> fakeMain.execute(r) }
        val events = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(4)
        t.onMessageReceived { from, bytes -> events += "msg:$from:${String(bytes)}"; threads += Thread.currentThread().name; done.countDown() }
        t.onPlayerJoined { id -> events += "join:$id"; threads += Thread.currentThread().name; done.countDown() }
        t.onPlayerLeft { id -> events += "left:$id"; threads += Thread.currentThread().name; done.countDown() }
        val s = conn.socket!!
        s.send("""{"type":"message","fromPlayerId":"p-host","payloadBase64":"${b64("one")}"}""")
        s.send("""{"type":"playerJoined","playerId":"p-x","displayName":"X"}""")
        s.send("""{"type":"message","fromPlayerId":"p-host","payloadBase64":"${b64("two")}"}""")
        s.send("""{"type":"playerLeft","playerId":"p-x"}""")
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("msg:p-host:one", "join:p-x", "msg:p-host:two", "left:p-x"), events.toList())
        assertEquals(setOf("fake-main"), threads.toSet())
    }

    @Test
    fun `an oversized relayed payload is dropped before it reaches the game`() {
        val (t, conn) = joined(OnlineTransport.DIRECT)
        val got = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        t.onMessageReceived { _, bytes -> got += String(bytes); if (String(bytes) == "ok") done.countDown() }
        val huge = "A".repeat(OnlineTransport.MAX_PAYLOAD_BASE64_CHARS + 4)
        conn.socket!!.send("""{"type":"message","fromPlayerId":"p-host","payloadBase64":"$huge"}""")
        conn.socket!!.send("""{"type":"message","fromPlayerId":"p-host","payloadBase64":"${b64("ok")}"}""")
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("ok"), got.toList())
    }
}
