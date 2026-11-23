package com.gamesuite.games.toytennis

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.toysoccer.ToyClock
import com.gamesuite.games.toysoccer.ToyFakeTransport
import com.gamesuite.games.toysoccer.ToyHub
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Host-authoritative Nearby / Online Toy Tennis: tick-stamped swings, identity from the transport, versioned snapshots. */
class TennisNetworkedTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun enc(m: TennisNetMessage) = json.encodeToString(TennisNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)
    private fun dec(b: ByteArray) = json.decodeFromString(TennisNetMessage.serializer(), String(b, Charsets.UTF_8))

    private class Table(val hub: ToyHub, val host: ToyTennisGame, val guest: ToyTennisGame, val ht: ToyFakeTransport, val gt: ToyFakeTransport)

    private fun table(mode: PlayMode = PlayMode.LOCAL_AD_HOC, assist: Boolean = false, format: String = "points7"): Table {
        val hub = ToyHub(); val clock = ToyClock()
        val ht = hub.endpoint("h"); val gt = hub.endpoint("g")
        val roster = listOf(PlayerInfo("h", "Hana"), PlayerInfo("g", "Gus"))
        val host = ToyTennisGame(Random(3), clock); host.assist = assist; host.formatId = format
        val guest = ToyTennisGame(Random(4), clock)
        host.init(GameContext(mode, roster, 0, ht)); host.startMatch()
        guest.init(GameContext(mode, roster, 1, gt))
        return Table(hub, host, guest, ht, gt)
    }

    private fun court(g: ToyTennisGame) = g.state.value!!.court

    /** The host serves perfectly; returns the contact tick of the guest's return. */
    private fun hostServes(t: Table): Int {
        TennisKit.runUntil(t.host) { it.phase == TPhase.TOSS }
        while (court(t.host).tick < court(t.host).cTick) t.host.stepTicks(1)
        t.host.swing(0, 0.0, 0.6); t.host.stepTicks(1)
        assertEquals(1, court(t.host).hitSerial)
        return court(t.host).cTick
    }

    @Test fun theGuestReceivesTheCourtAndNobodyLiesAboutWhoSentWhat() {
        val t = table()
        val gs = t.guest.state.value
        assertNotNull(gs)
        assertEquals(court(t.host), gs!!.court)
        assertTrue(t.hub.senderLies.isEmpty())
        // the guest sees the same score and format
        assertEquals(TennisFormat.POINTS7, gs.format)
    }

    @Test fun aGuestSwingIsJudgedAtTheTickItSawEvenWhenItArrivesLate() {
        fun run(lag: Int, claim: (Int) -> Int): TennisState {
            val t = table()
            val contact = hostServes(t)
            TennisKit.runUntil(t.host) { it.tick >= contact + lag }
            val now = court(t.host).tick
            t.gt.send("g", "h", enc(TennisNetMessage.SwingRequest(claim(contact).let { if (it == Int.MIN_VALUE) now else it }, 0.3, 0.6)))
            t.host.stepTicks(1)                              // a swing with no rewind is applied by the next tick
            assertTrue("the host is at (or just past) now", court(t.host).tick >= now)
            return court(t.host).copy()
        }
        // 12 ticks (0.1 s) of lag, but the guest SAW the contact moment: judged perfect
        val compensated = run(12) { it }
        assertEquals(4, compensated.lastQuality)
        assertEquals(2, compensated.hitSerial)
        assertEquals(1, compensated.lastHitter)
        // exactly the same court as if the swing had arrived without any lag
        val live = run(0) { it }
        assertEquals(4, live.lastQuality)
        // swings that claim "now" get no compensation: 0.1 s late is only an OK shot
        val uncompensated = run(12) { Int.MIN_VALUE }
        assertEquals(2, uncompensated.lastQuality)
        // a stamp from the far past is clamped to the rewind window, never trusted
        val ancient = run(12) { it - 500 }
        assertTrue("clamped to now-36 (0.2 s early): ${ancient.lastQuality}", ancient.lastQuality <= 1)
        // a stamp from the future is clamped to now
        val future = run(12) { it + 500 }
        assertEquals(2, future.lastQuality)
    }

    @Test fun aRewoundSwingGivesTheSameCourtAsOneThatArrivedOnTime() {
        fun scenario(delayedBy: Int): TennisState {
            val t = table()
            val contact = hostServes(t)
            if (delayedBy == 0) {
                TennisKit.runUntil(t.host) { it.tick >= contact }
                t.gt.send("g", "h", enc(TennisNetMessage.SwingRequest(contact, 0.3, 0.6)))
                TennisKit.runUntil(t.host) { it.tick >= contact + 14 }
            } else {
                TennisKit.runUntil(t.host) { it.tick >= contact + 14 }
                t.gt.send("g", "h", enc(TennisNetMessage.SwingRequest(contact, 0.3, 0.6)))
            }
            return court(t.host).copy()
        }
        assertEquals(scenario(0), scenario(14))
    }

    @Test fun aGuestWhoNeverSwingsLosesThePointToTheAceAsInAnyOtherGame() {
        val t = table()
        hostServes(t)
        TennisKit.runUntil(t.host) { it.phase == TPhase.POINT }
        assertEquals(PointReason.ACE, court(t.host).pointReason)
        assertEquals(0, court(t.host).pointWinner)
        assertEquals(court(t.host).pointSerial, court(t.guest).pointSerial)
        assertEquals(court(t.host).score, court(t.guest).score)
    }

    @Test fun aFloodOfGuestSwingsIsDroppedBeforeItCostsARewind() {
        val t = table()
        val contact = hostServes(t)
        TennisKit.runUntil(t.host) { it.tick >= contact }
        repeat(60) { t.ht.inject("g", enc(TennisNetMessage.SwingRequest(contact, 0.3, 0.6))) }
        assertEquals("only the first of 60 same-tick swings may be judged", 1, t.host.guestSwingsJudged)
    }

    @Test fun intentsFromTheHostSeatOrUnknownSendersAreIgnored() {
        val t = table()
        val contact = hostServes(t)
        TennisKit.runUntil(t.host) { it.tick >= contact }
        val msg = enc(TennisNetMessage.SwingRequest(contact, 0.0, 0.5))
        t.ht.inject("h", msg); t.ht.inject("nobody", msg); t.ht.inject("zz", msg)
        t.ht.inject("g", "garbage".toByteArray()); t.ht.inject("g", ByteArray(0)); t.ht.inject("g", "{\"type\":\"x\"}".toByteArray())
        assertEquals(1, court(t.host).hitSerial)
        // a request in the wrong state is just a whiff, never an error
        t.ht.inject("g", enc(TennisNetMessage.SwingRequest(-5, 0.0, 0.5)))
        assertTrue(court(t.host).swingSerial >= 2)
        // a guest cannot swing for the host's seat, nor the host for the guest's
        assertEquals(TennisReject.NOT_YOUR_SEAT, t.guest.swing(0, 0.0, 0.5))
        assertEquals(TennisReject.NOT_YOUR_SEAT, t.host.swing(1, 0.0, 0.5))
    }

    @Test fun onlyTheHostMaySendState() {
        val t = table()
        val tmpl = t.ht.sent.map { dec(it.payload) }.filterIsInstance<TennisNetMessage.StateSync>().last().state
        val forgedCourt = tmpl.court.copy(score = TennisScore(TennisFormat.POINTS7).pointWonBy(1).pointWonBy(1))
        val forged = enc(TennisNetMessage.StateSync(9000, tmpl.copy(court = forgedCourt)))
        t.gt.inject("g", forged); t.gt.inject("nobody", forged)
        assertEquals(0, court(t.guest).score.totalPoints)
        t.gt.inject("h", forged)
        assertEquals(2, court(t.guest).score.pointsB)
        t.ht.inject("g", forged)
        assertEquals(0, court(t.host).score.totalPoints)
    }

    @Test fun staleAndMalformedSnapshotsAreIgnored() {
        val t = table()
        val tmpl = t.ht.sent.map { dec(it.payload) }.filterIsInstance<TennisNetMessage.StateSync>().last().state
        fun withScore(n: Int) = tmpl.copy(court = tmpl.court.copy(score = TennisScore(TennisFormat.POINTS7, pointsA = n, totalPoints = n)))
        t.gt.inject("h", enc(TennisNetMessage.StateSync(7000, withScore(2))))
        assertEquals(2, court(t.guest).score.pointsA)
        t.gt.inject("h", enc(TennisNetMessage.StateSync(7000, withScore(3))))
        t.gt.inject("h", enc(TennisNetMessage.StateSync(6999, withScore(4))))
        assertEquals(2, court(t.guest).score.pointsA)
        val c = tmpl.court
        val bad = listOf(
            tmpl.copy(court = c.copy(bx = 1e9)), tmpl.copy(court = c.copy(bvz = 9e3)), tmpl.copy(court = c.copy(p0x = 400.0)),
            tmpl.copy(court = c.copy(phaseTicks = 99999)), tmpl.copy(court = c.copy(bounces = 77)), tmpl.copy(court = c.copy(lastHitter = 5)),
            tmpl.copy(court = c.copy(server = 3)), tmpl.copy(court = c.copy(boxSign = 0)), tmpl.copy(court = c.copy(lastQuality = 9)),
            tmpl.copy(court = c.copy(hitSerial = -1)), tmpl.copy(court = c.copy(score = c.score.copy(pointsA = -3))),
            tmpl.copy(court = c.copy(score = c.score.copy(format = TennisFormat(false, pointsTarget = 500)))),
            tmpl.copy(players = tmpl.players.map { it.copy(playerId = "x") }), tmpl.copy(sessionWins = listOf(1))
        )
        for ((i, b) in bad.withIndex()) {
            t.gt.inject("h", enc(TennisNetMessage.StateSync(7001 + i, b)))
            assertEquals("bad #$i", 2, court(t.guest).score.pointsA)
        }
        t.gt.inject("h", enc(TennisNetMessage.StateSync(7500, withScore(5))))
        assertEquals(5, court(t.guest).score.pointsA)
    }

    @Test fun aGuestCarriesTheBallForwardBetweenSnapshotsAndNeverScores() {
        val t = table()
        hostServes(t)
        t.ht.router = null                                   // cut the stream: both carry the same state on their own
        t.guest.stepTicks(30); t.host.stepTicks(30)
        val h = court(t.host); val g = court(t.guest)
        assertEquals(h.bx, g.bx, 1e-9); assertEquals(h.by, g.by, 1e-9); assertEquals(h.bz, g.bz, 1e-9)
        t.guest.stepTicks(3000)
        assertEquals(0, court(t.guest).pointSerial)
        assertEquals(TPhase.RALLY, court(t.guest).phase)
    }

    @Test fun theHostStreamsEveryPointAndTheMatchEndAndAGuestAsksForARematch() {
        val t = table()
        t.host.stepTicks(1)
        var guard = 0
        while (court(t.host).phase != TPhase.OVER && guard++ < 200) t.host.stepTicks(500)   // nobody swings: serves fault, points flow
        assertEquals(TPhase.OVER, court(t.host).phase)
        assertEquals(TPhase.OVER, court(t.guest).phase)
        assertEquals(court(t.host).score, court(t.guest).score)
        val w = court(t.host).score.winner
        assertEquals(1, t.guest.sessionWins.value[w])
        t.guest.playAgain()
        assertEquals(TPhase.PREP, court(t.host).phase)
        assertEquals(TPhase.PREP, court(t.guest).phase)
        assertEquals(0, court(t.guest).score.totalPoints)
        assertEquals(1, t.guest.sessionWins.value[w])
    }

    @Test fun aRematchRequestIsIgnoredWhileTheMatchIsStillOn() {
        val t = table()
        t.gt.send("g", "h", enc(TennisNetMessage.RequestPlayAgain))
        assertEquals(0, court(t.host).hitSerial)
        assertEquals(TPhase.PREP, court(t.host).phase)
        t.host.stepTicks(50)
        assertTrue(court(t.host).tick >= 50)             // not restarted
    }

    @Test fun aLeavingGuestEndsTheMatchForTheHost() {
        val t = table()
        t.ht.fireLeft("g")
        assertTrue(t.host.opponentLeft.value)
        assertEquals(TennisReject.OPPONENT_LEFT, t.host.swing(0, 0.0, 0.5))
        t.ht.fireJoined("g")
        assertFalse(t.host.opponentLeft.value)
        assertEquals(TennisReject.NONE, t.host.swing(0, 0.0, 0.5))
    }

    @Test fun aGuestWithNoStateShowsNothingAndNeverStartsAMatch() {
        val hub = ToyHub()
        val g = ToyTennisGame(Random(1), ToyClock())
        g.init(GameContext(PlayMode.ONLINE, listOf(PlayerInfo("h", "A"), PlayerInfo("g", "B")), 1, hub.endpoint("g")))
        assertNull(g.state.value)
        g.startMatch()
        assertNull(g.state.value)
        assertNull(g.snapshot())
    }

    @Test fun relaxedTimingIsHostDecidedAndSyncedToGuests() {
        val t = table(assist = true)
        assertTrue(t.guest.state.value!!.assist)
        TennisKit.runUntil(t.host) { it.phase == TPhase.TOSS }
        while (court(t.host).tick < court(t.host).cTick + 4) t.host.stepTicks(1)
        assertTrue(court(t.host).held)
        t.host.swing(0, 0.0, 0.6); t.host.stepTicks(1)
        TennisKit.runUntil(t.host) { it.held }
        // the guest's swing arrives at any time and is judged perfect: nothing to rewind
        t.gt.send("g", "h", enc(TennisNetMessage.SwingRequest(0, -0.3, 0.6)))
        t.host.stepTicks(1)
        assertEquals(4, court(t.host).lastQuality)
        assertEquals(2, court(t.host).hitSerial)
    }
}
