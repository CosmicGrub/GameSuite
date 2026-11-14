package com.gamesuite.games.sevens

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.games.president.PsFakeTransport
import com.gamesuite.games.president.PsHub
import com.gamesuite.games.president.psOfflineContext
import com.gamesuite.games.president.psPlayers
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Sevens as the screens use it: offline, CPUs, undo, hints, save and restore, fuzz, and the wire. */
class SevensGameTest {
    private fun game(humans: Int, bots: Int, seed: Int = 1, level: CpuDifficulty = CpuDifficulty.MEDIUM, rules: SevensRuleSet = SevensRuleSet()): SevensGame {
        val g = SevensGame(Random(seed))
        g.rules = rules
        g.difficulty = level
        g.init(psOfflineContext(psPlayers(humans, bots), if (humans > 1) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT))
        g.startMatch()
        return g
    }

    private fun humanMove(g: SevensGame, rnd: Random) {
        val s = g.state.value!!
        val hand = s.seats[s.currentSeat].hand
        val playable = SevensRules.playableCards(hand, s.low, s.high)
        if (playable.isEmpty()) assertEquals(SevensReject.NONE, g.tryPass())
        else assertEquals(SevensReject.NONE, g.tryPlay(playable[rnd.nextInt(playable.size)]))
    }

    private fun stepOnce(g: SevensGame, rnd: Random) {
        val s = g.state.value!!
        when {
            s.roundOver -> g.continueSession()
            g.botToMove(s) -> g.playBotStep()
            else -> humanMove(g, rnd)
        }
    }

    @Test fun fuzzManyRoundsKeepsEveryInvariant() {
        for (seed in 0 until 150) {
            val seats = 3 + seed % 4
            val humans = 1 + seed % 3
            val h = minOf(humans, seats)
            val rules = SevensRuleSet(passPenalty = seed % 2 == 0)
            val g = game(h, seats - h, seed = seed, level = CpuDifficulty.values()[seed % 3], rules = rules)
            val rnd = Random(seed)
            var steps = 0
            var rounds = 0
            while (rounds < 3) {
                assertTrue("round did not finish seed $seed", steps++ < 5000)
                stepOnce(g, rnd)
                val s = g.state.value!!
                assertTrue(SevensFlow.conserved(s))
                assertTrue("valid seed $seed step $steps", SevensValidator.isValid(s, psPlayers(h, seats - h), allowHidden = false))
                if (steps % 11 == 0) {
                    val snap = g.snapshot()!!
                    val before = g.state.value
                    assertTrue(g.restore(snap))
                    assertEquals(before, g.state.value)
                }
                if (s.roundOver) {
                    rounds++
                    val w = s.winnerSeat!!
                    assertTrue(s.seats[w].hand.isEmpty())
                    assertEquals(0, s.seats[w].roundScore - (if (rules.passPenalty) s.seats[w].passes * SevensRules.PASS_PENALTY else 0))
                    assertEquals(s.wins.values.sum(), s.roundNumber)
                }
            }
        }
    }

    @Test fun cpuGamesNeverGetStuck() {
        for (level in CpuDifficulty.values()) for (n in 3..6) {
            val g = game(1, n - 1, seed = n, level = level)
            val rnd = Random(1)
            var steps = 0
            while (!g.state.value!!.roundOver && steps++ < 3000) stepOnce(g, rnd)
            assertTrue(g.state.value!!.roundOver)
        }
    }

    @Test fun savedRoundResumesExactlyAndBadSavesAreRefused() {
        val g = game(1, 3, seed = 4)
        val rnd = Random(1)
        repeat(10) { stepOnce(g, rnd) }
        val snap = g.snapshot()!!
        val other = SevensGame(Random(2))
        other.init(psOfflineContext(psPlayers(1, 3)))
        assertTrue(other.restore(snap))
        assertEquals(g.state.value, other.state.value)
        val wrong = SevensGame(Random(2))
        wrong.init(psOfflineContext(psPlayers(1, 2)))
        assertFalse(wrong.restore(snap))
        assertNull(wrong.state.value)
        assertFalse(other.restore("garbage"))
        assertFalse(other.restore(snap.replace("\"hand\":[", "\"hand\":[0,0,")))
        // a hand that lost a card no longer adds up
        val s = g.state.value!!
        val broken = s.copy(seats = s.seats.mapIndexed { i, x -> if (i == 1) x.copy(hand = x.hand.drop(1)) else x })
        val json = Json { encodeDefaults = true }.encodeToString(SevensState.serializer(), broken)
        assertFalse(other.restore("{\"roster\":[\"p1\",\"bot1\",\"bot2\",\"bot3\"],\"state\":$json,\"difficulty\":\"HARD\"}"))
    }

    @Test fun noSaveOnlineBeforeTheDealOrAfterTheMatch() {
        val g = SevensGame(Random(1))
        g.init(psOfflineContext(psPlayers(1, 2)))
        assertNull(g.snapshot())
        g.startMatch()
        assertNotNull(g.snapshot())
        g.endMatch(GameResult())
        assertNull(g.snapshot())
    }

    @Test fun undoReturnsToTheMomentBeforeTheHumanMove() {
        val g = game(1, 2, seed = 21)
        val rnd = Random(2)
        var guard = 0
        while (!g.isMyTurn(g.state.value!!) && guard++ < 30) stepOnce(g, rnd)
        val s0 = g.state.value!!
        assertFalse(g.canUndo)
        humanMove(g, rnd)
        assertTrue(g.canUndo)
        guard = 0
        while (g.botToMove() && guard++ < 10) g.playBotStep()
        g.undo()
        assertEquals(s0, g.state.value)
        assertFalse(g.canUndo)
    }

    @Test fun refusalsExplainThemselves() {
        val g = game(1, 2, seed = 21)
        val rnd = Random(2)
        var guard = 0
        while (!g.isMyTurn(g.state.value!!) && guard++ < 30) stepOnce(g, rnd)
        val s = g.state.value!!
        val hand = s.seats[s.currentSeat].hand
        val playable = SevensRules.playableCards(hand, s.low, s.high)
        val unplayable = hand.firstOrNull { it !in playable }
        if (unplayable != null) {
            assertEquals(
                if (s.started) SevensReject.NOT_PLAYABLE else SevensReject.MUST_START_WITH_SEVEN_OF_DIAMONDS,
                g.tryPlay(unplayable)
            )
        }
        if (playable.isNotEmpty()) assertEquals(SevensReject.MUST_PLAY_IF_ABLE, g.tryPass())
        assertEquals(SevensReject.NOT_IN_HAND, g.tryPlay(s.seats.first { it.playerId != s.seats[s.currentSeat].playerId }.hand.first()))
        assertFalse(g.canUndo)
    }

    @Test fun theHintIsAPlayableCardOrAPassWhenStuck() {
        for (seed in 0 until 30) {
            val g = game(1, 3, seed = seed)
            val rnd = Random(seed)
            var guard = 0
            while (!g.isMyTurn(g.state.value!!) && guard++ < 50) stepOnce(g, rnd)
            val s = g.state.value!!
            val playable = SevensRules.playableCards(s.seats[s.currentSeat].hand, s.low, s.high)
            val hint = g.hintNow()
            if (playable.isEmpty()) assertNull(hint) else assertTrue(hint in playable)
            assertTrue(g.hintShown.value)
        }
    }

    @Test fun leavingAfterARoundRecordsTheWinsAndBeforeOneAborts() {
        val early = game(1, 2)
        var r: GameResult? = null
        early.setOnMatchEnd { r = it }
        early.leaveSession()
        assertTrue(r!!.wasAborted)
        val g = game(1, 2, seed = 5)
        var res: GameResult? = null
        g.setOnMatchEnd { res = it }
        val rnd = Random(2)
        var guard = 0
        while (!g.state.value!!.roundOver && guard++ < 3000) stepOnce(g, rnd)
        g.leaveSession()
        assertFalse(res!!.wasAborted)
        assertEquals(1, res!!.scores.count { it.isWinner })
        assertEquals(g.state.value!!.seats[g.state.value!!.winnerSeat!!].playerId, res!!.scores.first { it.isWinner }.playerId)
    }

    // ---- the wire ---------------------------------------------------------------------------------

    private class Table(val hub: PsHub, val games: List<SevensGame>, val watcher: SevensGame?)

    private fun table(seats: Int, watcher: Boolean = false, seed: Int = 1): Table {
        val players = psPlayers(seats)
        val hub = PsHub()
        val games = players.mapIndexed { i, p ->
            SevensGame(Random(seed + i)).also { it.init(GameContext(PlayMode.ONLINE, players, i, hub.endpoint(p.playerId))) }
        }
        val w = if (watcher) SevensGame(Random(7)).also { it.init(GameContext(PlayMode.ONLINE, players, -1, hub.endpoint("watcher"))) } else null
        games.forEach { it.startMatch() }
        return Table(hub, games, w)
    }

    private fun json(m: SevensNetMessage) = Json.encodeToString(SevensNetMessage.serializer(), m).toByteArray()

    private fun netMove(t: Table, rnd: Random) {
        val s = t.games[0].state.value!!
        val g = t.games[s.currentSeat]
        val v = g.state.value!!
        val playable = SevensRules.playableCards(v.seats[s.currentSeat].hand, v.low, v.high)
        if (playable.isEmpty()) assertEquals(SevensReject.NONE, g.tryPass()) else assertEquals(SevensReject.NONE, g.tryPlay(playable[rnd.nextInt(playable.size)]))
    }

    @Test fun guestsHoldOnlyTheirOwnCardsAndConvergeThroughARound() {
        val t = table(4, watcher = true)
        val rnd = Random(3)
        var steps = 0
        while (!t.games[0].state.value!!.roundOver && steps++ < 3000) {
            netMove(t, rnd)
            val h = t.games[0].state.value!!
            for (i in 1..3) {
                val v = t.games[i].state.value!!
                assertEquals(h.eventSerial, v.eventSerial)
                assertEquals(h.low, v.low)
                assertEquals(h.high, v.high)
                assertEquals(h.seats[i].hand, v.seats[i].hand)
                for (j in v.seats.indices) if (j != i) assertTrue(v.seats[j].hand.all { it == SV_HIDDEN_CARD })
                assertEquals(h.seats.map { it.hand.size }, v.seats.map { it.hand.size })
            }
            val w = t.watcher!!.state.value!!
            assertEquals(h.eventSerial, w.eventSerial)
            assertTrue(w.seats.all { seat -> seat.hand.all { it == SV_HIDDEN_CARD } })
        }
        assertTrue(t.games[0].state.value!!.roundOver)
        assertTrue(t.hub.senderLies.none { !it.startsWith("endpoint watcher") })
        // a guest asks for the next deal
        t.games[2].continueSession()
        assertEquals(2, t.games[0].state.value!!.roundNumber)
        assertEquals(2, t.games[3].state.value!!.roundNumber)
        assertTrue(SevensFlow.conserved(t.games[0].state.value!!))
    }

    @Test fun forgedAndOutOfTurnIntentsAreIgnored() {
        val t = table(3)
        val host = t.games[0]
        val s = host.state.value!!
        val cur = s.currentSeat
        val other = (0..2).first { it != cur }
        val before = host.state.value
        val card = s.seats[cur].hand.first { SevensRules.isPlayable(it, s.low, s.high) || true }
        // a seat that is not on turn tries to play the seat-on-turn's card
        if (other != 0) t.hub.endpoints["p${other + 1}"]!!.send("p${cur + 1}", "p1", json(SevensNetMessage.Intent(SevensIntent.Play(card))))
        t.hub.endpoints["p${other + 1}"]!!.send("p${other + 1}", "p1", json(SevensNetMessage.Intent(SevensIntent.Pass)))
        assertEquals(before, host.state.value)
        // a card the sender does not hold
        if (cur != 0) {
            val foreign = s.seats[0].hand.first()
            t.hub.endpoints["p${cur + 1}"]!!.send("p${cur + 1}", "p1", json(SevensNetMessage.Intent(SevensIntent.Play(foreign))))
            assertEquals(before, host.state.value)
        }
    }

    @Test fun forgedAndInvalidStateSyncsAreIgnored() {
        val t = table(3)
        val g = t.games[1]
        val keep = g.state.value
        val hostView = sevensRedactFor(t.games[0].state.value!!, "p2")
        t.hub.endpoints["p3"]!!.send("p3", "p2", json(SevensNetMessage.StateSync(9999, hostView.copy(currentSeat = 2))))
        assertEquals(keep, g.state.value)
        t.hub.endpoints["p1"]!!.send("p1", "p2", json(SevensNetMessage.StateSync(0, hostView)))   // old version
        assertEquals(keep, g.state.value)
        t.hub.endpoints["p1"]!!.send("p1", "p2", json(SevensNetMessage.StateSync(600, hostView.copy(low = listOf(1, 1, 1, 1), high = listOf(7, 7, 7, 7)))))
        assertEquals(keep, g.state.value)
        t.hub.endpoints["p2"]!!.send("p2", "p1", "junk".toByteArray())
        assertEquals(keep, g.state.value)
    }

    @Test fun watchersCannotAct() {
        val t = table(3, watcher = true)
        val w = t.watcher!!
        assertTrue(w.isSpectator)
        assertEquals(SevensReject.SPECTATOR, w.tryPass())
        assertEquals(SevensReject.SPECTATOR, w.tryPlay(0))
        val before = t.games[0].state.value
        t.hub.endpoints["watcher"]!!.send("watcher", "p1", json(SevensNetMessage.Intent(SevensIntent.Pass)))
        t.hub.endpoints["watcher"]!!.send("watcher", "p1", json(SevensNetMessage.Intent(SevensIntent.Continue)))
        assertEquals(before, t.games[0].state.value)
    }

    @Test fun aLeavingSeatIsPlayedByAComputerAndReturned() {
        val t = table(3)
        val host = t.games[0]
        var s = host.state.value!!
        val rnd = Random(4)
        var guard = 0
        while (s.currentSeat == 0 && guard++ < 10) { netMove(t, rnd); s = host.state.value!! }
        val leaver = s.currentSeat
        if (leaver == 0) return
        t.hub.endpoints["p1"]!!.fireLeft("p${leaver + 1}")
        assertTrue(host.state.value!!.seats[leaver].isBot)
        assertTrue(host.botToMove())
        host.playBotStep()
        assertNotEquals(leaver, host.state.value!!.currentSeat)
        t.hub.endpoints["p1"]!!.fireJoined("p${leaver + 1}")
        assertFalse(host.state.value!!.seats[leaver].isBot)
        t.hub.endpoints["p2"]!!.fireLeft("p1")
        assertTrue(t.games[1].hostLeft.value)
    }

    @Test fun noUndoOrHintOnline() {
        val t = table(3)
        assertFalse(t.games[0].canUndo)
        assertNull(t.games[0].hintNow())
    }
}
