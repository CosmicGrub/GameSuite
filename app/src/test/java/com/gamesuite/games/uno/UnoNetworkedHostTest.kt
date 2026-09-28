package com.gamesuite.games.uno

import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.games.uno.UnoTestKit.c
import com.gamesuite.games.uno.UnoTestKit.deadHand
import com.gamesuite.games.uno.UnoTestKit.seedDrawPile
import com.gamesuite.games.uno.UnoTestKit.table
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Host side (localPlayerIndex 0) of UNO's host-authoritative networking, driven through a recording
 * [UnoFakeTransport]: every outbound send is captured and inbound traffic is injected through the
 * very listeners UnoGame registers in init().
 *
 * Randomness: the deal is unseeded, so any test that needs a specific hand overwrites `state.value`
 * with a hand-built [table] and (for draws) seeds the draw pile through [UnoTestKit.seedDrawPile]. The
 * tests that use a naturally dealt game (broadcast shape, redaction) only assert properties that
 * hold for every possible deal.
 */
class UnoNetworkedHostTest {

    private val networkedModes = listOf(PlayMode.LOCAL_AD_HOC, PlayMode.ONLINE)
    private val redSeven = c(UnoColor.RED, UnoRank.SEVEN, 700)
    private val redNine = c(UnoColor.RED, UnoRank.NINE, 500)
    private val blueNine = c(UnoColor.BLUE, UnoRank.NINE, 503)

    private fun host(n: Int = 3, mode: PlayMode = PlayMode.LOCAL_AD_HOC, rules: UnoRules = UnoRules(), start: Boolean = true) =
        UnoTestKit.node(localIndex = 0, n = n, mode = mode, rules = rules, start = start)

    private fun lastVersion(t: UnoFakeTransport): Int = t.sent.last().stateSync.version

    /** Unplayable cards, plenty of them, so drawCard() never surprises a test with a playable draw. */
    private fun deadPile(count: Int = 20) = (0 until count).map { c(UnoColor.BLUE, UnoRank.NINE, 3000 + it) }

    // ---- init(): who listens, who talks ----

    /** Guards init()'s role split. The host registers message/reconnect/player-left listeners and sends
     *  nothing; a guest registers message/reconnect (NOT player-left, host-only) and sends exactly one
     *  RequestState, from itself to the host, so a late listener still gets the opening state. Loops over
     *  LOCAL_AD_HOC and ONLINE because the ONLINE fix (isNetworked) once silently didn't apply. */
    @Test
    fun `init wires listeners per role in both networked modes`() {
        for (mode in networkedModes) {
            val h = host(mode = mode, start = false)
            assertEquals("$mode host message listeners", 1, h.transport.messageListeners.size)
            assertEquals("$mode host reconnect listeners", 1, h.transport.reconnectedListeners.size)
            assertEquals("$mode host player-left listeners", 1, h.transport.leftListeners.size)
            assertTrue("$mode host must not send at init", h.transport.sent.isEmpty())

            val g = UnoTestKit.node(localIndex = 2, mode = mode)
            assertEquals(1, g.transport.messageListeners.size)
            assertEquals(1, g.transport.reconnectedListeners.size)
            assertEquals("$mode guest must not track departures", 0, g.transport.leftListeners.size)
            assertEquals("$mode guest sends exactly one message at init", 1, g.transport.sent.size)
            val req = g.transport.sent.single()
            assertEquals("p2", req.from)
            assertEquals("p0", req.to)
            assertEquals(UnoNetMessage.RequestState, req.message)
        }
    }

    /** A spectator (localPlayerIndex -1) has no id to send as: init must not crash or send anything. */
    @Test
    fun `a spectator seat sends nothing at init`() {
        val s = try {
            UnoTestKit.node(localIndex = -1)
        } catch (e: Throwable) {
            fail("a spectator must not crash init (sendToHost must bail out when the local seat has no id): $e")
            return
        }
        assertTrue(s.transport.sent.isEmpty())
    }

    // ---- Broadcast shape ----

    /** Guards "every host mutation broadcasts to every non-host seat exactly once, versioned, never to the host":
     *  after a real deal in each networked mode every guest sees the same consecutive versions 1..k,
     *  sent as the host, and the host id never appears as a recipient. */
    @Test
    fun `dealing broadcasts consecutive versions to every guest exactly once and never to the host`() {
        for (mode in networkedModes) {
            val h = host(n = 4, mode = mode)
            val sends = h.transport.sent
            assertTrue(sends.isNotEmpty())
            assertTrue("$mode: host id must never be a recipient", sends.none { it.to == "p0" })
            assertTrue("$mode: every send is addressed", sends.none { it.to == null })
            assertTrue("$mode: every send is from the host", sends.all { it.from == "p0" })

            val byRecipient = sends.groupBy { it.to }
            assertEquals(setOf<String?>("p1", "p2", "p3"), byRecipient.keys)
            val expectedVersions = (1..byRecipient.getValue("p1").size).toList()
            for ((to, msgs) in byRecipient) {
                assertEquals("$mode $to versions", expectedVersions, msgs.map { it.stateSync.version })
            }
        }
    }

    /** Each mutating entry point that changes state bumps the version by one per commit and sends one StateSync
     *  to each of the other seats (n-1, none to the host). Guards commitState's `stateVersion++` and its
     *  broadcast call together, for EVERY public mutator. The commit count is derived from the observed traffic
     *  and checked against an allowed range (jumpIn may commit once or twice: seat change, then the play), so an
     *  internal refactor of the commit granularity does not fail the test while the protocol contract (consecutive
     *  versions per guest, one StateSync per guest per commit, never to the host) stays pinned. One step per entry
     *  point so a per-method regression names itself. */
    @Test
    fun `every mutating entry point increments the version and broadcasts once per commit`() {
        class Step(val name: String, val state: UnoState, val commits: IntRange, val action: (UnoGame) -> Unit)

        val wild = c(UnoColor.WILD, UnoRank.WILD, 701)
        val wd4Top = c(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, 702)
        val redFive = c(UnoColor.RED, UnoRank.FIVE, 703)
        val steps = listOf(
            Step("callUno", table(4), 1..1) { it.callUno(3) },
            Step("playCard number", table(4, hands = mapOf(1 to listOf(redSeven) + deadHand(1))), 1..1) { it.playCard(1, redSeven) },
            Step("playCard wild", table(4, hands = mapOf(1 to listOf(wild) + deadHand(1))), 1..1) { it.playCard(1, wild) },
            Step("chooseColor", table(4).copy(awaitingColorChoice = true, discardPile = listOf(wild)), 1..1) { it.chooseColor(UnoColor.BLUE) },
            Step(
                "resolveChallenge",
                table(4, current = 2, top = wd4Top).copy(
                    awaitingChallenge = true, challengeVictimIndex = 2, challengePlayedByIndex = 1,
                    colorBeforeWildDrawFour = UnoColor.RED
                ), 1..1
            ) { it.resolveChallenge(accept = true) },
            Step("drawCard", table(4), 1..1) { it.drawCard(1) },
            Step("keepDrawnCard", table(4).copy(awaitingDrawDecision = true), 1..1) { it.keepDrawnCard(1) },
            Step(
                "catchUnoFailure",
                table(4, current = 2).let { st ->
                    st.copy(players = st.players.mapIndexed { i, p ->
                        if (i == 1) p.copy(hand = listOf(redNine), catchWindowClosesAfterPlayerIndex = 2) else p
                    })
                }, 1..1
            ) { it.catchUnoFailure(accuserIndex = 2, targetIndex = 1) },
            Step("jumpIn (jump + play)", table(4, current = 0, hands = mapOf(2 to listOf(redFive) + deadHand(2))), 1..2) { it.jumpIn(2, redFive) },
            Step("endMatch", table(4), 1..1) { it.endMatch(GameResult()) }
        )

        val h = host(n = 4, rules = UnoRules(jumpIn = true))
        var version = lastVersion(h.transport)
        for (step in steps) {
            h.game.state.value = step.state
            seedDrawPile(h.game, deadPile())
            val mark = h.transport.mark()

            step.action(h.game)

            val out = h.transport.sentSince(mark)
            assertEquals("${step.name}: sends must be a whole number of commits (3 guests each)", 0, out.size % 3)
            val commits = out.size / 3
            assertTrue("${step.name}: commits=$commits must be within ${step.commits}", commits in step.commits)
            assertTrue("${step.name}: never to host", out.none { it.to == "p0" })
            for (to in listOf("p1", "p2", "p3")) {
                assertEquals(
                    "${step.name}: versions received by $to",
                    (1..commits).map { version + it },
                    out.filter { it.to == to }.map { it.stateSync.version }
                )
            }
            version += commits
        }
    }

    /** Illegal or ignored actions must not commit: no version bump, no traffic. Guards against a
     *  "broadcast on every call" regression that would spam guests and inflate versions. */
    @Test
    fun `ignored actions emit no traffic and leave the version alone`() {
        val h = host(rules = UnoRules(jumpIn = false))
        val st = table(hands = mapOf(1 to listOf(redSeven) + deadHand(1)))
        h.game.state.value = st
        seedDrawPile(h.game, deadPile())
        val mark = h.transport.mark()

        h.game.playCard(2, deadHand(2).first())          // not their turn
        h.game.playCard(1, blueNine)                      // card not in hand
        h.game.drawCard(2)                                // not current
        h.game.keepDrawnCard(1)                           // nothing pending
        h.game.chooseColor(UnoColor.RED)                  // no colour choice pending
        h.game.resolveChallenge(true)                     // no challenge pending
        h.game.jumpIn(2, redSeven)                        // house rule off
        h.game.startNextRound()                           // round not over

        assertTrue(h.transport.sentSince(mark).isEmpty())
        assertEquals(st, h.game.state.value)
    }

    // ---- Redaction ----

    /** Guards redaction end to end on a naturally dealt game (any deal): for EVERY guest the last StateSync equals
     *  the host's true state with every other seat's hand replaced by same-size sentinel placeholders
     *  (instanceId -1, WILD/WILD) and everything else untouched. Kills mutations that skip redaction, hide the
     *  recipient's own hand, drop non-hand fields, or change the placeholder shape. Also pins that broadcasting
     *  copies rather than mutates: the host's own state keeps every real hand (no sentinel anywhere). */
    @Test
    fun `each guest receives a state where only its own hand is real`() {
        for (mode in networkedModes) {
            val h = host(n = 4, mode = mode)
            val truth = h.game.state.value!!
            assertTrue(
                "$mode: broadcasting must not redact the host's own state",
                truth.players.flatMap { it.hand }.none { it.instanceId == -1 }
            )
            for (i in 1..3) {
                val msg = h.transport.sent.last { it.to == "p$i" }.stateSync
                UnoTestKit.assertRedactedFor(msg, truth, "p$i")
                assertEquals("own hand real for p$i", truth.players[i].hand, msg.state.players[i].hand)
                assertTrue("p$i's real hand contains no sentinel", msg.state.players[i].hand.none { it.instanceId == -1 })
                for (j in 0..3) {
                    if (j == i) continue
                    val seen = msg.state.players[j].hand
                    assertEquals("hand size of seat $j visible to p$i", truth.players[j].hand.size, seen.size)
                    assertTrue(seen.all { it == UnoTestKit.placeholder })
                }
            }
            val hostHands = h.game.state.value!!.players.map { it.hand }
            assertEquals("$mode: broadcasting must not mutate the host's own state", truth, h.game.state.value)
            assertTrue("$mode: the host keeps every seat's real hand", hostHands.flatten().none { it.instanceId == -1 })
            assertTrue("$mode: hands are non-empty after a real deal", hostHands.all { it.isNotEmpty() })
        }
    }

    /** Byte-level leak check: uniquely identifiable cards in seat 2's hand (a secret card plus its whole dead hand)
     *  must appear in the bytes sent to seat 2 and in NO byte sent to seat 1, and vice versa (guards leaking via a
     *  field redaction forgot, e.g. a second copy of the hand). The needles are built from the REAL encoder, and
     *  each is first asserted PRESENT in the recipient's own payload (positive control), so a change of JSON shape
     *  cannot turn the negative assertions vacuous. The decoded payloads are also checked structurally: no card
     *  with any of the other seat's instance ids exists anywhere in the state a guest received. */
    @Test
    fun `serialized payloads never contain another seat's real card`() {
        val h = host()
        val secretForP2 = c(UnoColor.YELLOW, UnoRank.NINE, 777123)
        val secretForP1 = c(UnoColor.GREEN, UnoRank.EIGHT, 888123)
        val realP1 = listOf(secretForP1) + deadHand(1)
        val realP2 = listOf(secretForP2) + deadHand(2)
        h.game.state.value = table(hands = mapOf(1 to realP1, 2 to realP2))
        val mark = h.transport.mark()

        h.game.callUno(0)

        val out = h.transport.sentSince(mark)
        val toP1 = String(out.single { it.to == "p1" }.payload, Charsets.UTF_8)
        val toP2 = String(out.single { it.to == "p2" }.payload, Charsets.UTF_8)

        for (card in realP2) {
            val needle = Json.encodeToString(UnoCard.serializer(), card)
            assertTrue("positive control: seat 2's own payload must contain its card $needle", toP2.contains(needle))
            assertFalse("seat 1 must not receive seat 2's card $needle", toP1.contains(needle))
        }
        for (card in realP1) {
            val needle = Json.encodeToString(UnoCard.serializer(), card)
            assertTrue("positive control: seat 1's own payload must contain its card $needle", toP1.contains(needle))
            assertFalse("seat 2 must not receive seat 1's card $needle", toP2.contains(needle))
        }

        val idsP1 = realP1.map { it.instanceId }.toSet()
        val idsP2 = realP2.map { it.instanceId }.toSet()
        fun cardIds(payload: ByteArray): List<Int> =
            (Json.decodeFromString<UnoNetMessage>(String(payload, Charsets.UTF_8)) as UnoNetMessage.StateSync)
                .state.players.flatMap { it.hand }.map { it.instanceId }
        val seenByP1 = cardIds(out.single { it.to == "p1" }.payload)
        val seenByP2 = cardIds(out.single { it.to == "p2" }.payload)
        assertTrue("positive control: decoded p2 view holds its own ids", seenByP2.containsAll(idsP2))
        assertTrue("positive control: decoded p1 view holds its own ids", seenByP1.containsAll(idsP1))
        assertTrue("decoded p1 view must contain none of seat 2's ids: $seenByP1", seenByP1.none { it in idsP2 })
        assertTrue("decoded p2 view must contain none of seat 1's ids: $seenByP2", seenByP2.none { it in idsP1 })
    }

    // ---- Spoof protection ----

    private class SpoofCase(
        val name: String,
        val rules: UnoRules = UnoRules(),
        val state: UnoState,
        val intent: UnoIntentPayload,
        val spoofer: String,
        val rightful: String
    )

    private fun spoofCases(): List<SpoofCase> {
        val wild = c(UnoColor.WILD, UnoRank.WILD, 701)
        val wd4 = c(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, 702)
        val redFive = c(UnoColor.RED, UnoRank.FIVE, 703)
        return listOf(
            SpoofCase("PlayCard", state = table(hands = mapOf(1 to listOf(redSeven) + deadHand(1))),
                intent = UnoIntentPayload.PlayCard(1, 700), spoofer = "p2", rightful = "p1"),
            SpoofCase("JumpIn", rules = UnoRules(jumpIn = true), state = table(current = 0, hands = mapOf(2 to listOf(redFive) + deadHand(2))),
                intent = UnoIntentPayload.JumpIn(2, 703), spoofer = "p1", rightful = "p2"),
            SpoofCase("DrawCard", state = table(), intent = UnoIntentPayload.DrawCard(1), spoofer = "p2", rightful = "p1"),
            SpoofCase("CallUno", state = table(), intent = UnoIntentPayload.CallUno(2), spoofer = "p1", rightful = "p2"),
            SpoofCase("KeepDrawnCard", state = table().copy(awaitingDrawDecision = true),
                intent = UnoIntentPayload.KeepDrawnCard(1), spoofer = "p2", rightful = "p1"),
            SpoofCase(
                "CatchUnoFailure (accuser spoofed)",
                state = table(current = 2).let { st ->
                    st.copy(players = st.players.mapIndexed { i, p ->
                        if (i == 1) p.copy(hand = listOf(redNine), catchWindowClosesAfterPlayerIndex = 2) else p
                    })
                },
                intent = UnoIntentPayload.CatchUnoFailure(accuserIndex = 2, targetIndex = 1), spoofer = "p1", rightful = "p2"
            ),
            SpoofCase("ChooseColor (acts for the current seat)", state = table().copy(awaitingColorChoice = true, discardPile = listOf(wild)),
                intent = UnoIntentPayload.ChooseColor(UnoColor.BLUE), spoofer = "p2", rightful = "p1"),
            SpoofCase(
                "ResolveChallenge (acts for the current seat)",
                state = table(top = wd4).copy(
                    awaitingChallenge = true, challengeVictimIndex = 1, challengePlayedByIndex = 0,
                    colorBeforeWildDrawFour = UnoColor.RED
                ),
                intent = UnoIntentPayload.ResolveChallenge(accept = true), spoofer = "p2", rightful = "p1"
            )
        )
    }

    /** Guards applyIntent's sender check for EVERY intent kind: a message whose real (transport-level) sender is
     *  not the seat it acts for -- another guest, the host's id, or an unknown id -- is dropped silently (state
     *  identical, nothing broadcast), while the SAME intent from the rightful sender demonstrably applies
     *  (state changes, n-1 broadcasts). The positive control makes the negative assertion meaningful. */
    @Test
    fun `an intent from a sender that does not own the acting seat is silently dropped`() {
        for (case in spoofCases()) {
            val h = host(rules = case.rules)
            h.game.state.value = case.state
            seedDrawPile(h.game, deadPile())

            for (spoofer in listOf(case.spoofer, "p0", "mallory")) {
                val mark = h.transport.mark()
                h.transport.inject(spoofer, UnoNetMessage.Intent(case.intent))
                assertEquals("${case.name}: spoofed by $spoofer must not change state", case.state, h.game.state.value)
                assertTrue("${case.name}: spoofed by $spoofer must not broadcast", h.transport.sentSince(mark).isEmpty())
            }

            val mark = h.transport.mark()
            h.transport.inject(case.rightful, UnoNetMessage.Intent(case.intent))
            assertNotEquals("${case.name}: the rightful sender's intent must apply", case.state, h.game.state.value)
            assertTrue("${case.name}: rightful intent must broadcast", h.transport.sentSince(mark).size >= 2)
        }
    }

    /** Out-of-range / negative seat indexes and card ids the sender does not hold are rejected without a crash
     *  (guards applyIntent's `getOrNull(actingIndex)` and findCardInHand's `firstOrNull`: plain indexing / `first`
     *  would throw). Each injection is wrapped so a crash surfaces as a named assertion failure, not a bare
     *  exception, and state plus traffic stay untouched. */
    @Test
    fun `out of range seats and cards the sender does not hold are rejected`() {
        val h = host(rules = UnoRules(jumpIn = true))
        val st = table(hands = mapOf(1 to listOf(redSeven) + deadHand(1), 2 to listOf(c(UnoColor.RED, UnoRank.FIVE, 703)) + deadHand(2)))
        h.game.state.value = st
        seedDrawPile(h.game, deadPile())
        val mark = h.transport.mark()

        fun mustBeIgnored(label: String, intent: UnoIntentPayload) {
            try {
                h.transport.inject("p1", UnoNetMessage.Intent(intent))
            } catch (e: Throwable) {
                fail("$label must be ignored, but threw $e")
            }
        }
        mustBeIgnored("PlayCard for seat 7", UnoIntentPayload.PlayCard(7, 700))
        mustBeIgnored("CallUno for seat -1", UnoIntentPayload.CallUno(-1))
        mustBeIgnored("CatchUnoFailure accuser 99", UnoIntentPayload.CatchUnoFailure(99, 0))
        // A GENUINE accuser (seat 1, sent by p1) naming a target seat that does not exist: passes the
        // sender check, so only catchUnoFailure's own bounds check keeps the host from throwing.
        mustBeIgnored("CatchUnoFailure genuine accuser, target 99", UnoIntentPayload.CatchUnoFailure(1, 99))
        mustBeIgnored("CatchUnoFailure genuine accuser, target -3", UnoIntentPayload.CatchUnoFailure(1, -3))
        mustBeIgnored("PlayCard of a nonexistent card", UnoIntentPayload.PlayCard(1, 99999))
        mustBeIgnored("PlayCard of a card in seat 2's hand", UnoIntentPayload.PlayCard(1, 703))
        mustBeIgnored("JumpIn of a card in seat 2's hand", UnoIntentPayload.JumpIn(1, 703))
        mustBeIgnored("JumpIn of a nonexistent card", UnoIntentPayload.JumpIn(1, 99999))

        assertEquals(st, h.game.state.value)
        assertTrue(h.transport.sentSince(mark).isEmpty())
    }

    /** Turn order still binds a GENUINE sender: seat 2 acting as itself out of turn is refused by the engine
     *  (the sender check alone would let it through). */
    @Test
    fun `a genuine guest cannot draw or play out of turn`() {
        val h = host()
        val st = table(current = 1, hands = mapOf(2 to listOf(redSeven) + deadHand(2)))
        h.game.state.value = st
        seedDrawPile(h.game, deadPile())
        val mark = h.transport.mark()

        h.transport.inject("p2", UnoNetMessage.Intent(UnoIntentPayload.DrawCard(2)))
        h.transport.inject("p2", UnoNetMessage.Intent(UnoIntentPayload.PlayCard(2, 700)))

        assertEquals(st, h.game.state.value)
        assertTrue(h.transport.sentSince(mark).isEmpty())
    }

    /** A replayed PlayCard (same bytes delivered twice) applies once: the card has left the hand. */
    @Test
    fun `a replayed PlayCard intent applies only once`() {
        val h = host()
        h.game.state.value = table(current = 1, hands = mapOf(1 to listOf(redSeven) + deadHand(1)))
        val play = UnoNetMessage.Intent(UnoIntentPayload.PlayCard(1, 700))

        h.transport.inject("p1", play)
        val afterFirst = h.game.state.value!!
        val mark = h.transport.mark()
        h.transport.inject("p1", play)

        assertEquals(afterFirst, h.game.state.value)
        assertTrue(h.transport.sentSince(mark).isEmpty())
        assertEquals(redSeven, afterFirst.topCard)
        assertEquals(2, afterFirst.currentPlayerIndex)
    }

    /** Full Wild Draw Four challenge over the wire: ChooseColor/ResolveChallenge carry no seat, so the host
     *  validates them against the seat its own state says is awaiting input. Spoofs at each stage are dropped;
     *  then the genuine victim challenges a LEGAL wild-four (the player had no red) and draws 6. */
    @Test
    fun `wild draw four challenge flow over the network validates the awaiting seat at each step`() {
        val wd4 = c(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, 601)
        val h = host()
        h.game.state.value = table(current = 1, hands = mapOf(1 to listOf(wd4) + deadHand(1)))
        seedDrawPile(h.game, deadPile())

        h.transport.inject("p1", UnoNetMessage.Intent(UnoIntentPayload.PlayCard(1, 601)))
        assertTrue(h.game.state.value!!.awaitingColorChoice)

        val beforeSpoof = h.game.state.value
        h.transport.inject("p2", UnoNetMessage.Intent(UnoIntentPayload.ChooseColor(UnoColor.YELLOW)))
        assertEquals("only the seat that played the wild may pick its colour", beforeSpoof, h.game.state.value)

        h.transport.inject("p1", UnoNetMessage.Intent(UnoIntentPayload.ChooseColor(UnoColor.BLUE)))
        var s = h.game.state.value!!
        assertTrue(s.awaitingChallenge)
        assertEquals(2, s.challengeVictimIndex)
        assertEquals(2, s.currentPlayerIndex)
        assertEquals(UnoColor.BLUE, s.currentColor)

        val beforeSpoof2 = h.game.state.value
        h.transport.inject("p1", UnoNetMessage.Intent(UnoIntentPayload.ResolveChallenge(accept = true)))
        assertEquals("the player who PLAYED the wild-four must not resolve its challenge", beforeSpoof2, h.game.state.value)

        h.transport.inject("p2", UnoNetMessage.Intent(UnoIntentPayload.ResolveChallenge(accept = false)))
        s = h.game.state.value!!
        assertFalse(s.awaitingChallenge)
        assertEquals("challenge failed: victim (seat 2) draws 6", 3 + 6, s.players[2].hand.size)
        assertEquals("then play passes beyond the victim", 0, s.currentPlayerIndex)
    }

    // ---- Duplicate DrawCard (networked path of commit a0879b2) ----

    /** Deterministic networked regression for a0879b2: seat 1 (genuine id) draws a PLAYABLE card, the same
     *  DrawCard bytes arrive a second time. Only one card may be added, the decision stays pending, and the
     *  duplicate produces no broadcast. With the awaitingDrawDecision guard removed the duplicate would take
     *  the second (also playable) card: hand 5, pile 0. */
    @Test
    fun `a duplicate DrawCard intent after a playable draw adds only one card`() {
        val h = host()
        h.game.state.value = table(current = 1)
        seedDrawPile(h.game, listOf(redNine, c(UnoColor.RED, UnoRank.TWO, 504), blueNine))
        val draw = UnoNetMessage.Intent(UnoIntentPayload.DrawCard(1))

        h.transport.inject("p1", draw)
        val afterFirst = h.game.state.value!!
        assertTrue(afterFirst.awaitingDrawDecision)
        assertEquals(4, afterFirst.players[1].hand.size)
        val mark = h.transport.mark()

        h.transport.inject("p1", draw)

        val after = h.game.state.value!!
        assertEquals("second identical DrawCard must add nothing", 4, after.players[1].hand.size)
        assertEquals(afterFirst, after)
        assertEquals(2, afterFirst.drawPileSize)
        assertTrue("no broadcast for an ignored duplicate", h.transport.sentSince(mark).isEmpty())
    }

    // ---- RequestState ----

    /** Guards RequestState handling on the host: the reply goes to the requester only, is redacted for the
     *  requester, carries the CURRENT version (a reply is not a mutation: no bump), and a second requester
     *  is answered independently. */
    @Test
    fun `RequestState is answered to the requester only, redacted, without bumping the version`() {
        val h = host(n = 4)
        val truth = h.game.state.value!!
        val version = lastVersion(h.transport)

        for (requester in listOf("p3", "p1")) {
            val mark = h.transport.mark()
            h.transport.inject(requester, UnoNetMessage.RequestState)
            val out = h.transport.sentSince(mark)
            assertEquals("$requester: exactly one reply", 1, out.size)
            assertEquals(requester, out.single().to)
            assertEquals("p0", out.single().from)
            assertEquals("a reply is not a new version", version, out.single().stateSync.version)
            UnoTestKit.assertRedactedFor(out.single().stateSync, truth, requester)
        }

        h.game.callUno(0)
        assertEquals("the next real mutation continues from the same counter", version + 1, lastVersion(h.transport))
    }

    /** The highest-value hidden-information case: an id that is NOT in context.players (a spectator, or a rogue
     *  client "mallory") asks for the state. The host still answers, but only that requester, at the current
     *  version, and the reply is FULLY redacted: every seat's hand is same-size placeholders, no real card
     *  reaches it. Guards against a recipient-lookup failure falling back to the raw (unredacted) state. */
    @Test
    fun `RequestState from an id outside the player list gets a fully redacted reply`() {
        val h = host(n = 4)
        val secret = c(UnoColor.YELLOW, UnoRank.NINE, 777123)
        h.game.state.value = table(4, hands = mapOf(1 to listOf(secret) + deadHand(1)))
        h.game.callUno(0) // real commit so the counter and the broadcast reflect the hand-built table
        val committed = h.game.state.value!!
        val version = lastVersion(h.transport)
        val mark = h.transport.mark()

        h.transport.inject("mallory", UnoNetMessage.RequestState)

        val out = h.transport.sentSince(mark)
        assertEquals("exactly one reply", 1, out.size)
        assertEquals("mallory", out.single().to)
        assertEquals("p0", out.single().from)
        assertEquals("a reply is not a new version", version, out.single().stateSync.version)
        UnoTestKit.assertRedactedFor(out.single().stateSync, committed, "mallory")
        for ((i, p) in out.single().stateSync.state.players.withIndex()) {
            assertEquals("seat $i hand size", committed.players[i].hand.size, p.hand.size)
            assertTrue("seat $i hand must be all placeholders for an outsider", p.hand.all { it == UnoTestKit.placeholder })
        }
        val raw = String(out.single().payload, Charsets.UTF_8)
        assertFalse("no real card id may leak to an outsider", raw.contains("777123"))
        assertEquals("the reply must not touch host state", committed, h.game.state.value)
    }

    /** ...and an outsider's normal intents (every kind that a real seat could send, claiming the current seat's
     *  index, plus the seat-less ChooseColor/ResolveChallenge) are dropped: no state change, no traffic. */
    @Test
    fun `intents from an id outside the player list are dropped`() {
        val h = host(n = 4)
        val st = table(4, current = 1, hands = mapOf(1 to listOf(redSeven) + deadHand(1)))
        h.game.state.value = st
        seedDrawPile(h.game, deadPile())
        val mark = h.transport.mark()

        val intents = listOf(
            UnoIntentPayload.PlayCard(1, 700),
            UnoIntentPayload.DrawCard(1),
            UnoIntentPayload.CallUno(1),
            UnoIntentPayload.KeepDrawnCard(1),
            UnoIntentPayload.ChooseColor(UnoColor.BLUE),
            UnoIntentPayload.ResolveChallenge(accept = true)
        )
        for (intent in intents) {
            try {
                h.transport.inject("mallory", UnoNetMessage.Intent(intent))
            } catch (e: Throwable) {
                fail("$intent from an unknown sender must be ignored, but threw $e")
            }
            assertEquals("$intent from mallory must not change state", st, h.game.state.value)
        }
        assertTrue(h.transport.sentSince(mark).isEmpty())
    }

    /** Before the deal there is no state: a RequestState must not crash or send anything. */
    @Test
    fun `RequestState before any state exists sends nothing`() {
        val h = host(start = false)
        h.transport.inject("p1", UnoNetMessage.RequestState)
        assertTrue(h.transport.sent.isEmpty())
    }

    // ---- Malformed input ----

    /** Guards handleNetworkMessage's decode try/catch: every kind of garbage (empty, non-JSON, wrong shape,
     *  unknown discriminator, truncated, corrupted field type, invalid UTF-8) is ignored with no exception,
     *  no state change, no traffic -- and the listener still works for the next good message. */
    @Test
    fun `malformed payloads are ignored without crashing and do not break later messages`() {
        val h = host()
        val st = table(hands = mapOf(1 to listOf(redSeven) + deadHand(1)))
        h.game.state.value = st
        val valid = String(UnoTestKit.encodeUnoMessage(UnoNetMessage.Intent(UnoIntentPayload.PlayCard(1, 700))), Charsets.UTF_8)
        val corruptedType = valid.replace("700", "\"seven-hundred\"")
        val unknownDiscriminator = valid.replace("PlayCard", "Bogus")
        assertNotEquals(valid, corruptedType)
        assertNotEquals(valid, unknownDiscriminator)

        val garbage: List<ByteArray> = listOf(
            ByteArray(0),
            "not json at all".toByteArray(),
            "{}".toByteArray(),
            "[]".toByteArray(),
            "null".toByteArray(),
            "12345".toByteArray(),
            "{\"type\":\"nope\"}".toByteArray(),
            valid.dropLast(12).toByteArray(),
            corruptedType.toByteArray(),
            unknownDiscriminator.toByteArray(),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x80.toByte()),
            ByteArray(4096) { (it * 31).toByte() }
        )
        val mark = h.transport.mark()
        for ((i, bytes) in garbage.withIndex()) {
            try {
                h.transport.inject("p1", bytes)
            } catch (e: Throwable) {
                fail("garbage #$i must be ignored, but threw $e")
            }
            assertEquals("garbage #$i changed state", st, h.game.state.value)
        }
        assertTrue(h.transport.sentSince(mark).isEmpty())

        h.transport.inject("p1", valid.toByteArray())
        assertEquals("a valid message after garbage must still be processed", redSeven, h.game.state.value!!.topCard)
    }

    // ---- Departures and reconnects ----

    /** Guards the host-only onPlayerLeft hook: the departed seat's DISPLAY NAME (not id) is written to
     *  lastAction, the change is committed (version bump + broadcast so guests see it), and everything else in
     *  the state is untouched. */
    @Test
    fun `a player leaving is recorded in lastAction and broadcast`() {
        val h = host(n = 3)
        val before = h.game.state.value!!
        val v = lastVersion(h.transport)
        val mark = h.transport.mark()

        h.transport.fireLeft("p2")

        val s = h.game.state.value!!
        assertEquals("Player 2 disconnected", s.lastAction)
        assertEquals(before.copy(lastAction = "Player 2 disconnected"), s)
        val out = h.transport.sentSince(mark)
        assertEquals(listOf("p1", "p2"), out.map { it.to })
        assertTrue(out.all { it.stateSync.version == v + 1 && it.stateSync.state.lastAction == "Player 2 disconnected" })
    }

    /** An unknown id falls back to the raw id; before the deal nothing happens (no crash, no traffic). */
    @Test
    fun `an unknown leaver falls back to its id and a leave before the deal is ignored`() {
        val h = host()
        h.transport.fireLeft("ghost")
        assertEquals("ghost disconnected", h.game.state.value!!.lastAction)

        val pre = host(start = false)
        pre.transport.fireLeft("p1")
        assertTrue(pre.transport.sent.isEmpty())
        assertEquals(null, pre.game.state.value)
    }

    /** Guards the host branch of onReconnected: it proactively re-broadcasts the CURRENT state (same version,
     *  n-1 recipients, redacted per recipient, nothing to the host) because a guest whose link stayed up would
     *  never think to ask. Not a mutation, so no version bump. */
    @Test
    fun `host reconnect re-broadcasts the current state to every guest`() {
        val h = host(n = 4)
        val truth = h.game.state.value!!
        val v = lastVersion(h.transport)
        val mark = h.transport.mark()

        h.transport.fireReconnected()

        val out = h.transport.sentSince(mark)
        assertEquals(listOf("p1", "p2", "p3"), out.map { it.to })
        assertTrue(out.all { it.from == "p0" && it.stateSync.version == v })
        for (m in out) UnoTestKit.assertRedactedFor(m.stateSync, truth, m.to!!)
    }

    /** Reconnect before there is any state: nothing to broadcast, must not crash. */
    @Test
    fun `host reconnect with no state sends nothing`() {
        val h = host(start = false)
        h.transport.fireReconnected()
        assertTrue(h.transport.sent.isEmpty())
    }

    // ---- Host rounds ----

    /** startNextRound on the host redeals into round 2, carrying scores, and broadcasts. */
    @Test
    fun `host startNextRound redeals with carried scores and broadcasts`() {
        val h = host()
        val over = h.game.state.value!!.copy(roundOver = true, cumulativeScores = mapOf("p1" to 42))
        h.game.state.value = over
        val mark = h.transport.mark()

        h.game.startNextRound()

        val s = h.game.state.value!!
        assertEquals(2, s.roundNumber)
        assertEquals(mapOf("p1" to 42), s.cumulativeScores)
        assertFalse(s.roundOver)
        val out = h.transport.sentSince(mark)
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.stateSync.state.roundNumber == 2 })
        assertNotNull(out.firstOrNull { it.to == "p1" })
    }

    // ---- Regression for an engine bug found by this suite (fixed together with it) ----

    /**
     * UNO-BUG-D (fixed; canonical networked regression, the local one is in UnoDrawDecisionTest):
     * over the network, seat 1 opens a draw decision, then ANY genuine guest sends a CallUno for
     * itself. callUno() has no turn/decision check and commitState() used to reset
     * awaitingDrawDecision on every commit, so seat 1's pending decision was wiped and a second
     * DrawCard from seat 1 was accepted -- a second draw in one turn, the hole a0879b2 was meant to
     * close. callUno now passes the flag through.
     */
    @Test
    fun `an unrelated guest CallUno must not re-open a double draw`() {
        val h = host()
        h.game.state.value = table(current = 1)
        seedDrawPile(h.game, listOf(redNine, c(UnoColor.RED, UnoRank.TWO, 504), blueNine))
        h.transport.inject("p1", UnoNetMessage.Intent(UnoIntentPayload.DrawCard(1)))
        h.transport.inject("p2", UnoNetMessage.Intent(UnoIntentPayload.CallUno(2)))
        h.transport.inject("p1", UnoNetMessage.Intent(UnoIntentPayload.DrawCard(1)))
        assertEquals("seat 1 must still hold only one drawn card", 4, h.game.state.value!!.players[1].hand.size)
    }

    /** UNO-BUG-D (departure side): the host's onPlayerLeft lastAction commit is a non-turn commit and
     *  must keep the drawer's pending decision, or a disconnect elsewhere re-opens a double draw. */
    @Test
    fun `a player leaving must not re-open a double draw`() {
        val h = host()
        h.game.state.value = table(current = 1)
        seedDrawPile(h.game, listOf(redNine, c(UnoColor.RED, UnoRank.TWO, 504), blueNine))
        h.transport.inject("p1", UnoNetMessage.Intent(UnoIntentPayload.DrawCard(1)))
        assertTrue(h.game.state.value!!.awaitingDrawDecision)
        h.transport.fireLeft("p2")
        assertTrue("the decision must survive the disconnect notice", h.game.state.value!!.awaitingDrawDecision)
        h.transport.inject("p1", UnoNetMessage.Intent(UnoIntentPayload.DrawCard(1)))
        assertEquals("seat 1 must still hold only one drawn card", 4, h.game.state.value!!.players[1].hand.size)
    }
}
