package com.gamesuite.games.holdem

import com.gamesuite.core.PlayMode
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Random play: whole tournaments of random legal actions, checking after EVERY step that no chip is created or
 * destroyed and that the state is one the engine could have produced; then computer-versus-computer tournaments
 * at every level; then a snapshot round trip at every decision.
 */
class HoldemFuzzTest {

    private fun randomLegal(s: HoldemState, rnd: Random): HoldemAction {
        val o = HoldemRules.options(s, s.toAct)!!
        val roll = rnd.nextInt(100)
        return when {
            roll < 12 && !o.canCheck -> HoldemAction.FOLD
            roll < 20 && o.canAllIn -> HoldemAction.ALL_IN
            roll < 45 && o.canRaise -> HoldemAction.raiseTo(rnd.nextInt(o.minRaiseTo, o.maxRaiseTo + 1))
            o.canCheck -> HoldemAction.CHECK
            else -> HoldemAction.CALL
        }
    }

    @Test fun randomTournamentsConserveChipsAndStayConsistent() {
        var hands = 0
        var showdowns = 0
        var sidePots = 0
        repeat(220) { game ->
            val rnd = Random(1000 + game)
            val engine = HoldemEngine(Random(5000 + game))
            val seats = rnd.nextInt(2, 7)
            val chips = listOf(500, 1000, 2000, 5000)[rnd.nextInt(4)]
            val speed = listOf("off", "slow", "normal", "fast")[rnd.nextInt(4)]
            val ids = List(seats) { "p${it + 1}" }
            val cfg = HoldemConfig(startingChips = chips, blindSpeed = speed, endWhenHumansOut = false)
            var s = engine.newMatch(T.players(seats, 0), cfg)
            val total = seats * chips
            var guard = 0
            while (!s.matchOver && guard++ < 6000) {
                s = engine.startHand(s)
                hands++
                assertTrue("hand start", HoldemRules.isConsistent(s, ids, engine.deck, engine.deckPos))
                assertEquals(total, s.chipsInPlay)
                var steps = 0
                while (!s.handOver && steps++ < 400) {
                    s = if (s.runout) engine.stepRunout(s) else {
                        val a = randomLegal(s, rnd)
                        val before = s
                        val step = engine.act(s, s.toAct, a)
                        assertTrue("legal action $a refused: ${step.reject} in $before", step.ok)
                        step.state!!
                    }
                    assertEquals("chips changed", total, s.chipsInPlay)
                    assertTrue("inconsistent state after step: $s", HoldemRules.isConsistent(s, ids, engine.deck, engine.deckPos))
                }
                assertTrue("hand did not finish", s.handOver)
                assertEquals(total, s.seats.sumOf { it.chips })
                if (s.showdown) showdowns++
                if (s.results.size > 1) sidePots++
                assertEquals(s.results.sumOf { it.amount }, s.seats.sumOf { it.won })
            }
            assertTrue("tournament did not finish in time", s.matchOver)
            assertEquals(total, s.seats.first { it.playerId == s.winnerPlayerId }.chips)
            assertEquals(1, s.seats.count { !it.busted })
        }
        assertTrue("fuzz saw $hands hands", hands > 500)
        assertTrue("the fuzz must reach showdowns ($showdowns)", showdowns > 100)
        assertTrue("the fuzz must build side pots ($sidePots)", sidePots > 5)
    }

    @Test fun illegalActionsNeverChangeTheState() {
        val rnd = Random(31)
        val engine = HoldemEngine(Random(31))
        repeat(40) {
            var s = engine.startHand(T.match(rnd.nextInt(2, 7), speed = "off", engine = engine))
            var guard = 0
            while (!s.handOver && guard++ < 100) {
                if (s.runout) { s = engine.stepRunout(s); continue }
                val o = HoldemRules.options(s, s.toAct)!!
                val seat = s.toAct
                val bad = buildList {
                    add(seat to HoldemAction.raiseTo(-5))
                    add(seat to HoldemAction.raiseTo(0))
                    add(seat to HoldemAction.raiseTo(s.currentBet))
                    add(seat to HoldemAction.raiseTo(o.maxRaiseTo + 1))
                    if (o.minRaiseTo > s.currentBet + 1 && o.minRaiseTo < o.maxRaiseTo) add(seat to HoldemAction.raiseTo(o.minRaiseTo - 1))
                    if (!o.canCheck) add(seat to HoldemAction.CHECK)
                    if (!o.canCall) add(seat to HoldemAction.CALL)
                    add((seat + 1) % s.seats.size to HoldemAction.FOLD)
                }
                for ((who, a) in bad) {
                    val step = engine.act(s, who, a)
                    assertNull("$a by $who should be refused", step.state)
                    assertTrue(step.reject != HoldemReject.NONE)
                }
                s = engine.act(s, seat, randomLegal(s, rnd)).state!!
            }
        }
    }

    @Test fun computerOnlyTournamentsFinishAtEveryLevelAndStyle() {
        for (level in CpuDifficulty.values()) for (style in listOf("balanced", "tight", "loose", "mixed")) {
            val rnd = Random(level.ordinal * 31 + style.length)
            val game = HoldemGame(rnd)
            game.difficulty = level
            game.cpuStyle = style
            game.equityIterationsOverride = 25
            game.config = HoldemConfig(startingChips = 500, blindSpeed = "fast", endWhenHumansOut = false)
            val players = T.players(0, 4)
            game.init(T.localContext(players))
            game.startMatch()
            var guard = 0
            val total = 4 * 500
            while (guard++ < 20_000) {
                val s = game.state.value!!
                assertEquals(total, s.chipsInPlay)
                when {
                    s.matchOver -> break
                    s.runout -> game.stepRunout()
                    s.handOver -> game.nextHand()
                    else -> game.playBotStep()
                }
            }
            val end = game.state.value!!
            assertTrue("$level/$style did not finish", end.matchOver)
            assertEquals(1, end.seats.count { !it.busted })
        }
    }

    @Test fun everyComputerDecisionIsLegalOnEveryStreet() {
        var decisions = 0
        for (level in CpuDifficulty.values()) {
            val rnd = Random(level.ordinal + 700)
            val engine = HoldemEngine(Random(level.ordinal + 900))
            repeat(25) { g ->
                val seats = rnd.nextInt(2, 7)
                var s = engine.newMatch(T.players(0, seats), HoldemConfig(startingChips = 1000, blindSpeed = "fast", endWhenHumansOut = false))
                var guard = 0
                while (!s.matchOver && guard++ < 3000) {
                    s = engine.startHand(s)
                    var steps = 0
                    while (!s.handOver && steps++ < 300) {
                        if (s.runout) { s = engine.stepRunout(s); continue }
                        val v = HoldemBot.view(s, s.toAct)
                        assertNotNull(v)
                        val equity = if (level == CpuDifficulty.EASY) null else HoldemEquity.estimate(v!!.hole, v.board, v.opponents, 12, rnd)
                        val style = BotStyle.forSeat("mixed", s.toAct)
                        val action = HoldemBot.decide(v!!, level, style, rnd, equity)
                        val step = engine.act(s, s.toAct, action)
                        assertTrue("$level decided an illegal $action: ${step.reject}", step.ok)
                        s = step.state!!
                        decisions++
                    }
                }
            }
        }
        assertTrue(decisions > 2000)
    }

    @Test fun snapshotRoundTripAtEveryDecision() {
        val rnd = Random(404)
        val players = T.players(2, 2)
        val ctx = T.localContext(players, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
        val game = HoldemGame(Random(11))
        game.equityIterationsOverride = 15
        game.difficulty = CpuDifficulty.MEDIUM
        game.config = HoldemConfig(startingChips = 500, blindSpeed = "fast", endWhenHumansOut = false)
        game.init(ctx)
        var checked = 0
        for (round in 0 until 8) {
            game.startMatch()
            var guard = 0
            while (guard++ < 6000 && game.state.value?.matchOver != true) {
                val s = game.state.value!!
                val snap = game.snapshot()
                assertNotNull(snap)
                val twin = HoldemGame(Random(99))
                twin.init(T.localContext(players, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY))
                assertTrue("restore failed at hand ${s.handNumber} street ${s.street}", twin.restore(snap!!))
                assertEquals(s, twin.state.value)
                assertEquals("snapshot is not stable", snap, twin.snapshot())
                checked++
                when {
                    s.runout -> game.stepRunout()
                    s.handOver -> game.nextHand()
                    game.botToAct() -> game.playBotStep()
                    else -> {
                        // a person: the same action on both, then the states must agree again
                        val a = randomLegal(s, rnd)
                        assertEquals(HoldemReject.NONE, game.act(s.toAct, a))
                        assertEquals(HoldemReject.NONE, twin.act(s.toAct, a))
                        assertEquals(game.state.value, twin.state.value)
                    }
                }
            }
        }
        assertTrue("checked $checked decisions", checked > 200)
    }

    @Test fun aTamperedSnapshotIsRejectedAndLeavesTheGameUntouched() {
        val players = T.players(1, 2)
        val game = HoldemGame(Random(3))
        game.init(T.localContext(players))
        game.startMatch()
        val snap = game.snapshot()!!
        val fresh = HoldemGame(Random(4))
        fresh.init(T.localContext(players))
        fresh.startMatch()
        val before = fresh.state.value
        val cheats = listOf(
            snap.replace("\"chips\":", "\"chips\":9"),
            snap.replace("\"startingChips\":1000", "\"startingChips\":999"),
            "{}", "not json", snap.substring(0, snap.length / 2),
            snap.replace("\"playerId\":\"p1\"", "\"playerId\":\"zz\""),
        )
        for (bad in cheats) {
            assertFalse(fresh.restore(bad))
            assertEquals(before, fresh.state.value)
        }
        // a snapshot for another table (different roster) is refused too
        val other = HoldemGame(Random(5))
        other.init(T.localContext(T.players(2, 1)))
        assertFalse(other.restore(snap))
        assertTrue(fresh.restore(snap))
    }
}
