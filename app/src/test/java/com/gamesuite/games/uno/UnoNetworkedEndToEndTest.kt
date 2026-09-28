package com.gamesuite.games.uno

import com.gamesuite.core.PlayMode
import com.gamesuite.games.uno.UnoTestKit.c
import com.gamesuite.games.uno.UnoTestKit.deadHand
import com.gamesuite.games.uno.UnoTestKit.seedDrawPile
import com.gamesuite.games.uno.UnoTestKit.table
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three real [UnoGame] instances (one host, two guests) wired through an in-memory [UnoHub] that,
 * like a real relay, tells the receiver the REAL sender endpoint regardless of what the sender claimed.
 * These tests prove the pieces work together: host deal -> RequestState -> redacted StateSync ->
 * guest tap -> Intent -> host validation -> re-broadcast -> guest state.
 */
class UnoNetworkedEndToEndTest {

    private val modes = listOf(PlayMode.LOCAL_AD_HOC, PlayMode.ONLINE)
    private val redSeven = c(UnoColor.RED, UnoRank.SEVEN, 700)
    private val redNine = c(UnoColor.RED, UnoRank.NINE, 500)

    private class Table(val hub: UnoHub, val host: UnoNode, val p1: UnoNode, val p2: UnoNode)

    /** Host deals BEFORE the guests exist (the startup race), then the guests join and ask for state. */
    private fun startTable(mode: PlayMode, rules: UnoRules = UnoRules()): Table {
        val hub = UnoHub()
        val host = UnoTestKit.node(0, mode = mode, rules = rules, transport = hub.endpoint("p0"), start = true)
        val p1 = UnoTestKit.node(1, mode = mode, rules = rules, transport = hub.endpoint("p1"))
        val p2 = UnoTestKit.node(2, mode = mode, rules = rules, transport = hub.endpoint("p2"))
        return Table(hub, host, p1, p2)
    }

    private fun Table.assertGuestsMirrorHost(label: String) {
        val truth = host.game.state.value!!
        assertEquals("$label: p1 view", UnoTestKit.expectedRedaction(truth, "p1"), p1.game.state.value)
        assertEquals("$label: p2 view", UnoTestKit.expectedRedaction(truth, "p2"), p2.game.state.value)
        assertTrue("$label: nobody lied about its id: ${hub.senderLies}", hub.senderLies.isEmpty())
    }

    /** Overwrites the host table (a "hand-built deal") and commits once so the guests receive it. */
    private fun Table.setTable(st: UnoState, pile: List<UnoCard> = (0 until 20).map { c(UnoColor.BLUE, UnoRank.NINE, 3000 + it) }) {
        host.game.state.value = st
        seedDrawPile(host.game, pile)
        host.game.callUno(0) // any real commit: bumps the version and broadcasts st (+ calledUno on seat 0)
    }

    /** Guards the startup race fix: a guest whose listener registers after the host already dealt still ends up
     *  with exactly the host's state (redacted for it) purely through its own init-time RequestState. */
    @Test
    fun `guests that join after the deal receive the state via RequestState`() {
        for (mode in modes) {
            val t = startTable(mode)
            t.assertGuestsMirrorHost("$mode after join")
            assertEquals("each guest holds exactly its own real hand", t.host.game.state.value!!.players[1].hand, t.p1.game.state.value!!.players[1].hand)
            assertEquals(t.host.game.state.value!!.players[2].hand, t.p2.game.state.value!!.players[2].hand)
        }
    }

    /** A guest's tap travels as an Intent, the host applies it, and BOTH guests (including the one that
     *  did not act) converge on the host's new state. Guards the whole request/apply/broadcast loop. */
    @Test
    fun `a guest move is applied by the host and mirrored to every guest`() {
        for (mode in modes) {
            val t = startTable(mode)
            t.setTable(table(current = 1, hands = mapOf(1 to listOf(redSeven) + deadHand(1))))
            t.assertGuestsMirrorHost("$mode after setup")

            t.p1.game.playCard(1, redSeven)

            val s = t.host.game.state.value!!
            assertEquals(redSeven, s.topCard)
            assertEquals(2, s.currentPlayerIndex)
            assertEquals("the guest itself sees the move only via the host's echo", redSeven, t.p1.game.state.value!!.topCard)
            t.assertGuestsMirrorHost("$mode after p1 played")
        }
    }

    /** Spoofing end to end: guest 2 claims to be seat 1 (playing seat 1's real card id and drawing for it).
     *  The relay stamps the true sender, the host drops both, and no guest state moves. */
    @Test
    fun `a guest impersonating another seat changes nothing anywhere`() {
        val t = startTable(PlayMode.LOCAL_AD_HOC)
        t.setTable(table(current = 1, hands = mapOf(1 to listOf(redSeven) + deadHand(1))))
        val hostBefore = t.host.game.state.value
        val p1Before = t.p1.game.state.value
        val p2Before = t.p2.game.state.value

        t.p2.game.playCard(1, redSeven)
        t.p2.game.drawCard(1)
        t.p2.game.callUno(1)

        assertEquals(hostBefore, t.host.game.state.value)
        assertEquals(p1Before, t.p1.game.state.value)
        assertEquals(p2Before, t.p2.game.state.value)
        assertTrue(t.hub.senderLies.isEmpty())
    }

    /** UI double-tap on Draw: the guest's screen fires drawCard twice in a row. The host must add exactly one
     *  card (a0879b2 over the real wire), both guests see the pending decision, and keeping the card then
     *  hands the turn on for everyone. */
    @Test
    fun `a double-tapped Draw from a guest yields one card and a pending decision everywhere`() {
        for (mode in modes) {
            val t = startTable(mode)
            t.setTable(table(current = 1), pile = listOf(redNine, c(UnoColor.RED, UnoRank.TWO, 504), c(UnoColor.BLUE, UnoRank.NINE, 503)))

            t.p1.game.drawCard(1)
            t.p1.game.drawCard(1)

            val s = t.host.game.state.value!!
            assertEquals(4, s.players[1].hand.size)
            assertTrue(s.awaitingDrawDecision)
            assertEquals(2, s.drawPileSize)
            t.assertGuestsMirrorHost("$mode after double draw")
            assertEquals(4, t.p2.game.state.value!!.players[1].hand.size)
            assertTrue(t.p2.game.state.value!!.awaitingDrawDecision)

            t.p1.game.keepDrawnCard(1)
            assertEquals(2, t.host.game.state.value!!.currentPlayerIndex)
            assertTrue(!t.host.game.state.value!!.awaitingDrawDecision)
            t.assertGuestsMirrorHost("$mode after keep")
        }
    }

    /** The whole Wild Draw Four challenge, driven purely through guests' public methods. */
    @Test
    fun `wild draw four with a failed challenge plays out across three devices`() {
        val wd4 = c(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, 601)
        val t = startTable(PlayMode.LOCAL_AD_HOC)
        t.setTable(table(current = 1, hands = mapOf(1 to listOf(wd4) + deadHand(1))))

        t.p1.game.playCard(1, wd4)
        t.p1.game.chooseColor(UnoColor.BLUE)
        t.assertGuestsMirrorHost("awaiting challenge")
        assertTrue(t.p2.game.state.value!!.awaitingChallenge)

        t.p1.game.resolveChallenge(accept = true) // the attacker tries to resolve its own challenge: dropped
        assertTrue(t.host.game.state.value!!.awaitingChallenge)

        t.p2.game.resolveChallenge(accept = false)

        val s = t.host.game.state.value!!
        assertEquals(9, s.players[2].hand.size)
        assertEquals(0, s.currentPlayerIndex)
        t.assertGuestsMirrorHost("after challenge")
    }

    /** Recovery from missed broadcasts, both directions: (1) the guest re-requests on ITS reconnect, (2) the
     *  host re-broadcasts on ITS reconnect. Between them a guest that missed real mutations catches up to
     *  the host's current version with the correct redaction. */
    @Test
    fun `a guest that missed broadcasts catches up after either side reconnects`() {
        for (mode in modes) {
            val t = startTable(mode)
            t.setTable(table(current = 1))
            t.assertGuestsMirrorHost("$mode in sync")

            t.hub.dropTo += "p2"
            t.host.game.callUno(1)
            t.host.game.callUno(2)
            assertNotEquals("p2 must be stale while its link is down", t.host.game.state.value!!.lastAction, t.p2.game.state.value!!.lastAction)

            t.hub.dropTo.clear()
            t.p2.transport.fireReconnected() // guest asks again
            t.assertGuestsMirrorHost("$mode after guest re-request")

            t.hub.dropTo += "p2"
            t.host.game.callUno(0)
            t.hub.dropTo.clear()
            assertNotEquals(t.host.game.state.value!!.lastAction, t.p2.game.state.value!!.lastAction)
            t.host.transport.fireReconnected() // host re-broadcasts
            t.assertGuestsMirrorHost("$mode after host re-broadcast")
        }
    }
}
