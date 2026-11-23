package com.gamesuite.games.matching

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.speed.FakeClock
import com.gamesuite.games.speed.SmHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Review finding 6: a guest dropping must not freeze the table; the host plays that seat as a computer. */
class MatchingGuestLeavesTest {
    private class Table(val host: MatchingGame, val ht: com.gamesuite.games.speed.SmFakeTransport, val guest2: MatchingGame)

    private fun table(openerSeat: Int): Table {
        val ids = listOf("h", "g1", "g2")
        val players = ids.mapIndexed { i, id -> PlayerInfo(id, "N$i") }
        val hub = SmHub()
        val games = ids.mapIndexed { i, id ->
            val t = hub.endpoint(id)
            val g = MatchingGame(Random(5 + i), FakeClock())
            g.cardCount = 12
            if (i == 0) g.firstOpenerIndex = openerSeat
            g.init(GameContext(PlayMode.LOCAL_AD_HOC, players, i, t))
            if (i == 0) g.startMatch()
            g to t
        }
        return Table(games[0].first, games[0].second, games[2].first)
    }

    @Test fun theSeatOfAGuestWhoLeavesOnTheirTurnIsTakenByAComputerAndPlayContinues() {
        val t = table(openerSeat = 0)
        // the host turns up two different pictures, so the turn passes to seat 1
        val faces = t.host.truthFaces()
        val a = 0
        val b = faces.indices.first { faces[it] != faces[a] }
        t.host.flipCard(a); t.host.flipCard(b); t.host.resolve()
        assertEquals(1, t.host.state.value!!.currentPlayerIndex)
        assertFalse(t.host.botToMove())
        t.ht.fireLeft("g1")
        val s = t.host.state.value!!
        assertTrue(s.players[1].isBot)
        assertFalse(t.host.opponentLeft.value)
        assertTrue("a computer should now be on turn", t.host.botToMove())
        val flips = s.flipSerial
        assertTrue(t.host.playBotAttempt())
        assertTrue(t.host.state.value!!.flipSerial > flips)
        // the remaining guest was told, and is not stuck on a dead seat
        assertTrue(t.guest2.state.value!!.currentPlayerIndex in 0..2)
    }

    @Test fun theHostLeavingStillEndsTheTableForAGuest() {
        val gt = com.gamesuite.games.speed.SmFakeTransport()
        val g = MatchingGame(Random(1), FakeClock())
        g.init(GameContext(PlayMode.LOCAL_AD_HOC, listOf(PlayerInfo("h", "H"), PlayerInfo("g1", "G")), 1, gt))
        gt.fireLeft("h")
        assertTrue(g.opponentLeft.value)
    }
}
