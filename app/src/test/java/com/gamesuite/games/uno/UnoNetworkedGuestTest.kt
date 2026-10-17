package com.gamesuite.games.uno

import com.gamesuite.core.PlayMode
import com.gamesuite.games.uno.UnoTestKit.c
import com.gamesuite.games.uno.UnoTestKit.deadHand
import com.gamesuite.games.uno.UnoTestKit.table
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Guest side (localPlayerIndex != 0) of UNO's host-authoritative networking. A guest is a pure renderer:
 * it applies the host's StateSync (dropping stale/duplicate versions), forwards every local action as
 * exactly one Intent to the host, and never mutates or answers anything itself.
 */
class UnoNetworkedGuestTest {

    private val networkedModes = listOf(PlayMode.LOCAL_AD_HOC, PlayMode.ONLINE)
    private val redSeven = c(UnoColor.RED, UnoRank.SEVEN, 700)
    private val redFive = c(UnoColor.RED, UnoRank.FIVE, 703)

    private fun guest(index: Int = 1, mode: PlayMode = PlayMode.LOCAL_AD_HOC, rules: UnoRules = UnoRules()) =
        UnoTestKit.node(localIndex = index, n = 3, mode = mode, rules = rules)

    /** A state as the host would send it to [viewer]. */
    private fun viewFor(viewer: String, truth: UnoState) = UnoTestKit.expectedRedaction(truth, viewer)

    private fun sync(version: Int, state: UnoState) = UnoNetMessage.StateSync(version, state)

    private fun labelled(label: String) = table().copy(lastAction = label)

    // ---- Applying StateSync ----

    /** Guards the basic render contract: a guest starts with no state, adopts the host's StateSync verbatim
     *  (its own hand real, others' placeholders preserved), and sends nothing in response. */
    @Test
    fun `a guest adopts the host's StateSync verbatim and stays silent`() {
        for (mode in networkedModes) {
            val g = guest(mode = mode)
            assertNull(g.game.state.value)
            val mark = g.transport.mark()
            val truth = table(hands = mapOf(1 to listOf(redSeven) + deadHand(1)))
            val mine = viewFor("p1", truth)

            g.transport.inject("p0", sync(1, mine))

            assertEquals(mine, g.game.state.value)
            assertEquals(listOf(redSeven) + deadHand(1), g.game.state.value!!.players[1].hand)
            assertTrue(g.game.state.value!!.players[2].hand.all { it.instanceId == -1 })
            assertTrue(g.transport.sentSince(mark).isEmpty())
        }
    }

    /** Guards applyRemoteState's `version <= lastAppliedRemoteVersion` check with an out-of-order,
     *  duplicate-version and zero-version sequence: only strictly newer versions are applied, and the
     *  high-water mark advances only on applied messages (v4 after v5 is stale even though v3 was skipped). */
    @Test
    fun `stale, duplicate and out-of-order versions are ignored`() {
        val g = guest()
        val steps = listOf(
            5 to "v5" to true,
            3 to "stale v3" to false,
            5 to "duplicate v5, different content" to false,
            0 to "v0" to false,
            4 to "v4 after v5" to false,
            6 to "v6" to true,
            2 to "v2 after v6" to false,
            6 to "duplicate v6" to false,
            7 to "v7" to true
        )
        var expected: String? = null
        for ((pair, applied) in steps) {
            val (version, label) = pair
            g.transport.inject("p0", sync(version, labelled(label)))
            if (applied) expected = label
            assertEquals("after delivering '$label'", expected, g.game.state.value?.lastAction)
        }
        assertEquals("v7", g.game.state.value!!.lastAction)
    }

    /** Version 1 (the host's very first commit) must be accepted from a fresh guest: guards the initial
     *  high-water mark from being set too high (e.g. 1) and locking the guest out of the opening state. */
    @Test
    fun `the host's first version is applied by a fresh guest`() {
        val g = guest()
        g.transport.inject("p0", sync(1, labelled("first")))
        assertEquals("first", g.game.state.value!!.lastAction)
    }

    /** A guest that reconnects and gets a state at the SAME version it already holds ignores it (idempotent
     *  re-broadcast), but adopts a newer one. */
    @Test
    fun `a re-sent identical version is harmless and a newer one still applies`() {
        val g = guest()
        g.transport.inject("p0", sync(3, labelled("A")))
        g.transport.inject("p0", sync(3, labelled("A")))
        assertEquals("A", g.game.state.value!!.lastAction)
        g.transport.inject("p0", sync(4, labelled("B")))
        assertEquals("B", g.game.state.value!!.lastAction)
    }

    // ---- Guest actions become exactly one Intent ----

    private class ActionCase(
        val name: String,
        val rules: UnoRules = UnoRules(),
        val state: UnoState,
        val expected: UnoIntentPayload,
        val act: (UnoGame) -> Unit
    )

    private fun actionCases(): List<ActionCase> {
        val wild = c(UnoColor.WILD, UnoRank.WILD, 701)
        val wd4 = c(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, 702)
        return listOf(
            ActionCase("playCard", state = table(hands = mapOf(1 to listOf(redSeven) + deadHand(1))),
                expected = UnoIntentPayload.PlayCard(1, 700)) { it.playCard(1, redSeven) },
            ActionCase("drawCard", state = table(), expected = UnoIntentPayload.DrawCard(1)) { it.drawCard(1) },
            ActionCase("chooseColor", state = table().copy(awaitingColorChoice = true, discardPile = listOf(wild)),
                expected = UnoIntentPayload.ChooseColor(UnoColor.GREEN)) { it.chooseColor(UnoColor.GREEN) },
            ActionCase(
                "resolveChallenge",
                state = table(top = wd4).copy(
                    awaitingChallenge = true, challengeVictimIndex = 1, challengePlayedByIndex = 0,
                    colorBeforeWildDrawFour = UnoColor.RED
                ),
                expected = UnoIntentPayload.ResolveChallenge(false)
            ) { it.resolveChallenge(false) },
            ActionCase("keepDrawnCard", state = table().copy(awaitingDrawDecision = true),
                expected = UnoIntentPayload.KeepDrawnCard(1)) { it.keepDrawnCard(1) },
            ActionCase("callUno", state = table(), expected = UnoIntentPayload.CallUno(1)) { it.callUno(1) },
            ActionCase(
                "catchUnoFailure",
                state = table(current = 2).let { st ->
                    st.copy(players = st.players.mapIndexed { i, p ->
                        if (i == 2) p.copy(hand = listOf(redSeven), catchWindowClosesAfterPlayerIndex = 2) else p
                    })
                },
                expected = UnoIntentPayload.CatchUnoFailure(1, 2)
            ) { it.catchUnoFailure(1, 2) },
            ActionCase("jumpIn", rules = UnoRules(jumpIn = true),
                state = table(current = 0, hands = mapOf(1 to listOf(redFive) + deadHand(1))),
                expected = UnoIntentPayload.JumpIn(1, 703)) { it.jumpIn(1, redFive) }
        )
    }

    /** Guards the `isNetworked && !isHost -> sendIntent; return` prologue of ALL eight player actions. For each,
     *  on a guest whose local state is primed so that running the action locally WOULD change something:
     *  exactly one message goes out (from the guest's own id to the host's), it decodes to the expected Intent,
     *  and the guest's own state is byte-for-byte unchanged. A prologue missing its `return` (falls through and
     *  also mutates locally) or missing entirely fails the state assertion; a doubled send fails the count. */
    @Test
    fun `every guest action sends exactly one Intent to the host and changes nothing locally`() {
        for (mode in networkedModes) for (case in actionCases()) {
            val g = guest(mode = mode, rules = case.rules)
            g.game.state.value = case.state
            val mark = g.transport.mark()

            case.act(g.game)

            val out = g.transport.sentSince(mark)
            assertEquals("$mode ${case.name}: number of sends", 1, out.size)
            assertEquals("${case.name}: sender", "p1", out.single().from)
            assertEquals("${case.name}: recipient", "p0", out.single().to)
            assertEquals("${case.name}: payload", UnoNetMessage.Intent(case.expected), out.single().message)
            assertEquals("$mode ${case.name}: guest state must not change", case.state, g.game.state.value)
        }
    }

    /** A guest forwards what the UI asked verbatim (it does not police seat indexes -- the host does), so a
     *  tampered client's claimed seat reaches the host untouched. Guards against a guest that "helpfully"
     *  rewrites the index (which would mask spoof attempts from the host's sender check). */
    @Test
    fun `a guest forwards a claimed seat verbatim and the transport sender stays its own id`() {
        val g = guest(index = 1)
        g.game.state.value = table()
        val mark = g.transport.mark()
        g.game.drawCard(2)
        val sent = g.transport.sentSince(mark).single()
        assertEquals("p1", sent.from)
        assertEquals(UnoNetMessage.Intent(UnoIntentPayload.DrawCard(2)), sent.message)
    }

    /** A spectator has no seat id: its actions are dropped without a crash or a send. */
    @Test
    fun `a spectator's actions send nothing`() {
        val s = try {
            UnoTestKit.node(localIndex = -1)
        } catch (e: Throwable) {
            fail("a spectator must not crash init: $e")
            return
        }
        s.game.state.value = table()
        val actions = listOf<Pair<String, () -> Unit>>(
            "drawCard" to { s.game.drawCard(1) },
            "callUno" to { s.game.callUno(1) },
            "playCard" to { s.game.playCard(1, redSeven) }
        )
        for ((name, act) in actions) {
            try { act() } catch (e: Throwable) { fail("spectator $name must be dropped silently, but threw $e") }
        }
        assertTrue(s.transport.sent.isEmpty())
    }

    // ---- What a guest must not do ----

    /** Guards the `isHost` conditions in handleNetworkMessage: a guest that (wrongly or maliciously) receives a
     *  RequestState or an Intent must neither reply nor apply it -- it has no authoritative state to give and
     *  must not run the rules. Any Intent would, if applied on a guest, re-forward itself to the host, so the
     *  "no sends" assertion catches that loop as well as a local mutation. */
    @Test
    fun `a guest ignores RequestState and Intents addressed to it`() {
        val g = guest(index = 1)
        val st = viewFor("p1", table(hands = mapOf(1 to listOf(redSeven) + deadHand(1))))
        g.transport.inject("p0", sync(1, st))
        val mark = g.transport.mark()

        g.transport.inject("p2", UnoNetMessage.RequestState)
        g.transport.inject("p1", UnoNetMessage.Intent(UnoIntentPayload.DrawCard(1)))
        g.transport.inject("p1", UnoNetMessage.Intent(UnoIntentPayload.PlayCard(1, 700)))
        g.transport.inject("p2", UnoNetMessage.Intent(UnoIntentPayload.CallUno(2)))

        assertTrue("guest must stay silent, sent=${g.transport.sentSince(mark).size}", g.transport.sentSince(mark).isEmpty())
        assertEquals(st, g.game.state.value)
    }

    /** startMatch/startNextRound on a guest must not deal (a guest dealing locally would invent a second,
     *  diverging game): state stays null / unchanged and nothing but the init RequestState was sent. */
    @Test
    fun `a guest never deals`() {
        val g = guest()
        g.game.startMatch()
        assertNull(g.game.state.value)

        val over = labelled("over").copy(roundOver = true)
        g.transport.inject("p0", sync(1, over))
        g.game.startNextRound()
        assertEquals(over, g.game.state.value)
        assertEquals("only the init RequestState", 1, g.transport.sent.size)
    }

    /** Malformed inbound bytes never crash or alter a guest, and a later valid StateSync still applies. */
    @Test
    fun `a guest ignores malformed payloads`() {
        val g = guest()
        g.transport.inject("p0", sync(1, labelled("good")))
        val garbage = listOf(
            ByteArray(0), "junk".toByteArray(), "{}".toByteArray(), "{\"type\":\"x\"}".toByteArray(),
            byteArrayOf(0xC3.toByte(), 0x28), String(UnoTestKit.encodeUnoMessage(sync(9, labelled("cut")))).dropLast(20).toByteArray()
        )
        for ((i, bytes) in garbage.withIndex()) {
            try { g.transport.inject("p0", bytes) } catch (e: Throwable) { fail("garbage #$i threw $e") }
            assertEquals("good", g.game.state.value!!.lastAction)
        }
        g.transport.inject("p0", sync(2, labelled("still works")))
        assertEquals("still works", g.game.state.value!!.lastAction)
    }

    // ---- Reconnect ----

    /** Guards the guest branch of onReconnected: it asks the host again (one RequestState from itself to the
     *  host per event, mirroring startup) rather than broadcasting or staying silent. */
    @Test
    fun `a guest re-requests state after a reconnect`() {
        for (mode in networkedModes) {
            val g = guest(mode = mode)
            val mark = g.transport.mark()
            g.transport.fireReconnected()
            g.transport.fireReconnected()
            val out = g.transport.sentSince(mark)
            assertEquals(2, out.size)
            assertTrue(out.all { it.from == "p1" && it.to == "p0" && it.message == UnoNetMessage.RequestState })
        }
    }

    // ---- Regression for an engine bug found by this suite (fixed together with it) ----

    /**
     * UNO-BUG-E (fixed): handleNetworkMessage used to apply a StateSync from ANY sender, not just the
     * host. A malicious guest that can address another guest could forge the whole board for it
     * (including fabricated opponent hand sizes) and, with version Int.MAX_VALUE, freeze it so every
     * genuine host update was dropped as stale. The sender check that protects Intents (applyIntent)
     * now has a counterpart for StateSync: only context.players[0] (the host) is accepted.
     */
    @Test
    fun `a guest must ignore a StateSync that did not come from the host`() {
        val g = guest(index = 1)
        g.transport.inject("p2", sync(Int.MAX_VALUE, labelled("forged by p2")))
        assertNull("forged state from a non-host must not be adopted", g.game.state.value)
        g.transport.inject("p0", sync(1, labelled("genuine")))
        assertNotNull(g.game.state.value)
        assertEquals("genuine", g.game.state.value!!.lastAction)
    }
}
