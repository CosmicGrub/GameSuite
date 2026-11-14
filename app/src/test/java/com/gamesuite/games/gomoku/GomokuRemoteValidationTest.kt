package com.gamesuite.games.gomoku

import com.gamesuite.core.PlayMode
import com.gamesuite.games.gomoku.TestSeats.GUEST
import com.gamesuite.games.gomoku.TestSeats.HOST
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review findings 20 and 9: a guest refuses a state holding an unrecorded five, and one with bad session tallies. */
class GomokuRemoteValidationTest {
    private val n = GOMOKU_SIZE

    private fun encode(m: GomokuNetMessage) = Json.encodeToString(GomokuNetMessage.serializer(), m)

    private class Rig(val host: GomokuGame, val guest: GomokuGame, val hostTransport: FakeTransport, val guestTransport: FakeTransport)

    private fun rig(): Rig {
        val hub = FakeHub()
        val ht = hub.endpoint(HOST); val gt = hub.endpoint(GUEST)
        val host = GomokuGame(); val guest = GomokuGame()
        host.init(TestSeats.context(PlayMode.LOCAL_AD_HOC, TestSeats.humans, 0, ht))
        guest.init(TestSeats.context(PlayMode.LOCAL_AD_HOC, TestSeats.humans, 1, gt))
        host.startMatch()
        host.placeStone(7 * n + 7)
        return Rig(host, guest, ht, gt)
    }

    private fun lastSync(r: Rig): GomokuNetState =
        r.hostTransport.sentSince(0).map { Json.decodeFromString(GomokuNetMessage.serializer(), it.text) }
            .filterIsInstance<GomokuNetMessage.StateSync>().last().state

    @Test fun aFiveOnTheBoardThatIsNotMarkedOverIsRefused() {
        val r = rig()
        val good = r.guest.state.value
        val cells = MutableList<Int?>(n * n) { null }
        for (c in 3..7) cells[7 * n + c] = 0   // five black stones in a row ...
        for (c in 3..6) cells[9 * n + c] = 1   // ... and four white: a legal stone count
        val bad = lastSync(r).copy(cells = cells, boardOver = false, winnerPlayerId = null, winLine = emptyList(), lastMove = 7 * n + 7)
        r.guestTransport.inject(HOST, encode(GomokuNetMessage.StateSync(500, bad)))
        assertEquals(good, r.guest.state.value)
    }

    @Test fun negativeOrForeignSessionTalliesAreRefused() {
        val r = rig()
        val good = r.guest.state.value
        val s = lastSync(r)
        r.guestTransport.inject(HOST, encode(GomokuNetMessage.StateSync(500, s.copy(sessionWins = mapOf(HOST to -4)))))
        r.guestTransport.inject(HOST, encode(GomokuNetMessage.StateSync(501, s.copy(sessionWins = mapOf("ghost" to 2)))))
        r.guestTransport.inject(HOST, encode(GomokuNetMessage.StateSync(502, s.copy(sessionDraws = -1))))
        assertEquals(good, r.guest.state.value)
    }

    @Test fun aSyncWithAFieldFromANewerHostIsStillUnderstood() {
        val r = rig()
        val s = lastSync(r)
        val text = encode(GomokuNetMessage.StateSync(600, s)).replaceFirst("\"version\":", "\"fromTheFuture\":1,\"version\":")
        assertTrue(text.contains("fromTheFuture"))
        r.guestTransport.inject(HOST, text)
        assertEquals(s.lastMove, r.guest.state.value!!.lastMove)
    }
}
