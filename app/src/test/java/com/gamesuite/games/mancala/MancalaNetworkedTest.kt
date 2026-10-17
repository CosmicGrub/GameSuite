package com.gamesuite.games.mancala

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mancala over LOCAL_AD_HOC / ONLINE with the host (seat 0) owning the engine and the guest (seat
 * 1) forwarding taps, using a fake relay (see MancalaNetTestSupport.kt). The opening board is
 * 4 stones in every pit; the hand-worked positions below follow from it:
 *   host sows pit 0   -> [0,5,5,5,5,4,0, 4,4,4,4,4,4,0], guest to move   (state version 2)
 *   guest sows pit 9  -> ends in its store 13 (extra turn, still guest)   (version 3)
 *   guest sows pit 7  -> ends in pit 11, host to move                     (version 4)
 */
class MancalaNetworkedTest {

    private val afterHostPit0 = listOf(0, 5, 5, 5, 5, 4, 0, 4, 4, 4, 4, 4, 4, 0)
    private val afterGuestPit9 = listOf(0, 5, 5, 5, 5, 4, 0, 4, 4, 0, 5, 5, 5, 1)

    private fun MancalaPair.hostMovesPit0() {
        host.game.sow(0, 0)
    }

    /** Asserts that injecting [message] from [from] into the host changes nothing and sends nothing. */
    private fun assertHostIgnores(pair: MancalaPair, from: String, message: MancalaNetMessage) {
        val before = pair.host.game.state.value
        val mark = pair.host.transport.mark()
        pair.host.transport.inject(from, message)
        assertSame(before, pair.host.game.state.value)
        assertTrue(pair.host.transport.sentSince(mark).isEmpty())
    }

    // ---- modes + local-play invariants -----------------------------------------------------

    @Test
    fun `mancala advertises both networked modes next to the local ones`() {
        val modes = MancalaGame().supportedModes
        assertTrue(PlayMode.LOCAL_AD_HOC in modes)
        assertTrue(PlayMode.ONLINE in modes)
        assertTrue(PlayMode.SINGLE_PLAYER_VS_BOT in modes)
        assertTrue(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY in modes)
    }

    @Test
    fun `local play is not networked and keeps seat zero at the bottom`() {
        val g = MancalaGame()
        g.init(
            GameContext(
                activeMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                players = MancalaTestKit.roster,
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        assertFalse(g.isNetworkedPlay)
        assertEquals(0, g.viewSeat)
        g.openingSeatIndex = 1
        g.startMatch()
        assertEquals(1, g.state.value!!.currentPlayerIndex) // the first-mover setting still applies locally
    }

    @Test
    fun `the view seat is the local seat when networked`() {
        val p = MancalaTestKit.pair()
        assertTrue(p.host.game.isNetworkedPlay)
        assertEquals(0, p.host.game.viewSeat)
        assertEquals(1, p.guest.game.viewSeat)
    }

    @Test
    fun `networked play always opens with seat zero and the opening seat setting is ignored`() {
        val hub = MancalaHub()
        val host = MancalaTestKit.node(0, transport = hub.endpoint(MancalaTestKit.HOST_ID))
        host.game.openingSeatIndex = 1
        host.game.startMatch()
        assertEquals(0, host.game.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `a guest never deals and waits for the host`() {
        val guest = MancalaTestKit.node(1)
        guest.game.startMatch()
        assertNull(guest.game.state.value)
        // It asked the host for the board as soon as it started listening.
        assertEquals(1, guest.transport.sent.size)
        val first = guest.transport.sent[0]
        assertEquals(MancalaTestKit.GUEST_ID, first.from)
        assertEquals(MancalaTestKit.HOST_ID, first.to)
        assertEquals(MancalaNetMessage.RequestState, first.message)
    }

    @Test
    fun `networked games are not resumable and have no undo or hint`() {
        val p = MancalaTestKit.pair()
        p.host.game.sow(0, 0)
        assertNull(p.host.game.snapshot())
        assertNull(p.guest.game.snapshot())
        assertFalse(p.host.game.canUndo)
        val before = p.host.game.state.value
        p.host.game.undo()
        assertSame(before, p.host.game.state.value)
        assertNull(p.host.game.hint())
        assertNull(p.guest.game.hint())
        assertNull(p.guest.game.hintPit.value)
    }

    @Test
    fun `re-initialising on the same transport does not register a second listener`() {
        val t = MancalaFakeTransport()
        val game = MancalaGame()
        val ctx = GameContext(PlayMode.LOCAL_AD_HOC, MancalaTestKit.roster, 0, t)
        game.init(ctx)
        game.init(ctx)
        assertEquals(1, t.messageListeners.size)
        assertEquals(1, t.reconnectedListeners.size)
        assertEquals(1, t.leftListeners.size)
    }

    // ---- guest -> host sow --------------------------------------------------------------------

    @Test
    fun `a valid guest sow is applied by the host and broadcast with the next version`() {
        val p = MancalaTestKit.pair()
        p.hostMovesPit0()
        assertEquals(1, p.guest.game.state.value!!.currentPlayerIndex)

        val hostMark = p.host.transport.mark()
        val guestMark = p.guest.transport.mark()
        p.guest.game.sow(1, 9)

        // The guest sent only an intent, stamped with the version it was looking at.
        val request = p.guest.transport.sentSince(guestMark).single()
        assertEquals(MancalaTestKit.GUEST_ID, request.from)
        assertEquals(MancalaTestKit.HOST_ID, request.to)
        assertEquals(MancalaNetMessage.SowRequest(pit = 9, baseVersion = 2), request.message)

        // The host applied it and broadcast the authoritative result (version 3).
        val broadcast = p.host.transport.sentSince(hostMark).single()
        assertEquals(MancalaTestKit.HOST_ID, broadcast.from)
        assertEquals(MancalaTestKit.GUEST_ID, broadcast.to)
        assertEquals(3, broadcast.stateSync.version)
        assertEquals(listOf(9, 10, 11, 12, 13), broadcast.stateSync.state.lastSowPath)
        assertEquals(1, broadcast.stateSync.state.pits[13])

        // Extra turn: the guest is still on move on both engines.
        assertEquals(1, p.host.game.state.value!!.currentPlayerIndex)
        assertEquals(afterGuestPit9, p.host.game.state.value!!.pits)
        MancalaTestKit.assertSynced(p)
        assertTrue(p.hub.senderLies.isEmpty())
    }

    @Test
    fun `after an extra turn the same seat moves again and the turn then passes`() {
        val p = MancalaTestKit.pair()
        p.hostMovesPit0()
        p.guest.game.sow(1, 9) // lands in store 13
        p.guest.game.sow(1, 7) // second move by the same seat
        MancalaTestKit.assertSynced(p)
        assertEquals(0, p.host.game.state.value!!.currentPlayerIndex)
        assertEquals(2, p.guest.transport.sent.count { it.message is MancalaNetMessage.SowRequest })
    }

    @Test
    fun `the host extra turn keeps the host on move`() {
        val p = MancalaTestKit.pair()
        p.host.game.sow(0, 2) // 4 stones from pit 2 end in store 6
        assertEquals(0, p.host.game.state.value!!.currentPlayerIndex)
        MancalaTestKit.assertSynced(p)
        // The guest may not move while the host is on an extra turn.
        val mark = p.guest.transport.mark()
        p.guest.game.sow(1, 7)
        assertTrue(p.guest.transport.sentSince(mark).isEmpty())
    }

    @Test
    fun `online mode plays exactly like ad hoc`() {
        val p = MancalaTestKit.pair(PlayMode.ONLINE)
        p.hostMovesPit0()
        p.guest.game.sow(1, 9)
        MancalaTestKit.assertSynced(p)
        assertEquals(afterGuestPit9, p.guest.game.state.value!!.pits)
    }

    @Test
    fun `a guest tap never changes its own engine until the host answers`() {
        val guest = MancalaTestKit.node(1)
        guest.transport.inject(
            MancalaTestKit.HOST_ID,
            MancalaNetMessage.StateSync(2, MancalaTestKit.netState(pits = afterHostPit0, current = 1, path = listOf(0, 1, 2, 3, 4)))
        )
        val before = guest.game.state.value
        val mark = guest.transport.mark()
        guest.game.sow(1, 7)
        assertSame(before, guest.game.state.value)
        assertEquals(MancalaNetMessage.SowRequest(7, 2), guest.transport.sentSince(mark).single().message)
    }

    @Test
    fun `local taps for the wrong seat or out of turn send and change nothing`() {
        val p = MancalaTestKit.pair() // host to move
        val hostMark = p.host.transport.mark()
        val guestMark = p.guest.transport.mark()
        p.guest.game.sow(1, 7) // guest, not its turn
        p.guest.game.sow(0, 0) // guest pretending to be seat 0
        p.host.game.sow(1, 7)  // host pretending to be seat 1
        assertTrue(p.guest.transport.sentSince(guestMark).isEmpty())
        assertTrue(p.host.transport.sentSince(hostMark).isEmpty())
        assertEquals(4, p.host.game.state.value!!.pits[0])
    }

    // ---- host-side validation ---------------------------------------------------------------

    @Test
    fun `the host ignores a guest request for the hosts own pit`() {
        val p = MancalaTestKit.pair()
        p.hostMovesPit0()
        for (pit in listOf(0, 3, 5, 6)) assertHostIgnores(p, MancalaTestKit.GUEST_ID, MancalaNetMessage.SowRequest(pit, 2))
    }

    @Test
    fun `the host ignores a guest request for a store or a pit off the board`() {
        val p = MancalaTestKit.pair()
        p.hostMovesPit0()
        for (pit in listOf(13, 14, -1, 99)) assertHostIgnores(p, MancalaTestKit.GUEST_ID, MancalaNetMessage.SowRequest(pit, 2))
    }

    @Test
    fun `the host ignores a guest request for an empty pit`() {
        val p = MancalaTestKit.pair()
        p.hostMovesPit0()
        p.guest.game.sow(1, 9) // pit 9 is now empty and the guest is on its extra turn (version 3)
        assertEquals(0, p.host.game.state.value!!.pits[9])
        assertHostIgnores(p, MancalaTestKit.GUEST_ID, MancalaNetMessage.SowRequest(9, 3))
    }

    @Test
    fun `the host ignores a guest request out of turn`() {
        val p = MancalaTestKit.pair() // host to move, version 1
        assertHostIgnores(p, MancalaTestKit.GUEST_ID, MancalaNetMessage.SowRequest(7, 1))
    }

    @Test
    fun `the host ignores spoofed senders`() {
        val p = MancalaTestKit.pair()
        p.hostMovesPit0() // guest to move, version 2
        // Not in the roster at all.
        assertHostIgnores(p, "intruder", MancalaNetMessage.SowRequest(7, 2))
        // A message arriving under the host's own id is not a network guest (and seat 0 is not on move).
        assertHostIgnores(p, MancalaTestKit.HOST_ID, MancalaNetMessage.SowRequest(7, 2))
        assertHostIgnores(p, MancalaTestKit.HOST_ID, MancalaNetMessage.SowRequest(1, 2))
        // The same request from the real guest is fine.
        p.hub.endpoints[MancalaTestKit.GUEST_ID]!!.send(MancalaTestKit.GUEST_ID, MancalaTestKit.HOST_ID, MancalaTestKit.encode(MancalaNetMessage.SowRequest(7, 2)))
        assertEquals(0, p.host.game.state.value!!.currentPlayerIndex)
        assertTrue(p.hub.senderLies.isEmpty())
    }

    @Test
    fun `a stale request changes nothing and the host resends the real board`() {
        val p = MancalaTestKit.pair()
        p.hostMovesPit0() // guest to move, version 2
        val before = p.host.game.state.value
        val mark = p.host.transport.mark()
        p.host.transport.inject(MancalaTestKit.GUEST_ID, MancalaNetMessage.SowRequest(7, 1))
        assertSame(before, p.host.game.state.value)
        val resent = p.host.transport.sentSince(mark).single()
        assertEquals(MancalaTestKit.GUEST_ID, resent.to)
        assertEquals(2, resent.stateSync.version)
        // And it is not applied twice: a duplicate of an accepted request is stale too.
        p.guest.game.sow(1, 9) // version 3
        val v3 = p.host.game.state.value
        p.host.transport.inject(MancalaTestKit.GUEST_ID, MancalaNetMessage.SowRequest(9, 2))
        assertSame(v3, p.host.game.state.value)
    }

    @Test
    fun `malformed or foreign payloads are ignored and the match continues`() {
        val p = MancalaTestKit.pair()
        p.hostMovesPit0()
        p.host.transport.inject(MancalaTestKit.GUEST_ID, "not json at all".toByteArray())
        p.host.transport.inject(MancalaTestKit.GUEST_ID, "{\"type\":\"nope\"}".toByteArray())
        p.guest.transport.inject(MancalaTestKit.HOST_ID, ByteArray(0))
        p.guest.game.sow(1, 9)
        MancalaTestKit.assertSynced(p)
    }

    @Test
    fun `a state sync sent to the host is never believed`() {
        val p = MancalaTestKit.pair()
        val before = p.host.game.state.value
        p.host.transport.inject(
            MancalaTestKit.GUEST_ID,
            MancalaNetMessage.StateSync(99, MancalaTestKit.netState(pits = afterGuestPit9, current = 1))
        )
        assertSame(before, p.host.game.state.value)
    }

    // ---- guest applying state ---------------------------------------------------------------

    @Test
    fun `the guest applies newer versions and ignores older or equal ones`() {
        val guest = MancalaTestKit.node(1)
        val t = guest.transport
        t.inject(MancalaTestKit.HOST_ID, MancalaNetMessage.StateSync(2, MancalaTestKit.netState(pits = afterHostPit0, current = 1, path = listOf(0, 1, 2, 3, 4))))
        assertEquals(afterHostPit0, guest.game.state.value!!.pits)
        // Joined mid-game (version jumped from nothing to 2): show the position, do not replay the sow.
        assertTrue(guest.game.state.value!!.restored)

        t.inject(MancalaTestKit.HOST_ID, MancalaNetMessage.StateSync(3, MancalaTestKit.netState(pits = afterGuestPit9, current = 1, path = listOf(9, 10, 11, 12, 13))))
        assertEquals(afterGuestPit9, guest.game.state.value!!.pits)
        assertFalse(guest.game.state.value!!.restored) // the very next version is a live move: animate it

        val current = guest.game.state.value
        t.inject(MancalaTestKit.HOST_ID, MancalaNetMessage.StateSync(3, MancalaTestKit.netState(pits = afterHostPit0, current = 1)))
        t.inject(MancalaTestKit.HOST_ID, MancalaNetMessage.StateSync(1, MancalaTestKit.netState(pits = afterHostPit0, current = 1)))
        assertSame(current, guest.game.state.value)
    }

    @Test
    fun `the guest only believes the host and only sane boards`() {
        val guest = MancalaTestKit.node(1)
        val t = guest.transport
        t.inject("intruder", MancalaNetMessage.StateSync(5, MancalaTestKit.netState()))
        t.inject(MancalaTestKit.GUEST_ID, MancalaNetMessage.StateSync(5, MancalaTestKit.netState()))
        assertNull(guest.game.state.value)
        // 47 stones, a negative pit, a short board and a winner outside the roster are all refused.
        t.inject(MancalaTestKit.HOST_ID, MancalaNetMessage.StateSync(5, MancalaTestKit.netState(pits = afterHostPit0.toMutableList().also { it[0] = -1; it[1] = 6 })))
        t.inject(MancalaTestKit.HOST_ID, MancalaNetMessage.StateSync(5, MancalaTestKit.netState(pits = afterHostPit0.dropLast(1))))
        t.inject(MancalaTestKit.HOST_ID, MancalaNetMessage.StateSync(5, MancalaTestKit.netState(pits = afterHostPit0.toMutableList().also { it[0] = 1 })))
        t.inject(MancalaTestKit.HOST_ID, MancalaNetMessage.StateSync(5, MancalaTestKit.netState(roundOver = true, winner = "stranger")))
        assertNull(guest.game.state.value)
        // A refused message does not burn its version number.
        t.inject(MancalaTestKit.HOST_ID, MancalaNetMessage.StateSync(5, MancalaTestKit.netState(pits = afterHostPit0, current = 1)))
        assertNotNull(guest.game.state.value)
    }

    @Test
    fun `a re-sync of the same board does not replay the move`() {
        val guest = MancalaTestKit.node(1)
        val t = guest.transport
        val live = MancalaTestKit.netState(pits = afterGuestPit9, current = 1, path = listOf(9, 10, 11, 12, 13))
        t.inject(MancalaTestKit.HOST_ID, MancalaNetMessage.StateSync(1, MancalaTestKit.netState()))
        t.inject(MancalaTestKit.HOST_ID, MancalaNetMessage.StateSync(2, live))
        val shown = guest.game.state.value
        // A later version carrying the identical board (e.g. a reconnect re-broadcast) must not
        // create a new state object: the screen replays the hop animation for every new object.
        t.inject(MancalaTestKit.HOST_ID, MancalaNetMessage.StateSync(3, live))
        assertSame(shown, guest.game.state.value)
    }

    @Test
    fun `the session tally travels with the board`() {
        val guest = MancalaTestKit.node(1)
        guest.transport.inject(
            MancalaTestKit.HOST_ID,
            MancalaNetMessage.StateSync(1, MancalaTestKit.netState().copy(scoreP1 = 2, scoreP2 = 1, draws = 3))
        )
        assertEquals(2, guest.game.scoreP1.value)
        assertEquals(1, guest.game.scoreP2.value)
        assertEquals(3, guest.game.draws.value)
    }

    // ---- start, RequestState, reconnect ------------------------------------------------------

    @Test
    fun `the host answers RequestState with the current board and version`() {
        val p = MancalaTestKit.pair()
        p.hostMovesPit0()
        val mark = p.host.transport.mark()
        p.host.transport.inject(MancalaTestKit.GUEST_ID, MancalaNetMessage.RequestState)
        val reply = p.host.transport.sentSince(mark).single()
        assertEquals(MancalaTestKit.GUEST_ID, reply.to)
        assertEquals(2, reply.stateSync.version)
        assertEquals(afterHostPit0, reply.stateSync.state.pits)

        val mark2 = p.host.transport.mark()
        p.host.transport.inject("intruder", MancalaNetMessage.RequestState)
        p.host.transport.inject(MancalaTestKit.HOST_ID, MancalaNetMessage.RequestState)
        assertTrue(p.host.transport.sentSince(mark2).isEmpty())
    }

    @Test
    fun `a guest that starts listening late gets the board that already exists`() {
        val hub = MancalaHub()
        val host = MancalaTestKit.node(0, transport = hub.endpoint(MancalaTestKit.HOST_ID))
        host.game.startMatch()
        host.game.sow(0, 0) // nobody was listening
        val guest = MancalaTestKit.node(1, transport = hub.endpoint(MancalaTestKit.GUEST_ID))
        assertEquals(afterHostPit0, guest.game.state.value!!.pits)
        assertEquals(1, guest.game.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `reconnect makes the host re-broadcast and the guest ask again`() {
        val p = MancalaTestKit.pair()
        p.hostMovesPit0()
        val hostMark = p.host.transport.mark()
        p.host.transport.fireReconnected()
        val rebroadcast = p.host.transport.sentSince(hostMark).single()
        assertEquals(MancalaTestKit.GUEST_ID, rebroadcast.to)
        assertEquals(2, rebroadcast.stateSync.version)

        val guestMark = p.guest.transport.mark()
        p.guest.transport.fireReconnected()
        assertEquals(MancalaNetMessage.RequestState, p.guest.transport.sentSince(guestMark).first().message)
    }

    @Test
    fun `a guest that missed a move catches up after reconnecting`() {
        val p = MancalaTestKit.pair()
        val guestEndpoint = p.hub.endpoints.remove(MancalaTestKit.GUEST_ID)!! // the guest's link is down
        p.host.game.sow(0, 2) // extra turn (version 2), then a normal move (version 3): two boards missed
        p.host.game.sow(0, 0)
        assertEquals(4, p.guest.game.state.value!!.pits[0]) // still the opening board
        p.hub.endpoints[MancalaTestKit.GUEST_ID] = guestEndpoint
        p.guest.transport.fireReconnected()
        MancalaTestKit.assertSynced(p)
        assertTrue(p.guest.game.state.value!!.restored) // missed move: shown, not replayed
        // And the guest can act on the board it now holds.
        p.guest.game.sow(1, 9)
        assertEquals(1, p.host.game.state.value!!.pits[13])
        assertEquals(1, p.host.game.state.value!!.currentPlayerIndex) // extra turn
        MancalaTestKit.assertSynced(p)
    }

    // ---- opponent leaving ---------------------------------------------------------------------

    @Test
    fun `the guest leaving flags the host without recording any result`() {
        val p = MancalaTestKit.pair()
        p.hostMovesPit0()
        var ended: GameResult? = null
        p.host.game.setOnMatchEnd { ended = it }
        val before = p.host.game.state.value

        p.host.transport.fireLeft("someone else")
        assertFalse(p.host.game.opponentDisconnected.value)
        p.host.transport.fireLeft(MancalaTestKit.GUEST_ID)
        assertTrue(p.host.game.opponentDisconnected.value)

        assertNull(ended)
        assertFalse(p.host.game.matchOver.value)
        assertSame(before, p.host.game.state.value)
        assertEquals(0, p.host.game.scoreP1.value)
        assertEquals(0, p.host.game.scoreP2.value)
        assertEquals(0, p.host.game.draws.value)
    }

    @Test
    fun `the host leaving flags the guest`() {
        val p = MancalaTestKit.pair()
        p.guest.transport.fireLeft(MancalaTestKit.HOST_ID)
        assertTrue(p.guest.game.opponentDisconnected.value)
    }

    @Test
    fun `leaving afterwards reports a session with no winner`() {
        val p = MancalaTestKit.pair()
        p.hostMovesPit0()
        var ended: GameResult? = null
        p.host.game.setOnMatchEnd { ended = it }
        p.host.transport.fireLeft(MancalaTestKit.GUEST_ID)
        p.host.game.leaveSession()
        val result = ended!!
        assertFalse(result.wasAborted)
        assertTrue(result.scores.none { it.isWinner })
    }

    @Test
    fun `hearing from the opponent again clears the flag`() {
        val p = MancalaTestKit.pair()
        p.host.transport.fireLeft(MancalaTestKit.GUEST_ID)
        p.host.transport.fireJoined(MancalaTestKit.GUEST_ID)
        assertFalse(p.host.game.opponentDisconnected.value)

        p.guest.transport.fireLeft(MancalaTestKit.HOST_ID)
        p.hostMovesPit0() // a fresh StateSync from the host proves it is back
        assertFalse(p.guest.game.opponentDisconnected.value)
    }

    @Test
    fun `nothing is processed once the session has ended`() {
        val p = MancalaTestKit.pair()
        p.hostMovesPit0()
        p.host.game.leaveSession()
        assertHostIgnores(p, MancalaTestKit.GUEST_ID, MancalaNetMessage.SowRequest(7, 2))
        assertHostIgnores(p, MancalaTestKit.GUEST_ID, MancalaNetMessage.PlayAgain)
    }

    // ---- Play Again ------------------------------------------------------------------------------

    /** Host about to finish the board: sowing pit 5 empties its side; host wins 25-23. */
    private fun MancalaPair.rigNearEnd() {
        val pits = MutableList(14) { 0 }
        pits[5] = 1
        pits[12] = 1
        pits[6] = 24
        pits[13] = 22
        host.game.state.value = MancalaState(pits = pits, currentPlayerIndex = 0, lastAction = "rigged")
    }

    @Test
    fun `a finished board is synced with its winner and tally`() {
        val p = MancalaTestKit.pair()
        p.rigNearEnd()
        p.host.game.sow(0, 5)
        val s = p.guest.game.state.value!!
        assertTrue(s.roundOver)
        assertEquals(MancalaTestKit.HOST_ID, s.winnerPlayerId)
        assertEquals(25, s.pits[6])
        assertEquals(23, s.pits[13])
        assertEquals(1, p.guest.game.scoreP1.value)
        assertEquals(0, p.guest.game.scoreP2.value)
        MancalaTestKit.assertSynced(p)
        // Nobody can sow on a finished board.
        val mark = p.guest.transport.mark()
        p.guest.game.sow(1, 7)
        assertTrue(p.guest.transport.sentSince(mark).isEmpty())
    }

    @Test
    fun `the guest can ask for Play Again and the host re-deals and broadcasts`() {
        val p = MancalaTestKit.pair()
        p.rigNearEnd()
        p.host.game.sow(0, 5) // version 2
        val hostMark = p.host.transport.mark()
        p.guest.game.playAgain()
        assertTrue(p.guest.transport.sent.any { it.message == MancalaNetMessage.PlayAgain })

        val deal = p.host.transport.sentSince(hostMark).single()
        assertEquals(3, deal.stateSync.version)
        val s = p.host.game.state.value!!
        assertFalse(s.roundOver)
        assertEquals(0, s.currentPlayerIndex) // later boards open with seat 0
        assertEquals(List(14) { if (it == 6 || it == 13) 0 else 4 }, s.pits)
        assertEquals(1, p.host.game.scoreP1.value) // the session tally survives
        MancalaTestKit.assertSynced(p)
        assertFalse(p.guest.game.state.value!!.roundOver)
    }

    @Test
    fun `the host can Play Again too`() {
        val p = MancalaTestKit.pair()
        p.rigNearEnd()
        p.host.game.sow(0, 5)
        p.host.game.playAgain()
        assertFalse(p.guest.game.state.value!!.roundOver)
        MancalaTestKit.assertSynced(p)
        assertEquals(1, p.guest.game.scoreP1.value)
    }

    @Test
    fun `Play Again is ignored while a board is still being played`() {
        val p = MancalaTestKit.pair()
        p.hostMovesPit0()
        val guestMark = p.guest.transport.mark()
        p.guest.game.playAgain() // not over: nothing to ask for
        assertTrue(p.guest.transport.sentSince(guestMark).isEmpty())
        p.host.game.playAgain()  // not over: no re-deal
        assertEquals(afterHostPit0, p.host.game.state.value!!.pits)
        assertHostIgnores(p, MancalaTestKit.GUEST_ID, MancalaNetMessage.PlayAgain)
    }

    @Test
    fun `Play Again from a stranger or from the hosts own id is ignored`() {
        val p = MancalaTestKit.pair()
        p.rigNearEnd()
        p.host.game.sow(0, 5)
        assertHostIgnores(p, "intruder", MancalaNetMessage.PlayAgain)
        assertHostIgnores(p, MancalaTestKit.HOST_ID, MancalaNetMessage.PlayAgain)
    }

    // ---- two engines, one game ----------------------------------------------------------------

    @Test
    fun `a scripted game is identical on both engines and on a local engine`() {
        val p = MancalaTestKit.pair()
        val oracle = MancalaGame()
        oracle.init(
            GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, MancalaTestKit.roster, 0, LocalPassAndPlayTransport())
        )
        oracle.startMatch()

        var moves = 0
        while (!p.host.game.state.value!!.roundOver && moves < 500) {
            val s = p.host.game.state.value!!
            val seat = s.currentPlayerIndex
            val own = if (seat == 0) 0..5 else 7..12
            val legal = own.filter { s.pits[it] > 0 }
            val pit = legal[(moves * 5 + seat) % legal.size]
            // Each seat taps on ITS OWN device.
            (if (seat == 0) p.host else p.guest).game.sow(seat, pit)
            oracle.sow(seat, pit)
            moves++

            MancalaTestKit.assertSynced(p)
            assertEquals(MancalaTestKit.board(oracle), MancalaTestKit.board(p.host.game))
            assertEquals(48, p.guest.game.state.value!!.pits.sum())
        }
        assertTrue("the game should finish", p.guest.game.state.value!!.roundOver)
        assertEquals(oracle.scoreP1.value, p.guest.game.scoreP1.value)
        assertEquals(oracle.scoreP2.value, p.guest.game.scoreP2.value)
        assertEquals(oracle.draws.value, p.guest.game.draws.value)
        assertTrue(p.hub.senderLies.isEmpty())
    }
}
