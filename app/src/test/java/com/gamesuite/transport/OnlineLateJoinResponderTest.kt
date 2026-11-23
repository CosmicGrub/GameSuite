package com.gamesuite.transport

import com.gamesuite.core.PlayerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A member that joins the Online room after the host pressed Start (a spectator, or a seated guest
 * that reconnects) asks with a Hello; the host's responder re-sends the GameStart to that sender alone.
 */
class OnlineLateJoinResponderTest {

    private val start = OnlineLobbyMessage.GameStart(
        gameRoute = "uno",
        players = listOf(PlayerInfo("host", "Host"), PlayerInfo("g1", "Guest 1")),
        gameDisplayName = "UNO"
    )

    private class Out(val to: String, val payload: ByteArray) {
        val message: OnlineLobbyMessage
            get() = LobbyJson.decodeFromString(OnlineLobbyMessage.serializer(), String(payload, Charsets.UTF_8))
    }

    private fun responder(sink: MutableList<Out>) = OnlineLateJoinResponder(start) { to, bytes -> sink += Out(to, bytes) }

    @Test
    fun `a late spectator gets the GameStart addressed to it alone`() {
        val sent = mutableListOf<Out>()
        responder(sent).onRawMessage("watcher", onlineHelloPayload(spectator = true))
        assertEquals(1, sent.size)
        assertEquals("watcher", sent.single().to)
        assertEquals(start, sent.single().message)
    }

    @Test
    fun `the re-sent GameStart is accepted by the spectator only from the host`() {
        val sent = mutableListOf<Out>()
        responder(sent).onRawMessage("watcher", onlineHelloPayload(spectator = true))
        val msg = sent.single().message as OnlineLobbyMessage.GameStart
        assertTrue(msg.isFromHost("host", "host"))
        assertEquals(-1, msg.localSeatIndex("watcher", asSpectator = true))
    }

    @Test
    fun `a seated guest that missed Start gets it again and keeps its seat`() {
        val sent = mutableListOf<Out>()
        responder(sent).onRawMessage("g1", onlineHelloPayload(spectator = false))
        val msg = sent.single().message as OnlineLobbyMessage.GameStart
        assertEquals(1, msg.localSeatIndex("g1", asSpectator = false))
    }

    @Test
    fun `a late seat request is refused as already started`() {
        val sent = mutableListOf<Out>()
        responder(sent).onRawMessage("latecomer", onlineHelloPayload(spectator = false))
        assertEquals("latecomer", sent.single().to)
        assertEquals(OnlineLobbyMessage.Rejected(LOBBY_STARTED_MESSAGE), sent.single().message)
    }

    @Test
    fun `game traffic, garbage and the host's own Hello are ignored`() {
        val sent = mutableListOf<Out>()
        val r = responder(sent)
        r.onRawMessage("watcher", """{"type":"StateSync","version":1}""".toByteArray())
        r.onRawMessage("watcher", "not json".toByteArray())
        r.onRawMessage("watcher", ByteArray(4096) { 'x'.code.toByte() })
        r.onRawMessage("host", onlineHelloPayload(spectator = true))
        // A GameStart or Rejected relayed by a member is not a Hello either.
        r.onRawMessage("watcher", LobbyJson.encodeToString(OnlineLobbyMessage.serializer(), start).toByteArray())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `one sender is answered a bounded number of times`() {
        val sent = mutableListOf<Out>()
        val r = responder(sent)
        repeat(50) { r.onRawMessage("watcher", onlineHelloPayload(spectator = true)) }
        assertTrue(sent.size in 1..10)
        // ...without starving a different member.
        r.onRawMessage("other", onlineHelloPayload(spectator = true))
        assertEquals("other", sent.last().to)
    }

    @Test
    fun `Hello survives the lobby JSON round trip`() {
        val decoded = LobbyJson.decodeFromString(
            OnlineLobbyMessage.serializer(),
            String(onlineHelloPayload(spectator = true), Charsets.UTF_8)
        )
        assertEquals(OnlineLobbyMessage.Hello(spectator = true), decoded)
    }
}
