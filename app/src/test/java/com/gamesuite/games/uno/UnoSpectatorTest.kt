package com.gamesuite.games.uno

import com.gamesuite.core.PlayMode
import com.gamesuite.games.uno.UnoTestKit.c
import com.gamesuite.games.uno.UnoTestKit.deadHand
import com.gamesuite.games.uno.UnoTestKit.table
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Watching an Online UNO game: the pure redaction rule, the seat-less RequestState round trip, a
 * spectator never mutating anything, and the host ignoring a spectator that tries to move.
 */
class UnoSpectatorTest {

    private val secretA = c(UnoColor.YELLOW, UnoRank.NINE, 777123)
    private val secretB = c(UnoColor.GREEN, UnoRank.REVERSE, 777124)

    private fun host(n: Int = 3) = UnoTestKit.node(localIndex = 0, n = n, mode = PlayMode.ONLINE, start = true)

    private fun spectator(n: Int = 3, transport: UnoFakeTransport = UnoFakeTransport()) =
        UnoTestKit.node(localIndex = -1, n = n, mode = PlayMode.ONLINE, transport = transport)

    /** A host whose state is a hand-built table with identifiable secret cards in seats 0 and 1. */
    private fun hostWithSecrets(): UnoNode {
        val h = host()
        h.game.state.value = table(
            3, current = 1,
            hands = mapOf(0 to listOf(secretA) + deadHand(0), 1 to listOf(secretB) + deadHand(1))
        )
        h.game.callUno(0) // a real commit, so the counter and the broadcast reflect the table above
        return h
    }

    // ---- pure redaction ----

    @Test
    fun `spectator redaction keeps every public field and blanks every hand`() {
        val s = table(4, current = 2, hands = mapOf(1 to listOf(secretA, secretB)))
            .copy(pendingDraw = 2, cumulativeScores = mapOf("p0" to 40), roundNumber = 3)
        val r = unoRedactForSpectator(s)

        assertEquals(s.drawPileSize, r.drawPileSize)
        assertEquals(s.discardPile, r.discardPile)
        assertEquals(s.currentColor, r.currentColor)
        assertEquals(s.currentPlayerIndex, r.currentPlayerIndex)
        assertEquals(s.direction, r.direction)
        assertEquals(s.pendingDraw, r.pendingDraw)
        assertEquals(s.cumulativeScores, r.cumulativeScores)
        assertEquals(s.roundNumber, r.roundNumber)
        assertEquals(s.lastAction, r.lastAction)
        assertEquals(s.players.map { it.hand.size }, r.players.map { it.hand.size })
        assertEquals(s.players.map { it.displayName }, r.players.map { it.displayName })
        r.players.forEach { p -> assertTrue(p.hand.all { it == UNO_REDACTED_CARD }) }
    }

    @Test
    fun `spectator redaction leaks no hand card even in the serialized payload`() {
        val s = table(3, hands = mapOf(0 to listOf(secretA), 1 to listOf(secretB), 2 to deadHand(2)))
        val raw = String(
            UnoTestKit.encodeUnoMessage(UnoNetMessage.StateSync(1, unoRedactForSpectator(s))),
            Charsets.UTF_8
        )
        s.players.flatMap { it.hand }.forEach { card ->
            assertFalse("card ${card.instanceId} must not appear", raw.contains("\"instanceId\":${card.instanceId}"))
        }
        // ...while a seat's own redaction still shows that seat its own hand (the existing behavior).
        assertEquals(s.players[0].hand, unoRedactHandsExcept(s, "p0").players[0].hand)
    }

    // ---- host streams to a spectator ----

    @Test
    fun `a seat-less RequestState is answered to that sender with a fully redacted state`() {
        val h = hostWithSecrets()
        val committed = h.game.state.value!!
        val version = h.transport.sent.last().stateSync.version
        val mark = h.transport.mark()

        h.transport.inject("watcher", UnoNetMessage.RequestState)

        val out = h.transport.sentSince(mark)
        assertEquals(1, out.size)
        assertEquals("watcher", out.single().to)
        assertEquals("p0", out.single().from)
        assertEquals(version, out.single().stateSync.version)
        assertEquals(unoRedactForSpectator(committed), out.single().stateSync.state)
        val raw = String(out.single().payload, Charsets.UTF_8)
        assertFalse(raw.contains("777123"))
        assertFalse(raw.contains("777124"))
    }

    @Test
    fun `after asking, a spectator receives every later commit redacted, seats still get their own hand`() {
        val h = hostWithSecrets()
        h.transport.inject("watcher", UnoNetMessage.RequestState)
        val mark = h.transport.mark()

        h.game.callUno(1) // another commit

        val out = h.transport.sentSince(mark)
        assertEquals(listOf("p1", "p2", "watcher"), out.map { it.to })
        val committed = h.game.state.value!!
        UnoTestKit.assertRedactedFor(out[0].stateSync, committed, "p1")
        assertTrue("seat 1 still sees its own secret", out[0].stateSync.state.players[1].hand.contains(secretB))
        val toWatcher = out[2]
        assertEquals(unoRedactForSpectator(committed), toWatcher.stateSync.state)
        assertEquals(out[0].stateSync.version, toWatcher.stateSync.version)
        assertFalse(String(toWatcher.payload, Charsets.UTF_8).let { it.contains("777123") || it.contains("777124") })
    }

    @Test
    fun `a spectator is not a seat so asking twice registers it once`() {
        val h = hostWithSecrets()
        h.transport.inject("watcher", UnoNetMessage.RequestState)
        h.transport.inject("watcher", UnoNetMessage.RequestState)
        val mark = h.transport.mark()
        h.game.callUno(1)
        assertEquals(1, h.transport.sentSince(mark).count { it.to == "watcher" })
    }

    @Test
    fun `the host re-broadcast after its own reconnect reaches the spectator too`() {
        val h = hostWithSecrets()
        h.transport.inject("watcher", UnoNetMessage.RequestState)
        val mark = h.transport.mark()
        h.transport.fireReconnected()
        val toWatcher = h.transport.sentSince(mark).filter { it.to == "watcher" }
        assertEquals(1, toWatcher.size)
        assertEquals(unoRedactForSpectator(h.game.state.value!!), toWatcher.single().stateSync.state)
    }

    // ---- spectator side ----

    @Test
    fun `a spectator asks the host for state without a seat`() {
        val s = spectator()
        assertTrue(s.game.isSpectator)
        val ask = s.transport.sent.single()
        assertEquals("p0", ask.to)
        assertEquals(UnoNetMessage.RequestState, ask.message)
        assertEquals(UNO_SPECTATOR_SENDER, ask.from)
    }

    @Test
    fun `a spectator and a host complete the state round trip with no hand visible`() {
        val hub = UnoHub()
        val hostT = hub.endpoint("p0")
        val h = UnoTestKit.node(localIndex = 0, n = 3, mode = PlayMode.ONLINE, transport = hostT, start = true)
        val specT = hub.endpoint("watcher")
        val s = spectator(transport = specT)

        val seen = s.game.state.value
        assertEquals(unoRedactForSpectator(h.game.state.value!!), seen)
        seen!!.players.forEach { p -> assertTrue(p.hand.all { it == UNO_REDACTED_CARD }) }
        assertEquals(h.game.state.value!!.players.map { it.hand.size }, seen.players.map { it.hand.size })

        // And it keeps following the host's commits.
        h.game.callUno(0)
        assertEquals(unoRedactForSpectator(h.game.state.value!!), s.game.state.value)
    }

    @Test
    fun `a spectator never mutates state or sends anything but RequestState`() {
        val hub = UnoHub()
        val hostT = hub.endpoint("p0")
        val h = UnoTestKit.node(localIndex = 0, n = 3, mode = PlayMode.ONLINE, transport = hostT, start = true)
        val specT = hub.endpoint("watcher")
        val s = spectator(transport = specT)
        val before = s.game.state.value
        val hostBefore = h.game.state.value
        val sentBefore = specT.sent.size

        val card = hostBefore!!.players[0].hand.first()
        s.game.playCard(0, card)
        s.game.drawCard(0)
        s.game.drawCard(-1)
        s.game.callUno(0)
        s.game.catchUnoFailure(0, 1)
        s.game.chooseColor(UnoColor.RED)
        s.game.resolveChallenge(true)
        s.game.jumpIn(0, card)
        s.game.keepDrawnCard(0)
        s.game.startMatch()
        s.game.startNextRound()

        assertEquals("no traffic from any action", sentBefore, specT.sent.size)
        assertEquals(before, s.game.state.value)
        assertEquals(hostBefore, h.game.state.value)
    }

    @Test
    fun `a spectator accepts state only from the host`() {
        val s = spectator()
        val forged = unoRedactForSpectator(table(3)).copy(lastAction = "forged")
        s.transport.inject("p1", UnoNetMessage.StateSync(99, forged))
        assertNull(s.game.state.value)
        s.transport.inject("p0", UnoNetMessage.StateSync(1, forged))
        assertEquals("forged", s.game.state.value!!.lastAction)
    }

    @Test
    fun `a spectator that reconnects asks again`() {
        val s = spectator()
        val mark = s.transport.mark()
        s.transport.fireReconnected()
        val out = s.transport.sentSince(mark)
        assertEquals(1, out.size)
        assertEquals(UnoNetMessage.RequestState, out.single().message)
        assertEquals("p0", out.single().to)
    }

    // ---- the host side of safety ----

    @Test
    fun `a spoofed spectator move request is ignored by the host`() {
        val h = hostWithSecrets()
        h.transport.inject("watcher", UnoNetMessage.RequestState)
        val st = h.game.state.value!!
        val mark = h.transport.mark()

        val intents = listOf(
            UnoIntentPayload.PlayCard(1, 701),
            UnoIntentPayload.DrawCard(1),
            UnoIntentPayload.CallUno(1),
            UnoIntentPayload.KeepDrawnCard(1),
            UnoIntentPayload.CatchUnoFailure(1, 0),
            UnoIntentPayload.JumpIn(1, 701),
            UnoIntentPayload.ChooseColor(UnoColor.BLUE),
            UnoIntentPayload.ResolveChallenge(accept = true)
        )
        for (intent in intents) {
            h.transport.inject("watcher", UnoNetMessage.Intent(intent))
            assertEquals("$intent from a spectator must change nothing", st, h.game.state.value)
        }
        assertTrue("and produce no traffic", h.transport.sentSince(mark).isEmpty())
    }

    @Test
    fun `a spectator leaving does not affect the match`() {
        val h = hostWithSecrets()
        h.transport.inject("watcher", UnoNetMessage.RequestState)
        val st = h.game.state.value!!
        val mark = h.transport.mark()

        h.transport.fireLeft("watcher")

        assertEquals("no 'disconnected' status line, no commit", st, h.game.state.value)
        assertTrue(h.transport.sentSince(mark).isEmpty())
        // The departed watcher is forgotten: later commits no longer address it.
        val mark2 = h.transport.mark()
        h.game.callUno(1)
        assertTrue(h.transport.sentSince(mark2).none { it.to == "watcher" })
        h.transport.fireLeft("p2")
        assertEquals("a seat leaving is still recorded", "Player 2 disconnected", h.game.state.value!!.lastAction)
    }

    @Test
    fun `the host caps how many spectators it keeps streaming to`() {
        val h = hostWithSecrets()
        for (i in 0 until UNO_MAX_SPECTATORS + 4) h.transport.inject("w$i", UnoNetMessage.RequestState)
        val mark = h.transport.mark()
        h.game.callUno(1)
        val watchers = h.transport.sentSince(mark).count { it.to!!.startsWith("w") }
        assertEquals(UNO_MAX_SPECTATORS, watchers)
    }
}
