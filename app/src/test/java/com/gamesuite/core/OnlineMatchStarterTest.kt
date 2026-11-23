package com.gamesuite.core

import com.gamesuite.transport.LOBBY_FULL_MESSAGE
import com.gamesuite.transport.LOBBY_STARTED_MESSAGE
import com.gamesuite.transport.LobbySeatLimits
import com.gamesuite.transport.OnlineLobbyMessage
import com.gamesuite.transport.OnlinePlayer
import com.gamesuite.transport.lobbyRouteFor
import com.gamesuite.transport.onlineHelloPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the behaviour the two Online lobby screens had inline before it moved into [OnlineMatchStarter]: the bytes
 * put on the wire are exactly what the old `Json.encodeToString(message)` calls produced, the late-join responder is
 * live before the broadcast, overflow guests are told once, and the guest only believes the host.
 */
class OnlineMatchStarterTest {

    private class FakeChannel : OnlineRawChannel {
        val sent = ArrayList<Pair<String?, String>>()
        val log = ArrayList<String>()
        var listener: ((String, ByteArray) -> Unit)? = null
        override fun sendRaw(toPlayerId: String?, payload: ByteArray) {
            sent += toPlayerId to String(payload, Charsets.UTF_8)
            log += "send:${toPlayerId ?: "*"}"
        }
        override fun onRawMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) {
            this.listener = listener
            log += "listen"
        }
    }

    private fun guest(n: Int) = OnlinePlayer("g$n", "Guest $n")
    private val twoSeat = LobbySeatLimits(2, 2)
    private val unoRoute = lobbyRouteFor("uno")

    @Test
    fun `host start broadcasts the exact legacy GameStart bytes and launches host first`() {
        val ch = FakeChannel()
        var launched: List<PlayerInfo>? = null
        val ok = OnlineMatchStarter.startAsHost(ch, "h", "Host", listOf(guest(1)), twoSeat, "chess", "Chess") { launched = it }
        assertTrue(ok)
        val expectedPlayers = listOf(PlayerInfo("h", "Host"), PlayerInfo("g1", "Guest 1"))
        assertEquals(expectedPlayers, launched)
        val legacy: OnlineLobbyMessage = OnlineLobbyMessage.GameStart("chess", expectedPlayers, "Chess")
        assertEquals(null to Json.encodeToString(legacy), ch.sent.single())
    }

    @Test
    fun `the late-join responder is installed before the broadcast and answers a Hello`() {
        val ch = FakeChannel()
        OnlineMatchStarter.startAsHost(ch, "h", "Host", listOf(guest(1)), twoSeat, "chess", "Chess") {}
        assertEquals(listOf("listen", "send:*"), ch.log)
        ch.listener!!.invoke("g1", onlineHelloPayload(false))
        assertEquals("g1", ch.sent.last().first)
        assertTrue(ch.sent.last().second.contains("\"chess\""))
        // A stranger who wanted a seat is told the game already started.
        ch.listener!!.invoke("stranger", onlineHelloPayload(false))
        assertTrue(ch.sent.last().second.contains(LOBBY_STARTED_MESSAGE))
    }

    @Test
    fun `start is refused when the seat count does not allow it and nothing is sent or launched`() {
        val ch = FakeChannel()
        var launched = false
        assertFalse(OnlineMatchStarter.startAsHost(ch, "h", "Host", emptyList(), twoSeat, "chess", "Chess") { launched = true })
        assertFalse(launched)
        assertTrue(ch.sent.isEmpty())
        assertNull(ch.listener)
    }

    @Test
    fun `guests beyond the games seats are not announced`() {
        val ch = FakeChannel()
        var launched: List<PlayerInfo>? = null
        OnlineMatchStarter.startAsHost(ch, "h", "Host", listOf(guest(1), guest(2), guest(3)), twoSeat, "chess", "Chess") { launched = it }
        assertEquals(listOf("h", "g1"), launched!!.map { it.playerId })
    }

    @Test
    fun `overflow guests are told the room is full exactly once`() {
        val ch = FakeChannel()
        val told = HashSet<String>()
        val first = OnlineMatchStarter.rejectOverflow(ch, listOf(guest(2), guest(3)), told)
        assertEquals(listOf("g2", "g3"), first)
        val rejected: OnlineLobbyMessage = OnlineLobbyMessage.Rejected(LOBBY_FULL_MESSAGE)
        assertEquals("g2" to Json.encodeToString(rejected), ch.sent[0])
        assertTrue(OnlineMatchStarter.rejectOverflow(ch, listOf(guest(2), guest(3)), told).isEmpty())
        assertEquals(2, ch.sent.size)
    }

    // ---- guest side ----

    private fun start(vararg ids: String, route: String = unoRoute, name: String? = "UNO") =
        OnlineLobbyMessage.GameStart(route, ids.map { PlayerInfo(it, it) }, name)

    @Test
    fun `guest launches into its seat`() {
        val d = OnlineMatchStarter.guestDecision(start("h", "me"), "uno", "me", false)
        assertEquals(1, (d as GuestStartDecision.Launch).localPlayerIndex)
    }

    @Test
    fun `spectator launches with seat minus one even when absent from the roster`() {
        val d = OnlineMatchStarter.guestDecision(start("h", "a"), "uno", "watcher", true)
        assertEquals(-1, (d as GuestStartDecision.Launch).localPlayerIndex)
    }

    @Test
    fun `a roster that does not seat a player is ignored and one claiming seat 0 is ignored`() {
        assertEquals(GuestStartDecision.Ignore, OnlineMatchStarter.guestDecision(start("h", "a"), "uno", "me", false))
        assertEquals(GuestStartDecision.Ignore, OnlineMatchStarter.guestDecision(start("me", "a"), "uno", "me", false))
    }

    @Test
    fun `a room playing another or unknown game is refused`() {
        val wrong = OnlineMatchStarter.guestDecision(start("h", "me", route = lobbyRouteFor("chess"), name = "Chess"), "uno", "me", false)
        assertTrue(wrong is GuestStartDecision.Refused)
        val unknown = OnlineMatchStarter.guestDecision(start("h", "me", route = "zzz-unknown", name = "Zzz"), "uno", "me", false)
        assertTrue(unknown is GuestStartDecision.Refused)
    }

    @Test
    fun `guest listener believes only the host`() {
        val gameStarts = ArrayList<OnlineLobbyMessage.GameStart>()
        val rejections = ArrayList<String>()
        val listener = OnlineMatchStarter.guestListener({ "h" }, { gameStarts += it }, { rejections += it })
        val startMsg: OnlineLobbyMessage = start("h", "me")
        val bytes = Json.encodeToString(startMsg).toByteArray()
        listener("imposter", bytes)
        assertTrue(gameStarts.isEmpty())
        listener("h", bytes)
        assertEquals(1, gameStarts.size)
        val rej: OnlineLobbyMessage = OnlineLobbyMessage.Rejected("No\u0000 room")
        val rejBytes = Json.encodeToString(rej).toByteArray()
        listener("imposter", rejBytes)
        assertTrue(rejections.isEmpty())
        listener("h", rejBytes)
        assertEquals(listOf("No room"), rejections)
        listener("h", "not json".toByteArray()) // game traffic is ignored, never throws
        assertEquals(1, gameStarts.size)
    }

    @Test
    fun `a GameStart whose roster does not put the host first is not from the host`() {
        val gameStarts = ArrayList<OnlineLobbyMessage.GameStart>()
        val listener = OnlineMatchStarter.guestListener({ "h" }, { gameStarts += it }, {})
        val m: OnlineLobbyMessage = start("someone-else", "h", "me")
        listener("h", Json.encodeToString(m).toByteArray())
        assertTrue(gameStarts.isEmpty())
    }
}
