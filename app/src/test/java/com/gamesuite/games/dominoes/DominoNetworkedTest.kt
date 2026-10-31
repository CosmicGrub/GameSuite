package com.gamesuite.games.dominoes

import com.gamesuite.core.PlayMode
import com.gamesuite.games.dominoes.DomTestKit.idOf
import com.gamesuite.games.dominoes.DomTestKit.t
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-authoritative Dominoes over a (fake) MultiplayerTransport with HIDDEN information. Host = seat
 * 0 "p0", guest = seat 1 "p1". The guest-bound state must hold the guest's OWN hand plus counts only.
 */
class DominoNetworkedTest {

    // --- fixtures ----------------------------------------------------------------------------

    private fun placed(a: Int, b: Int, flipped: Boolean = false) = PlacedDomino(t(a, b), flipped)

    /** Replaces the host's table with a fixed position (the real boneyard stays behind it). */
    private fun fixture(
        host: DomNode,
        hand0: List<Domino>,
        hand1: List<Domino>,
        chain: List<PlacedDomino>,
        current: Int,
        boneyardSize: Int = host.state.boneyardSize
    ) {
        host.game.state.value = DominoState(
            players = listOf(
                DominoPlayerState("p0", "Host", false, hand0),
                DominoPlayerState("p1", "Guest", false, hand1)
            ),
            boneyardSize = boneyardSize,
            chain = chain,
            currentPlayerIndex = current,
            consecutivePasses = 0,
            lastAction = "Fixture"
        )
    }

    /** Empties the host's real boneyard by drawing it down with seat 0 (a 6-6 chain and an empty hand make every draw legal). */
    private fun drainHostBoneyard(host: DomNode) {
        repeat(60) {
            val s = host.state
            if (s.boneyardSize == 0) return
            val players = s.players.toMutableList()
            players[0] = players[0].copy(hand = emptyList())
            host.game.state.value = s.copy(players = players, chain = listOf(placed(6, 6)), currentPlayerIndex = 0)
            host.game.drawFromBoneyard(0)
        }
    }

    private fun hostTileIds(host: DomNode): Set<Int> = host.state.players[0].hand.map { it.instanceId }.toSet()

    /** Asserts the latest host-to-guest sync leaks nothing: guest's own hand and public chain only, counts for the rest. */
    private fun assertRedacted(pair: DomTestKit.Pair2) {
        val last = pair.host.transport.sent.last { it.to == "p1" }
        val wire = last.stateSync.state
        val hostIds = hostTileIds(pair.host)
        val wireIds = (wire.chain.map { it.tile.id } + wire.myHand.map { it.id }).toSet()
        assertTrue("host tiles leaked: ${hostIds.intersect(wireIds)}", hostIds.intersect(wireIds).isEmpty())
        assertEquals(pair.host.state.players[1].hand.map { it.instanceId }.toSet(), wire.myHand.map { it.id }.toSet())
        assertEquals(pair.host.state.players.map { it.hand.size }, wire.handSizes)
        assertEquals(pair.host.state.boneyardSize, wire.boneyardSize)
        assertFalse("no boneyard order on the wire", last.text.contains("\"boneyard\""))
        assertFalse("no host hand on the wire", last.text.contains("\"hand\""))
    }

    // --- modes -------------------------------------------------------------------------------

    @Test
    fun advertisesNetworkedModes() {
        val modes = DominoGame().supportedModes
        assertTrue(PlayMode.LOCAL_AD_HOC in modes)
        assertTrue(PlayMode.ONLINE in modes)
        assertTrue(PlayMode.SINGLE_PLAYER_VS_BOT in modes)
        assertTrue(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY in modes)
    }

    @Test
    fun networkedGameIsNotResumableAndHasNoUndo() {
        for (mode in listOf(PlayMode.LOCAL_AD_HOC, PlayMode.ONLINE)) {
            val host = DomTestKit.node(0, mode, start = true)
            assertNull(host.game.snapshot())
            assertFalse(host.game.restore("{}"))
            assertFalse(host.game.canUndo)
            val before = host.game.state.value
            host.game.undo()
            assertEquals(before, host.game.state.value)
        }
    }

    // --- host: deal and redaction ---------------------------------------------------------------

    @Test
    fun hostDealsAndSendsTheGuestOnlyItsOwnHandAndCounts() {
        val host = DomTestKit.node(0, start = true)
        assertEquals(1, host.transport.sent.size)
        val sync = host.transport.sent[0]
        assertEquals("p0", sync.from)
        assertEquals("p1", sync.to)
        assertEquals(1, sync.stateSync.version)
        val wire = sync.stateSync.state
        assertEquals(host.state.players[1].hand.map { it.instanceId }, wire.myHand.map { it.id })
        assertEquals(listOf(7, 7), wire.handSizes)
        assertEquals(14, wire.boneyardSize)
        assertTrue(wire.chain.isEmpty())
        val leaked = hostTileIds(host).intersect(wire.myHand.map { it.id }.toSet())
        assertTrue("host tile ids on the guest-bound wire: $leaked", leaked.isEmpty())
        assertNull(wire.handPips)
    }

    @Test
    fun theGuestSeesPlaceholdersForTheHostHandAndNothingAboutTheBoneyard() {
        val pair = DomTestKit.pair()
        val view = pair.guest.state
        assertEquals(7, view.players[1].hand.size)
        assertEquals(pair.host.state.players[1].hand, view.players[1].hand)
        assertEquals(7, view.players[0].hand.size)
        assertTrue("host tiles are placeholders with no pips", view.players[0].hand.all { it.instanceId < 0 && it.a < 0 && it.b < 0 })
        assertEquals(14, view.boneyardSize)
        assertTrue(view.players[0].hand.map { it.instanceId }.toSet().intersect(hostTileIds(pair.host)).isEmpty())
        assertRedacted(pair)
    }

    @Test
    fun everySyncOfAWholeHandStaysRedacted() {
        val pair = DomTestKit.pair()
        assertRedacted(pair)
        var steps = 0
        while (!pair.host.state.handOver && steps++ < 600) {
            pair.step()
            assertRedacted(pair)
        }
        assertTrue(pair.host.state.handOver)
        assertRedacted(pair)
        // Even the final sync shows the host's leftover hand only as a pip total.
        val wire = pair.host.transport.sent.last { it.to == "p1" }.stateSync.state
        assertEquals(pair.host.state.players.map { p -> p.hand.sumOf { it.a + it.b } }, wire.handPips)
    }

    // --- host: validating the guest's requests -------------------------------------------------------

    @Test
    fun hostAppliesAValidGuestPlayOnTheChosenEndAndBroadcasts() {
        val host = DomTestKit.node(0, start = true)
        fixture(host, hand0 = listOf(t(0, 1)), hand1 = listOf(t(4, 6), t(5, 5), t(3, 6)), chain = listOf(placed(3, 4)), current = 1)
        val mark = host.transport.mark()
        host.transport.inject("p1", DominoNetMessage.PlayRequest(idOf(4, 6), attachToLeft = false))
        var s = host.state
        assertEquals(2, s.chain.size)
        assertEquals(6, s.rightEnd)
        assertEquals(0, s.currentPlayerIndex)
        assertEquals(listOf(t(5, 5), t(3, 6)), s.players[1].hand)
        val out = host.transport.sentSince(mark)
        assertEquals(1, out.size)
        assertEquals("p1", out[0].to)
        assertEquals(2, out[0].stateSync.version) // deal, then this play
        assertEquals(listOf(idOf(5, 5), idOf(3, 6)), out[0].stateSync.state.myHand.map { it.id })

        // The left end: 3-6 against the 3 is flipped so its 3 touches the chain.
        fixture(host, hand0 = listOf(t(0, 1)), hand1 = listOf(t(3, 6), t(5, 5)), chain = listOf(placed(3, 4)), current = 1)
        host.transport.inject("p1", DominoNetMessage.PlayRequest(idOf(3, 6), attachToLeft = true))
        s = host.state
        assertEquals(2, s.chain.size)
        assertEquals(6, s.leftEnd)
        assertTrue(s.chain.first().flipped)
    }

    @Test
    fun hostRejectsTilesTheSenderDoesNotHold() {
        val host = DomTestKit.node(0, start = true)
        fixture(host, hand0 = listOf(t(4, 4), t(0, 1)), hand1 = listOf(t(5, 5)), chain = listOf(placed(3, 4)), current = 1)
        val before = host.state
        val mark = host.transport.mark()
        host.transport.inject("p1", DominoNetMessage.PlayRequest(idOf(4, 4), attachToLeft = false)) // the host's tile
        host.transport.inject("p1", DominoNetMessage.PlayRequest(idOf(3, 4), attachToLeft = false)) // already in the chain
        host.transport.inject("p1", DominoNetMessage.PlayRequest(-1, attachToLeft = false))         // a placeholder id
        host.transport.inject("p1", DominoNetMessage.PlayRequest(9999, attachToLeft = true))        // nonexistent
        assertEquals(before, host.state)
        assertEquals(0, host.transport.sentSince(mark).size)
    }

    @Test
    fun hostRejectsAPlayThatDoesNotMatchTheChosenEnd() {
        val host = DomTestKit.node(0, start = true)
        fixture(host, hand0 = listOf(t(0, 1)), hand1 = listOf(t(5, 5), t(4, 6)), chain = listOf(placed(3, 4)), current = 1)
        val before = host.state
        val mark = host.transport.mark()
        host.transport.inject("p1", DominoNetMessage.PlayRequest(idOf(5, 5), attachToLeft = true))
        host.transport.inject("p1", DominoNetMessage.PlayRequest(idOf(4, 6), attachToLeft = true)) // fits the right end (4), not the left (3)
        assertEquals(before, host.state)
        assertEquals(0, host.transport.sentSince(mark).size)
    }

    @Test
    fun hostRejectsOutOfTurnRequests() {
        val host = DomTestKit.node(0, start = true)
        fixture(host, hand0 = listOf(t(0, 1)), hand1 = listOf(t(4, 6), t(2, 2)), chain = listOf(placed(3, 4)), current = 0)
        val before = host.state
        val mark = host.transport.mark()
        host.transport.inject("p1", DominoNetMessage.PlayRequest(idOf(4, 6), attachToLeft = false))
        host.transport.inject("p1", DominoNetMessage.DrawRequest)
        host.transport.inject("p1", DominoNetMessage.PassRequest)
        assertEquals(before, host.state)
        assertEquals(0, host.transport.sentSince(mark).size)
    }

    @Test
    fun hostRejectsSpoofedAndUnknownSenders() {
        val host = DomTestKit.node(0, start = true)
        fixture(host, hand0 = listOf(t(4, 5)), hand1 = listOf(t(4, 6)), chain = listOf(placed(3, 4)), current = 1)
        val before = host.state
        val mark = host.transport.mark()
        // The host's own id, a stranger and garbage can never move the guest's seat.
        host.transport.inject("p0", DominoNetMessage.PlayRequest(idOf(4, 6), attachToLeft = false))
        host.transport.inject("intruder", DominoNetMessage.PlayRequest(idOf(4, 6), attachToLeft = false))
        host.transport.inject("p1", "not json".toByteArray())
        host.transport.inject("p1", "{\"type\":\"PlayRequest\"}".toByteArray())
        assertEquals(before, host.state)
        assertEquals(0, host.transport.sentSince(mark).size)
        // A guest-sent StateSync is a forgery and is ignored by the authoritative engine.
        host.transport.inject("p1", DominoNetMessage.StateSync(99, DomTestKit.net()))
        assertEquals(before, host.state)
    }

    @Test
    fun hostRejectsAPlayWhenTheHandIsOver() {
        val pair = DomTestKit.pair()
        pair.stepUntil { pair.host.state.handOver }
        val before = pair.host.state
        pair.host.transport.inject("p1", DominoNetMessage.PlayRequest(idOf(0, 0), attachToLeft = true))
        pair.host.transport.inject("p1", DominoNetMessage.DrawRequest)
        pair.host.transport.inject("p1", DominoNetMessage.PassRequest)
        assertEquals(before, pair.host.state)
    }

    @Test
    fun hostHandlesGuestDrawOnlyWhenNothingFitsAndTheBoneyardHasTiles() {
        val host = DomTestKit.node(0, start = true)
        // Something fits: a draw is refused.
        fixture(host, hand0 = listOf(t(0, 1)), hand1 = listOf(t(3, 3)), chain = listOf(placed(3, 4)), current = 1)
        var mark = host.transport.mark()
        host.transport.inject("p1", DominoNetMessage.DrawRequest)
        assertEquals(1, host.state.players[1].hand.size)
        assertEquals(0, host.transport.sentSince(mark).size)

        // Nothing fits: exactly one tile moves from the boneyard to the guest's hand.
        fixture(host, hand0 = listOf(t(0, 1)), hand1 = listOf(t(0, 0)), chain = listOf(placed(3, 4)), current = 1, boneyardSize = 14)
        mark = host.transport.mark()
        host.transport.inject("p1", DominoNetMessage.DrawRequest)
        val s = host.state
        assertEquals(2, s.players[1].hand.size)
        assertEquals(13, s.boneyardSize)
        assertEquals(1, s.currentPlayerIndex) // the guest keeps the turn after drawing
        val out = host.transport.sentSince(mark)
        assertEquals(1, out.size)
        assertEquals(2, out[0].stateSync.state.myHand.size)
        assertEquals(listOf(1, 2), out[0].stateSync.state.handSizes)
        assertEquals(13, out[0].stateSync.state.boneyardSize)
    }

    @Test
    fun passesFollowTheEmptyBoneyardRuleAndTwoPassesBlockTheHand() {
        val host = DomTestKit.node(0, start = true)
        // A pass is refused while the boneyard still has tiles.
        fixture(host, hand0 = listOf(t(0, 1)), hand1 = listOf(t(0, 2)), chain = listOf(placed(6, 6)), current = 1)
        var mark = host.transport.mark()
        host.transport.inject("p1", DominoNetMessage.PassRequest)
        assertEquals(1, host.state.currentPlayerIndex)
        assertEquals(0, host.transport.sentSince(mark).size)

        drainHostBoneyard(host)
        assertEquals(0, host.state.boneyardSize)
        fixture(host, hand0 = listOf(t(0, 1)), hand1 = listOf(t(0, 2)), chain = listOf(placed(6, 6)), current = 1, boneyardSize = 0)
        // A pass is refused while a tile fits (the guest holds 6-x).
        fixture(host, hand0 = listOf(t(0, 1)), hand1 = listOf(t(0, 6)), chain = listOf(placed(6, 6)), current = 1, boneyardSize = 0)
        host.transport.inject("p1", DominoNetMessage.PassRequest)
        assertEquals(0, host.state.consecutivePasses)

        fixture(host, hand0 = listOf(t(0, 1)), hand1 = listOf(t(0, 2)), chain = listOf(placed(6, 6)), current = 1, boneyardSize = 0)
        mark = host.transport.mark()
        host.transport.inject("p1", DominoNetMessage.PassRequest)
        assertEquals(1, host.state.consecutivePasses)
        assertEquals(0, host.state.currentPlayerIndex)
        assertFalse(host.state.handOver)
        assertEquals(1, host.transport.sentSince(mark).size)

        host.game.pass(0) // the host passes too: blocked, 1 pip vs 2 pips, the host wins by the difference
        val s = host.state
        assertTrue(s.handOver)
        assertEquals("p0", s.winnerPlayerId)
        assertEquals(1, host.game.sessionScores.value["p0"])
        assertEquals(0, host.game.sessionScores.value["p1"])
        val wire = host.transport.sent.last { it.to == "p1" }.stateSync.state
        assertTrue(wire.handOver)
        assertEquals("p0", wire.winnerPlayerId)
        assertEquals(listOf(1, 2), wire.handPips)
        assertEquals(1, wire.sessionScores["p0"])
        assertEquals(1, wire.handsFinished)
        assertTrue("the host's leftover tile is not on the wire", wire.myHand.none { it.id == idOf(0, 1) })
    }

    @Test
    fun aBlockedHandWithLevelPipsIsATieOverTheNetworkToo() {
        val host = DomTestKit.node(0, start = true)
        drainHostBoneyard(host)
        fixture(host, hand0 = listOf(t(0, 2)), hand1 = listOf(t(1, 1)), chain = listOf(placed(6, 6)), current = 0, boneyardSize = 0)
        host.game.pass(0)
        host.transport.inject("p1", DominoNetMessage.PassRequest)
        val s = host.state
        assertTrue(s.handOver)
        assertNull(s.winnerPlayerId)
        assertEquals(0, host.game.sessionScores.value["p0"])
        assertEquals(0, host.game.sessionScores.value["p1"])
        val wire = host.transport.sent.last { it.to == "p1" }.stateSync.state
        assertNull(wire.winnerPlayerId)
        assertEquals(listOf(2, 2), wire.handPips)
    }

    @Test
    fun hostTapsOnTheGuestsTurnOrForTheGuestSeatAreIgnored() {
        val host = DomTestKit.node(0, start = true)
        fixture(host, hand0 = listOf(t(4, 5)), hand1 = listOf(t(4, 6)), chain = listOf(placed(3, 4)), current = 1)
        val before = host.state
        host.game.playDomino(0, t(4, 5), attachToLeft = false) // not the host's turn
        host.game.playDomino(1, t(4, 6), attachToLeft = false) // a local tap can never move the guest's seat
        host.game.drawFromBoneyard(1)
        host.game.pass(1)
        assertEquals(before, host.state)
    }

    // --- guest ----------------------------------------------------------------------------------

    @Test
    fun guestAsksHostForStateOnInitAndNeverDealsItself() {
        val guest = DomTestKit.node(1)
        assertEquals(1, guest.transport.sent.size)
        assertEquals("p1", guest.transport.sent[0].from)
        assertEquals("p0", guest.transport.sent[0].to)
        assertEquals(DominoNetMessage.RequestState, guest.transport.sent[0].message)
        assertNull(guest.game.state.value)
        guest.game.startMatch()
        assertNull(guest.game.state.value)
    }

    @Test
    fun guestAppliesNewerAndIgnoresOlderOrEqualVersions() {
        val guest = DomTestKit.node(1)
        guest.transport.inject("p0", DominoNetMessage.StateSync(2, DomTestKit.net(lastAction = "v2")))
        assertEquals("v2", guest.state.lastAction)
        guest.transport.inject("p0", DominoNetMessage.StateSync(1, DomTestKit.net(lastAction = "v1")))
        guest.transport.inject("p0", DominoNetMessage.StateSync(2, DomTestKit.net(lastAction = "again")))
        assertEquals("v2", guest.state.lastAction)
        guest.transport.inject("p0", DominoNetMessage.StateSync(3, DomTestKit.net(lastAction = "v3", current = 0)))
        assertEquals("v3", guest.state.lastAction)
        assertEquals(0, guest.state.currentPlayerIndex)
    }

    @Test
    fun guestIgnoresStateFromNonHostAndMalformedState() {
        val guest = DomTestKit.node(1)
        guest.transport.inject("p1", DominoNetMessage.StateSync(9, DomTestKit.net()))
        guest.transport.inject("intruder", DominoNetMessage.StateSync(9, DomTestKit.net()))
        assertNull(guest.game.state.value)

        val good = DomTestKit.net()
        val bad = listOf(
            good.copy(boneyardSize = good.boneyardSize + 1),                              // tiles do not add up
            good.copy(boneyardSize = -1),
            good.copy(handSizes = listOf(3)),                                              // wrong seat count
            good.copy(handSizes = listOf(3, 2)),                                           // own count disagrees with own hand
            good.copy(currentPlayerIndex = 7),
            good.copy(maxPip = 7),
            good.copy(myHand = good.myHand + good.myHand[0], handSizes = listOf(3, 4)),   // a tile twice
            good.copy(myHand = listOf(good.myHand[0].copy(a = 6, b = 6)) + good.myHand.drop(1)), // pips do not match the id
            good.copy(myHand = listOf(good.myHand[0].copy(id = 99)) + good.myHand.drop(1)),
            good.copy(winnerPlayerId = "stranger"),
            good.copy(sessionScores = mapOf("p0" to 0)),
            good.copy(handOver = true, handPips = null),                                   // an over hand must explain its score
            DomTestKit.net(chain = listOf(idOf(1, 2) to false, idOf(5, 6) to false)) // chain tiles that do not touch
        )
        bad.forEachIndexed { i, payload ->
            guest.transport.inject("p0", DominoNetMessage.StateSync(1 + i, payload))
            assertNull("malformed payload #$i must be dropped", guest.game.state.value)
        }
        // A good one still lands afterwards (rejected ones did not burn their version).
        guest.transport.inject("p0", DominoNetMessage.StateSync(1, DomTestKit.net(lastAction = "ok")))
        assertEquals("ok", guest.state.lastAction)
    }

    @Test
    fun guestTapSendsARequestAndNeverMutatesItself() {
        val guest = DomTestKit.node(1)
        guest.transport.inject("p0", DominoNetMessage.StateSync(1, DomTestKit.net(myHandIds = listOf(idOf(2, 3), idOf(4, 4)))))
        val before = guest.state
        val mark = guest.transport.mark()
        guest.game.playDomino(1, guest.state.players[1].hand[0], attachToLeft = false)
        assertEquals(before, guest.state)
        val out = guest.transport.sentSince(mark)
        assertEquals(1, out.size)
        assertEquals("p1", out[0].from)
        assertEquals("p0", out[0].to)
        assertEquals(DominoNetMessage.PlayRequest(idOf(2, 3), attachToLeft = false), out[0].message)
    }

    @Test
    fun guestTapsThatCannotBeLegalSendNothing() {
        val guest = DomTestKit.node(1)
        guest.transport.inject(
            "p0",
            DominoNetMessage.StateSync(1, DomTestKit.net(myHandIds = listOf(idOf(5, 5), idOf(1, 2)), chain = listOf(idOf(3, 4) to false)))
        )
        val mark = guest.transport.mark()
        guest.game.playDomino(1, t(5, 5), attachToLeft = true)                    // does not match the 3 end
        guest.game.playDomino(1, Domino(-1, -1, -1), attachToLeft = true)          // a hidden host tile
        guest.game.playDomino(1, t(0, 0), attachToLeft = true)                    // not in this hand
        guest.game.playDomino(0, t(5, 5), attachToLeft = true)                    // the host's seat
        guest.game.drawFromBoneyard(0)
        guest.game.pass(0)
        assertEquals(0, guest.transport.sentSince(mark).size)

        // Out of turn: nothing is sent either.
        guest.transport.inject("p0", DominoNetMessage.StateSync(2, DomTestKit.net(myHandIds = listOf(idOf(4, 5)), chain = listOf(idOf(3, 4) to false), current = 0)))
        val mark2 = guest.transport.mark()
        guest.game.playDomino(1, t(4, 5), attachToLeft = false)
        guest.game.drawFromBoneyard(1)
        guest.game.pass(1)
        assertEquals(0, guest.transport.sentSince(mark2).size)
    }

    @Test
    fun guestDrawAndPassRequestsFollowTheStandardRules() {
        val guest = DomTestKit.node(1)
        val nothingFits = listOf(idOf(1, 2), idOf(0, 1), idOf(0, 2))
        // Boneyard has tiles and nothing fits: a draw request, never a pass.
        guest.transport.inject("p0", DominoNetMessage.StateSync(1, DomTestKit.net(myHandIds = nothingFits, chain = listOf(idOf(3, 3) to false))))
        var mark = guest.transport.mark()
        guest.game.pass(1)
        assertEquals(0, guest.transport.sentSince(mark).size)
        guest.game.drawFromBoneyard(1)
        assertEquals(listOf<DominoNetMessage>(DominoNetMessage.DrawRequest), guest.transport.sentSince(mark).map { it.message })

        // Something fits: neither a draw nor a pass is sent.
        guest.transport.inject("p0", DominoNetMessage.StateSync(2, DomTestKit.net(myHandIds = nothingFits + idOf(3, 5), oppCount = 3, chain = listOf(idOf(3, 3) to false))))
        mark = guest.transport.mark()
        guest.game.drawFromBoneyard(1)
        guest.game.pass(1)
        assertEquals(0, guest.transport.sentSince(mark).size)

        // Boneyard empty and nothing fits: a pass request, never a draw.
        guest.transport.inject("p0", DominoNetMessage.StateSync(3, DomTestKit.net(myHandIds = nothingFits, oppCount = 24, chain = listOf(idOf(3, 3) to false))))
        assertEquals(0, guest.state.boneyardSize)
        mark = guest.transport.mark()
        guest.game.drawFromBoneyard(1)
        assertEquals(0, guest.transport.sentSince(mark).size)
        guest.game.pass(1)
        assertEquals(listOf<DominoNetMessage>(DominoNetMessage.PassRequest), guest.transport.sentSince(mark).map { it.message })
    }

    @Test
    fun guestHasNoUndoAndNoResume() {
        val guest = DomTestKit.node(1)
        guest.transport.inject("p0", DominoNetMessage.StateSync(1, DomTestKit.net()))
        val before = guest.state
        assertFalse(guest.game.canUndo)
        guest.game.undo()
        assertEquals(before, guest.state)
        assertNull(guest.game.snapshot())
        assertFalse(guest.game.restore("{}"))
    }

    @Test
    fun guestShowsHostSummaryAtHandOverFromPipTotals() {
        val guest = DomTestKit.node(1)
        guest.transport.inject(
            "p0",
            DominoNetMessage.StateSync(
                1,
                DomTestKit.net(
                    myHandIds = emptyList(), oppCount = 2, current = 0, handOver = true, winner = "p1",
                    scores = mapOf("p0" to 0, "p1" to 11), handPips = listOf(11, 0), handsFinished = 1
                )
            )
        )
        val s = guest.state
        assertTrue(s.handOver)
        assertEquals("p1", s.winnerPlayerId)
        assertEquals(mapOf("p0" to 11, "p1" to 0), s.knownPips)
        assertEquals(11, guest.game.sessionScores.value["p1"])
        assertEquals(1, guest.game.handsFinished.value)
    }

    // --- play again -----------------------------------------------------------------------------------

    @Test
    fun playAgainIsHonouredOnlyOnceTheHandIsOver() {
        val pair = DomTestKit.pair()
        val mark = pair.host.transport.mark()
        pair.guest.game.playAgain() // hand still running: the guest does not even ask
        pair.host.transport.inject("p1", DominoNetMessage.PlayAgainRequest)
        assertEquals(0, pair.host.transport.sentSince(mark).size)

        pair.stepUntil { pair.host.state.handOver }
        assertTrue(pair.guest.state.handOver)
        val finishedBefore = pair.host.game.handsFinished.value
        pair.guest.game.playAgain()
        assertFalse(pair.host.state.handOver)
        assertFalse(pair.guest.state.handOver)
        assertEquals(7, pair.host.state.players[0].hand.size)
        assertEquals(finishedBefore, pair.guest.game.handsFinished.value)
        assertRedacted(pair)

        // A duplicate request after the redeal must not wipe the new hand.
        val dealt = pair.host.state
        pair.host.transport.inject("p1", DominoNetMessage.PlayAgainRequest)
        assertEquals(dealt, pair.host.state)
    }

    @Test
    fun hostPlayAgainRedealsForBothSeats() {
        val pair = DomTestKit.pair()
        val running = pair.host.state
        pair.host.game.playAgain() // mid-hand: ignored
        assertEquals(running, pair.host.state)
        pair.stepUntil { pair.host.state.handOver }
        pair.host.game.playAgain()
        assertFalse(pair.host.state.handOver)
        assertEquals(pair.host.state.players[1].hand, pair.guest.state.players[1].hand)
        assertRedacted(pair)
    }

    // --- RequestState / reconnect / leaving ------------------------------------------------------------

    @Test
    fun hostAnswersRequestStateDirectlyAndOnlyForTheGuestSeat() {
        val host = DomTestKit.node(0, start = true)
        val mark = host.transport.mark()
        host.transport.inject("p1", DominoNetMessage.RequestState)
        val out = host.transport.sentSince(mark)
        assertEquals(1, out.size)
        assertEquals("p1", out[0].to)
        assertEquals(host.state.players[1].hand.map { it.instanceId }, out[0].stateSync.state.myHand.map { it.id })
        host.transport.inject("intruder", DominoNetMessage.RequestState)
        host.transport.inject("p0", DominoNetMessage.RequestState)
        assertEquals(1, host.transport.sentSince(mark).size)
    }

    @Test
    fun lateGuestCatchesUpThroughRequestState() {
        val pair = DomTestKit.pair()
        val g = pair.guest.game.state.value
        assertNotNull(g)
        assertEquals(pair.host.state.chain, g!!.chain)
        assertEquals(pair.host.state.currentPlayerIndex, g.currentPlayerIndex)
        assertEquals(pair.host.state.players[1].hand, g.players[1].hand)
    }

    @Test
    fun reconnectHostResendsAndGuestAsksAgain() {
        val host = DomTestKit.node(0, start = true)
        val hmark = host.transport.mark()
        host.transport.fireReconnected()
        val resent = host.transport.sentSince(hmark)
        assertEquals(1, resent.size)
        assertEquals("p1", resent[0].to)
        assertEquals(2, resent[0].stateSync.version)

        val guest = DomTestKit.node(1)
        val gmark = guest.transport.mark()
        guest.transport.fireReconnected()
        val asked = guest.transport.sentSince(gmark)
        assertEquals(1, asked.size)
        assertEquals(DominoNetMessage.RequestState, asked[0].message)
    }

    @Test
    fun opponentLeavingFreezesInputAndLeavingRecordsNothing() {
        val pair = DomTestKit.pair()
        pair.host.transport.fireLeft("p1")
        assertTrue(pair.host.game.opponentLeft.value)
        val frozen = pair.host.state
        // No more moves from either side once the opponent is gone.
        val seat = frozen.currentPlayerIndex
        if (seat == 0) {
            val tile = frozen.players[0].hand.first()
            pair.host.game.playDomino(0, tile, attachToLeft = true)
        } else {
            pair.host.transport.inject("p1", DominoNetMessage.PlayRequest(frozen.players[1].hand.first().instanceId, true))
        }
        assertEquals(frozen.chain, pair.host.state.chain)
        pair.host.game.playAgain()
        pair.host.game.leaveSession()
        assertEquals(1, pair.host.results.size)
        assertTrue("walking away from a left opponent is an abort, never a recorded loss", pair.host.results[0].wasAborted)
        assertTrue(pair.host.results[0].scores.isEmpty())
    }

    @Test
    fun guestSeesTheHostLeaveAndAbortsWithoutScores() {
        val guest = DomTestKit.node(1)
        guest.transport.inject("p0", DominoNetMessage.StateSync(1, DomTestKit.net()))
        guest.transport.fireLeft("p0")
        assertTrue(guest.game.opponentLeft.value)
        assertEquals(DominoGame.OPPONENT_DISCONNECTED, guest.state.lastAction)
        val mark = guest.transport.mark()
        guest.game.playDomino(1, guest.state.players[1].hand[0], attachToLeft = true)
        guest.game.drawFromBoneyard(1)
        assertEquals(0, guest.transport.sentSince(mark).size)
        guest.game.leaveSession()
        assertTrue(guest.results.single().wasAborted)
        assertTrue(guest.results.single().scores.isEmpty())
    }

    @Test
    fun rejoiningOpponentUnfreezesAndHostResyncs() {
        val host = DomTestKit.node(0, start = true)
        host.transport.fireLeft("p1")
        assertTrue(host.game.opponentLeft.value)
        val mark = host.transport.mark()
        host.transport.fireJoined("p1")
        assertFalse(host.game.opponentLeft.value)
        assertEquals(1, host.transport.sentSince(mark).size)
    }

    // --- a whole scripted session ---------------------------------------------------------------------

    @Test
    fun aScriptedSessionEndsIdenticallyOnBothDevices() {
        for (set in listOf(DominoSet.DOUBLE_SIX, DominoSet.DOUBLE_NINE)) {
            val pair = DomTestKit.pair(set = set)
            assertEquals(set, pair.guest.game.tileSet)
            repeat(6) { handNo ->
                pair.stepUntil { pair.host.state.handOver }
                val h = pair.host.state
                val g = pair.guest.state
                assertTrue("hand $handNo must finish", h.handOver)
                assertTrue(g.handOver)
                assertEquals(h.winnerPlayerId, g.winnerPlayerId)
                assertEquals(h.chain, g.chain)
                assertEquals(h.boneyardSize, g.boneyardSize)
                assertEquals(h.currentPlayerIndex, g.currentPlayerIndex)
                assertEquals(pair.host.game.sessionScores.value, pair.guest.game.sessionScores.value)
                assertEquals(pair.host.game.handsFinished.value, pair.guest.game.handsFinished.value)
                // The guest knows how every hand was scored although it never held the host's tiles.
                assertEquals(
                    h.players.associate { it.playerId to it.hand.sumOf { d -> d.a + d.b } },
                    g.players.associate { it.playerId to (g.knownPips[it.playerId] ?: -1) }
                )
                assertRedacted(pair)
                assertEquals(handNo + 1, pair.host.game.handsFinished.value)
                if (handNo < 5) pair.guest.game.playAgain()
            }
            assertTrue("the pair never desynchronised", pair.hub.senderLies.isEmpty())

            pair.host.game.leaveSession()
            pair.guest.game.leaveSession()
            val hr = pair.host.results.single()
            val gr = pair.guest.results.single()
            assertFalse(hr.wasAborted)
            assertEquals(hr.scores, gr.scores)
        }
    }
}
