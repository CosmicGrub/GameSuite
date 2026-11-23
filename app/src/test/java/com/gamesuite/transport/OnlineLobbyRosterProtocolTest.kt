package com.gamesuite.transport

import com.gamesuite.core.OnlineMatchStarter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wave-5 lobby messages (Ready, RosterUpdate): round trips, who is believed, and what is made safe to show. */
class OnlineLobbyRosterProtocolTest {

    private fun encode(m: OnlineLobbyMessage) = LobbyJson.encodeToString(OnlineLobbyMessage.serializer(), m).toByteArray()
    private fun decode(b: ByteArray) = LobbyJson.decodeFromString(OnlineLobbyMessage.serializer(), String(b))

    @Test
    fun `Ready and RosterUpdate survive a round trip`() {
        val ready = OnlineLobbyMessage.Ready(true)
        assertEquals(ready, decode(encode(ready)))
        val roster = OnlineLobbyMessage.RosterUpdate(
            listOf(
                SeatView(SeatKind.HOST, "h", "Host", true),
                SeatView(SeatKind.HUMAN, "g1", "Sam", false),
                SeatView(SeatKind.CPU, "cpu-1", "Computer 1", true),
                SeatView(SeatKind.EMPTY),
            )
        )
        assertEquals(roster, decode(encode(roster)))
    }

    @Test
    fun `a roster from a newer host with extra fields is still read`() {
        val real = String(encode(OnlineLobbyMessage.Ready(true)))
        val withExtra = real.dropLast(1) + ""","futureField":1}"""
        assertEquals(OnlineLobbyMessage.Ready(true), decode(withExtra.toByteArray()))
    }

    @Test
    fun `roster text is made safe and bounded before it is shown`() {
        val dirty = OnlineLobbyMessage.RosterUpdate(
            List(40) { SeatView(SeatKind.HUMAN, "id\u0000$it", "  Na\u0007me".padEnd(200, 'x'), true) }
        )
        val clean = dirty.sanitized()
        assertEquals(MAX_LOBBY_SEATS, clean.size)
        assertTrue(clean.all { it.name.length <= 40 && it.name.none { c -> c.isISOControl() } })
        assertTrue(clean.all { it.playerId!!.none { c -> c.isISOControl() } })
    }

    @Test
    fun `only the host describes the table`() {
        val roster = OnlineLobbyMessage.RosterUpdate(listOf(SeatView(SeatKind.HOST, "h", "Host", true)))
        assertTrue(roster.isFromHost("h", "h"))
        assertEquals(false, roster.isFromHost("g1", "h"))
        assertEquals(false, roster.isFromHost("h", null))

        val seen = ArrayList<List<SeatView>>()
        val listener = OnlineMatchStarter.guestListener({ "h" }, {}, {}, { seen += it })
        listener("g1", encode(roster))
        assertTrue("a guest's roster is ignored", seen.isEmpty())
        listener("h", encode(roster))
        assertEquals(1, seen.size)
        listener("h", "garbage".toByteArray())
        assertEquals(1, seen.size)
    }

    @Test
    fun `a Ready from the wire never decodes into something the late-join responder answers`() {
        val responder = OnlineLateJoinResponder(
            OnlineLobbyMessage.GameStart("chess", listOf(com.gamesuite.core.PlayerInfo("h", "H"), com.gamesuite.core.PlayerInfo("g1", "G"))),
        ) { _, _ -> error("must not answer a Ready") }
        responder.onRawMessage("g1", encode(OnlineLobbyMessage.Ready(true)))
    }
}
