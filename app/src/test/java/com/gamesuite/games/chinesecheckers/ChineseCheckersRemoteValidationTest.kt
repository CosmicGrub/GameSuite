package com.gamesuite.games.chinesecheckers

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review finding 9: the guest applies the same piece-count / playability / tally checks as the snapshot decoder. */
class ChineseCheckersRemoteValidationTest {
    private fun encode(m: CCNetMessage) = Json.encodeToString(CCNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    private class Rig(val guest: ChineseCheckersGame, val guestTransport: ClFakeTransport, val real: CCNetState)

    private fun rig(): Rig {
        val ids = listOf("h", "g1")
        val players = ids.map { PlayerInfo(it, "N$it") }
        val hub = ClHub()
        val ht = hub.endpoint("h"); val gt = hub.endpoint("g1")
        val host = ChineseCheckersGame(); val guest = ChineseCheckersGame()
        host.init(GameContext(PlayMode.LOCAL_AD_HOC, players, 0, ht))
        guest.init(GameContext(PlayMode.LOCAL_AD_HOC, players, 1, gt))
        host.startMatch()
        val last = ht.sent.map { Json.decodeFromString(CCNetMessage.serializer(), String(it.payload)) }
            .filterIsInstance<CCNetMessage.StateSync>().last().state
        return Rig(guest, gt, last)
    }

    @Test fun aStateMissingPiecesOfASeatIsRefused() {
        val r = rig()
        val before = r.guest.state.value
        val cells = r.real.cells.toMutableList()
        val firstOfSeat0 = cells.indexOfFirst { it == 0 }
        cells[firstOfSeat0] = null
        r.guestTransport.inject("h", encode(CCNetMessage.StateSync(900, r.real.copy(cells = cells))))
        assertEquals(before, r.guest.state.value)
    }

    @Test fun negativeOrForeignSessionTalliesAreRefused() {
        val r = rig()
        val before = r.guest.state.value
        r.guestTransport.inject("h", encode(CCNetMessage.StateSync(900, r.real.copy(sessionWins = mapOf("h" to -1)))))
        r.guestTransport.inject("h", encode(CCNetMessage.StateSync(901, r.real.copy(sessionWins = mapOf("ghost" to 1)))))
        assertEquals(before, r.guest.state.value)
    }

    @Test fun theGenuineHostStateIsStillAccepted() {
        val r = rig()
        r.guestTransport.inject("h", encode(CCNetMessage.StateSync(900, r.real)))
        assertTrue(r.guest.state.value != null)
        assertEquals(r.real.turnCount, r.guest.state.value!!.turnCount)
    }
}
